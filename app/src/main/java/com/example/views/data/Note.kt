package com.example.views.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.Date

@Immutable
@Serializable
data class Note(
    val id: String,
    val author: Author,
    val content: String,
    val timestamp: Long,
    val likes: Int = 0,
    val shares: Int = 0,
    val comments: Int = 0,
    /** Number of zaps (NIP-57) on this note; shown in counts row. */
    val zapCount: Int = 0,
    /** NIP-25 emoji reactions to show (e.g. ["❤️", "🔥"]); order/count from relay. */
    val reactions: List<String> = emptyList(),
    val isLiked: Boolean = false,
    val isShared: Boolean = false,
    val mediaUrls: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val urlPreviews: List<UrlPreviewInfo> = emptyList(),
    /** Event IDs of quoted notes (from nostr:nevent1... / nostr:note1... in content). */
    val quotedEventIds: List<String> = emptyList(),
    /** Relay URL this note was received from (primary); used to filter feed by selected relay(s). */
    val relayUrl: String? = null,
    /** All relay URLs this note was seen on (same event from multiple relays); for relay orbs and display filter. */
    val relayUrls: List<String> = emptyList(),
    /** True if this kind-1 is a reply (NIP-10); shown only in thread view, not in primary feed. */
    val isReply: Boolean = false,
    /** Root note id (NIP-10 "root" e-tag); used to build threaded reply chains for kind-1. */
    val rootNoteId: String? = null,
    /** Direct parent reply/note id (NIP-10 "reply" e-tag); used to build threaded reply chains for kind-1. */
    val replyToId: String? = null
)

@Immutable
@Serializable
data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false,
    /** NIP-01 kind-0 "about" / bio. */
    val about: String? = null,
    /** NIP-05 identifier (e.g. user@domain.com). */
    val nip05: String? = null,
    /** Profile website URL. */
    val website: String? = null,
    /** Lightning address (LUD-16) for zaps, e.g. user@walletofsatoshi.com */
    val lud16: String? = null
)

@Immutable
@Serializable
data class Comment(
    val id: String,
    val author: Author,
    val content: String,
    val timestamp: Long,
    val likes: Int = 0,
    val isLiked: Boolean = false
)

@Immutable
@Serializable
data class WebSocketMessage(
    val type: String,
    val data: String
)

/** Minimal metadata for a quoted note (event id, author id, content snippet). */
@Immutable
data class QuotedNoteMeta(
    val eventId: String,
    val authorId: String,
    val contentSnippet: String
)

enum class NoteAction {
    LIKE, UNLIKE, SHARE, COMMENT, DELETE
}

@Immutable
@Serializable
data class NoteUpdate(
    val noteId: String,
    val action: String,
    val userId: String,
    val timestamp: Long
)
