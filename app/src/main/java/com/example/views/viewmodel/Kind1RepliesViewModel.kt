package com.example.views.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.Note
import com.example.views.data.ThreadReply
import com.example.views.data.ThreadedReply
import com.example.views.data.toThreadReplyForThread
import com.example.views.repository.Kind1RepliesRepository
import com.example.views.repository.ProfileMetadataCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Immutable
data class Kind1RepliesUiState(
    val note: Note? = null,
    val replies: List<Note> = emptyList(),
    val threadedReplies: List<ThreadedReply> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalReplyCount: Int = 0,
    val sortOrder: Kind1ReplySortOrder = Kind1ReplySortOrder.CHRONOLOGICAL
)

enum class Kind1ReplySortOrder {
    CHRONOLOGICAL,           // Oldest first
    REVERSE_CHRONOLOGICAL,   // Newest first
    MOST_LIKED               // Most liked first
}

/**
 * ViewModel for managing Kind 1 replies to Kind 1 notes
 * Handles fetching, organizing, and displaying threaded conversations for home feed
 */
class Kind1RepliesViewModel : ViewModel() {
    private val repository = Kind1RepliesRepository()

    private val _uiState = MutableStateFlow(Kind1RepliesUiState())
    val uiState: StateFlow<Kind1RepliesUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "Kind1RepliesViewModel"
    }

    init {
        observeRepliesFromRepository()
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey -> repository.updateAuthorInReplies(pubkey) }
            .launchIn(viewModelScope)
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches (used when loading replies).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        repository.setCacheRelayUrls(urls)
    }

    /**
     * Observe replies from the repository. Every time the list updates (new reply or fetched parent),
     * we re-run organizeRepliesIntoThreads so the tree stays correct (Amethyst-style).
     */
    private fun observeRepliesFromRepository() {
        viewModelScope.launch {
            repository.replies.collect { repliesMap ->
                val currentNote = _uiState.value.note
                if (currentNote != null) {
                    val replies = repliesMap[currentNote.id] ?: emptyList()
                    updateRepliesState(replies)
                }
            }
        }

        viewModelScope.launch {
            repository.isLoading.collect { isLoading ->
                _uiState.value = _uiState.value.copy(isLoading = isLoading)
            }
        }

        viewModelScope.launch {
            repository.error.collect { error ->
                if (error != null) {
                    _uiState.value = _uiState.value.copy(error = error)
                }
            }
        }
    }

    /**
     * Load Kind 1 replies for a specific note
     */
    fun loadRepliesForNote(note: Note, relayUrls: List<String>) {
        Log.d(TAG, "Loading Kind 1 replies for note ${note.id.take(8)}... from ${relayUrls.size} relays")

        _uiState.value = _uiState.value.copy(
            note = note,
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            try {
                // Connect to relays if not already connected
                repository.connectToRelays(relayUrls)

                // Fetch replies for this note
                repository.fetchRepliesForNote(
                    noteId = note.id,
                    relayUrls = relayUrls,
                    limit = 200
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading replies: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    error = "Failed to load replies: ${e.message}",
                    isLoading = false
                )
            }
        }
    }

    /**
     * Update replies state with sorting and threaded structure (NIP-10 reply chains).
     */
    private fun updateRepliesState(replies: List<Note>) {
        val sortedReplies = when (_uiState.value.sortOrder) {
            Kind1ReplySortOrder.CHRONOLOGICAL -> replies.sortedBy { it.timestamp }
            Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL -> replies.sortedByDescending { it.timestamp }
            Kind1ReplySortOrder.MOST_LIKED -> replies.sortedByDescending { it.likes }
        }
        val threadReplies = sortedReplies.map { it.toThreadReplyForThread() }
        val threadedReplies = organizeRepliesIntoThreads(threadReplies)

        val noteId = _uiState.value.note?.id
        _uiState.value = _uiState.value.copy(
            replies = sortedReplies,
            threadedReplies = threadedReplies,
            totalReplyCount = replies.size,
            isLoading = false
        )
        if (noteId != null) com.example.views.repository.ReplyCountCache.set(noteId, replies.size)

        Log.d(TAG, "Updated replies state: ${replies.size} replies, ${threadedReplies.size} root threads")
    }

    /**
     * Organize flat list of replies into threaded structure.
     * Direct replies to root and replies with no parent are roots.
     * Orphan replies (whose replyToId doesn't match any reply in the list) are also
     * promoted to root level so they remain visible even when the parent wasn't fetched.
     */
    private fun organizeRepliesIntoThreads(replies: List<ThreadReply>): List<ThreadedReply> {
        if (replies.isEmpty()) return emptyList()
        val noteId = _uiState.value.note?.id ?: return emptyList()
        val replyIds = replies.map { it.id }.toSet()

        fun buildThreadedReply(reply: ThreadReply, level: Int = 0): ThreadedReply {
            val children = replies
                .filter { it.replyToId == reply.id }
                .map { buildThreadedReply(it, level + 1) }
                .sortedBy { it.reply.timestamp }
            return ThreadedReply(reply = reply, children = children, level = level)
        }

        // Roots: direct replies to thread root, replies with no parent, OR orphans whose
        // parent isn't in the fetched list (so they don't silently disappear).
        val rootReplies = replies
            .filter { reply ->
                reply.replyToId == noteId ||
                reply.replyToId == null ||
                reply.replyToId !in replyIds
            }
            .map { reply ->
                val isOrphan = reply.replyToId != null &&
                    reply.replyToId != noteId &&
                    reply.replyToId !in replyIds
                buildThreadedReply(reply).copy(isOrphan = isOrphan)
            }

        return when (_uiState.value.sortOrder) {
            Kind1ReplySortOrder.CHRONOLOGICAL -> rootReplies.sortedBy { it.reply.timestamp }
            Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL -> rootReplies.sortedByDescending { it.reply.timestamp }
            Kind1ReplySortOrder.MOST_LIKED -> rootReplies.sortedByDescending { it.reply.likes }
        }
    }

    /**
     * Change sort order for replies
     */
    fun setSortOrder(sortOrder: Kind1ReplySortOrder) {
        if (_uiState.value.sortOrder != sortOrder) {
            _uiState.value = _uiState.value.copy(sortOrder = sortOrder)
            updateRepliesState(_uiState.value.replies)
        }
    }

    /**
     * Refresh replies for current note
     */
    fun refreshReplies(relayUrls: List<String>) {
        val currentNote = _uiState.value.note
        if (currentNote != null) {
            loadRepliesForNote(currentNote, relayUrls)
        }
    }

    /**
     * Like a reply
     */
    fun likeReply(replyId: String) {
        viewModelScope.launch {
            val currentReplies = _uiState.value.replies
            val updatedReplies = currentReplies.map { reply ->
                if (reply.id == replyId) {
                    reply.copy(
                        likes = reply.likes + (if (reply.isLiked) -1 else 1),
                        isLiked = !reply.isLiked
                    )
                } else {
                    reply
                }
            }
            updateRepliesState(updatedReplies)
        }
    }

    /**
     * Build threaded structure for display
     * Returns map of parent ID to child notes for hierarchical rendering
     */
    fun buildThreadStructure(): Map<String, List<Note>> {
        val noteId = _uiState.value.note?.id ?: return emptyMap()
        return repository.buildThreadStructure(noteId)
    }

    /**
     * Clear all replies and reset state
     */
    fun clearReplies() {
        repository.clearAllReplies()
        _uiState.value = Kind1RepliesUiState()
    }

    /**
     * Clear replies for specific note
     */
    fun clearRepliesForNote(noteId: String) {
        repository.clearRepliesForNote(noteId)
        if (_uiState.value.note?.id == noteId) {
            _uiState.value = _uiState.value.copy(
                replies = emptyList(),
                threadedReplies = emptyList(),
                totalReplyCount = 0
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnectAll()
    }
}
