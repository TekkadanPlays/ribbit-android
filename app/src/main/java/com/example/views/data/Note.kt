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
    val isLiked: Boolean = false,
    val isShared: Boolean = false,
    val mediaUrls: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val urlPreviews: List<UrlPreviewInfo> = emptyList()
)

@Immutable
@Serializable
data class Author(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isVerified: Boolean = false
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
