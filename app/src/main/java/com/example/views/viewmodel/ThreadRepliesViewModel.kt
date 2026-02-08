package com.example.views.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.NoteWithReplies
import com.example.views.data.ThreadReply
import com.example.views.data.ThreadedReply
import com.example.views.data.Note
import com.example.views.repository.ProfileMetadataCache
import com.example.views.repository.ThreadRepliesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@Immutable
data class ThreadRepliesUiState(
    val note: Note? = null,
    val replies: List<ThreadReply> = emptyList(),
    val threadedReplies: List<ThreadedReply> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val totalReplyCount: Int = 0,
    val sortOrder: ReplySortOrder = ReplySortOrder.CHRONOLOGICAL
)

enum class ReplySortOrder {
    CHRONOLOGICAL,      // Oldest first
    REVERSE_CHRONOLOGICAL, // Newest first
    MOST_LIKED          // Most liked first
}

/**
 * ViewModel for managing thread replies (kind 1111 events)
 * Handles fetching, organizing, and displaying threaded conversations
 */
class ThreadRepliesViewModel : ViewModel() {
    private val repository = ThreadRepliesRepository()

    private val _uiState = MutableStateFlow(ThreadRepliesUiState())
    val uiState: StateFlow<ThreadRepliesUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "ThreadRepliesViewModel"
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
     * Observe replies from the repository
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
     * Load thread replies for a specific note
     */
    fun loadRepliesForNote(note: Note, relayUrls: List<String>) {
        Log.d(TAG, "Loading replies for note ${note.id.take(8)}... from ${relayUrls.size} relays")

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
     * Update replies state with sorting and threading
     */
    private fun updateRepliesState(replies: List<ThreadReply>) {
        val sortedReplies = when (_uiState.value.sortOrder) {
            ReplySortOrder.CHRONOLOGICAL -> replies.sortedBy { it.timestamp }
            ReplySortOrder.REVERSE_CHRONOLOGICAL -> replies.sortedByDescending { it.timestamp }
            ReplySortOrder.MOST_LIKED -> replies.sortedByDescending { it.likes }
        }

        val threadedReplies = organizeRepliesIntoThreads(sortedReplies)

        val noteId = _uiState.value.note?.id
        _uiState.value = _uiState.value.copy(
            replies = sortedReplies,
            threadedReplies = threadedReplies,
            totalReplyCount = replies.size,
            isLoading = false
        )
        if (noteId != null) com.example.views.repository.ReplyCountCache.set(noteId, replies.size)

        Log.d(TAG, "Updated replies state: ${replies.size} replies, ${threadedReplies.size} threads")
    }

    /**
     * Organize flat list of replies into threaded structure
     */
    private fun organizeRepliesIntoThreads(replies: List<ThreadReply>): List<ThreadedReply> {
        if (replies.isEmpty()) return emptyList()

        val noteId = _uiState.value.note?.id ?: return emptyList()
        val replyMap = replies.associateBy { it.id }

        // Build threaded structure recursively
        fun buildThreadedReply(reply: ThreadReply, level: Int = 0): ThreadedReply {
            val children = replies
                .filter { it.replyToId == reply.id }
                .map { buildThreadedReply(it, level + 1) }
                .sortedBy { it.reply.timestamp }

            return ThreadedReply(
                reply = reply,
                children = children,
                level = level
            )
        }

        // Topics (Kind 1111): strict roots = only direct-to-thread or no parent. Nested replies (replyToId = another reply.id) become children.
        val rootReplies = replies
            .filter { reply ->
                reply.replyToId == noteId || reply.replyToId == null
            }
            .map { buildThreadedReply(it) }

        return when (_uiState.value.sortOrder) {
            ReplySortOrder.CHRONOLOGICAL -> rootReplies.sortedBy { it.reply.timestamp }
            ReplySortOrder.REVERSE_CHRONOLOGICAL -> rootReplies.sortedByDescending { it.reply.timestamp }
            ReplySortOrder.MOST_LIKED -> rootReplies.sortedByDescending { it.reply.likes }
        }
    }

    /**
     * Change sort order for replies
     */
    fun setSortOrder(sortOrder: ReplySortOrder) {
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
     * Get direct replies count (top-level replies only)
     */
    fun getDirectRepliesCount(): Int {
        val noteId = _uiState.value.note?.id ?: return 0
        return _uiState.value.replies.count { reply ->
            reply.replyToId == noteId || reply.isDirectReply
        }
    }

    /**
     * Get nested replies count
     */
    fun getNestedRepliesCount(): Int {
        val directCount = getDirectRepliesCount()
        return _uiState.value.totalReplyCount - directCount
    }

    /**
     * Clear all replies and reset state
     */
    fun clearReplies() {
        repository.clearAllReplies()
        _uiState.value = ThreadRepliesUiState()
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
