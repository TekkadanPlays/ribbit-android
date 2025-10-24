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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.consumeWindowInsets
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.ui.components.SmartBottomNavigationBar
import com.example.views.ui.components.ScrollAwareBottomNavigationBar
import com.example.views.ui.components.BottomNavDestinations
import com.example.views.ui.components.ModernSidebar
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.ModernNoteCard
import com.example.views.ui.components.NoteCard
import com.example.views.viewmodel.DashboardViewModel
import com.example.views.viewmodel.AuthViewModel
import com.example.views.viewmodel.RelayManagementViewModel
import com.example.views.repository.RelayRepository
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
    accountStateViewModel: com.example.views.viewmodel.AccountStateViewModel = viewModel(),
    relayRepository: RelayRepository? = null,
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    // Relay management
    val relayViewModel: RelayManagementViewModel? = relayRepository?.let { 
        viewModel { RelayManagementViewModel(it) } 
    }
    val relayUiState = if (relayViewModel != null) {
        relayViewModel.uiState.collectAsState().value
    } else {
        com.example.views.viewmodel.RelayManagementUiState()
    }

    // Search state - using simple String instead of TextFieldValue
    var searchQuery by remember { mutableStateOf("") }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Feed view state
    var currentFeedView by remember { mutableStateOf("Home") }

    // Account switcher state
    var showAccountSwitcher by remember { mutableStateOf(false) }
    
    // Zap menu state - shared across all note cards
    var shouldCloseZapMenus by remember { mutableStateOf(false) }
    
    // Zap configuration dialog state
    var showZapConfigDialog by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }

    // Close zap menus when feed scroll starts (not during scroll)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }

    // Use Material3's built-in scroll behavior for top app bar
    // Inherit state from thread view when navigating back
    val topAppBarState = rememberTopAppBarState(
        initialHeightOffsetLimit = initialTopAppBarState?.heightOffsetLimit ?: 0f,
        initialHeightOffset = initialTopAppBarState?.heightOffset ?: 0f,
        initialContentOffset = initialTopAppBarState?.contentOffset ?: 0f
    )
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }

    // Simple navigation bar visibility - always visible for now
    var isBottomNavVisible by remember { mutableStateOf(true) }

    // Calculate total notification count for badge
    val totalNotificationCount = remember {
        // In a real app, this would come from a notification service
        // For now, we'll use a sample count
        6 // This matches the sample notifications we created
    }

    // Notify parent of TopAppBarState changes for thread view inheritance
    LaunchedEffect(topAppBarState) {
        onTopAppBarStateChange(topAppBarState)
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
                "login" -> {
                    onLoginClick?.invoke()
                }
                "logout" -> {
                    // Handle logout - for now just navigate to settings
                    // In real app, this would call authViewModel.logout()
                    onNavigateTo("settings")
                }
                "settings" -> {
                    onNavigateTo("settings")
                }
                else -> viewModel.onSidebarItemClick(itemId)
            }
        },
        authState = authState,
        relays = relayUiState.relays,
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
                        onLoginClick = onLoginClick,
                        onProfileClick = {
                            // Navigate to user's own profile
                            onNavigateTo("user_profile")
                        },
                        onAccountsClick = {
                            // Show account switcher
                            showAccountSwitcher = true
                        },
                        onSettingsClick = {
                            // Navigate to settings
                            onNavigateTo("settings")
                        },
                        isGuest = authState.isGuest,
                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                        userAvatarUrl = authState.userProfile?.picture,
                        scrollBehavior = scrollBehavior,
                        currentFeedView = currentFeedView,
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView }
                    )
                }
            },
            bottomBar = {
                if (!isSearchMode) {
                    ScrollAwareBottomNavigationBar(
                        currentDestination = "home",
                        isVisible = isBottomNavVisible,
                        notificationCount = totalNotificationCount,
                        topAppBarState = topAppBarState,
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
                                "messages" -> onNavigateTo("messages")
                                "relays" -> onNavigateTo("relays")
                                "wallet" -> onNavigateTo("wallet")
                                "notifications" -> onNavigateTo("notifications")
                                else -> { /* Other destinations not implemented yet */ }
                            }
                        }
                    )
                }
            }
        ) { paddingValues ->
            // Calculate dynamic content padding based on navigation bar state
            val bottomBarHeight = 72.dp // Height of the navigation bar
            val collapsedFraction = topAppBarState.collapsedFraction

            // Calculate dynamic bottom padding
            val dynamicBottomPadding by remember(collapsedFraction) {
                derivedStateOf {
                    if (collapsedFraction > 0.5f) {
                        0.dp // Remove bottom padding to expand content
                    } else {
                        // Gradually reduce bottom padding as navigation bar hides
                        bottomBarHeight * (1 - collapsedFraction)
                    }
                }
            }

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
                    .consumeWindowInsets(paddingValues)
                    .padding(
                        start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                        top = paddingValues.calculateTopPadding(),
                        end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
                        bottom = dynamicBottomPadding
                    )
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
                            onZapSettings = { showZapConfigDialog = true },
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // Account switcher bottom sheet
    if (showAccountSwitcher) {
        com.example.views.ui.components.AccountSwitchBottomSheet(
            accountStateViewModel = accountStateViewModel,
            onDismiss = { showAccountSwitcher = false },
            onAddAccount = {
                showAccountSwitcher = false
                onLoginClick?.invoke()
            }
        )
    }

    // Zap configuration dialog
    if (showZapConfigDialog) {
        com.example.views.ui.components.ZapConfigurationDialog(
            onDismiss = { showZapConfigDialog = false },
            onOpenWalletSettings = { 
                showZapConfigDialog = false
                showWalletConnectDialog = true
            }
        )
    }

    // Wallet Connect dialog
    if (showWalletConnectDialog) {
        com.example.views.ui.components.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }
}



@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    MaterialTheme {
        DashboardScreen()
    }
}
