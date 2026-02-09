package com.example.views.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Aggregated counts per note: zap count and NIP-25 reactions (kind-7, kind-9735).
 * Subscriptions are driven by [RelayConnectionStateMachine] when [setNoteIdsOfInterest] is set.
 */
data class NoteCounts(
    val zapCount: Int = 0,
    /** Distinct reaction emojis (e.g. ["❤️", "🔥"]); NIP-25 content or "+" as "❤️". */
    val reactions: List<String> = emptyList()
)

object NoteCountsRepository {

    private val _countsByNoteId = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    val countsByNoteId: StateFlow<Map<String, NoteCounts>> = _countsByNoteId.asStateFlow()

    /** Feed note IDs (from NotesRepository). */
    @Volatile
    private var feedNoteIds: Set<String> = emptySet()

    /** Thread reply note IDs (from thread view when visible). Merged with feed for counts subscription. */
    @Volatile
    private var threadNoteIds: Set<String> = emptySet()

    @Volatile
    private var lastMergedNoteIds: Set<String> = emptySet()

    /**
     * Set note IDs from the feed to subscribe for kind-7 and kind-9735.
     * Call from NotesRepository when displayed note IDs change (debounced).
     */
    fun setNoteIdsOfInterest(noteIds: Set<String>) {
        feedNoteIds = noteIds
        updateMergedSubscription()
    }

    /**
     * Set note IDs from the current thread view (replies). Merged with feed IDs so kind-1111 replies
     * get reaction/zap counts. Call with empty set when leaving thread view.
     */
    fun setThreadNoteIdsOfInterest(noteIds: Set<String>) {
        threadNoteIds = noteIds
        updateMergedSubscription()
    }

    private fun updateMergedSubscription() {
        val merged = feedNoteIds + threadNoteIds
        if (merged == lastMergedNoteIds) return
        lastMergedNoteIds = merged
        com.example.views.relay.RelayConnectionStateMachine.getInstance()
            .requestFeedChangeWithCounts(merged)
    }

    /**
     * Called by [RelayConnectionStateMachine] when a kind-7 or kind-9735 event is received.
     */
    fun onCountsEvent(event: Event) {
        when (event.kind) {
            7 -> handleKind7(event)
            9735 -> handleKind9735(event)
            else -> { }
        }
    }

    private fun handleKind7(event: Event) {
        // NIP-25: last "e" tag = id of note being reacted to
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val content = event.content.ifBlank { "+" }
        val emoji = when {
            content == "+" -> "❤️"
            content == "-" -> return // skip downvotes for display
            content.startsWith(":") && content.endsWith(":") -> content // :shortcode:
            content.length <= 4 -> content // single emoji
            else -> content
        }
        updateCounts(noteId) { counts ->
            val existing = counts.reactions.toMutableSet()
            existing.add(emoji)
            counts.copy(reactions = existing.toList())
        }
    }

    private fun handleKind9735(event: Event) {
        // NIP-57 zap receipt: "e" tag in the receipt points to the note that was zapped
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        updateCounts(noteId) { counts ->
            counts.copy(zapCount = counts.zapCount + 1)
        }
    }

    private fun updateCounts(noteId: String, update: (NoteCounts) -> NoteCounts) {
        _countsByNoteId.value = _countsByNoteId.value + (noteId to update(_countsByNoteId.value[noteId] ?: NoteCounts()))
    }
}
