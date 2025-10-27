package com.example.views.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.views.data.Note
import com.example.views.data.SampleData
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat (creating it is VERY expensive)
// SimpleDateFormat creation can take 50-100ms, causing visible lag in lists
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/**
 * Modern Note Card following Material3 best practices.
 *
 * Features:
 * - Interactive chip hashtags
 * - Proper elevation and theming
 * - Smooth animations
 * - Modern action buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernNoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onDislike: (String) -> Unit = {},
    onBookmark: (String) -> Unit = {},
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onTestZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    NoteCardContent(
        note = note,
        onLike = onLike,
        onDislike = onDislike,
        onBookmark = onBookmark,
        onZap = onZap,
        onCustomZap = onCustomZap,
        onTestZap = onTestZap,
        onZapSettings = onZapSettings,
        onComment = onComment,
        onProfileClick = onProfileClick,
        onNoteClick = onNoteClick,
        onHashtagClick = onHashtagClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCardContent(
    note: Note,
    onLike: (String) -> Unit,
    onDislike: (String) -> Unit,
    onBookmark: (String) -> Unit,
    onZap: (String, Long) -> Unit,
    onCustomZap: (String) -> Unit,
    onTestZap: (String) -> Unit,
    onZapSettings: () -> Unit,
    onComment: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onHashtagClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isZapMenuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 1.dp,
            pressedElevation = 4.dp,
            hoveredElevation = 3.dp
        ),
        shape = RectangleShape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Author info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfilePicture(
                    author = note.author,
                    size = 40.dp,
                    onClick = { onProfileClick(note.author.id) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = note.author.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        if (note.author.isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Verified",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "@${note.author.username}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    val formattedTime = remember(note.timestamp) {
                        formatTimestamp(note.timestamp)
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            Text(
                text = note.content,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 20.sp
            )

            // URL Previews
            if (note.urlPreviews.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.urlPreviews.forEach { previewInfo ->
                        UrlPreviewCard(
                            previewInfo = previewInfo,
                            onUrlClick = { url ->
                                // Handle URL click - could open in browser
                            },
                            onUrlLongClick = { url ->
                                // Handle URL long click - could show context menu
                            }
                        )
                    }
                }
            }

            // Hashtags as chips
            if (note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    note.hashtags.take(3).forEach { hashtag ->
                        SuggestionChip(
                            onClick = { onHashtagClick(hashtag) },
                            label = {
                                Text(
                                    text = "#$hashtag",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                    if (note.hashtags.size > 3) {
                        SuggestionChip(
                            onClick = { /* Show all hashtags */ },
                            label = {
                                Text(
                                    text = "+${note.hashtags.size - 3}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onLike(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Upvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onDislike(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = "Downvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onBookmark(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bookmark,
                        contentDescription = "Bookmark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onComment(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubble,
                        contentDescription = "Comment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Zap button - simple square icon like others
                IconButton(
                    onClick = { isZapMenuExpanded = !isZapMenuExpanded },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "Zap",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Isolate menu state to prevent card recomposition
                NoteMoreOptionsMenu(
                    onShare = { onNoteClick(note) },
                    onReport = { /* Handle report */ }
                )
            }

            // Zap menu - completely separate, appears below action buttons
            ZapMenuRow(
                isExpanded = isZapMenuExpanded,
                onExpandedChange = { isZapMenuExpanded = it },
                onZap = { amount -> onZap(note.id, amount) },
                onCustomZap = { onCustomZap(note.id) },
                onTestZap = { onTestZap(note.id) },
                onSettingsClick = onZapSettings
            )
        }
    }
}

@Composable
private fun NoteMoreOptionsMenu(
    onShare: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Share") },
                onClick = {
                    showMenu = false
                    onShare()
                },
                leadingIcon = {
                    Icon(Icons.Filled.Send, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Report") },
                onClick = {
                    showMenu = false
                    onReport()
                },
                leadingIcon = {
                    Icon(Icons.Filled.Flag, contentDescription = null)
                }
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> dateFormatter.format(Date(timestamp)) // ✅ Use cached formatter
    }
}

@Preview(showBackground = true)
@Composable
fun ModernNoteCardPreview() {
    MaterialTheme {
        ModernNoteCard(note = SampleData.sampleNotes[0])
    }
}
