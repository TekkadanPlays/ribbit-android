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

    /** Last set of note IDs we requested for subscription; used to avoid re-subscribing on every tick. */
    @Volatile
    private var lastNoteIdsOfInterest: Set<String> = emptySet()

    /**
     * Set note IDs to subscribe for kind-7 and kind-9735. Pass empty to stop counts subscription.
     * Call from feed layer (e.g. NotesRepository) when displayed note IDs change (debounced).
     */
    fun setNoteIdsOfInterest(noteIds: Set<String>) {
        if (noteIds == lastNoteIdsOfInterest) return
        lastNoteIdsOfInterest = noteIds
        com.example.views.relay.RelayConnectionStateMachine.getInstance()
            .requestFeedChangeWithCounts(noteIds)
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
