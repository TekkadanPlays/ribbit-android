package com.example.views.repository

import android.util.Log
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.TemporarySubscriptionHandle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Aggregated counts per note: zap count and NIP-25 reactions (kind-7, kind-9735).
 *
 * Uses a **dedicated temporary subscription** to NIP-65 indexer relays (from
 * [Nip65RelayListRepository]) for counts, completely decoupled from the main feed
 * subscription. This avoids burdening feed relays with counts filters and uses
 * relays that are better suited for aggregating reactions and zap receipts.
 */
data class NoteCounts(
    val zapCount: Int = 0,
    /** Distinct reaction emojis (e.g. ["❤️", "🔥"]); NIP-25 content or "+" as "❤️". */
    val reactions: List<String> = emptyList()
)

object NoteCountsRepository {

    private const val TAG = "NoteCountsRepository"
    private const val DEBOUNCE_MS = 800L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _countsByNoteId = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    val countsByNoteId: StateFlow<Map<String, NoteCounts>> = _countsByNoteId.asStateFlow()

    /** Feed note IDs (from NotesRepository). */
    @Volatile
    private var feedNoteIds: Set<String> = emptySet()

    /** Topic note IDs (kind-11 from TopicFeedScreen). Merged with feed for counts subscription. */
    @Volatile
    private var topicNoteIds: Set<String> = emptySet()

    /** Thread reply note IDs (from thread view when visible). Merged with feed for counts subscription. */
    @Volatile
    private var threadNoteIds: Set<String> = emptySet()

    @Volatile
    private var lastSubscribedNoteIds: Set<String> = emptySet()

    /** Active counts subscription handle (temporary sub to indexer relays). */
    @Volatile
    private var countsHandle: TemporarySubscriptionHandle? = null

    /** Debounce job so rapid note-ID changes don't thrash subscriptions. */
    private var debounceJob: Job? = null

    /** Dedup: event IDs we've already processed so relay overlap doesn't double-count. */
    private val processedEventIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Set note IDs from the feed to subscribe for kind-7 and kind-9735.
     * Call from NotesRepository when displayed note IDs change (debounced).
     */
    fun setNoteIdsOfInterest(noteIds: Set<String>) {
        feedNoteIds = noteIds
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the current thread view (replies). Merged with feed IDs so replies
     * get reaction/zap counts. Call with empty set when leaving thread view.
     */
    fun setThreadNoteIdsOfInterest(noteIds: Set<String>) {
        threadNoteIds = noteIds
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the topic feed (kind-11 topics). Merged with feed and thread IDs
     * so topic reactions/zaps get counted. Call with empty set when leaving topic feed.
     */
    fun setTopicNoteIdsOfInterest(noteIds: Set<String>) {
        topicNoteIds = noteIds
        scheduleSubscriptionUpdate()
    }

    private fun scheduleSubscriptionUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            updateCountsSubscription()
        }
    }

    /**
     * Open a dedicated temporary subscription to indexer relays for kind-7 and kind-9735.
     * Replaces any previous counts subscription.
     */
    private fun updateCountsSubscription() {
        val merged = feedNoteIds + topicNoteIds + threadNoteIds
        if (merged.isEmpty()) {
            countsHandle?.cancel()
            countsHandle = null
            lastSubscribedNoteIds = emptySet()
            return
        }
        if (merged == lastSubscribedNoteIds) return
        lastSubscribedNoteIds = merged

        // Cancel previous subscription
        countsHandle?.cancel()
        countsHandle = null

        val indexerRelays = Nip65RelayListRepository.getCountsRelayUrls()
        if (indexerRelays.isEmpty()) {
            Log.w(TAG, "No indexer relays available for counts subscription")
            return
        }

        // Cap note IDs to avoid oversized filters
        val noteIdList = merged.take(200).toList()

        val filters = listOf(
            Filter(kinds = listOf(7), tags = mapOf("e" to noteIdList)),
            Filter(kinds = listOf(9735), tags = mapOf("e" to noteIdList))
        )

        Log.d(TAG, "Opening counts subscription: ${noteIdList.size} notes on ${indexerRelays.size} indexer relays (${indexerRelays.take(3).joinToString()})")

        val handle = RelayConnectionStateMachine.getInstance()
            .requestTemporarySubscription(indexerRelays, filters)  { event ->
                onCountsEvent(event)
            }
        countsHandle = handle
    }

    /**
     * Called when a kind-7 or kind-9735 event is received from the counts subscription
     * OR from the main feed subscription (backward compat).
     */
    fun onCountsEvent(event: Event) {
        // Dedup across relays
        if (!processedEventIds.add(event.id)) return
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

    /**
     * Clear all counts and cancel subscription (e.g. on logout).
     */
    fun clear() {
        countsHandle?.cancel()
        countsHandle = null
        debounceJob?.cancel()
        feedNoteIds = emptySet()
        topicNoteIds = emptySet()
        threadNoteIds = emptySet()
        lastSubscribedNoteIds = emptySet()
        processedEventIds.clear()
        _countsByNoteId.value = emptyMap()
    }
}
