package com.example.views.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tag
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.views.repository.TopicNote
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.ui.components.SmartBottomNavigationBar
import com.example.views.ui.components.ScrollAwareBottomNavigationBar
import com.example.views.ui.components.BottomNavDestinations
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.GlobalSidebar
import com.example.views.ui.components.NoteCard
import com.example.views.ui.components.LoadingAnimation
import com.example.views.ui.components.NoteCard
import com.example.views.viewmodel.DashboardViewModel
import com.example.views.viewmodel.AuthViewModel
import com.example.views.viewmodel.RelayManagementViewModel
import com.example.views.viewmodel.TopicsViewModel
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.RelayState
import com.example.views.repository.RelayRepository
import com.example.views.repository.RelayStorageManager
import androidx.compose.ui.platform.LocalContext
import com.example.views.ui.performance.animatedYOffset
import com.example.views.repository.HashtagStats
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Comment
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun TopicsScreen(
    isSearchMode: Boolean = false,
    onSearchModeChange: (Boolean) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onThreadClick: (Note, List<String>?) -> Unit = { _, _ -> },
    onScrollToTop: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    topicsViewModel: TopicsViewModel = viewModel(),
    feedStateViewModel: com.example.views.viewmodel.FeedStateViewModel = viewModel(),
    appViewModel: com.example.views.viewmodel.AppViewModel = viewModel(),
    accountStateViewModel: com.example.views.viewmodel.AccountStateViewModel = viewModel(),
    relayRepository: RelayRepository? = null,
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    onQrClick: () -> Unit = {},
    onNavigateToCreateTopic: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val topicsUiState by topicsViewModel.uiState.collectAsState()
    val topicsFeedState by feedStateViewModel.topicsFeedState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Relay management
    val storageManager = remember { RelayStorageManager(context) }
    val relayViewModel: RelayManagementViewModel? = relayRepository?.let {
        viewModel { RelayManagementViewModel(it, storageManager) }
    }
    val relayUiState = if (relayViewModel != null) {
        relayViewModel.uiState.collectAsState().value
    } else {
        com.example.views.viewmodel.RelayManagementUiState()
    }

    // Load user relays when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.loadUserRelays(pubkey)
        }
    }

    // Get relay categories for sidebar
    val relayCategories = relayViewModel?.relayCategories?.collectAsState()?.value ?: emptyList()

    // Track if we've already loaded relays on this mount
    var hasLoadedRelays by remember { mutableStateOf(false) }

    // Auto-load topics: subscription = all relays, display = sidebar selection
    LaunchedEffect(relayCategories, currentAccount, topicsFeedState.isGlobal, topicsFeedState.selectedCategoryId, topicsFeedState.selectedRelayUrl) {
        if (relayCategories.isNotEmpty() && !hasLoadedRelays) {
            val allUserRelayUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
            val displayUrls = when {
                topicsFeedState.isGlobal -> allUserRelayUrls
                topicsFeedState.selectedCategoryId != null -> relayCategories
                    .firstOrNull { it.id == topicsFeedState.selectedCategoryId }?.relays?.map { it.url } ?: emptyList()
                topicsFeedState.selectedRelayUrl != null -> listOf(topicsFeedState.selectedRelayUrl!!)
                else -> allUserRelayUrls
            }
            if (allUserRelayUrls.isNotEmpty()) {
                topicsViewModel.loadTopicsFromRelays(allUserRelayUrls, displayUrls)
                hasLoadedRelays = true
            }
        }
    }

    // Ensure relays are recalled when navigating back to topics feed
    LaunchedEffect(Unit, relayCategories, topicsFeedState.isGlobal, topicsFeedState.selectedCategoryId, topicsFeedState.selectedRelayUrl) {
        if (!hasLoadedRelays && relayCategories.isNotEmpty() && topicsUiState.hashtagStats.isEmpty() && !topicsUiState.isLoading) {
            val allUserRelayUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
            val displayUrls = when {
                topicsFeedState.isGlobal -> allUserRelayUrls
                topicsFeedState.selectedCategoryId != null -> relayCategories
                    .firstOrNull { it.id == topicsFeedState.selectedCategoryId }?.relays?.map { it.url } ?: emptyList()
                topicsFeedState.selectedRelayUrl != null -> listOf(topicsFeedState.selectedRelayUrl!!)
                else -> allUserRelayUrls
            }
            if (allUserRelayUrls.isNotEmpty()) {
                topicsViewModel.loadTopicsFromRelays(allUserRelayUrls, displayUrls)
                hasLoadedRelays = true
            }
        }
    }

    // Fetch user's NIP-65 relay list when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.fetchUserRelaysFromNetwork(pubkey)
        }
    }

    // Set cache relay URLs for kind-0 profile fetches when account is available
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val cacheUrls = storageManager.loadCacheRelays(pubkey).map { it.url }
            if (cacheUrls.isNotEmpty()) topicsViewModel.setCacheRelayUrls(cacheUrls)
        }
    }

    // Load follow list once when account/relays available; default All (topicsIsFollowing = false)
    LaunchedEffect(currentAccount, relayCategories) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val urls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
            if (urls.isNotEmpty()) {
                topicsViewModel.loadFollowListForTopics(pubkey, urls, topicsFeedState.topicsIsFollowing)
            }
        }
    }
    // Apply All vs Following when user toggles (uses cached follow list)
    LaunchedEffect(topicsFeedState.topicsIsFollowing) {
        topicsViewModel.setFollowFilterForTopics(topicsFeedState.topicsIsFollowing)
    }

    // Search state - using simple String instead of TextFieldValue
    var searchQuery by remember { mutableStateOf("") }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Feed view state
    var currentFeedView by remember { mutableStateOf("Topics") }

    // Selected hashtag state from ViewModel (persists across navigation)
    val selectedHashtag = topicsUiState.selectedHashtag
    val isViewingHashtagFeed = topicsUiState.isViewingHashtagFeed

    // Restore selected topic when returning to Topics tab (persisted in FeedStateViewModel)
    LaunchedEffect(topicsFeedState.selectedHashtag) {
        topicsFeedState.selectedHashtag?.let { hashtag ->
            topicsViewModel.selectHashtag(hashtag)
        }
    }

    // Handle back navigation for hashtag feed
    BackHandler(enabled = isViewingHashtagFeed) {
        feedStateViewModel.clearTopicsSelectedHashtag()
        topicsViewModel.clearSelectedHashtag()
    }

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
    val topAppBarState = initialTopAppBarState ?: rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }

    // Bottom navigation bar visibility controlled by scroll
    val isBottomNavVisible = true

    // Calculate total notification count for badge
    val notificationList by com.example.views.repository.NotificationsRepository.notifications.collectAsState(initial = emptyList())
    val totalNotificationCount = notificationList.size

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

    GlobalSidebar(
        drawerState = drawerState,
        relayCategories = relayCategories,
        feedState = topicsFeedState,
        selectedDisplayName = feedStateViewModel.getTopicsDisplayName(),
        onQrClick = onQrClick,
        onItemClick = { itemId ->
            when {
                itemId == "global" -> {
                    feedStateViewModel.setTopicsGlobal()
                    feedStateViewModel.setHomeGlobal()
                    val allUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
                    if (allUrls.isNotEmpty()) topicsViewModel.setDisplayFilterOnly(allUrls)
                }
                itemId.startsWith("relay_category:") -> {
                    val categoryId = itemId.removePrefix("relay_category:")
                    val category = relayCategories.firstOrNull { it.id == categoryId }
                    val relayUrls = category?.relays?.map { it.url } ?: emptyList()
                    if (relayUrls.isNotEmpty()) {
                        feedStateViewModel.setTopicsSelectedCategory(categoryId, category?.name)
                        feedStateViewModel.setHomeSelectedCategory(categoryId, category?.name)
                        topicsViewModel.setDisplayFilterOnly(relayUrls)
                    }
                }
                itemId.startsWith("relay:") -> {
                    val relayUrl = itemId.removePrefix("relay:")
                    val relay = relayCategories.flatMap { it.relays }.firstOrNull { it.url == relayUrl }
                    feedStateViewModel.setTopicsSelectedRelay(relayUrl, relay?.displayName)
                    feedStateViewModel.setHomeSelectedRelay(relayUrl, relay?.displayName)
                    topicsViewModel.setDisplayFilterOnly(listOf(relayUrl))
                }
                itemId == "user_profile" -> {
                    onNavigateTo("user_profile")
                }
                itemId == "relays" -> {
                    onNavigateTo("relays")
                }
                itemId == "login" -> {
                    onLoginClick?.invoke()
                }
                itemId == "logout" -> {
                    onNavigateTo("settings")
                }
                itemId == "settings" -> {
                    onNavigateTo("settings")
                }
                else -> { /* Other sidebar actions */ }
            }
        },
        onToggleCategory = { categoryId ->
            feedStateViewModel.toggleTopicsExpandedCategory(categoryId)
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
                    // Search mode - show docked SearchBar
                    ModernSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* Optional: Handle explicit search submission */ },
                        searchResults = searchResults,
                        onResultClick = { note ->
                            onThreadClick(note, null)
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
                        title = if (selectedHashtag != null) "#$selectedHashtag" else "topics",
                        isSearchMode = false,
                        showBackArrow = selectedHashtag != null,
                        searchQuery = androidx.compose.ui.text.input.TextFieldValue(""),
                        onSearchQueryChange = { },
                        onMenuClick = {
                            if (isViewingHashtagFeed) {
                                feedStateViewModel.clearTopicsSelectedHashtag()
                                topicsViewModel.clearSelectedHashtag()
                            } else {
                                // Hamburger menu when viewing all hashtags
                                scope.launch {
                                    if (drawerState.isClosed) {
                                        drawerState.open()
                                    } else {
                                        drawerState.close()
                                    }
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
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView },
                        isTopicsFollowingFilter = topicsFeedState.topicsIsFollowing,
                        onTopicsFollowingFilterChange = { feedStateViewModel.setTopicsFollowingFilter(it) },
                        topicsSortOrder = topicsFeedState.topicsSortOrder,
                        onTopicsSortOrderChange = { feedStateViewModel.setTopicsSortOrder(it) }
                    )
                }
            },
            bottomBar = {
                if (!isSearchMode) {
                    ScrollAwareBottomNavigationBar(
                        currentDestination = "topics",
                        isVisible = isBottomNavVisible,
                        notificationCount = totalNotificationCount,
                        topAppBarState = topAppBarState,
                        onDestinationClick = { destination ->
                            when (destination) {
                                "topics" -> {
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
            },
            floatingActionButton = {
                if (!isSearchMode) {
                    val fabBottomBarOffset = (48 * topAppBarState.collapsedFraction).dp
                    Box(modifier = Modifier.offset(y = fabBottomBarOffset)) {
                        FloatingActionButton(
                            onClick = { onNavigateToCreateTopic(selectedHashtag) },
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Create topic")
                        }
                    }
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
                    topicsViewModel.refreshTopics()
                    scope.launch {
                        delay(800)
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
                // Topics/Hashtag Discovery Grid
                if (topicsUiState.connectedRelays.isEmpty()) {
                    // No relays configured
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "No Relays",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Relays Connected",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Open the sidebar and select a relay collection to discover topics",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Sidebar")
                            }
                        }
                    }
                } else if (topicsUiState.isLoading && topicsUiState.hashtagStats.isEmpty()) {
                    // Loading state - connecting to relays
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingAnimation(indicatorSize = 32.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Connecting to relays...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (topicsUiState.isReceivingEvents && topicsUiState.hashtagStats.isEmpty()) {
                    // Receiving events but haven't gotten any topics yet
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingAnimation(indicatorSize = 32.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Retrieving topics from ${topicsUiState.connectedRelays.size} relay${if (topicsUiState.connectedRelays.size > 1) "s" else ""}...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (topicsUiState.hashtagStats.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tag,
                                contentDescription = "No Topics",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Topics Found",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No topics found from the selected relay${if (topicsUiState.connectedRelays.size > 1) "s" else ""}. Try selecting different relays or check back later.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Main content area
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!isViewingHashtagFeed) {
                            // Hashtag list - connection status, new topics, then full width cards
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                // Connection status (same pattern as DashboardScreen)
                                item(key = "topics_connection_status") {
                                    val relayState = topicsUiState.relayState
                                    val connectionText = when (relayState) {
                                        is RelayState.Disconnected -> "Disconnected"
                                        is RelayState.Connecting -> "Connecting…"
                                        is RelayState.Connected -> "Connected"
                                        is RelayState.Subscribed -> "Connected"
                                        is RelayState.ConnectFailed -> "Connection failed" + (relayState.message?.let { ": $it" } ?: "")
                                    }
                                    val isConnecting = relayState is RelayState.Connecting
                                    val isFailed = relayState is RelayState.ConnectFailed
                                    val stateMachine = RelayConnectionStateMachine.getInstance()
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = when (relayState) {
                                            is RelayState.Disconnected -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                            is RelayState.Connecting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            is RelayState.ConnectFailed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (isConnecting) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(
                                                    text = buildString {
                                                        append(connectionText)
                                                        topicsUiState.relayCountSummary?.let { append(" · $it") }
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (isFailed) {
                                                TextButton(onClick = { stateMachine.requestRetry() }) {
                                                    Text("Retry", style = MaterialTheme.typography.labelMedium)
                                                }
                                            }
                                        }
                                    }
                                }
                                // "x new topics" row (topics-only indicator)
                                if (topicsUiState.newTopicsCount > 0) {
                                    item(key = "new_topics_header") {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shadowElevation = 2.dp
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { topicsViewModel.refreshTopics() }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${topicsUiState.newTopicsCount} new topic${if (topicsUiState.newTopicsCount == 1) "" else "s"}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "Tap to show",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }
                                items(
                                    items = topicsUiState.hashtagStats,
                                    key = { it.hashtag }
                                ) { stats ->
                                    HashtagCard(
                                        stats = stats,
                                        isFavorited = false, // TODO: Track favorites
                                        onToggleFavorite = {
                                            // TODO: Toggle favorite
                                        },
                                        onMenuClick = {
                                            // TODO: Show menu
                                        },
                                        onClick = {
                                            feedStateViewModel.setTopicsSelectedHashtag(stats.hashtag)
                                            topicsViewModel.selectHashtag(stats.hashtag)
                                        }
                                    )
                                }
                            }
                        } else {
                            // Kind 11 feed for selected hashtag, or empty state when no topics on this relay
                            if (topicsUiState.topicsForSelectedHashtag.isEmpty() && !topicsUiState.isLoading && selectedHashtag != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tag,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "No topics for #$selectedHashtag on this relay",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Try switching to global or view all topics",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    feedStateViewModel.clearTopicsSelectedHashtag()
                                                    topicsViewModel.clearSelectedHashtag()
                                                }
                                            ) {
                                                Text("View all topics")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    feedStateViewModel.setTopicsGlobal()
                                                    feedStateViewModel.setHomeGlobal()
                                                    val allUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
                                                    if (allUrls.isNotEmpty()) topicsViewModel.setDisplayFilterOnly(allUrls)
                                                }
                                            ) {
                                                Text("Switch to global")
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    items(
                                        items = topicsUiState.topicsForSelectedHashtag,
                                        key = { it.id }
                                    ) { topic ->
                                        Kind11TopicCard(
                                            topic = topic,
                                            isFavorited = false,
                                            onToggleFavorite = {
                                                // TODO: Implement favorite
                                            },
                                            onMenuClick = {
                                                // TODO: Show menu
                                            },
                                            onClick = {
                                                appViewModel.updateThreadSourceScreen("topics")
                                                onThreadClick(topic.toNote(), topicsUiState.connectedRelays)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ✅ ZAP CONFIGURATION: Dialogs for editing zap amounts
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



/**
 * Hashtag card displaying statistics - full width like feed cards
 */
@Composable
private fun HashtagCard(
    stats: HashtagStats,
    isFavorited: Boolean,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Hashtag info
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // Hashtag name and stats
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "#${stats.hashtag}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${stats.topicCount} ${if (stats.topicCount == 1) "topic" else "topics"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stats.totalReplies > 0) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "${stats.totalReplies} ${if (stats.totalReplies == 1) "reply" else "replies"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Latest: ${formatHashtagTimestamp(stats.latestActivity)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Right: Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Star button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                        tint = if (isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Menu button
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
 * Format timestamp to relative time for hashtag cards
 */
private fun formatHashtagTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> "${diff / 604800_000}w ago"
    }
}

/**
 * Kind 11 Topic card displaying topic info - custom design with karma and styled body
 */
@Composable
private fun Kind11TopicCard(
    topic: com.example.views.repository.TopicNote,
    isFavorited: Boolean,
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
        shape = RectangleShape,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
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
                        text = formatHashtagTimestamp(topic.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

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

            Spacer(modifier = Modifier.height(12.dp))

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

            // Karma count + Reply count
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // TODO: Calculate actual karma (upvotes - downvotes)
                val karma = 169 // Placeholder
                Text(
                    text = karma.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "${topic.replyCount} ${if (topic.replyCount == 1) "Reply" else "Replies"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body text with light background
            if (topic.content.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = topic.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Hashtags
            if (topic.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    topic.hashtags.take(3).forEach { hashtag ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
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
 * Convert TopicNote to Note for navigation compatibility
 */
private fun com.example.views.repository.TopicNote.toNote(): Note {
    return Note(
        id = id,
        author = author,
        content = if (title.isNotEmpty()) "$title\n\n$content" else content,
        timestamp = timestamp,
        likes = 0,
        shares = 0,
        comments = replyCount,
        isLiked = false,
        hashtags = hashtags,
        mediaUrls = emptyList()
    )
}

@Preview(showBackground = true)
@Composable
fun TopicsScreenPreview() {
    MaterialTheme {
        TopicsScreen()
    }
}
