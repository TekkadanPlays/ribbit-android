package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.views.data.Author
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.NoteCard
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    // Notification view state
    var currentNotificationView by remember { mutableStateOf("All") }
    var notificationDropdownExpanded by remember { mutableStateOf(false) }
    
    // Calculate notification counts for badges
    val allNotifications = createSampleNotifications()
    val notificationCounts = remember(allNotifications) {
        mapOf(
            "All" to allNotifications.size,
            "Likes" to allNotifications.count { it.type == NotificationType.LIKE },
            "Replies" to allNotifications.count { it.type == NotificationType.REPLY },
            "Mentions" to allNotifications.count { it.type == NotificationType.MENTION },
            "Follows" to allNotifications.count { it.type == NotificationType.FOLLOW }
        )
    }
    
    // Use predictive back for smooth gesture navigation
    BackHandler {
        onBackClick()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = when (currentNotificationView) {
                            "All" -> "all notifications"
                            else -> currentNotificationView.lowercase()
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { notificationDropdownExpanded = true }
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Caret on the left
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Notification view selector",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            // Large icon for current view
                            val currentIcon = when (currentNotificationView) {
                                "All" -> Icons.Default.Notifications
                                "Likes" -> Icons.Default.Favorite
                                "Replies" -> Icons.Outlined.Reply
                                "Mentions" -> Icons.Outlined.AlternateEmail
                                "Follows" -> Icons.Default.PersonAdd
                                else -> Icons.Default.Notifications
                            }
                            
                            Icon(
                                imageVector = currentIcon,
                                contentDescription = "Current notification view",
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Notification view dropdown menu
                        DropdownMenu(
                            expanded = notificationDropdownExpanded,
                            onDismissRequest = { notificationDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("All")
                                        if (notificationCounts["All"]!! > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = notificationCounts["All"].toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Default.Notifications, 
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "All") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                },
                                onClick = { 
                                    currentNotificationView = "All"
                                    notificationDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Likes")
                                        if (notificationCounts["Likes"]!! > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = notificationCounts["Likes"].toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Default.Favorite, 
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Likes") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                },
                                onClick = { 
                                    currentNotificationView = "Likes"
                                    notificationDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Replies")
                                        if (notificationCounts["Replies"]!! > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = notificationCounts["Replies"].toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Outlined.Reply, 
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Replies") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                },
                                onClick = { 
                                    currentNotificationView = "Replies"
                                    notificationDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Mentions")
                                        if (notificationCounts["Mentions"]!! > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = notificationCounts["Mentions"].toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Outlined.AlternateEmail, 
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Mentions") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                },
                                onClick = { 
                                    currentNotificationView = "Mentions"
                                    notificationDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Follows")
                                        if (notificationCounts["Follows"]!! > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = notificationCounts["Follows"].toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = { 
                                    Icon(
                                        Icons.Default.PersonAdd, 
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Follows") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ) 
                                },
                                onClick = { 
                                    currentNotificationView = "Follows"
                                    notificationDropdownExpanded = false
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Filter notifications based on selected view
            val filteredNotifications = when (currentNotificationView) {
                "Likes" -> allNotifications.filter { it.type == NotificationType.LIKE }
                "Replies" -> allNotifications.filter { it.type == NotificationType.REPLY }
                "Mentions" -> allNotifications.filter { it.type == NotificationType.MENTION }
                "Follows" -> allNotifications.filter { it.type == NotificationType.FOLLOW }
                else -> allNotifications
            }
            
            items(
                items = filteredNotifications,
                key = { it.id }
            ) { notification ->
                NotificationItem(
                    notification = notification,
                    onNoteClick = onNoteClick,
                    onLike = onLike,
                    onShare = onShare,
                    onComment = onComment,
                    onProfileClick = onProfileClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun NotificationItem(
    notification: NotificationData,
    onNoteClick: (Note) -> Unit,
    onLike: (String) -> Unit,
    onShare: (String) -> Unit,
    onComment: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Modern thread-view inspired design
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min) // Critical for proper vertical lines
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { 
                notification.note?.let { onNoteClick(it) }
            }
    ) {
        // Vertical line for visual hierarchy (like thread view)
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    when (notification.type) {
                        NotificationType.LIKE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        NotificationType.REPLY -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                        NotificationType.MENTION -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        NotificationType.FOLLOW -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    }
                )
        )
        Spacer(modifier = Modifier.width(12.dp))
        
        // Notification content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Main notification card
            Card(
                modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
                    // Notification icon with modern styling
            Box(
                modifier = Modifier
                            .size(36.dp)
                    .background(
                        when (notification.type) {
                            NotificationType.LIKE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            NotificationType.REPLY -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                            NotificationType.MENTION -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                            NotificationType.FOLLOW -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (notification.type) {
                        NotificationType.LIKE -> Icons.Default.Favorite
                                NotificationType.REPLY -> Icons.Outlined.Reply
                        NotificationType.MENTION -> Icons.Outlined.AlternateEmail
                        NotificationType.FOLLOW -> Icons.Default.PersonAdd
                    },
                    contentDescription = null,
                    tint = when (notification.type) {
                        NotificationType.LIKE -> MaterialTheme.colorScheme.primary
                        NotificationType.REPLY -> MaterialTheme.colorScheme.secondary
                        NotificationType.MENTION -> MaterialTheme.colorScheme.tertiary
                        NotificationType.FOLLOW -> MaterialTheme.colorScheme.error
                    },
                            modifier = Modifier.size(18.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                        // Notification text with better typography
                Text(
                    text = notification.text,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        )
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        // Time ago with subtle styling
                Text(
                    text = notification.timeAgo,
                    style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                    }
                }
            }
                
            // Note preview with modern styling (if exists)
                notification.note?.let { note ->
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                    shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Author avatar
                                Box(
                                    modifier = Modifier
                                    .size(20.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = note.author.displayName.first().toString().uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = note.author.displayName,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Text(
                                    text = formatTimeAgo(note.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = note.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 18.sp
                            )
                    }
                }
            }
        }
    }
}

// Data classes for notifications
data class NotificationData(
    val id: String,
    val type: NotificationType,
    val text: String,
    val timeAgo: String,
    val note: Note? = null,
    val author: Author? = null
)

enum class NotificationType {
    LIKE, REPLY, MENTION, FOLLOW
}

// Sample data
private fun createSampleNotifications(): List<NotificationData> {
    val sampleNotes = SampleData.sampleNotes
    val sampleAuthors = sampleNotes.map { it.author }
    
    return listOf(
        NotificationData(
            id = "1",
            type = NotificationType.LIKE,
            text = "Alice liked your post",
            timeAgo = "2m ago",
            note = sampleNotes[0],
            author = sampleAuthors[0]
        ),
        NotificationData(
            id = "2",
            type = NotificationType.REPLY,
            text = "Bob replied to your post",
            timeAgo = "5m ago",
            note = sampleNotes[1],
            author = sampleAuthors[1]
        ),
        NotificationData(
            id = "3",
            type = NotificationType.MENTION,
            text = "Charlie mentioned you in a post",
            timeAgo = "1h ago",
            note = sampleNotes[2],
            author = sampleAuthors[2]
        ),
        NotificationData(
            id = "4",
            type = NotificationType.FOLLOW,
            text = "Diana started following you",
            timeAgo = "2h ago",
            author = sampleAuthors[3]
        ),
        NotificationData(
            id = "5",
            type = NotificationType.LIKE,
            text = "Eve liked your post",
            timeAgo = "3h ago",
            note = sampleNotes[4],
            author = sampleAuthors[4]
        ),
        NotificationData(
            id = "6",
            type = NotificationType.REPLY,
            text = "Frank replied to your post",
            timeAgo = "5h ago",
            note = sampleNotes[5],
            author = sampleAuthors[5]
        )
    )
}

private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationsScreenPreview() {
    MaterialTheme {
        NotificationsScreen(
            onBackClick = {}
        )
    }
}
