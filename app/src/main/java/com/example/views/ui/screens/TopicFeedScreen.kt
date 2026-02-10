package com.example.views.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.views.repository.TopicNote
import com.example.views.repository.TopicRepliesRepository
import com.example.views.viewmodel.TopicsViewModel
import com.example.views.viewmodel.AccountStateViewModel
import com.example.views.ui.components.LoadingAnimation
import com.example.views.ui.components.ProfilePicture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * Topic Feed Screen - Displays Kind 11 topics for a specific hashtag
 * Shows list of topics that can be tapped to view their Kind 1111 replies
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TopicFeedScreen(
    hashtag: String,
    onBackClick: () -> Unit = {},
    onTopicClick: (String) -> Unit = {}, // Takes topicId to navigate to TopicThreadScreen
    listState: LazyListState = rememberLazyListState(),
    topicsViewModel: TopicsViewModel = viewModel(),
    accountStateViewModel: AccountStateViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by topicsViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Track subscription state for this hashtag anchor
    val anchor = "#$hashtag"
    val subscribedAnchors by accountStateViewModel.getSubscribedAnchors().collectAsState()
    val isSubscribed = anchor in subscribedAnchors
    
    // Track kind:1 replies to topics
    val topicRepliesRepo = remember { TopicRepliesRepository.getInstance() }
    val repliesByTopicId by topicRepliesRepo.repliesByTopicId.collectAsState()

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // Get topics for this hashtag
    val topics = uiState.topicsForSelectedHashtag

    // Register topic IDs with NoteCountsRepository for reaction/zap counts
    val topicIds = remember(topics) { topics.map { it.id }.toSet() }
    LaunchedEffect(topicIds) {
        com.example.views.repository.NoteCountsRepository.setTopicNoteIdsOfInterest(topicIds)
    }
    DisposableEffect(Unit) {
        onDispose { com.example.views.repository.NoteCountsRepository.setTopicNoteIdsOfInterest(emptySet()) }
    }
    val noteCountsByNoteId by com.example.views.repository.NoteCountsRepository.countsByNoteId.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "#$hashtag",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val error = if (isSubscribed) {
                                accountStateViewModel.unsubscribeFromAnchor(anchor)
                            } else {
                                accountStateViewModel.subscribeToAnchor(anchor)
                            }
                            if (error != null) {
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            } else {
                                val message = if (isSubscribed) "Unsubscribed from #$hashtag" else "Subscribed to #$hashtag"
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSubscribed) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                            contentDescription = if (isSubscribed) "Unsubscribe" else "Subscribe",
                            tint = if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    topicsViewModel.refreshTopics()
                    delay(1000)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading && topics.isEmpty()) {
                // Loading state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingAnimation(indicatorSize = 32.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading topics...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (topics.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "No topics found",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Be the first to create a topic with #$hashtag",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Topics list
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)
                ) {
                    items(
                        items = topics,
                        key = { it.id }
                    ) { topic ->
                        // Get kind:1 reply count for this topic
                        val kind1ReplyCount = repliesByTopicId[topic.id]?.size ?: 0
                        val totalReplyCount = topic.replyCount + kind1ReplyCount
                        
                        val counts = noteCountsByNoteId[topic.id]
                        TopicCard(
                            topic = topic.copy(replyCount = totalReplyCount),
                            isFavorited = false, // TODO: Track favorites
                            reactions = counts?.reactions ?: emptyList(),
                            zapCount = counts?.zapCount ?: 0,
                            onToggleFavorite = {
                                // TODO: Implement favorite
                            },
                            onMenuClick = {
                                // TODO: Show menu
                            },
                            onClick = {
                                onTopicClick(topic.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Topic card displaying Kind 11 topic info
 */
@Composable
private fun TopicCard(
    topic: TopicNote,
    isFavorited: Boolean,
    reactions: List<String> = emptyList(),
    zapCount: Int = 0,
    onToggleFavorite: () -> Unit,
    onMenuClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RectangleShape, // Edge-to-edge
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Author + Time + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Author info
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    ProfilePicture(
                        author = topic.author,
                        size = 28.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = topic.author.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = formatTopicTimestamp(topic.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorited) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                            tint = if (isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            if (topic.title.isNotEmpty()) {
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Content preview
            if (topic.content.isNotEmpty()) {
                val contentPreview = if (topic.title.isEmpty()) {
                    // Use first line as title if no title tag
                    topic.content.lines().first()
                } else {
                    topic.content
                }

                Text(
                    text = contentPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer: Hashtags + Reply count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hashtags
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    topic.hashtags.take(3).forEach { hashtag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                text = "#$hashtag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Counts: reactions, zaps, replies
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reactions
                    if (reactions.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = reactions.take(3).joinToString(""),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (reactions.size > 3) {
                                Text(
                                    text = "+${reactions.size - 3}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Zap count
                    if (zapCount > 0) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚡",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = zapCount.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Reply count
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Comment,
                            contentDescription = "Replies",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = topic.replyCount.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            thickness = 1.dp
        )
    }
}

/**
 * Format timestamp to relative time
 */
private fun formatTopicTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> "${diff / 604800_000}w"
    }
}
