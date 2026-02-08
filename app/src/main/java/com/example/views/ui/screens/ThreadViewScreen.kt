package com.example.views.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.views.data.Author
import com.example.views.data.Comment
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.ui.components.NoteCard
import com.example.views.ui.components.ProfilePicture
import com.example.views.ui.components.ZapMenuRow
import com.example.views.ui.icons.ArrowDownward
import com.example.views.ui.icons.ArrowUpward
import com.example.views.ui.icons.Bolt
import com.example.views.ui.icons.Bookmark
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Single animation spec for consistency
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

// ✅ PERFORMANCE: Immutable state data class
@Immutable
data class CommentState(
    val isExpanded: Boolean = true,
    val isCollapsed: Boolean = false,
    val showControls: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadViewScreen(
    note: Note,
    comments: List<CommentThread>,
    onBackClick: () -> Unit = {},
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onReact: (Note, String) -> Unit = { _, _ -> },
    onCommentLike: (String) -> Unit = {},
    onCommentReply: (String) -> Unit = {},
    accountNpub: String? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // ✅ SIMPLIFIED STATE: Single state map for all comment states
    val commentStates = remember { mutableStateMapOf<String, CommentState>() }
    var expandedControlsCommentId by remember { mutableStateOf<String?>(null) }
    
    // ✅ ZAP MENU AWARENESS: Global state for zap menu closure (like feed cards)
    var shouldCloseZapMenus by remember { mutableStateOf(false) }
    var expandedZapMenuCommentId by remember { mutableStateOf<String?>(null) }
    
    // ✅ ZAP CONFIGURATION: Dialog state for editing zap amounts
    var showZapConfigDialog by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }
    
    // ✅ ZAP MENU AWARENESS: Close zap menus when scrolling starts (like feed cards)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            expandedZapMenuCommentId = null
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }
    
    // ✅ PERFORMANCE: Memoized timestamp formatting
    val formattedTimestamp = remember(note.timestamp) {
        formatTimestamp(note.timestamp)
    }
    
    Scaffold(
        topBar = {
            AdaptiveHeader(
                title = "Thread",
                showBackArrow = true,
                onBackClick = onBackClick,
                onFilterClick = { /* TODO: Handle filter/sort */ },
                onMoreOptionClick = { /* TODO: Handle more options */ }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    delay(1500)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp)
            ) {
                // Main note card
                item(key = "main_note") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        NoteCard(
                            note = note,
                            onLike = onLike,
                            onShare = onShare,
                            onComment = onComment,
                            onReact = onReact,
                            onProfileClick = onProfileClick,
                            onNoteClick = { /* Already on thread */ },
                            onImageTap = onImageTap,
                            onOpenImageViewer = onOpenImageViewer,
                            onVideoClick = onVideoClick,
                            // ✅ MAIN NOTE ZAP AWARENESS: Pass zap menu state to main note
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            accountNpub = accountNpub,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }
                
                // Comments with simplified rendering
                comments.forEach { commentThread ->
                    item(key = "comment_${commentThread.comment.id}") {
                        CommentThreadItem(
                            commentThread = commentThread,
                            onLike = onCommentLike,
                            onReply = onCommentReply,
                            onProfileClick = onProfileClick,
                            onZap = { commentId, amount -> /* TODO: Handle zap */ },
                            onCustomZap = { commentId -> /* TODO: Handle custom zap */ },
                            onZapSettings = { showZapConfigDialog = true },
                            depth = 0,
                            commentStates = commentStates,
                            expandedControlsCommentId = expandedControlsCommentId,
                            onExpandControls = { commentId ->
                                expandedControlsCommentId = if (expandedControlsCommentId == commentId) null else commentId
                            },
                            // ✅ ZAP MENU AWARENESS: Pass zap menu state and handlers
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            expandedZapMenuCommentId = expandedZapMenuCommentId,
                            onExpandZapMenu = { commentId ->
                                expandedZapMenuCommentId = if (expandedZapMenuCommentId == commentId) null else commentId
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
    
    // ✅ ZAP CONFIGURATION: Dialogs for editing zap amounts
    if (showZapConfigDialog) {
        com.example.views.ui.components.ZapConfigurationDialog(
            onDismiss = { showZapConfigDialog = false },
            onOpenWalletSettings = { 
                showZapConfigDialog = false
                showWalletConnectDialog = true
            }
        )
    }

    if (showWalletConnectDialog) {
        com.example.views.ui.components.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }
}

@Composable
private fun CommentThreadItem(
    commentThread: CommentThread,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    depth: Int,
    commentStates: MutableMap<String, CommentState>,
    expandedControlsCommentId: String?,
    onExpandControls: (String) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val commentId = commentThread.comment.id
    val state = commentStates.getOrPut(commentId) { CommentState() }
    val isControlsExpanded = expandedControlsCommentId == commentId
    
    // ✅ ULTRA COMPACT INDENTATION: Very tight spacing for child comments
    val indentPadding = (depth * 2).dp
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indentPadding)
            .animateContentSize(animationSpec = standardAnimation)
    ) {
        // Thread line for visual hierarchy
        if (depth > 0) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(24.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        RoundedCornerShape(1.dp)
                    )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Comment card
        CommentCard(
            comment = commentThread.comment,
            onLike = onLike,
            onReply = onReply,
            onProfileClick = onProfileClick,
            onZap = onZap,
            onCustomZap = onCustomZap,
            onZapSettings = onZapSettings,
            isControlsExpanded = isControlsExpanded,
            onToggleControls = { onExpandControls(commentId) },
            isCollapsed = state.isCollapsed,
            onCollapsedChange = { collapsed ->
                commentStates[commentId] = state.copy(
                    isCollapsed = collapsed,
                    isExpanded = !collapsed
                )
            },
            // ✅ ZAP MENU AWARENESS: Pass zap menu state to CommentCard
            shouldCloseZapMenus = shouldCloseZapMenus,
            expandedZapMenuCommentId = expandedZapMenuCommentId,
            onExpandZapMenu = { onExpandZapMenu(commentId) },
            modifier = Modifier.fillMaxWidth()
        )
        
        // Replies
        if (state.isExpanded && !state.isCollapsed && commentThread.replies.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            commentThread.replies.forEach { reply ->
                CommentThreadItem(
                    commentThread = reply,
                    onLike = onLike,
                    onReply = onReply,
                    onProfileClick = onProfileClick,
                    onZap = onZap,
                    onCustomZap = onCustomZap,
                    onZapSettings = onZapSettings,
                    depth = depth + 1,
                    commentStates = commentStates,
                    expandedControlsCommentId = expandedControlsCommentId,
                    onExpandControls = onExpandControls,
                    // ✅ ZAP MENU AWARENESS: Pass zap menu state to nested replies
                    shouldCloseZapMenus = shouldCloseZapMenus,
                    expandedZapMenuCommentId = expandedZapMenuCommentId,
                    onExpandZapMenu = onExpandZapMenu,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        // Separator for top-level comments
        if (depth == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommentCard(
    comment: Comment,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    isControlsExpanded: Boolean,
    onToggleControls: () -> Unit,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val commentId = comment.id
    val isZapMenuExpanded = expandedZapMenuCommentId == commentId
    
    // ✅ ZAP MENU AWARENESS: Close zap menu when shouldCloseZapMenus is true (like feed cards)
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus && isZapMenuExpanded) {
            onExpandZapMenu(commentId) // This will close the menu
        }
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { 
                    if (isCollapsed) {
                        onCollapsedChange(false)
                    } else {
                        onToggleControls()
                    }
                },
                onLongClick = { 
                    if (!isCollapsed) {
                        onCollapsedChange(true)
                    }
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCollapsed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RectangleShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (!isCollapsed) {
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
                        author = comment.author,
                        size = 32.dp,
                        onClick = { onProfileClick(comment.author.id) }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = comment.author.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatTimestamp(comment.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Content
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )
                
                // Controls
                AnimatedVisibility(
                    visible = isControlsExpanded,
                    enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(100))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // ✅ COMPACT CONTROLS: Right-aligned with consistent spacing
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Upvote - compact button
                            CompactCommentButton(
                                icon = Icons.Outlined.ArrowUpward,
                                contentDescription = "Upvote",
                                isActive = comment.isLiked,
                                onClick = { onLike(comment.id) }
                            )
                            
                            // Downvote - compact button
                            CompactCommentButton(
                                icon = Icons.Outlined.ArrowDownward,
                                contentDescription = "Downvote",
                                isActive = false,
                                onClick = { /* Handle downvote */ }
                            )
                            
                            // Bookmark - compact button
                            CompactCommentButton(
                                icon = Icons.Outlined.Bookmark,
                                contentDescription = "Bookmark",
                                isActive = false,
                                onClick = { /* Handle bookmark */ }
                            )
                            
                            // Reply - compact button
                            CompactCommentButton(
                                icon = Icons.Outlined.Reply,
                                contentDescription = "Reply",
                                isActive = false,
                                onClick = { onReply(comment.id) }
                            )
                            
                            // Zap - compact button with shared state
                            CompactCommentButton(
                                icon = Icons.Filled.Bolt,
                                contentDescription = "Zap",
                                isActive = false,
                                onClick = { onExpandZapMenu(commentId) }
                            )
                            
                            // More options - compact button
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                CompactCommentButton(
                                    icon = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    isActive = false,
                                    onClick = { showMenu = true }
                                )
                                
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Share") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Report") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }
                    
                    // Zap menu - flowing layout with custom amounts, test, and edit buttons
                    AnimatedVisibility(
                        visible = isZapMenuExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Flowing zap amounts using FlowRow
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Get custom zap amounts from ZapAmountManager
                                val context = LocalContext.current
                                LaunchedEffect(Unit) {
                                    com.example.views.utils.ZapAmountManager.initialize(context)
                                }
                                val zapAmounts by com.example.views.utils.ZapAmountManager.zapAmounts.collectAsState()
                                
                                // Zap amount chips - sorted largest to smallest
                                zapAmounts.sortedDescending().forEach { amount ->
                                    FilterChip(
                                        selected = amount == 1L, // Highlight 1 sat
                                        onClick = {
                                            onExpandZapMenu(commentId) // Close menu using shared state
                                            onZap(comment.id, amount)
                                        },
                                        label = {
                                            Text(
                                                text = com.example.views.utils.ZapUtils.formatZapAmount(amount),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Bolt,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFFA500),
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White,
                                            containerColor = Color(0xFFFFA500),
                                            labelColor = Color.White,
                                            iconColor = Color.White
                                        ),
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color(0xFFFFA500)
                                        )
                                    )
                                }
                                
                                // Edit zap amounts chip
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onExpandZapMenu(commentId) // Close menu using shared state
                                        onZapSettings()
                                    },
                                    label = { Text("Edit") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit Zap Amounts",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Collapsed state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Expand thread",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = comment.author.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onProfileClick(comment.author.id) }
                )
                
                Spacer(modifier = Modifier.width(4.dp))
                
                Text(
                    text = "· tap to expand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// Data classes for comment threading - Public for use in sample data
data class CommentThread(
    val comment: Comment,
    val replies: List<CommentThread> = emptyList()
)

// Sample nested comment threads for testing
fun createSampleCommentThreads(): List<CommentThread> {
    return listOf(
        CommentThread(
            comment = SampleData.sampleComments[0], // "This is such an interesting topic!"
            replies = listOf(
                CommentThread(
                    comment = SampleData.sampleComments[1], // "Completely agree!"
                    replies = listOf(
                        CommentThread(
                            comment = SampleData.sampleComments[6], // "That's a great explanation"
                            replies = listOf(
                                CommentThread(
                                    comment = SampleData.sampleComments[11] // "Absolutely!"
                                )
                            )
                        ),
                        CommentThread(
                            comment = SampleData.sampleComments[7], // "I see where you're coming from"
                            replies = listOf(
                                CommentThread(
                                    comment = SampleData.sampleComments[10], // "In my experience..."
                                    replies = listOf(
                                        CommentThread(
                                            comment = SampleData.sampleComments[12] // "This thread has been enlightening"
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                CommentThread(
                    comment = SampleData.sampleComments[5] // "Sure! What I meant was..."
                )
            )
        ),
        CommentThread(
            comment = SampleData.sampleComments[2], // "I have a different perspective"
            replies = listOf(
                CommentThread(
                    comment = SampleData.sampleComments[3], // "Can you elaborate?"
                    replies = listOf(
                        CommentThread(
                            comment = SampleData.sampleComments[8], // "But what about edge cases?"
                            replies = listOf(
                                CommentThread(
                                    comment = SampleData.sampleComments[9] // "Edge cases are important"
                                )
                            )
                        )
                    )
                )
            )
        ),
        CommentThread(
            comment = SampleData.sampleComments[4] // "This reminds me of something..."
        )
    )
}

@Composable
private fun CommentActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
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
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp) // ✅ CONSISTENT: Match main card icon size
        )
    }
}

@Composable
private fun CompactCommentButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ COMPACT CONTROLS: Smaller, right-aligned with consistent spacing
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(36.dp) // Slightly bigger button
            .padding(horizontal = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp) // Slightly bigger icon
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
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
fun ThreadViewScreenPreview() {
    MaterialTheme {
        ThreadViewScreen(
            note = SampleData.sampleNotes.first(),
            comments = createSampleCommentThreads()
        )
    }
}
