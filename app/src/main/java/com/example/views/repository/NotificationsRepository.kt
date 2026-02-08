package com.example.views.repository

import android.util.Log
import com.example.views.data.Author
import com.example.views.data.NotificationData
import com.example.views.data.NotificationType
import com.example.views.data.Note
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.utils.normalizeAuthorIdForCache
import com.example.views.relay.TemporarySubscriptionHandle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for real Nostr notifications: events that reference the user (p tag).
 * Amethyst-style: kinds 1 (reply/mention), 7 (like), 6 (repost), 9735 (zap). No follows.
 * Parses e-tags for target note, root/reply for replies; consolidates reposts by reposted note id.
 */
object NotificationsRepository {

    private const val TAG = "NotificationsRepository"
    private const val NOTIFICATION_KIND_TEXT = 1
    private const val NOTIFICATION_KIND_REPOST = 6
    private const val NOTIFICATION_KIND_REACTION = 7
    private const val NOTIFICATION_KIND_ZAP = 9735
    private const val NOTIFICATION_KIND_TOPIC_REPLY = 1111
    private const val ONE_WEEK_SEC = 7 * 24 * 60 * 60L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val profileCache = ProfileMetadataCache.getInstance()
    private val json = Json { ignoreUnknownKeys = true }

    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
    val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

    /** IDs of notifications the user has "seen" (opened notifications screen or tapped one). Badge and dropdown use unseen count. */
    private val _seenIds = MutableStateFlow<Set<String>>(emptySet())
    val seenIds: StateFlow<Set<String>> = _seenIds.asStateFlow()
    val unseenCount: StateFlow<Int> = combine(_notifications, _seenIds) { list, seen ->
        list.count { it.id !in seen }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val notificationsById = ConcurrentHashMap<String, NotificationData>()
    private var notificationsHandle: TemporarySubscriptionHandle? = null
    private var cacheRelayUrls = listOf<String>()
    private var subscriptionRelayUrls = listOf<String>()
    /** Current user hex pubkey (p-tag); used to filter kind-7 so we only show reactions to our notes. */
    private var myPubkeyHex: String? = null

    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    fun getCacheRelayUrls(): List<String> = cacheRelayUrls

    /** Mark all current notifications as seen (e.g. when user opens the notifications screen). Clears badge. */
    fun markAllAsSeen() {
        _seenIds.value = _notifications.value.mapTo(mutableSetOf()) { it.id }
    }

    /** Mark one notification as seen (e.g. when user taps it to open thread). */
    fun markAsSeen(notificationId: String) {
        _seenIds.value = _seenIds.value + notificationId
    }

    /**
     * Start subscription for the given user; events with "p" tag = pubkey (replies, likes, reposts, zaps). No follows.
     */
    fun startSubscription(pubkey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty() || pubkey.isBlank()) {
            Log.w(TAG, "startSubscription: empty relays or pubkey")
            return
        }
        stopSubscription()
        notificationsById.clear()
        _notifications.value = emptyList()
        subscriptionRelayUrls = relayUrls
        myPubkeyHex = pubkey
        val since = (System.currentTimeMillis() / 1000) - ONE_WEEK_SEC
        val filter = Filter(
            kinds = listOf(NOTIFICATION_KIND_TEXT, NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_ZAP, NOTIFICATION_KIND_TOPIC_REPLY),
            tags = mapOf("p" to listOf(pubkey)),
            since = since,
            limit = 500
        )
        val stateMachine = RelayConnectionStateMachine.getInstance()
        notificationsHandle = stateMachine.requestTemporarySubscription(relayUrls, filter) { event -> handleEvent(event) }
        Log.d(TAG, "Notifications subscription started for ${pubkey.take(8)}... on ${relayUrls.size} relays")
    }

    fun stopSubscription() {
        try {
            notificationsHandle?.cancel()
            notificationsHandle = null
        } catch (_: Exception) { }
        Log.d(TAG, "Notifications subscription stopped")
    }

    private fun handleEvent(event: Event) {
        if (event.kind !in listOf(NOTIFICATION_KIND_TEXT, NOTIFICATION_KIND_REPOST, NOTIFICATION_KIND_REACTION, NOTIFICATION_KIND_ZAP, NOTIFICATION_KIND_TOPIC_REPLY)) return
        val author = profileCache.resolveAuthor(event.pubKey)
        if (profileCache.getAuthor(event.pubKey) == null && cacheRelayUrls.isNotEmpty()) {
            scope.launch { profileCache.requestProfiles(listOf(event.pubKey), cacheRelayUrls) }
        }
        val ts = event.createdAt * 1000L
        when (event.kind) {
            NOTIFICATION_KIND_REACTION -> handleLike(event, author, ts)
            NOTIFICATION_KIND_TEXT -> handleReply(event, author, ts)
            NOTIFICATION_KIND_TOPIC_REPLY -> handleTopicReply(event, author, ts)
            NOTIFICATION_KIND_REPOST -> handleRepost(event, author, ts)
            NOTIFICATION_KIND_ZAP -> handleZap(event, author, ts)
            else -> { }
        }
    }

    private fun handleLike(event: Event, author: Author, ts: Long) {
        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (eTag == null) return
        val list = likeByTargetId.getOrPut(eTag) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == event.pubKey }) list.add(event.pubKey to ts)
        }
        val actorPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val text = if (actorPubkeys.size == 1) "${author.displayName} liked your post" else "${actorPubkeys.size} people liked your post"
        val data = NotificationData(
            id = "like:$eTag",
            type = NotificationType.LIKE,
            text = text,
            timeAgo = formatTimeAgo(latestTs),
            note = null,
            author = author,
            targetNoteId = eTag,
            actorPubkeys = actorPubkeys,
            sortTimestamp = latestTs
        )
        notificationsById[data.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(eTag, data.id) { d -> { note -> d.copy(targetNote = note) } } }
    }

    private fun handleReply(event: Event, author: Author, ts: Long) {
        // Don't show notifications for our own replies
        if (myPubkeyHex != null && normalizeAuthorIdForCache(author.id) == myPubkeyHex) return
        // Must have a root note to classify as a reply; standalone mentions (kind-1 with just a p-tag) are less useful
        val rootId = getReplyRootNoteId(event)
        // Determine if the user is tagged as a reply target (p-tag) or just mentioned in passing
        val pTags = event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1].lowercase() }
        val isDirectlyTagged = myPubkeyHex != null && myPubkeyHex!!.lowercase() in pTags
        // Skip if no root note AND not directly tagged (random mention, not a reply to us)
        if (rootId == null && !isDirectlyTagged) return
        val note = eventToNote(event)
        val replyId = event.id
        val isMention = rootId == null
        val text = if (isMention) "${author.displayName} mentioned you" else "${author.displayName} replied to your post"
        val notifType = if (isMention) NotificationType.MENTION else NotificationType.REPLY
        val data = NotificationData(
            id = event.id,
            type = notifType,
            text = text,
            timeAgo = formatTimeAgo(ts),
            note = note,
            author = author,
            rootNoteId = rootId,
            replyNoteId = replyId,
            replyKind = NOTIFICATION_KIND_TEXT,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        if (rootId != null) {
            scope.launch { fetchAndSetTargetNote(rootId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
        }
    }

    /** NIP-22: root note id from uppercase "E" tag or ["e", id, ..., "root"] for kind-1111. */
    private fun getTopicReplyRootNoteId(event: Event): String? {
        val tags = event.tags
        tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "E") return tag.getOrNull(1)
        }
        val eTags = tags.filter { it.size >= 2 && it[0] == "e" }
        val rootTag = eTags.firstOrNull { tag ->
            val m3 = tag.getOrNull(3)
            val m4 = tag.getOrNull(4)
            m3 == "root" || m4 == "root"
        }
        if (rootTag != null) return rootTag.getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    private fun handleTopicReply(event: Event, author: Author, ts: Long) {
        if (myPubkeyHex != null && normalizeAuthorIdForCache(author.id) == myPubkeyHex) return
        val rootId = getTopicReplyRootNoteId(event) ?: return
        val pTags = event.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1].lowercase() }
        val isDirectlyTagged = myPubkeyHex != null && myPubkeyHex!!.lowercase() in pTags
        if (!isDirectlyTagged) return
        val note = eventToNote(event)
        val data = NotificationData(
            id = event.id,
            type = NotificationType.REPLY,
            text = "${author.displayName} replied to your topic",
            timeAgo = formatTimeAgo(ts),
            note = note,
            author = author,
            rootNoteId = rootId,
            replyNoteId = event.id,
            replyKind = NOTIFICATION_KIND_TOPIC_REPLY,
            sortTimestamp = ts
        )
        notificationsById[event.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(rootId, event.id) { d -> { n -> d.copy(targetNote = n) } } }
    }

    /** NIP-10: root note id from "e" tag with "root" marker, or first "e" tag. */
    private fun getReplyRootNoteId(event: Event): String? {
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        val rootTag = eTags.firstOrNull { tag ->
            val m3 = tag.getOrNull(3)
            val m4 = tag.getOrNull(4)
            m3 == "root" || m4 == "root"
        }
        if (rootTag != null) return rootTag.getOrNull(1)
        return eTags.firstOrNull()?.getOrNull(1)
    }

    private val repostByTargetId = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val likeByTargetId = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()
    private val zapByTargetId = ConcurrentHashMap<String, MutableList<Pair<String, Long>>>()

    private fun handleRepost(event: Event, author: Author, ts: Long) {
        val repostedNoteId = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
            ?: parseRepostedNoteIdFromContent(event.content)
        if (repostedNoteId == null) {
            val data = NotificationData(
                id = event.id,
                type = NotificationType.REPOST,
                text = "${author.displayName} reposted",
                timeAgo = formatTimeAgo(ts),
                note = null,
                author = author,
                sortTimestamp = ts
            )
            notificationsById[event.id] = data
            emitSorted()
            return
        }
        val list = repostByTargetId.getOrPut(repostedNoteId) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == event.pubKey }) list.add(event.pubKey to ts)
        }
        val reposterPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val targetNote = parseRepostedNoteFromContent(event.content)
        val data = NotificationData(
            id = "repost:$repostedNoteId",
            type = NotificationType.REPOST,
            text = if (reposterPubkeys.size == 1) "${author.displayName} reposted your post" else "${reposterPubkeys.size} reposted your post",
            timeAgo = formatTimeAgo(latestTs),
            note = null,
            author = author,
            targetNote = targetNote,
            targetNoteId = if (targetNote == null) repostedNoteId else null,
            reposterPubkeys = reposterPubkeys,
            actorPubkeys = reposterPubkeys,
            sortTimestamp = latestTs
        )
        notificationsById[data.id] = data
        emitSorted()
    }

    private fun parseRepostedNoteIdFromContent(content: String): String? {
        return Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1)
    }

    private fun parseRepostedNoteFromContent(content: String): Note? {
        val id = Regex(""""id"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
        val pubkey = Regex(""""pubkey"\s*:\s*"([a-f0-9]{64})"""").find(content)?.groupValues?.get(1) ?: return null
        val created = Regex(""""created_at"\s*:\s*([0-9]+)""").find(content)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val cont = Regex(""""content"\s*:\s*"((?:[^"\\\\]|\\\\.)*)"""").find(content)?.groupValues?.get(1) ?: ""
        val author = profileCache.resolveAuthor(pubkey)
        return Note(
            id = id,
            author = author,
            content = cont,
            timestamp = created * 1000L,
            likes = 0, shares = 0, comments = 0,
            isLiked = false, hashtags = emptyList(), mediaUrls = emptyList()
        )
    }

    private fun handleZap(event: Event, author: Author, ts: Long) {
        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        if (eTag == null) return
        val list = zapByTargetId.getOrPut(eTag) { mutableListOf() }
        synchronized(list) {
            if (list.none { it.first == event.pubKey }) list.add(event.pubKey to ts)
        }
        val actorPubkeys = list.map { it.first }.distinct()
        val latestTs = list.maxOfOrNull { it.second } ?: ts
        val text = if (actorPubkeys.size == 1) "${author.displayName} zapped your post" else "${actorPubkeys.size} people zapped your post"
        val data = NotificationData(
            id = "zap:$eTag",
            type = NotificationType.ZAP,
            text = text,
            timeAgo = formatTimeAgo(latestTs),
            note = null,
            author = author,
            targetNoteId = eTag,
            actorPubkeys = actorPubkeys,
            sortTimestamp = latestTs
        )
        notificationsById[data.id] = data
        emitSorted()
        scope.launch { fetchAndSetTargetNote(eTag, data.id) { d -> { note -> d.copy(targetNote = note) } } }
    }

    private fun emitSorted() {
        _notifications.value = notificationsById.values
            .sortedByDescending { it.sortTimestamp }
            .toList()
    }

    private suspend fun fetchAndSetTargetNote(noteId: String, notificationId: String, update: (NotificationData) -> (Note?) -> NotificationData) {
        if (subscriptionRelayUrls.isEmpty()) return
        val filter = Filter(kinds = listOf(1), ids = listOf(noteId), limit = 1)
        var fetched: Note? = null
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscription(subscriptionRelayUrls, filter) { ev ->
            if (ev.kind == 1) fetched = eventToNote(ev)
        }
        delay(3000)
        handle.cancel()
        val note = fetched
        if (note != null) {
            val current = notificationsById[notificationId] ?: return
            // Kind-7 (like): only show if target note author is the current user
            if (current.type == NotificationType.LIKE && myPubkeyHex != null) {
                val targetAuthorHex = normalizeAuthorIdForCache(note.author.id)
                if (targetAuthorHex != myPubkeyHex) {
                    notificationsById.remove(notificationId)
                    emitSorted()
                    return
                }
            }
            // Kind-1 (reply): only show if the note being replied to (root) was authored by the current user
            if (current.type == NotificationType.REPLY && myPubkeyHex != null) {
                val rootAuthorHex = normalizeAuthorIdForCache(note.author.id)
                if (rootAuthorHex != myPubkeyHex) {
                    notificationsById.remove(notificationId)
                    emitSorted()
                    return
                }
            }
            notificationsById[notificationId] = update(current)(note)
            emitSorted()
        }
    }

    private fun eventToNote(event: Event): Note {
        val author = profileCache.resolveAuthor(event.pubKey)
        val hashtags = event.tags.toList()
            .filter { it.size >= 2 && it[0] == "t" }
            .mapNotNull { it.getOrNull(1) }
        return Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = emptyList(),
            isReply = false
        )
    }

    private fun formatTimeAgo(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }
}
