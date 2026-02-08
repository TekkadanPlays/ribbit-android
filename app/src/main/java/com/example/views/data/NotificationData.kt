package com.example.views.data

/**
 * Notification item for the notifications feed (replies, likes, reposts, zaps, etc.).
 * Amethyst-style: target note for likes/zaps, root for replies, consolidated reposts.
 */
data class NotificationData(
    val id: String,
    val type: NotificationType,
    val text: String,
    val timeAgo: String,
    /** The notification event as a note (e.g. reply content, reaction event). */
    val note: Note? = null,
    val author: Author? = null,
    /** Note that was liked (kind 7 "e" tag) or zapped (9735 "e" tag); for display below the line. */
    val targetNote: Note? = null,
    /** Id of the target note when we don't have the note yet (e.g. still fetching). */
    val targetNoteId: String? = null,
    /** Root note id (e-tag root) for replies; open thread at this note. */
    val rootNoteId: String? = null,
    /** Reply note id (this notification's event id) for replies. */
    val replyNoteId: String? = null,
    /** Reply kind: 1 = thread (kind-1), 1111 = topic (kind-1111); null for non-reply or legacy. */
    val replyKind: Int? = null,
    /** For consolidated reposts: pubkeys of users who reposted this note (one row per reposted note). */
    val reposterPubkeys: List<String> = emptyList(),
    /** For consolidated notifications: pubkeys of actors (likes/zaps/reposts). */
    val actorPubkeys: List<String> = emptyList(),
    /** Sort key: latest repost time or notification time. */
    val sortTimestamp: Long = 0L
)

enum class NotificationType {
    LIKE,
    REPLY,
    MENTION,
    REPOST,
    ZAP
}
