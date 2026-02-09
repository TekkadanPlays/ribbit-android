package com.example.views.repository

import android.util.Log
import com.example.views.cache.ThreadReplyCache
import com.example.views.data.Author
import com.example.views.data.Note
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.TemporarySubscriptionHandle
import com.example.views.repository.ProfileMetadataCache
import com.example.views.utils.UrlDetector
import com.example.views.utils.extractPubkeysFromContent
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Repository for fetching and managing Kind 1 replies to Kind 1 notes using the shared
 * RelayConnectionStateMachine (one-off subscription API). Handles threaded conversations
 * for regular notes following NIP-10 (Reply Tags). Uses the same NostrClient as the feed
 * to avoid duplicate connections to the same relays.
 *
 * Kind 1 events are standard text notes. Replies are also Kind 1 events that:
 * - Reference the root note via "e" tags with "root" marker
 * - Reference the parent reply via "e" tags with "reply" marker
 * - Can be nested to create threaded conversations
 */
class Kind1RepliesRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    // Replies for a specific note ID
    private val _replies = MutableStateFlow<Map<String, List<Note>>>(emptyMap())
    val replies: StateFlow<Map<String, List<Note>>> = _replies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var connectedRelays = listOf<String>()
    private var cacheRelayUrls = listOf<String>()
    private val activeSubscriptions = mutableMapOf<String, TemporarySubscriptionHandle>()
    private val profileCache = ProfileMetadataCache.getInstance()

    /** Parent ids we've requested (per thread root) to avoid duplicate fetches. */
    private val pendingParentFetches = mutableMapOf<String, MutableSet<String>>()
    /** One-off subscriptions for fetching missing parents; key = "$rootNoteId:$parentId". */
    private val activeParentFetchSubscriptions = mutableMapOf<String, TemporarySubscriptionHandle>()

    /** Per-thread cache: rootNoteId -> (replyId -> Note) for fast lookup and tree building. */
    private val threadReplyCache = mutableMapOf<String, MutableMap<String, Note>>()

    /**
     * Set cache relay URLs for kind-0 profile fetches (from RelayStorageManager.loadCacheRelays).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    companion object {
        private const val TAG = "Kind1RepliesRepository"
        /** Short initial window; loading clears on first reply (live) or after this. Subscription stays open for more replies. */
        private const val INITIAL_LOAD_WINDOW_MS = 1500L
        /** Timeout for one-off parent fetch; then we destroy subscription to avoid leaks. */
        private const val PARENT_FETCH_TIMEOUT_MS = 12_000L
    }

    /**
     * Set relay URLs for subsequent fetchRepliesForNote. Uses shared client; no separate connect.
     */
    fun connectToRelays(relayUrls: List<String>) {
        Log.d(TAG, "Relay URLs set for Kind 1 replies: ${relayUrls.size}")
        connectedRelays = relayUrls
    }

    /**
     * Cancel all reply subscriptions and clear state. Does not disconnect the shared client.
     */
    fun disconnectAll() {
        Log.d(TAG, "Cleaning up Kind 1 reply subscriptions")
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        activeParentFetchSubscriptions.values.forEach { it.cancel() }
        activeParentFetchSubscriptions.clear()
        connectedRelays = emptyList()
        _replies.value = emptyMap()
    }

    /**
     * Fetch Kind 1 replies for a specific note
     *
     * @param noteId The ID of the note to fetch replies for
     * @param relayUrls Optional list of relays to query (uses connected relays if not provided)
     * @param limit Maximum number of replies to fetch
     */
    suspend fun fetchRepliesForNote(
        noteId: String,
        relayUrls: List<String>? = null,
        limit: Int = 100
    ) {
        val targetRelays = relayUrls ?: connectedRelays
        if (targetRelays.isEmpty()) {
            Log.w(TAG, "No relays available to fetch replies")
            return
        }

        _isLoading.value = true
        _error.value = null

        val cached = ThreadReplyCache.getReplies(noteId)
        val existing = _replies.value[noteId]?.toMutableList() ?: mutableListOf()
        val merged = (existing + cached).distinctBy { it.id }.sortedBy { it.timestamp }
        if (merged.isNotEmpty()) {
            _replies.value = _replies.value + (noteId to merged)
            updateThreadReplyCache(noteId, merged)
            _isLoading.value = false
            Log.d(TAG, "Emitted ${merged.size} replies for note ${noteId.take(8)}... (${cached.size} from cache, instant)")
            scheduleFetchMissingParents(noteId)
        }

        try {
            // Cancel previous subscription for this note if exists
            activeSubscriptions[noteId]?.cancel()
            activeSubscriptions.remove(noteId)

            Log.d(TAG, "Fetching Kind 1 replies for note ${noteId.take(8)}... from ${targetRelays.size} relays (shared client)")

            // Create filter for Kind 1 replies
            val filter = Filter(
                kinds = listOf(1),
                tags = mapOf("e" to listOf(noteId)),
                limit = limit
            )

            val handle = relayStateMachine.requestTemporarySubscription(
                relayUrls = targetRelays,
                filter = filter,
                onEvent = { event -> handleReplyEvent(noteId, event) }
            )
            activeSubscriptions[noteId] = handle

            // Clear loading after short window so UI shows live; subscription stays open for streaming replies
            delay(INITIAL_LOAD_WINDOW_MS)
            _isLoading.value = false
            Log.d(TAG, "Replies live for note ${noteId.take(8)}... (${getRepliesForNote(noteId).size} so far)")

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching replies: ${e.message}", e)
            _error.value = "Failed to load replies: ${e.message}"
            _isLoading.value = false
        }
    }

    /**
     * Handle incoming reply event from relay
     */
    private fun handleReplyEvent(noteId: String, event: Event) {
        try {
            if (event.kind == 1) {
                // Check if this event is actually a reply to our note
                val referencedNoteIds = extractReferencedNoteIds(event)
                if (noteId in referencedNoteIds) {
                    val reply = convertEventToNote(event)

                    // Add reply to the collection for this note
                    val currentReplies = _replies.value[noteId]?.toMutableList() ?: mutableListOf()

                    // Avoid duplicates
                    if (!currentReplies.any { it.id == reply.id }) {
                        currentReplies.add(reply)

                        // Update the flow with new replies sorted by timestamp (live update)
                        val sorted = currentReplies.sortedBy { it.timestamp }
                        _replies.value = _replies.value + (noteId to sorted)
                        updateThreadReplyCache(noteId, sorted)

                        // Clear loading as soon as we have at least one reply so UI shows content immediately
                        _isLoading.value = false
                        Log.d(TAG, "Added reply from ${reply.author.username}: ${reply.content.take(50)}... (Total: ${currentReplies.size})")
                    }
                    // Fetch any missing parents so threading can attach correctly (Amethyst-style)
                    scheduleFetchMissingParents(noteId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reply event: ${e.message}", e)
        }
    }

    /**
     * Keep thread cache in sync with reply list for fast id->Note lookup per thread.
     */
    private fun updateThreadReplyCache(rootNoteId: String, replies: List<Note>) {
        threadReplyCache.getOrPut(rootNoteId) { mutableMapOf() }.clear()
        replies.forEach { threadReplyCache[rootNoteId]!![it.id] = it }
    }

    /**
     * Find replyToIds that are not in the current list (and not the thread root) and fetch those events
     * so the thread tree can attach children to parents (Amethyst-style: resolve missing parents).
     */
    private fun scheduleFetchMissingParents(rootNoteId: String) {
        val currentReplies = _replies.value[rootNoteId] ?: return
        val existingIds = currentReplies.map { it.id }.toSet() + rootNoteId
        val missingParentIds = currentReplies.mapNotNull { it.replyToId }.filter { it !in existingIds }.toSet()
        val pending = pendingParentFetches.getOrPut(rootNoteId) { mutableSetOf() }
        missingParentIds.forEach { parentId ->
            if (parentId in pending) return@forEach
            pending.add(parentId)
            scope.launch { fetchMissingParent(parentId, rootNoteId) }
        }
    }

    /**
     * One-off fetch of an event by id; add to this thread's replies if it belongs (same root).
     * Subscription is destroyed after first event or timeout to avoid leaks.
     */
    private suspend fun fetchMissingParent(parentId: String, rootNoteId: String) {
        val targetRelays = connectedRelays
        if (targetRelays.isEmpty()) {
            pendingParentFetches[rootNoteId]?.remove(parentId)
            return
        }
        val key = "$rootNoteId:$parentId"
        val filter = Filter(
            kinds = listOf(1),
            ids = listOf(parentId),
            limit = 1
        )
        val handled = AtomicBoolean(false)
        val handle = relayStateMachine.requestTemporarySubscription(
            relayUrls = targetRelays,
            filter = filter,
            onEvent = { event ->
                if (event.kind != 1 || event.id != parentId) return@requestTemporarySubscription
                if (!handled.compareAndSet(false, true)) return@requestTemporarySubscription
                val note = convertEventToNote(event)
                if (note.rootNoteId != rootNoteId) {
                    pendingParentFetches[rootNoteId]?.remove(parentId)
                    activeParentFetchSubscriptions.remove(key)?.cancel()
                    return@requestTemporarySubscription
                }
                val currentReplies = _replies.value[rootNoteId]?.toMutableList() ?: mutableListOf()
                if (!currentReplies.any { it.id == note.id }) {
                    currentReplies.add(note)
                    val sorted = currentReplies.sortedBy { it.timestamp }
                    _replies.value = _replies.value + (rootNoteId to sorted)
                    updateThreadReplyCache(rootNoteId, sorted)
                    Log.d(TAG, "Fetched missing parent ${parentId.take(8)}... for thread ${rootNoteId.take(8)}...")
                }
                pendingParentFetches[rootNoteId]?.remove(parentId)
                activeParentFetchSubscriptions.remove(key)?.cancel()
            }
        )
        activeParentFetchSubscriptions[key] = handle
        kotlinx.coroutines.delay(PARENT_FETCH_TIMEOUT_MS)
        if (!handled.get()) {
            pendingParentFetches[rootNoteId]?.remove(parentId)
            activeParentFetchSubscriptions.remove(key)?.cancel()
            Log.d(TAG, "Timeout fetching parent ${parentId.take(8)}... for thread ${rootNoteId.take(8)}...")
        }
    }

    /**
     * Extract all note IDs referenced in "e" tags
     */
    private fun extractReferencedNoteIds(event: Event): List<String> {
        val referencedIds = mutableListOf<String>()
        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "e") {
                referencedIds.add(tag[1])
            }
        }
        return referencedIds
    }

    /**
     * Parse root and reply IDs for NIP-10 threading.
     * Uses Quartz TextNoteEvent.root()/reply() when event is kind 1 (same logic as Amethyst);
     * otherwise falls back to manual e-tag parsing.
     */
    private fun parseRootAndReplyFromEvent(event: Event): Pair<String?, String?> {
        if (event.kind == 1) {
            try {
                val textNote = EventFactory.create<TextNoteEvent>(
                    event.id,
                    event.pubKey,
                    event.createdAt,
                    event.kind,
                    event.tags,
                    event.content,
                    event.sig
                )
                val rootId = textNote.root()?.eventId
                var replyToId = textNote.reply()?.eventId

                // Fallback: root present but no reply — scan e-tags for a parent that
                // differs from root (same logic as parseThreadRelationship). Many clients
                // send ["e", rootId, relay, "root"], ["e", parentId, relay] without a
                // "reply" marker, so Quartz's markedReply() returns null and
                // unmarkedReply() may also miss it if the tag has 4+ elements.
                if (rootId != null && replyToId == null) {
                    val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
                    if (eTags.size >= 2) {
                        val ids = eTags.map { it[1] }
                        replyToId = ids.lastOrNull { it != rootId } ?: ids.last()
                    }
                }

                return rootId to replyToId
            } catch (e: Exception) {
                Log.w(TAG, "TextNoteEvent parse failed, using fallback: ${e.message}")
            }
        }
        val (rootId, replyToId, _) = parseThreadRelationship(event)
        return rootId to replyToId
    }

    /**
     * Parse thread relationship from "e" tags (fallback when not using TextNoteEvent)
     * Returns Triple of (rootId, replyToId, isDirectReply)
     */
    private fun parseThreadRelationship(event: Event): Triple<String?, String?, Boolean> {
        var rootId: String? = null
        var replyToId: String? = null

        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }

        if (eTags.isEmpty()) {
            return Triple(null, null, false)
        }

        // Look for marked tags first (NIP-10 preferred format).
        // Marker can be at index 3 (["e", id, relay, "root"/"reply"]) or index 4 when
        // pubkey comes before marker (["e", id, relay, pubkey, "reply"]) — match Amethyst's pickMarker.
        eTags.forEach { tag ->
            val eventId = tag[1]
            val marker = pickETagMarker(tag)

            when (marker) {
                "root" -> rootId = eventId
                "reply" -> replyToId = eventId
            }
        }

        // Fallback to positional format if no markers found
        if (rootId == null && replyToId == null) {
            when (eTags.size) {
                1 -> {
                    // Single "e" tag is a direct reply to that note
                    rootId = eTags[0][1]
                    replyToId = eTags[0][1]
                }
                else -> {
                    // First "e" is root, last "e" is reply-to (NIP-10 positional)
                    rootId = eTags.first()[1]
                    replyToId = eTags.last()[1]
                }
            }
        }

        // Nested reply with marked "root" but no marked "reply": many clients send
        // ["e", rootId, relay, "root"], ["e", parentId, relay] (reply-to only positional).
        // Without this, replyToId stays null and the reply is shown at root — fragmenting the tree.
        // Prefer the tag whose id != rootId (the actual parent); if all same, direct reply to root.
        if (rootId != null && replyToId == null && eTags.size >= 2) {
            val ids = eTags.map { it[1] }
            replyToId = ids.lastOrNull { it != rootId } ?: ids.last()
        }

        // Determine if this is a direct reply to root
        val isDirectReply = rootId == replyToId || replyToId == null

        return Triple(rootId, replyToId, isDirectReply)
    }

    /**
     * Pick "root" or "reply" marker from an "e" tag, checking index 3, then 4, then 2
     * (same order as Amethyst MarkedETag.pickMarker) so we handle both NIP-10 orderings:
     * ["e", id, relay, marker] and ["e", id, relay, pubkey, marker].
     */
    private fun pickETagMarker(tag: Array<out String>): String? {
        val m3 = tag.getOrNull(3)
        if (m3 == "root" || m3 == "reply") return m3
        val m4 = tag.getOrNull(4)
        if (m4 == "root" || m4 == "reply") return m4
        val m2 = tag.getOrNull(2)
        if (m2 == "root" || m2 == "reply") return m2
        return null
    }

    /**
     * Convert Nostr Event to Note data model
     */
    private fun convertEventToNote(event: Event): Note {
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        if (profileCache.getAuthor(pubkeyHex) == null && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(listOf(pubkeyHex), cacheRelayUrls) }
        }
        // Request kind-0 for pubkeys mentioned in content so @mentions resolve to display names
        val contentPubkeys = extractPubkeysFromContent(event.content).filter { profileCache.getAuthor(it) == null }
        if (contentPubkeys.isNotEmpty() && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(contentPubkeys, cacheRelayUrls) }
        }

        // Extract hashtags from tags
        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }

        // Extract image and video URLs from content (embedded in card media area)
        val mediaUrls = UrlDetector.findUrls(event.content)
            .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
            .distinct()

        // NIP-10 thread relationship: use Quartz TextNoteEvent (same as Amethyst) when possible
        val (rootId, replyToId) = parseRootAndReplyFromEvent(event)

        return Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L, // Convert to milliseconds
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            isReply = rootId != null || replyToId != null,
            rootNoteId = rootId,
            replyToId = replyToId,
            // Per-event relay source not yet available from temporary subscription; leave empty so we don't show misleading "all relays" orbs
            relayUrls = emptyList()
        )
    }

    /**
     * Get replies for a specific note ID
     */
    fun getRepliesForNote(noteId: String): List<Note> {
        return _replies.value[noteId] ?: emptyList()
    }

    /**
     * Get a reply by id from the thread cache (fast lookup for a thread root).
     * Returns null if not in cache or not part of that thread.
     */
    fun getNoteInThread(rootNoteId: String, replyId: String): Note? =
        threadReplyCache[rootNoteId]?.get(replyId)

    /**
     * Get reply count for a specific note
     */
    fun getReplyCount(noteId: String): Int {
        return _replies.value[noteId]?.size ?: 0
    }

    /**
     * Build threaded structure from flat list of replies using NIP-10 replyToId.
     * Returns a map of parentId to list of direct child notes.
     */
    fun buildThreadStructure(noteId: String): Map<String, List<Note>> {
        val replies = getRepliesForNote(noteId)
        val threadMap = mutableMapOf<String, MutableList<Note>>()

        replies.forEach { reply ->
            val parentId = reply.replyToId ?: noteId
            threadMap.getOrPut(parentId) { mutableListOf() }.add(reply)
        }

        return threadMap
    }

    /**
     * Update author in all reply lists when profile cache is updated.
     */
    fun updateAuthorInReplies(pubkey: String) {
        val author = profileCache.getAuthor(pubkey) ?: return
        val current = _replies.value
        var updated = false
        val keyLower = pubkey.lowercase()
        val newMap = current.mapValues { (_, list) ->
            val newList = list.map { note ->
                if (note.author.id.lowercase() == keyLower) {
                    updated = true
                    note.copy(author = author)
                } else note
            }
            newList
        }
        if (updated) _replies.value = newMap
    }

    /**
     * Clear replies for a specific note (and pending parent fetches / thread cache for that thread).
     */
    fun clearRepliesForNote(noteId: String) {
        activeSubscriptions[noteId]?.cancel()
        activeSubscriptions.remove(noteId)
        pendingParentFetches.remove(noteId)
        threadReplyCache.remove(noteId)
        activeParentFetchSubscriptions.filterKeys { it.startsWith("$noteId:") }.values.forEach { it.cancel() }
        activeParentFetchSubscriptions.entries.removeIf { it.key.startsWith("$noteId:") }
        _replies.value = _replies.value - noteId
    }

    /**
     * Clear all replies, pending parent fetches, and thread cache.
     */
    fun clearAllReplies() {
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        activeParentFetchSubscriptions.values.forEach { it.cancel() }
        activeParentFetchSubscriptions.clear()
        pendingParentFetches.clear()
        threadReplyCache.clear()
        _replies.value = emptyMap()
    }

    /**
     * Check if currently loading replies
     */
    fun isLoadingReplies(): Boolean = _isLoading.value
}
