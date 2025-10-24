package com.example.views.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.icons.ArrowDownward
import com.example.views.ui.icons.ArrowUpward
import com.example.views.ui.icons.Bolt
import com.example.views.ui.icons.Bookmark
import com.example.views.ui.icons.ChatBubble
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onTestZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    shouldCloseZapMenus: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isZapMenuExpanded by remember { mutableStateOf(false) }
    
    // Close zap menu when feed scrolls
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus) {
            isZapMenuExpanded = false
        }
    }
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RectangleShape
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
                // Avatar with shared element support
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
                                imageVector = Icons.Default.Star,
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
                    // ✅ PERFORMANCE: Memoized timestamp formatting (Thread view pattern)
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

            Spacer(modifier = Modifier.height(12.dp))

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

            // Hashtags
            if (note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = note.hashtags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons - 6 icons total with expanded hitboxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Upvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowUpward,
                    contentDescription = "Upvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Downvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowDownward,
                    contentDescription = "Downvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Bookmark button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.Bookmark,
                    contentDescription = "Bookmark",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Comment button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ChatBubble,
                    contentDescription = "Comment",
                    onClick = { onComment(note.id) },
                    modifier = Modifier.weight(1f)
                )

                // Zap button - simple square icon like others
                ActionButton(
                    icon = Icons.Filled.Bolt,
                    contentDescription = "Zap",
                    onClick = { isZapMenuExpanded = !isZapMenuExpanded },
                    modifier = Modifier.weight(1f)
                )

                // More options menu - expanded hitbox
                ActionButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
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
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ EXPANDED HITBOX: Wider touch target to prevent accidental card activation
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> dateFormatter.format(Date(timestamp))
    }
}

@Preview(showBackground = true)
@Composable
fun NoteCardPreview() {
    NoteCard(note = SampleData.sampleNotes[0])
}
