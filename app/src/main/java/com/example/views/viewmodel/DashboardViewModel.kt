package com.example.views.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.Note
import com.example.views.data.NoteUpdate
import com.example.views.data.SampleData
import com.example.views.network.WebSocketClient
import com.example.views.repository.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class DashboardUiState(
    val notes: List<Note> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentDestination: String = "home",
    val hasRelays: Boolean = false,
    val isLoadingFromRelays: Boolean = false
)

class DashboardViewModel : ViewModel() {
    private val webSocketClient = WebSocketClient()
    private val notesRepository = NotesRepository()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    init {
        // Start with empty state until relays are configured
        loadInitialData()
        connectWebSocket()
        observeNotesFromRepository()
    }

    private fun loadInitialData() {
        _uiState.value = _uiState.value.copy(
            notes = emptyList(),
            isLoading = false,
            hasRelays = false
        )
    }

    /**
     * Observe notes from the NotesRepository
     */
    private fun observeNotesFromRepository() {
        viewModelScope.launch {
            notesRepository.notes.collect { notes ->
                _uiState.value = _uiState.value.copy(
                    notes = notes,
                    isLoadingFromRelays = false,
                    hasRelays = notes.isNotEmpty() || _uiState.value.hasRelays
                )
            }
        }

        viewModelScope.launch {
            notesRepository.isLoading.collect { isLoading ->
                _uiState.value = _uiState.value.copy(
                    isLoadingFromRelays = isLoading
                )
            }
        }

        viewModelScope.launch {
            notesRepository.error.collect { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(
                        error = error
                    )
                }
            }
        }


    }

    private fun connectWebSocket() {
        viewModelScope.launch {
            try {
                webSocketClient.connect()
                webSocketClient.loadNotes(SampleData.sampleNotes)

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
                loadNotesFromSpecificRelay(relayUrl)
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
     * Load notes from all general relays
     */
    fun loadNotesFromAllGeneralRelays(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) {
            Log.d(TAG, "No general relays configured")
            _uiState.value = _uiState.value.copy(
                notes = emptyList(),
                hasRelays = false,
                isLoadingFromRelays = false
            )
            return
        }

        Log.d(TAG, "Loading notes from ${relayUrls.size} general relays")
        _uiState.value = _uiState.value.copy(
            hasRelays = true,
            isLoadingFromRelays = true
        )

        viewModelScope.launch {
            try {
                notesRepository.disconnectAll()
                notesRepository.connectToRelays(relayUrls)
                notesRepository.subscribeToNotes(limit = 100)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notes from general relays: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load notes: ${e.message}",
                    isLoadingFromRelays = false
                )
            }
        }
    }

    /**
     * Load notes from user's favorite category relays
     */
    fun loadNotesFromFavoriteCategory(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) {
            Log.d(TAG, "No relays configured for favorite category")
            _uiState.value = _uiState.value.copy(
                notes = emptyList(),
                hasRelays = false,
                isLoadingFromRelays = false
            )
            return
        }

        Log.d(TAG, "Loading notes from ${relayUrls.size} relays")
        _uiState.value = _uiState.value.copy(
            hasRelays = true,
            isLoadingFromRelays = true
        )

        viewModelScope.launch {
            try {
                notesRepository.disconnectAll()
                notesRepository.connectToRelays(relayUrls)
                notesRepository.subscribeToNotes(limit = 100)
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
     * Load notes from a specific relay only
     */
    fun loadNotesFromSpecificRelay(relayUrl: String) {
        Log.d(TAG, "Loading notes from specific relay: $relayUrl")
        _uiState.value = _uiState.value.copy(
            hasRelays = true,
            isLoadingFromRelays = true
        )

        viewModelScope.launch {
            try {
                notesRepository.disconnectAll()
                notesRepository.connectToRelays(listOf(relayUrl))
                notesRepository.subscribeToRelayNotes(relayUrl, limit = 100)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading notes from relay $relayUrl: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load notes from relay: ${e.message}",
                    isLoadingFromRelays = false
                )
            }
        }
    }

    /**
     * Refresh notes - flush cached notes to visible feed
     */
    fun refreshNotes() {
        Log.d(TAG, "Refreshing notes - flushing cached notes to feed")
        // Notes now stream live - no manual flush needed
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.cleanup()
        notesRepository.disconnectAll()
    }
}
