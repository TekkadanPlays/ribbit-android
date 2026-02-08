package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.views.data.Author
import com.example.views.data.NotificationData
import com.example.views.data.NotificationType
import com.example.views.data.Note
import com.example.views.repository.NotificationsRepository
import com.example.views.repository.ProfileMetadataCache
import com.example.views.ui.components.ProfilePicture
import com.example.views.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    /** When user taps a reply notification, open thread at root (rootNoteId, replyKind, optional replyNoteId to scroll to). */
    onOpenThreadForRootId: (rootNoteId: String, replyKind: Int, replyNoteId: String?) -> Unit = { _, _, _ -> },
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    modifier: Modifier = Modifier
) {
    // Real notifications from NotificationsRepository (Amethyst-style p-tag subscription)
    val allNotifications by NotificationsRepository.notifications.collectAsState(initial = emptyList())

    // Mark all as seen when user opens the notifications screen so badge clears
    LaunchedEffect(Unit) {
        NotificationsRepository.markAllAsSeen()
    }

    // Batch-request profiles for all notification and note authors when list is shown/updated
    val profileCache = ProfileMetadataCache.getInstance()
    val cacheRelayUrls = NotificationsRepository.getCacheRelayUrls()
    LaunchedEffect(allNotifications, cacheRelayUrls) {
        if (cacheRelayUrls.isEmpty()) return@LaunchedEffect
        val authorIds = allNotifications.flatMap { n ->
            buildList {
                n.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
                (n.targetNote ?: n.note)?.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
            }
        }.distinct().filter { it.isNotBlank() }
        if (authorIds.isNotEmpty()) profileCache.requestProfiles(authorIds, cacheRelayUrls)
    }

    var currentNotificationView by remember { mutableStateOf("All") }
    var notificationDropdownExpanded by remember { mutableStateOf(false) }

    val seenIds by NotificationsRepository.seenIds.collectAsState(initial = emptySet())
    val notificationCounts = remember(allNotifications, seenIds) {
        val unseen = { n: NotificationData -> n.id !in seenIds }
        mapOf(
            "All" to allNotifications.count(unseen),
            "Likes" to allNotifications.count { it.type == NotificationType.LIKE && unseen(it) },
            "Replies" to allNotifications.count { it.type == NotificationType.REPLY && unseen(it) },
            "Thread replies" to allNotifications.count { it.type == NotificationType.REPLY && (it.replyKind == null || it.replyKind == 1) && unseen(it) },
            "Topic replies" to allNotifications.count { it.type == NotificationType.REPLY && it.replyKind == 1111 && unseen(it) },
            "Mentions" to allNotifications.count { it.type == NotificationType.MENTION && unseen(it) },
            "Reposts" to allNotifications.count { it.type == NotificationType.REPOST && unseen(it) },
            "Zaps" to allNotifications.count { it.type == NotificationType.ZAP && unseen(it) }
        )
    }

    // Scroll behavior for collapsible top bar
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // Use predictive back for smooth gesture navigation
    BackHandler {
        onBackClick()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
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
                                "Thread replies" -> Icons.Outlined.Reply
                                "Topic replies" -> Icons.Outlined.Chat
                                "Mentions" -> Icons.Outlined.AlternateEmail
                                "Reposts" -> Icons.Default.Repeat
                                "Zaps" -> Icons.Default.Bolt
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
                                        Text("Thread replies")
                                        if ((notificationCounts["Thread replies"] ?: 0) > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = (notificationCounts["Thread replies"] ?: 0).toString(),
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
                                        tint = if (currentNotificationView == "Thread replies") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    currentNotificationView = "Thread replies"
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
                                        Text("Topic replies")
                                        if ((notificationCounts["Topic replies"] ?: 0) > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(
                                                    text = (notificationCounts["Topic replies"] ?: 0).toString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Chat,
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Topic replies") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    currentNotificationView = "Topic replies"
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
                                        Text("Reposts")
                                        (notificationCounts["Reposts"] ?: 0).takeIf { it > 0 }?.let { count ->
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(text = count.toString(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Repeat,
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Reposts") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    currentNotificationView = "Reposts"
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
                                        Text("Zaps")
                                        (notificationCounts["Zaps"] ?: 0).takeIf { it > 0 }?.let { count ->
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text(text = count.toString(), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (currentNotificationView == "Zaps") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    currentNotificationView = "Zaps"
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
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        ) {
            val filteredNotifications = when (currentNotificationView) {
                "Likes" -> allNotifications.filter { it.type == NotificationType.LIKE }
                "Replies" -> allNotifications.filter { it.type == NotificationType.REPLY }
                "Thread replies" -> allNotifications.filter { it.type == NotificationType.REPLY && (it.replyKind == null || it.replyKind == 1) }
                "Topic replies" -> allNotifications.filter { it.type == NotificationType.REPLY && it.replyKind == 1111 }
                "Mentions" -> allNotifications.filter { it.type == NotificationType.MENTION }
                "Reposts" -> allNotifications.filter { it.type == NotificationType.REPOST }
                "Zaps" -> allNotifications.filter { it.type == NotificationType.ZAP }
                else -> allNotifications
            }

            if (filteredNotifications.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (allNotifications.isEmpty()) "No notifications yet" else "No ${currentNotificationView.lowercase()}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(
                items = filteredNotifications,
                key = { it.id }
            ) { notification ->
                NotificationItem(
                    notification = notification,
                    onNoteClick = onNoteClick,
                    onOpenThreadForRootId = onOpenThreadForRootId,
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

private val placeholderAuthor = Author(
    id = "",
    username = "...",
    displayName = "…",
    avatarUrl = null,
    isVerified = false
)

@Composable
private fun NotificationNotePreview(
    note: Note,
    onProfileClick: (String) -> Unit
) {
    val profileCache = ProfileMetadataCache.getInstance()
    val noteAuthorId = note.author.id
    val noteCacheKey = remember(noteAuthorId) { normalizeAuthorIdForCache(noteAuthorId) }
    var noteDisplayAuthor by remember(noteAuthorId) {
        mutableStateOf(profileCache.getAuthor(noteCacheKey) ?: note.author)
    }
    LaunchedEffect(Unit) {
        profileCache.getAuthor(noteCacheKey)?.let { noteDisplayAuthor = it }
    }
    LaunchedEffect(noteCacheKey) {
        if (noteCacheKey.isBlank()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it == noteCacheKey }
            .collect { noteDisplayAuthor = profileCache.getAuthor(noteCacheKey) ?: noteDisplayAuthor }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePicture(
                    author = noteDisplayAuthor,
                    size = 20.dp,
                    onClick = { onProfileClick(note.author.id) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = noteDisplayAuthor.displayName.ifBlank { noteDisplayAuthor.id.take(8) + "…" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            if (note.mediaUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    note.mediaUrls.take(4).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    notification: NotificationData,
    onNoteClick: (Note) -> Unit,
    onOpenThreadForRootId: (rootNoteId: String, replyKind: Int, replyNoteId: String?) -> Unit = { _, _, _ -> },
    onLike: (String) -> Unit,
    onShare: (String) -> Unit,
    onComment: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val profileCache = ProfileMetadataCache.getInstance()
    val authorId = notification.author?.id ?: ""
    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
    var displayAuthor by remember(authorId) {
        mutableStateOf(profileCache.getAuthor(cacheKey) ?: notification.author ?: placeholderAuthor)
    }
    val actorPubkeys = notification.actorPubkeys
    val actorAuthors = remember(actorPubkeys) {
        actorPubkeys.take(3).map { pk ->
            profileCache.getAuthor(pk) ?: placeholderAuthor.copy(
                id = pk,
                username = pk.take(8) + "...",
                displayName = pk.take(8) + "..."
            )
        }
    }
    LaunchedEffect(Unit) {
        profileCache.getAuthor(cacheKey)?.let { displayAuthor = it }
    }
    LaunchedEffect(cacheKey) {
        if (cacheKey.isBlank()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it == cacheKey }
            .collect { displayAuthor = profileCache.getAuthor(cacheKey) ?: displayAuthor }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clickable {
                NotificationsRepository.markAsSeen(notification.id)
                when {
                    notification.type == NotificationType.REPLY && notification.rootNoteId != null ->
                        onOpenThreadForRootId(notification.rootNoteId!!, notification.replyKind ?: 1, notification.replyNoteId)
                    notification.type == NotificationType.MENTION && notification.note != null ->
                        onNoteClick(notification.note!!)
                    notification.targetNote != null -> onNoteClick(notification.targetNote!!)
                    notification.note != null -> onNoteClick(notification.note!!)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(
                    when (notification.type) {
                        NotificationType.LIKE -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        NotificationType.REPLY -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                        NotificationType.MENTION -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                        NotificationType.REPOST -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        NotificationType.ZAP -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                    }
                )
        )
        Spacer(modifier = Modifier.width(4.dp))

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
                    if (actorPubkeys.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            actorAuthors.forEach { actor ->
                                ProfilePicture(
                                    author = actor,
                                    size = 28.dp,
                                    onClick = { onProfileClick(actor.id) }
                                )
                            }
                            if (actorPubkeys.size > actorAuthors.size) {
                                Text(
                                    text = "+${actorPubkeys.size - actorAuthors.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                )
                            }
                        }
                    } else {
                        ProfilePicture(
                            author = displayAuthor,
                            size = 36.dp,
                            onClick = { notification.author?.id?.let { onProfileClick(it) } }
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

            // Reposter summary for consolidated reposts
                if (notification.reposterPubkeys.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (notification.reposterPubkeys.size == 1) "1 person reposted" else "${notification.reposterPubkeys.size} people reposted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            // Target note (liked/zapped/reposted post) or reply content
                val noteToShow = notification.targetNote ?: notification.note
                noteToShow?.let { note ->
                    Spacer(modifier = Modifier.height(8.dp))
                    NotificationNotePreview(note = note, onProfileClick = onProfileClick)
                }
                notification.targetNoteId?.let { id ->
                    if (notification.targetNote == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Post ${id.take(8)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
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
