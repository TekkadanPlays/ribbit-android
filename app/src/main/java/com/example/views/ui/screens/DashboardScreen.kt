package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.ui.components.BottomNavDestinations
import com.example.views.ui.components.ModernSidebar
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.ModernNoteCard
import com.example.views.ui.components.NoteCard
import com.example.views.viewmodel.DashboardViewModel
import com.example.views.viewmodel.AuthViewModel
import com.example.views.ui.performance.animatedYOffset
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter (Thread view pattern)
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Consistent animation specs (Thread view pattern)
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    isSearchMode: Boolean = false,
    onSearchModeChange: (Boolean) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onThreadClick: (Note) -> Unit = {},
    onScrollToTop: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel(),
    onLoginClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by authViewModel.authState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Search state - using simple String instead of TextFieldValue
    var searchQuery by remember { mutableStateOf("") }
    
    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    
    // Use Material3's built-in scroll behavior for top app bar
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }
    
    // ✅ PERFORMANCE: Optimized search filtering (Thread view pattern)
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
    
    // ✅ Performance: Cache divider color (don't recreate on every item)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    
    val uriHandler = LocalUriHandler.current
    
    ModernSidebar(
        drawerState = drawerState,
        onItemClick = { itemId -> 
            when (itemId) {
                "user_profile" -> {
                    onNavigateTo("user_profile")
                }
                "relays" -> {
                    onNavigateTo("relays")
                }
                "bug_report" -> {
                    uriHandler.openUri("https://github.com/TekkadanPlays/ribbit-android/issues")
                }
                else -> viewModel.onSidebarItemClick(itemId)
            }
        },
        authState = authState,
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
                    // Search mode - show docked SearchBar
                    ModernSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* Optional: Handle explicit search submission */ },
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
                    // Normal mode - show scrollable header
                    AdaptiveHeader(
                        title = "Ribbit",
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
                        onLoginClick = onLoginClick,
                        isGuest = authState.isGuest,
                        scrollBehavior = scrollBehavior
                    )
                }
            },
            bottomBar = {
                if (!isSearchMode) {
                    // ✅ BLACK BACKGROUND: Space beneath home bar should be black
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                    ) {
                        BottomNavigationBar(
                            currentDestination = "home",
                            onDestinationClick = { destination -> 
                                when (destination) {
                                    "home" -> {
                                        scope.launch {
                                            topAppBarState.heightOffset = 0f
                                            // ✅ Performance: Use scrollToItem for instant jump (no animation overhead)
                                            // If already at top, this is virtually free
                                            listState.scrollToItem(0)
                                        }
                                    }
                                    "search" -> onSearchModeChange(true)
                                    "profile" -> onNavigateTo("user_profile")
                                    else -> { /* Other destinations not implemented yet */ }
                                }
                            }
                        )
                    }
                }
            }
        ) { paddingValues ->
            // Main content with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        delay(1500) // Simulate network refresh
                        // Refresh logic would go here
                        isRefreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = uiState.notes,
                        key = { it.id }
                    ) { note ->
                        // ✅ PERFORMANCE: Remove individual item animations to prevent stuttering (Thread view pattern)
                        NoteCard(
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



@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen()
    }
}
