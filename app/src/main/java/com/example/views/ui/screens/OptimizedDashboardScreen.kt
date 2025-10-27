package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.ui.components.ModernSidebar
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.NoteCard
import com.example.views.ui.icons.ArrowDownward
import com.example.views.ui.icons.ArrowUpward
import com.example.views.ui.icons.Bolt
import com.example.views.ui.icons.Bookmark
import com.example.views.ui.icons.ChatBubble
import com.example.views.viewmodel.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter (Thread view pattern)
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Consistent animation specs (Thread view pattern)
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

/**
 * Optimized Dashboard Screen following Thread view performance patterns
 * 
 * Key Performance Improvements:
 * - Cached date formatter
 * - Memoized timestamp formatting
 * - Simplified state management
 * - No individual item animations
 * - Optimized search filtering
 * - Reduced recompositions
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OptimizedDashboardScreen(
    isSearchMode: Boolean = false,
    onSearchModeChange: (Boolean) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onThreadClick: (Note) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // ✅ SIMPLIFIED STATE: Single search query state (Thread view pattern)
    var searchQuery by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Feed view state
    var currentFeedView by remember { mutableStateOf("Home") }
    
    // ✅ PERFORMANCE: Simplified scroll behavior (Thread view pattern)
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }
    
    // ✅ PERFORMANCE: Optimized search filtering with memoization (Thread view pattern)
    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                uiState.notes.filter { note ->
                    note.content.contains(searchQuery, ignoreCase = true) ||
                    note.author.displayName.contains(searchQuery, ignoreCase = true) ||
                    note.author.username.contains(searchQuery, ignoreCase = true) ||
                    note.hashtags.any { it.contains(searchQuery, ignoreCase = true) }
                }
            }
        }
    }
    
    // ✅ PERFORMANCE: Cache theme colors (Thread view pattern)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    
    val uriHandler = LocalUriHandler.current
    
    ModernSidebar(
        drawerState = drawerState,
        onItemClick = { itemId -> 
            when (itemId) {
                "login" -> {
                    // Handle login - this would need to be passed as parameter
                }
                "settings" -> {
                    onNavigateTo("settings")
                }
                else -> viewModel.onSidebarItemClick(itemId)
            }
        },
        modifier = modifier
    ) {
        Scaffold(
            modifier = if (!isSearchMode) {
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            } else {
                Modifier
            },
            topBar = {
                if (isSearchMode) {
                    // Search mode - optimized search bar
                    ModernSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* Handle search submission */ },
                        searchResults = searchResults,
                        onResultClick = { note ->
                            onThreadClick(note)
                            onSearchModeChange(false)
                            searchQuery = ""
                        },
                        active = isSearchMode,
                        onActiveChange = { active -> 
                            if (!active) {
                                onSearchModeChange(false)
                                searchQuery = ""
                            }
                        },
                        onBackClick = { 
                            searchQuery = ""
                            onSearchModeChange(false) 
                        },
                        placeholder = { Text("Search notes, users, hashtags...") }
                    )
                } else {
                    // Normal mode - optimized header
                    AdaptiveHeader(
                        title = "ribbit",
                        isSearchMode = false,
                        searchQuery = androidx.compose.ui.text.input.TextFieldValue(""),
                        onSearchQueryChange = { },
                        onMenuClick = { 
                            scope.launch {
                                if (drawerState.isClosed) {
                                    drawerState.open()
                                } else {
                                    drawerState.close()
                                }
                            }
                        },
                        onSearchClick = { onSearchModeChange(true) },
                        onFilterClick = { /* TODO: Handle filter/sort */ },
                        onMoreOptionClick = { option ->
                            when (option) {
                                "about" -> onNavigateTo("about")
                                "settings" -> onNavigateTo("settings")
                                else -> viewModel.onMoreOptionClick(option)
                            }
                        },
                        onBackClick = { },
                        onClearSearch = { },
                        scrollBehavior = scrollBehavior,
                        currentFeedView = currentFeedView,
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView }
                    )
                }
            },
            bottomBar = {
                if (!isSearchMode) {
                    BottomNavigationBar(
                        currentDestination = "home",
                        onDestinationClick = { destination -> 
                            when (destination) {
                                "home" -> {
                                    scope.launch {
                                        topAppBarState.heightOffset = 0f
                                        // ✅ PERFORMANCE: Use scrollToItem for instant jump (Thread view pattern)
                                        listState.scrollToItem(0)
                                    }
                                }
                                "search" -> onSearchModeChange(true)
                                "relays" -> onNavigateTo("relays")
                                "profile" -> onNavigateTo("user_profile")
                                else -> { /* Other destinations not implemented yet */ }
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            // ✅ PERFORMANCE: Optimized pull-to-refresh (Thread view pattern)
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        delay(1500) // Simulate network refresh
                        isRefreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // ✅ PERFORMANCE: Optimized LazyColumn (Thread view pattern)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = uiState.notes,
                        key = { it.id }
                    ) { note ->
                        // ✅ PERFORMANCE: No individual animations to prevent stuttering (Thread view pattern)
                        OptimizedNoteCard(
                            note = note,
                            onLike = { noteId -> viewModel.toggleLike(noteId) },
                            onShare = { noteId -> /* Handle share */ },
                            onComment = { noteId -> onThreadClick(note) },
                            onProfileClick = onProfileClick,
                            onNoteClick = onThreadClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Optimized Note Card following Thread view performance patterns
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptimizedNoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // ✅ PERFORMANCE: Memoized timestamp formatting (Thread view pattern)
    val formattedTime = remember(note.timestamp) {
        formatTimestampOptimized(note.timestamp)
    }
    
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = androidx.compose.ui.graphics.RectangleShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Author info - optimized layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                com.example.views.ui.components.ProfilePicture(
                    author = note.author,
                    size = 40.dp,
                    onClick = { onProfileClick(note.author.id) }
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text(
                            text = note.author.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        )
                        if (note.author.isVerified) {
                            Spacer(modifier = Modifier.width(4.dp))
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Verified",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        androidx.compose.material3.Text(
                            text = "@${note.author.username}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                    androidx.compose.material3.Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Content
            androidx.compose.material3.Text(
                text = note.content,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 20.sp
            )
            
            // Hashtags - simplified rendering
            if (note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.Text(
                    text = note.hashtags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Action buttons - simplified layout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                // Simplified action buttons without complex state management
                androidx.compose.material3.IconButton(
                    onClick = { onLike(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Upvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                androidx.compose.material3.IconButton(
                    onClick = { /* Handle downvote */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = "Downvote",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                androidx.compose.material3.IconButton(
                    onClick = { /* Handle bookmark */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Bookmark,
                        contentDescription = "Bookmark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                androidx.compose.material3.IconButton(
                    onClick = { onComment(note.id) },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.ChatBubble,
                        contentDescription = "Comment",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                androidx.compose.material3.IconButton(
                    onClick = { /* Handle zap */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = "Lightning",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                androidx.compose.material3.IconButton(
                    onClick = { /* Handle more options */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ✅ PERFORMANCE: Cached timestamp formatting (Thread view pattern)
private fun formatTimestampOptimized(timestamp: Long): String {
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
fun OptimizedDashboardScreenPreview() {
    MaterialTheme {
        OptimizedDashboardScreen()
    }
}
