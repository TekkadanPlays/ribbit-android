package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.consumeWindowInsets
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.example.views.data.Note
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
import com.example.views.viewmodel.FeedStateViewModel
import com.example.views.viewmodel.HomeSortOrder
import com.example.views.viewmodel.ScrollPosition
import com.example.views.data.RelayConnectionStatus
import com.example.views.relay.RelayState
import com.example.views.repository.RelayRepository
import com.example.views.repository.RelayStorageManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
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
    onThreadClick: (Note, List<String>?) -> Unit = { _, _ -> },
    onImageTap: (com.example.views.data.Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onScrollToTop: () -> Unit = {},
    /** Retrieve the shared media album page for a note (from AppViewModel). */
    mediaPageForNote: (String) -> Int = { 0 },
    /** Store the media album page when user swipes (to AppViewModel). */
    onMediaPageChanged: (String, Int) -> Unit = { _, _ -> },
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    feedStateViewModel: FeedStateViewModel = viewModel(),
    accountStateViewModel: com.example.views.viewmodel.AccountStateViewModel = viewModel(),
    relayRepository: RelayRepository? = null,
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    isDashboardVisible: Boolean = true,
    onQrClick: () -> Unit = {},
    onSidebarSettingsClick: () -> Unit = {},
    onRelayClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val homeFeedState by feedStateViewModel.homeFeedState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val accountsRestored by accountStateViewModel.accountsRestored.collectAsState()
    val zapInProgressNoteIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
    val zappedNoteIds by accountStateViewModel.zappedNoteIds.collectAsState()
    val zappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()
    val replyCountByNoteId by com.example.views.repository.ReplyCountCache.replyCountByNoteId.collectAsState()
    val countsByNoteId by com.example.views.repository.NoteCountsRepository.countsByNoteId.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Real per-relay connection status from RelayConnectionStateMachine
    val perRelayState by com.example.views.relay.RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val liveConnectionStatus = remember(perRelayState) {
        perRelayState.mapValues { (_, status) ->
            when (status) {
                com.example.views.relay.RelayEndpointStatus.Connected -> RelayConnectionStatus.CONNECTED
                com.example.views.relay.RelayEndpointStatus.Connecting -> RelayConnectionStatus.CONNECTING
                com.example.views.relay.RelayEndpointStatus.Failed -> RelayConnectionStatus.ERROR
            }
        }
    }
    val connectedRelayCount = perRelayState.values.count { it == com.example.views.relay.RelayEndpointStatus.Connected }
    val subscribedRelayCount = perRelayState.size

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

    // Onboarding: detect when feed has been empty for too long (new account / no relays)
    var feedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(isDashboardVisible, uiState.notes.isEmpty(), hasLoadedRelays) {
        feedTimedOut = false
        if (isDashboardVisible && uiState.notes.isEmpty()) {
            kotlinx.coroutines.delay(10_000L)
            if (uiState.notes.isEmpty()) feedTimedOut = true
        }
    }
    // Reset timeout when notes arrive
    LaunchedEffect(uiState.notes.size) {
        if (uiState.notes.isNotEmpty()) feedTimedOut = false
    }
    val hasOutboxRelays = relayUiState.outboxRelays.isNotEmpty()
    val hasAnyConfiguredRelays = relayCategories.flatMap { it.relays }.isNotEmpty()

    // If the selected relay/category was removed, fall back to Global
    LaunchedEffect(relayCategories, homeFeedState) {
        val allRelayUrls = relayCategories.flatMap { it.relays }.map { it.url }.toSet()
        val selectedUrl = homeFeedState.selectedRelayUrl
        val selectedCatId = homeFeedState.selectedCategoryId
        if (selectedUrl != null && selectedUrl !in allRelayUrls) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            val allUrls = allRelayUrls.toList()
            if (allUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allUrls)
        } else if (selectedCatId != null && relayCategories.none { it.id == selectedCatId }) {
            feedStateViewModel.setHomeGlobal()
            feedStateViewModel.setTopicsGlobal()
            val allUrls = allRelayUrls.toList()
            if (allUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allUrls)
        }
    }

    // When dashboard is visible, apply feed subscription. Key by selection only so expand/collapse does not reload.
    // When categories have no relays (e.g. fresh install or default category empty), fall back to cache + outbox so feed still loads.
    // Subscription setup: only re-run when visibility, account, or relay config changes.
    // Sidebar relay/category selection is handled by setDisplayFilterOnly in onItemClick — NOT here.
    LaunchedEffect(
        isDashboardVisible,
        currentAccount,
        relayCategories,
        relayUiState.outboxRelays,
        homeFeedState.isGlobal
    ) {
        if (!isDashboardVisible || relayCategories.isEmpty()) return@LaunchedEffect
        val allUserRelayUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
        val pubkey = currentAccount?.toHexKey()
        // If categories have no relays (e.g. default "My Relays" before NIP-65 or storage load), use cache + outbox so we still get a feed
        val relayUrlsToUse = if (allUserRelayUrls.isNotEmpty()) {
            allUserRelayUrls
        } else if (pubkey != null) {
            val cacheUrls = storageManager.loadCacheRelays(pubkey).map { it.url }
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            (cacheUrls + outboxUrls).distinct()
        } else {
            emptyList()
        }
        val displayUrls = when {
            homeFeedState.isGlobal -> relayUrlsToUse
            homeFeedState.selectedCategoryId != null -> relayCategories
                .firstOrNull { it.id == homeFeedState.selectedCategoryId }?.relays?.map { it.url }
                ?: relayUrlsToUse
            homeFeedState.selectedRelayUrl != null -> listOf(homeFeedState.selectedRelayUrl!!)
            else -> relayUrlsToUse
        }
        if (relayUrlsToUse.isNotEmpty()) {
            hasLoadedRelays = true
            // Always run subscription path so connections resume after app close (notes may be from cache).
            // When notes are already present, ensureSubscriptionToNotes only re-applies subscription and does not clear the feed.
            viewModel.loadNotesFromFavoriteCategory(relayUrlsToUse, displayUrls)
            com.example.views.repository.QuotedNoteCache.setRelayUrls(relayUrlsToUse)
        }
    }

    // When feed is visible, sync profile cache into notes so names/avatars render (e.g. after debug Fetch all or returning from profile)
    LaunchedEffect(isDashboardVisible) {
        if (isDashboardVisible) {
            kotlinx.coroutines.delay(400)
            viewModel.syncFeedAuthorsFromCache()
        }
    }

    // Fetch user's NIP-65 relay list when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.fetchUserRelaysFromNetwork(pubkey)
        }
    }

    // Set cache relay URLs, request kind-0 for current user, load follow list, and fetch NIP-65 relay list
    LaunchedEffect(currentAccount, relayUiState.outboxRelays) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val cacheUrls = storageManager.loadCacheRelays(pubkey).map { it.url }
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            val followRelayUrls = (cacheUrls + outboxUrls).distinct()
            if (followRelayUrls.isNotEmpty()) {
                viewModel.setCacheRelayUrls(cacheUrls)
                com.example.views.repository.ProfileMetadataCache.getInstance()
                    .requestProfiles(listOf(pubkey), cacheUrls)
                viewModel.loadFollowList(pubkey, followRelayUrls)
                // NIP-65: fetch kind-10002 relay list for outbox model (counts use indexer relays)
                com.example.views.repository.Nip65RelayListRepository.fetchRelayList(pubkey, cacheUrls)
                // NIP-66: fetch relay discovery events for relay type categorization
                com.example.views.repository.Nip66RelayDiscoveryRepository.init(context)
                com.example.views.repository.Nip66RelayDiscoveryRepository.fetchRelayDiscovery(cacheUrls)
            }
        }
    }

    // Apply follow filter when Following/Global or follow list changes
    LaunchedEffect(homeFeedState.isFollowing, uiState.followList) {
        viewModel.setFollowFilter(homeFeedState.isFollowing)
    }

    // Pending notes build up in the background. User pulls down to refresh to see them.
    // The "X new notes" banner at the top of the feed shows the count.
    // Do NOT auto-apply; let users decide when to see new notes (swipe-to-refresh or tap banner).

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
    // Relay orb tap navigates to relay log page via onRelayClick callback

    // Restore home feed scroll position when returning to dashboard (one-shot; do not re-run on notes.size)
    val scrollPos = homeFeedState.scrollPosition
    LaunchedEffect(isDashboardVisible, scrollPos.firstVisibleItem, scrollPos.scrollOffset) {
        if (isDashboardVisible && scrollPos.firstVisibleItem > 0 && uiState.notes.isNotEmpty()) {
            listState.scrollToItem(
                scrollPos.firstVisibleItem.coerceAtMost(uiState.notes.size - 1),
                scrollPos.scrollOffset
            )
            feedStateViewModel.updateHomeFeedState { copy(scrollPosition = ScrollPosition(0, 0)) }
        }
    }

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

    // Use Material3's built-in scroll behavior for top app bar (shared with nav so back gesture doesn't flash)
    val topAppBarState = initialTopAppBarState ?: rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }

    // Notify parent of TopAppBarState changes for thread view inheritance
    LaunchedEffect(topAppBarState) {
        onTopAppBarStateChange(topAppBarState)
    }

    // Engagement filter: null = all, "replies" / "likes" / "zaps"
    var engagementFilter by remember { mutableStateOf<String?>(null) }

    // Merge enrichment side channel (url previews) into notes — only copy notes that actually have new previews
    val notesWithPreviews by remember(uiState.notes, uiState.urlPreviewsByNoteId) {
        derivedStateOf {
            val previews = uiState.urlPreviewsByNoteId
            if (previews.isEmpty()) {
                uiState.notes
            } else {
                uiState.notes.map { n ->
                    val newPreviews = previews[n.id]
                    if (newPreviews != null && newPreviews != n.urlPreviews) n.copy(urlPreviews = newPreviews) else n
                }
            }
        }
    }
    val notesList = notesWithPreviews
    // Home feed sort: Latest (by time) or Popular (by likes, then time)
    val sortedNotes by remember(notesList, homeFeedState.homeSortOrder) {
        derivedStateOf {
            when (homeFeedState.homeSortOrder) {
                HomeSortOrder.Latest -> notesList.sortedByDescending { it.timestamp }
                HomeSortOrder.Popular -> notesList.sortedWith(
                    compareByDescending<Note> { it.likes }.thenByDescending { it.timestamp }
                )
            }
        }
    }

    // Sort by engagement type: Most Replies / Most Likes / Most Zaps
    val engagementFilteredNotes by remember(sortedNotes, engagementFilter, replyCountByNoteId, countsByNoteId) {
        derivedStateOf {
            when (engagementFilter) {
                "replies" -> sortedNotes.sortedByDescending { replyCountByNoteId[it.id] ?: 0 }
                "likes" -> sortedNotes.sortedByDescending {
                    countsByNoteId[it.id]?.reactionAuthors?.values?.sumOf { authors -> authors.size } ?: 0
                }
                "zaps" -> sortedNotes.sortedByDescending { countsByNoteId[it.id]?.zapTotalSats ?: 0L }
                else -> sortedNotes
            }
        }
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
        feedState = homeFeedState,
        selectedDisplayName = feedStateViewModel.getHomeDisplayName(),
        relayState = uiState.relayState,
        connectionStatus = liveConnectionStatus,
        connectedRelayCount = connectedRelayCount,
        subscribedRelayCount = subscribedRelayCount,
        onQrClick = onQrClick,
        onSettingsClick = onSidebarSettingsClick,
        onItemClick = { itemId ->
            when {
                itemId == "global" -> {
                    feedStateViewModel.setHomeGlobal()
                    feedStateViewModel.setTopicsGlobal()
                    val allUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
                    if (allUrls.isNotEmpty()) viewModel.setDisplayFilterOnly(allUrls)
                }
                itemId.startsWith("relay_category:") -> {
                    val categoryId = itemId.removePrefix("relay_category:")
                    val category = relayCategories.firstOrNull { it.id == categoryId }
                    val relayUrls = category?.relays?.map { it.url } ?: emptyList()
                    if (relayUrls.isNotEmpty()) {
                        feedStateViewModel.setHomeSelectedCategory(categoryId, category?.name)
                        feedStateViewModel.setTopicsSelectedCategory(categoryId, category?.name)
                        viewModel.setDisplayFilterOnly(relayUrls)
                    }
                }
                itemId.startsWith("relay:") -> {
                    val relayUrl = itemId.removePrefix("relay:")
                    val relay = relayCategories.flatMap { it.relays }.firstOrNull { it.url == relayUrl }
                    feedStateViewModel.setHomeSelectedRelay(relayUrl, relay?.displayName)
                    feedStateViewModel.setTopicsSelectedRelay(relayUrl, relay?.displayName)
                    viewModel.setDisplayFilterOnly(listOf(relayUrl))
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
                else -> viewModel.onSidebarItemClick(itemId)
            }
        },
        onToggleCategory = { categoryId ->
            feedStateViewModel.toggleHomeExpandedCategory(categoryId)
        },
        modifier = modifier
    ) {
        // Hide header and bottom bar when not logged in
        val isLoggedIn = currentAccount != null

        // When not logged in, bypass Scaffold entirely to avoid bottom line artifact
        if (!isLoggedIn) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (!accountsRestored) {
                    // Account restore in progress
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp,
                        color = Color(0xFF8B9D83)
                    )
                } else {
                    // Sign-in prompt
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "\uD83D\uDC38",
                            style = MaterialTheme.typography.displayLarge
                        )
                        Text(
                            text = "Welcome to Ribbit",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (onLoginClick != null) {
                            Button(
                                onClick = { onLoginClick.invoke() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Login with Amber")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = "  or  ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }

                        val loginContext = LocalContext.current
                        TextButton(
                            onClick = {
                                loginContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/greenart7c3/Amber")))
                            }
                        ) {
                            Text("Download Amber")
                        }
                    }
                }
            }
            return@GlobalSidebar
        }

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
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView },
                        isFollowingFilter = homeFeedState.isFollowing,
                        onFollowingFilterChange = { enabled ->
                            scope.launch {
                                listState.scrollToItem(0)
                                feedStateViewModel.setHomeFollowingFilter(enabled)
                            }
                        },
                        onEditFeedClick = { /* TODO: custom feed filter views */ },
                        homeSortOrder = homeFeedState.homeSortOrder,
                        onHomeSortOrderChange = { feedStateViewModel.setHomeSortOrder(it) },
                        activeEngagementFilter = engagementFilter,
                        onEngagementFilterChange = { engagementFilter = it },
                        onNavigateToTopics = { onNavigateTo("topics") },
                        onNavigateToLive = { onNavigateTo("live_explorer") }
                    )
                }
            },
            floatingActionButton = {
                val fabVisible by remember(topAppBarState) {
                    derivedStateOf { topAppBarState.collapsedFraction < 0.5f }
                }
                AnimatedVisibility(
                    visible = !isSearchMode && fabVisible,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = { onNavigateTo("compose") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(bottom = 80.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Compose note")
                    }
                }
            }
        ) { paddingValues ->
            // Pull-to-refresh: merge pending notes and re-apply relay + follow + reply filters (no full clear).
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    scope.launch {
                        isRefreshing = true
                        kotlinx.coroutines.delay(300)
                        viewModel.applyPendingNotes()
                        com.example.views.relay.RelayConnectionStateMachine.getInstance().requestRetry()
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
                ) {
                    // New notes counter (tap to load) — below live activities, non-sticky
                    run {
                        val newCount = if (homeFeedState.isFollowing) uiState.newNotesCountFollowing else uiState.newNotesCountAll
                        val otherCount = if (homeFeedState.isFollowing) uiState.newNotesCountAll else uiState.newNotesCountFollowing
                        if (newCount > 0) {
                            item(key = "new_notes_counter") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                viewModel.applyPendingNotes()
                                                listState.animateScrollToItem(0)
                                            }
                                        },
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "\u2191 $newCount new note${if (newCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (otherCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "($otherCount in ${if (homeFeedState.isFollowing) "All" else "Following"})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Logged in but notes still loading (first load)
                    if (sortedNotes.isEmpty()) {
                        if (feedTimedOut || (!hasOutboxRelays && !hasAnyConfiguredRelays)) {
                            // Onboarding empty state — new account or no relays configured
                            item(key = "onboarding_prompt") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxHeight()
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Public,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            text = "Welcome to Ribbit",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = if (!hasOutboxRelays)
                                                "Set up your relays to start seeing notes and let others find you."
                                            else
                                                "No notes found yet. Try adding more relays or discovering new ones.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(Modifier.height(24.dp))
                                        FilledTonalButton(
                                            onClick = { onNavigateTo("settings/relay_health") },
                                            modifier = Modifier.fillMaxWidth(0.7f)
                                        ) {
                                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Set Up Relays")
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = { onNavigateTo("relay_discovery") },
                                            modifier = Modifier.fillMaxWidth(0.7f)
                                        ) {
                                            Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Discover Relays")
                                        }
                                    }
                                }
                            }
                        } else {
                            // Still loading — show spinner
                            item(key = "notes_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxHeight()
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(48.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    // Stable keys (note.id) so LazyColumn recomposes only changed items when the feed list updates.
                    // contentType enables efficient item recycling across scroll.
                    items(
                        items = engagementFilteredNotes,
                        key = { it.id },
                        contentType = { "note_card" }
                    ) { note ->
                        NoteCard(
                            note = note,
                            onLike = { noteId -> viewModel.toggleLike(noteId) },
                            onShare = { noteId -> /* Handle share */ },
                            onComment = { noteId -> onThreadClick(note, null) },
                            onReact = { reactedNote, emoji ->
                                val error = accountStateViewModel.sendReaction(reactedNote, emoji)
                                if (error != null) {
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onProfileClick = onProfileClick,
                            onNoteClick = { note -> onThreadClick(note, null) },
                            onImageTap = onImageTap,
                            onOpenImageViewer = onOpenImageViewer,
                            onVideoClick = onVideoClick,
                            onZap = { noteId, amount ->
                                val n = sortedNotes.find { it.id == noteId } ?: return@NoteCard
                                val err = accountStateViewModel.sendZap(n, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { n, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(n, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZapSettings = { showZapConfigDialog = true },
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            onRelayClick = onRelayClick,
                            accountNpub = currentAccount?.npub,
                            isZapInProgress = note.id in zapInProgressNoteIds,
                            isZapped = note.id in zappedNoteIds,
                            myZappedAmount = zappedAmountByNoteId[note.id],
                            overrideReplyCount = replyCountByNoteId[note.id] ?: countsByNoteId[note.id]?.replyCount,
                            overrideZapCount = countsByNoteId[note.id]?.zapCount,
                            overrideZapTotalSats = countsByNoteId[note.id]?.zapTotalSats,
                            overrideReactions = countsByNoteId[note.id]?.reactions,
                            overrideReactionAuthors = countsByNoteId[note.id]?.reactionAuthors,
                            overrideZapAuthors = countsByNoteId[note.id]?.zapAuthors,
                            overrideZapAmountByAuthor = countsByNoteId[note.id]?.zapAmountByAuthor,
                            overrideCustomEmojiUrls = countsByNoteId[note.id]?.customEmojiUrls,
                            showHashtagsSection = false,
                            initialMediaPage = mediaPageForNote(note.id),
                            onMediaPageChanged = { page -> onMediaPageChanged(note.id, page) },
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
