package com.example.views.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.Note
import com.example.views.data.NoteUpdate
import com.example.views.data.UrlPreviewInfo
import com.example.views.network.WebSocketClient
import com.example.views.repository.ContactListRepository
import com.example.views.repository.NotesRepository
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.RelayEndpointStatus
import com.example.views.relay.RelayState
import com.example.views.repository.ProfileMetadataCache
import com.example.views.services.UrlPreviewCache
import com.example.views.services.UrlPreviewManager
import com.example.views.services.UrlPreviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class DashboardUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentDestination: String = "home",
    val hasRelays: Boolean = false,
    val isLoadingFromRelays: Boolean = false,
    /** Pending new notes count for All feed (current relay set, no follow filter). */
    val newNotesCountAll: Int = 0,
    /** Pending new notes count for Following feed (current relay set, follow filter). */
    val newNotesCountFollowing: Int = 0,
    /** Follow list (kind-3 p-tags) for "Following" filter. */
    val followList: Set<String> = emptySet(),
    /** Relay connection state for feed/connection indicator. */
    val relayState: RelayState = RelayState.Disconnected,
    /** Per-relay summary for UI (e.g. "3/5 relays"); null when not applicable. */
    val relayCountSummary: String? = null,
    /** URL previews by note id (enrichment side channel); avoids replacing whole notes list when previews load. */
    val urlPreviewsByNoteId: Map<String, List<UrlPreviewInfo>> = emptyMap()
)

class DashboardViewModel : ViewModel() {
    private val webSocketClient = WebSocketClient()
    private val notesRepository = NotesRepository.getInstance()
    private val urlPreviewManager = UrlPreviewManager(UrlPreviewService(), UrlPreviewCache)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        loadInitialData()
        // Defer WebSocket: feed is relay-driven; connect only when a real backend exists (reduces startup/no-op connection)
        // connectWebSocket()
        observeNotesFromRepository()
        observeRelayConnectionState()
        // Profile→notes updates are coalesced and applied in NotesRepository (profile update coalescer)
    }

    private fun observeRelayConnectionState() {
        val stateMachine = RelayConnectionStateMachine.getInstance()
        viewModelScope.launch {
            stateMachine.state.collect { state ->
                _uiState.value = _uiState.value.copy(relayState = state)
            }
        }
        viewModelScope.launch {
            stateMachine.perRelayState.collect { perRelay ->
                val total = perRelay.size
                if (total <= 1) {
                    _uiState.value = _uiState.value.copy(relayCountSummary = null)
                    return@collect
                }
                val connected = perRelay.values.count { it == RelayEndpointStatus.Connected }
                _uiState.value = _uiState.value.copy(relayCountSummary = "$connected/$total relays")
            }
        }
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches. Call from UI when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        notesRepository.setCacheRelayUrls(urls)
    }

    /**
     * Load follow list (kind-3) for the given pubkey. Call from UI when account is available.
     * Uses ContactListRepository cache (5 min TTL) so repeated calls are cheap.
     * @param forceRefresh if true, bypass cache (e.g. pull-to-refresh on Following).
     */
    fun loadFollowList(pubkey: String, cacheRelayUrls: List<String>, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val cached = ContactListRepository.getCachedFollowList(pubkey)
            if (cached != null && !forceRefresh) {
                _uiState.value = _uiState.value.copy(followList = cached)
                return@launch
            }
            val list = ContactListRepository.fetchFollowList(pubkey, cacheRelayUrls, forceRefresh)
            _uiState.value = _uiState.value.copy(followList = list)
        }
    }

    /**
     * Set follow filter on notes: when enabled, only notes from followList authors are shown.
     * When Following is selected but followList is still empty (loading), pass null so repo shows all until loaded.
     */
    fun setFollowFilter(enabled: Boolean) {
        val list = _uiState.value.followList
        val toPass = if (enabled) { if (list.isEmpty()) null else list } else null
        notesRepository.setFollowFilter(toPass, enabled)
    }

    private fun loadInitialData() {
        _uiState.value = _uiState.value.copy(
            notes = emptyList(),
            isLoading = false,
            hasRelays = false
        )
    }

    /** Debounced enrichment job: only one runs at a time, after list stabilizes, so UI stays fast. */
    private var enrichmentJob: Job? = null

    /**
     * Observe notes from the NotesRepository; emit immediately for fast render; enrich with URL previews after list stabilizes.
     */
    private fun observeNotesFromRepository() {
        viewModelScope.launch {
            try {
                notesRepository.notes.collect { notes ->
                    try {
                        _uiState.value = _uiState.value.copy(
                            notes = notes,
                            isLoadingFromRelays = false,
                            hasRelays = notes.isNotEmpty() || _uiState.value.hasRelays
                        )
                        if (notes.isEmpty()) return@collect
                        enrichmentJob?.cancel()
                        enrichmentJob = viewModelScope.launch {
                            try {
                                delay(250)
                                val snapshot = notes.map { it.id }
                                val enriched = withContext(Dispatchers.IO) {
                                    urlPreviewManager.processNotesForUrlPreviews(notes)
                                }
                                if (_uiState.value.notes.map { it.id } == snapshot) {
                                    val previewsByNoteId = enriched.associate { it.id to it.urlPreviews }
                                    _uiState.value = _uiState.value.copy(urlPreviewsByNoteId = previewsByNoteId)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "Enrichment failed: ${e.message}", e)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Notes collect failed: ${e.message}", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Notes flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.isLoading.collect { isLoading ->
                    _uiState.value = _uiState.value.copy(isLoadingFromRelays = isLoading)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Loading flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.error.collect { error ->
                    if (error != null) {
                        _uiState.value = _uiState.value.copy(error = error)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error flow failed: ${e.message}", e)
            }
        }

        viewModelScope.launch {
            try {
                notesRepository.newNotesCounts.collect { counts ->
                    _uiState.value = _uiState.value.copy(
                        newNotesCountAll = counts.all,
                        newNotesCountFollowing = counts.following
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "NewNotesCounts flow failed: ${e.message}", e)
            }
        }
    }

    /**
     * Apply pending new notes to the feed (call on pull-to-refresh).
     */
    fun applyPendingNotes() {
        notesRepository.applyPendingNotes()
    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            try {
                webSocketClient.connect()
                // No fake notes: feed is driven by NotesRepository (relay). WebSocket is for real-time like/share updates only.

                // Listen for real-time updates
                webSocketClient.realTimeUpdates.collect { update ->
                    handleRealTimeUpdate(update)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket connection error: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = "Connection error: ${e.message}"
                )
            }
        }
    }

    private fun handleRealTimeUpdate(update: NoteUpdate) {
        _uiState.value = _uiState.value.copy(
            notes = _uiState.value.notes.map { note ->
                if (note.id == update.noteId) {
                    when (update.action) {
                        "like" -> note.copy(likes = note.likes + 1, isLiked = true)
                        "unlike" -> note.copy(likes = note.likes - 1, isLiked = false)
                        "share" -> note.copy(shares = note.shares + 1, isShared = true)
                        else -> note
                    }
                } else {
                    note
                }
            }
        )
    }

    fun toggleLike(noteId: String) {
        viewModelScope.launch {
            var isLikedAction = false

            _uiState.value = _uiState.value.copy(
                notes = _uiState.value.notes.map { note ->
                    if (note.id == noteId) {
                        val updatedNote = if (note.isLiked) {
                            note.copy(likes = note.likes - 1, isLiked = false)
                        } else {
                            note.copy(likes = note.likes + 1, isLiked = true)
                        }
                        isLikedAction = updatedNote.isLiked
                        updatedNote
                    } else {
                        note
                    }
                }
            )

            // Send update via WebSocket
            val update = NoteUpdate(
                noteId = noteId,
                action = if (isLikedAction) "like" else "unlike",
                userId = "current_user",
                timestamp = System.currentTimeMillis()
            )
            webSocketClient.sendNoteUpdate(update)
        }
    }

    fun shareNote(noteId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                notes = _uiState.value.notes.map { note ->
                    if (note.id == noteId) {
                        note.copy(shares = note.shares + 1, isShared = true)
                    } else {
                        note
                    }
                }
            )

            val update = NoteUpdate(
                noteId = noteId,
                action = "share",
                userId = "current_user",
                timestamp = System.currentTimeMillis()
            )
            webSocketClient.sendNoteUpdate(update)
        }
    }

    fun commentOnNote(noteId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                notes = _uiState.value.notes.map { note ->
                    if (note.id == noteId) {
                        note.copy(comments = note.comments + 1)
                    } else {
                        note
                    }
                }
            )
        }
    }

    fun openProfile(userId: String) {
        // Navigate to profile - handled in UI layer
    }

    fun onSidebarItemClick(itemId: String) {
        when {
            itemId.startsWith("relay_category:") -> {
                val categoryId = itemId.removePrefix("relay_category:")
                Log.d(TAG, "Category clicked: $categoryId")
                // This will be handled by passing relay URLs from the UI layer
            }
            itemId.startsWith("relay:") -> {
                val relayUrl = itemId.removePrefix("relay:")
                Log.d(TAG, "Relay clicked: $relayUrl")
                setDisplayFilterOnly(listOf(relayUrl))
            }
            itemId == "profile" -> openProfile("current_user")
            itemId == "settings" -> {
                // Navigate to settings - handled in UI layer
            }
            itemId == "logout" -> {
                // Handle logout - handled in UI layer
            }
            else -> {
                Log.d(TAG, "Unknown sidebar item: $itemId")
            }
        }
    }

    fun onMoreOptionClick(option: String) {
        // Handle more options
    }

    fun navigateToDestination(destination: String) {
        _uiState.value = _uiState.value.copy(
            currentDestination = destination
        )
    }

    /**
     * Load notes from all general relays (subscription + display both use same list).
     */
    fun loadNotesFromAllGeneralRelays(allUserRelayUrls: List<String>) {
        loadNotesFromFavoriteCategory(allUserRelayUrls, allUserRelayUrls)
    }

    /**
     * Set subscription to all user relays and display filter to sidebar selection.
     * Call on first load / when categories change. allUserRelayUrls = all relays we stay connected to; displayUrls = what to show (sidebar selection).
     */
    fun loadNotesFromFavoriteCategory(allUserRelayUrls: List<String>, displayUrls: List<String>) {
        if (allUserRelayUrls.isEmpty()) {
            Log.d(TAG, "No relays configured for favorite category")
            _uiState.value = _uiState.value.copy(
                notes = emptyList(),
                hasRelays = false,
                isLoadingFromRelays = false
            )
            return
        }

        Log.d(TAG, "Loading notes: subscription=${allUserRelayUrls.size} relays, display=${displayUrls.size} relay(s)")
        _uiState.value = _uiState.value.copy(
            hasRelays = true,
            isLoadingFromRelays = true
        )
        notesRepository.connectToRelays(if (displayUrls.isEmpty()) allUserRelayUrls else displayUrls)

        viewModelScope.launch {
            try {
                notesRepository.ensureSubscriptionToNotes(allUserRelayUrls, limit = 100)
                _uiState.value = _uiState.value.copy(isLoadingFromRelays = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notes from relays: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load notes: ${e.message}",
                    isLoadingFromRelays = false
                )
            }
        }
    }

    /**
     * Update display filter only (sidebar selection). Does NOT change subscription or follow filter;
     * only which relays' notes are shown. Follow and reply filters stay applied.
     */
    fun setDisplayFilterOnly(displayUrls: List<String>) {
        notesRepository.connectToRelays(displayUrls)
    }

    /**
     * Load notes from a specific relay only (display filter). Subscription stays on all relays.
     */
    fun loadNotesFromSpecificRelay(relayUrl: String) {
        setDisplayFilterOnly(listOf(relayUrl))
    }

    /**
     * Full re-fetch from relays. Use sparingly; pull-to-refresh uses applyPendingNotes instead.
     */
    fun refreshNotes() {
        viewModelScope.launch {
            notesRepository.refresh()
        }
    }

    /**
     * Push profile cache into the feed so notes show updated names/avatars.
     * Call when the feed becomes visible so cached profiles (e.g. from debug Fetch all) render.
     */
    fun syncFeedAuthorsFromCache() {
        notesRepository.refreshAuthorsFromCache()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.cleanup()
        // Do not call notesRepository.disconnectAll() - shared connection and notes outlive this screen
    }
}
