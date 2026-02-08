package com.example.views.ui.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.views.data.Author
import com.example.views.repository.NotesRepository
import com.example.views.repository.NotificationsRepository
import com.example.views.repository.ProfileMetadataCache
import com.example.views.repository.RelayRepository
import com.example.views.repository.RelayStorageManager
import com.example.views.ui.components.NoteCard
import com.example.views.utils.normalizeAuthorIdForCache
import com.example.views.ui.components.RelayInfoDialog
import com.example.views.ui.components.ScrollAwareBottomNavigationBar
import com.example.views.ui.components.ThreadSlideBackBox
import com.example.views.ui.screens.AboutScreen
import com.example.views.ui.screens.AccountPreferencesScreen
import com.example.views.ui.screens.AppearanceSettingsScreen
import com.example.views.ui.screens.ComposeNoteScreen
import com.example.views.ui.screens.ComposeTopicScreen
import com.example.views.ui.screens.DashboardScreen
import com.example.views.ui.screens.DebugFollowListScreen
import com.example.views.ui.screens.ImageContentViewerScreen
import com.example.views.ui.screens.VideoContentViewerScreen
import com.example.views.ui.screens.ModernThreadViewScreen
import com.example.views.ui.screens.NotificationsScreen
import com.example.views.ui.screens.TopicsScreen
import com.example.views.ui.screens.ProfileScreen
import com.example.views.ui.screens.RelayLogScreen
import com.example.views.ui.screens.RelayManagementScreen
import com.example.views.ui.screens.SettingsScreen
import com.example.views.ui.screens.QrCodeScreen
import com.example.views.ui.screens.ReplyComposeScreen
import com.example.views.viewmodel.AppViewModel
import com.example.views.viewmodel.rememberThreadStateHolder
import com.example.views.viewmodel.DashboardViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import com.example.views.auth.AmberState
import com.vitorpamplona.quartz.nip55AndroidSigner.client.IActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main navigation composable for Ribbit app using Jetpack Navigation. This provides proper
 * backstack management like Primal, allowing infinite exploration through feeds, threads, and
 * profiles without losing history.
 *
 * The bottom navigation bar is persistent across main screens and hidden on detail screens. Uses
 * MaterialFadeThrough transitions for navigation bar page changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RibbitNavigation(
        appViewModel: AppViewModel,
        accountStateViewModel: com.example.views.viewmodel.AccountStateViewModel,
        onAmberLogin: (android.content.Intent) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val relayRepository = remember { RelayRepository(context) }

    // Report TTFD after first frame so system and tools can measure startup accurately
    LaunchedEffect(Unit) {
        withFrameMillis { }
        (context as? ComponentActivity)?.reportFullyDrawn()
    }
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

    // Feed state - separate states for Home and Topics feeds
    val feedStateViewModel: com.example.views.viewmodel.FeedStateViewModel = viewModel()

    // Thread state holder - persists scroll positions and comment states per thread
    val threadStateHolder = rememberThreadStateHolder()

    // Track scroll states for different screens
    val feedListState = rememberLazyListState()

    // Global top app bar state for collapsible navigation
    // This state is shared across main screens so collapse state persists during navigation
    val topAppBarState = rememberTopAppBarState()

    // Dashboard and Topics list states for scroll-to-top (no feed refresh)
    val dashboardListState = rememberLazyListState()
    val topicsListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Observe async toast messages (e.g. reaction failures)
    val toastMsg by accountStateViewModel.toastMessage.collectAsState()
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            accountStateViewModel.clearToast()
        }
    }

    // Register Amber foreground launcher so NIP-55 signing can prompt the user when needed
    val amberState by accountStateViewModel.amberState.collectAsState()
    val signer = (amberState as? AmberState.LoggedIn)?.signer
    if (signer is IActivityLauncher) {
        val activityLauncher = signer as IActivityLauncher
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { activityLauncher.newResponse(it) }
            }
        }
        DisposableEffect(activityLauncher, launcher) {
            val launcherFn: (Intent) -> Unit = { intent ->
                try {
                    launcher.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "Amber signer not found", Toast.LENGTH_SHORT).show()
                }
            }
            activityLauncher.registerForegroundLauncher(launcherFn)
            onDispose {
                activityLauncher.unregisterForegroundLauncher(launcherFn)
            }
        }
    }

    // Overlay thread state – hoisted so the Scaffold can hide the bottom nav when a thread overlay is active
    var overlayThreadNoteId by remember { mutableStateOf<String?>(null) }

    // Determine current route to show/hide bottom nav
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Clear overlay when navigating away from dashboard (but not when in image/video viewer – pop returns to thread overlay)
    LaunchedEffect(currentRoute) {
        if (currentRoute != "dashboard" && currentRoute != "image_viewer" && currentRoute != "video_viewer") {
            overlayThreadNoteId = null
        }
    }

    // Main screens that should show the bottom navigation
    val mainScreenRoutes = setOf("dashboard", "notifications", "relays", "messages", "profile", "topics")
    val showBottomNav = (currentRoute in mainScreenRoutes || currentRoute?.startsWith("profile") == true)
        && currentRoute?.startsWith("thread") != true
        && overlayThreadNoteId == null

    // Defer showing bottom bar when returning from thread so pop transition settles (avoids flash)
    var allowBottomNavVisible by remember { mutableStateOf(true) }
    val isMainScreen = (currentRoute in mainScreenRoutes || currentRoute?.startsWith("profile") == true) &&
            currentRoute?.startsWith("thread") != true && overlayThreadNoteId == null
    LaunchedEffect(currentRoute, overlayThreadNoteId) {
        if (!isMainScreen) {
            allowBottomNavVisible = false
        } else if (!allowBottomNavVisible) {
            kotlinx.coroutines.delay(80)
            allowBottomNavVisible = true
        }
    }

    // Current destination for bottom nav highlighting
    val currentDestination =
            when {
                currentRoute == "dashboard" -> "home"
                currentRoute == "topics" -> "topics"
                currentRoute == "notifications" -> "notifications"
                currentRoute == "relays" -> "relays"
                currentRoute?.startsWith("profile") == true -> "profile"
                currentRoute in mainScreenRoutes -> currentRoute ?: "home"
                else -> "home"
            }

    // Real notification count from NotificationsRepository (subscription started below when account + relays ready)
    val notificationUnseenCount by NotificationsRepository.unseenCount.collectAsState(initial = 0)

    // Cached reply counts (updated when user opens a thread); used by Dashboard and Profile cards
    val replyCountByNoteId by com.example.views.repository.ReplyCountCache.replyCountByNoteId.collectAsState()
    // Zap/reaction counts from kind-7 and kind-9735; used by Dashboard and Profile cards
    val noteCountsByNoteId by com.example.views.repository.NoteCountsRepository.countsByNoteId.collectAsState()

    // Start notifications subscription when we have account + relays (works regardless of which tab is visible)
    val storageManager = remember { RelayStorageManager(context) }
    LaunchedEffect(currentAccount) {
        val pubkey = currentAccount?.toHexKey() ?: return@LaunchedEffect
        val categories = storageManager.loadCategories(pubkey)
        val allUserRelayUrls = categories.flatMap { it.relays }.map { it.url }.distinct()
        if (allUserRelayUrls.isNotEmpty()) {
            val cacheUrls = storageManager.loadCacheRelays(pubkey).map { it.url }
            NotificationsRepository.setCacheRelayUrls(cacheUrls)
            NotificationsRepository.startSubscription(pubkey, allUserRelayUrls)
        }
    }

    // Double-tap back to exit
    var backPressedTime by remember { mutableLongStateOf(0L) }
    var shouldShowExitToast by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Show toast when needed
    LaunchedEffect(shouldShowExitToast) {
        if (shouldShowExitToast) {
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            shouldShowExitToast = false
        }
    }

    // Handle back press on main screens
    BackHandler(enabled = currentRoute in mainScreenRoutes) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - backPressedTime < 2000) {
            // Double tap detected - exit app
            (context as? android.app.Activity)?.finish()
        } else {
            // First tap - show toast
            backPressedTime = currentTime
            shouldShowExitToast = true
        }
    }

    Scaffold(
            bottomBar = {
                if (showBottomNav && allowBottomNavVisible) {
                    ScrollAwareBottomNavigationBar(
                            currentDestination = currentDestination,
                            onDestinationClick = { destination ->
                                when (destination) {
                                    "home" -> {
                                        if (currentDestination == "home") {
                                            // Already on home feed - scroll to top only (no refresh)
                                            coroutineScope.launch {
                                                dashboardListState.animateScrollToItem(0)
                                            }
                                        } else {
                                            navController.navigate("dashboard") {
                                                popUpTo("dashboard") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    "messages" -> {
                                        /* No action - icon disabled */
                                    }
                                    "relays" ->
                                            navController.navigate("relays") {
                                                popUpTo("dashboard") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                    "topics" -> {
                                            if (currentDestination == "topics") {
                                                // Already on topics feed - scroll to top only (no refresh)
                                                coroutineScope.launch {
                                                    topicsListState.animateScrollToItem(0)
                                                }
                                            } else {
                                                navController.navigate("topics") {
                                                    popUpTo("dashboard") { inclusive = false }
                                                    launchSingleTop = true
                                                }
                                            }
                                        }
                                    "notifications" ->
                                            navController.navigate("notifications") {
                                                popUpTo("dashboard") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                    "profile" ->
                                            currentAccount?.toHexKey()?.let { pubkey ->
                                                navController.navigateToProfile(pubkey)
                                            } ?: run {
                                                navController.navigate("dashboard")
                                            }
                                }
                            },
                            isVisible = true,
                            notificationCount = notificationUnseenCount,
                            topAppBarState = topAppBarState
                    )
                }
            }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                    navController = navController,
                    startDestination = "dashboard",
                    enterTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                // Fade through transition for navigation bar navigation
                                fadeIn(animationSpec = tween(210, delayMillis = 90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideIntoContainer(
                                        towards =
                                                AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardDecelerate
                                                )
                                ) + fadeIn(animationSpec = tween(300))
                            }
                        }
                    },
                    exitTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                // Fade through transition for navigation bar navigation
                                fadeOut(animationSpec = tween(90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideOutOfContainer(
                                        towards =
                                                AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardAccelerate
                                                )
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        }
                    },
                    popEnterTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                fadeIn(animationSpec = tween(210, delayMillis = 90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardDecelerate
                                                )
                                ) + fadeIn(animationSpec = tween(300))
                            }
                        }
                    },
                    popExitTransition = {
                        // Use MaterialFadeThrough for main screen transitions only
                        when {
                            initialState.destination.route in mainScreenRoutes &&
                                    targetState.destination.route in mainScreenRoutes -> {
                                fadeOut(animationSpec = tween(90))
                            }
                            else -> {
                                // Default: slide + fade (overridden by per-route transitions)
                                slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec =
                                                tween(
                                                        300,
                                                        easing =
                                                                MaterialMotion
                                                                        .EasingStandardAccelerate
                                                )
                                ) + fadeOut(animationSpec = tween(300))
                            }
                        }
                    }
            ) {
                // Dashboard - Home feed (thread opens as overlay so feed stays visible for slide-back)
                composable("dashboard") {
                    val appState by appViewModel.appState.collectAsState()
                    val context = LocalContext.current
                    val storageManager = remember { com.example.views.repository.RelayStorageManager(context) }
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val fallbackRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            val favoriteCategory = categories.firstOrNull { it.isFavorite }
                                ?: categories.firstOrNull { it.isDefault }
                            favoriteCategory?.relays?.map { it.url } ?: emptyList()
                        } ?: emptyList()
                    }
                    val cacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadCacheRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        DashboardScreen(
                            isSearchMode = appState.isSearchMode,
                            onSearchModeChange = { appViewModel.updateSearchMode(it) },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onNavigateTo = { screen ->
                                when (screen) {
                                    "settings" -> navController.navigate("settings")
                                    "relays" -> navController.navigate("relays")
                                    "notifications" -> navController.navigate("notifications")
                                    "messages" -> navController.navigate("messages")
                                    "user_profile" -> currentAccount?.toHexKey()?.let { navController.navigateToProfile(it) }
                                    "compose" -> navController.navigate("compose")
                                }
                            },
                            onThreadClick = { note, _ ->
                                feedStateViewModel.saveHomeScrollPosition(
                                    dashboardListState.firstVisibleItemIndex,
                                    dashboardListState.firstVisibleItemScrollOffset
                                )
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                overlayThreadNoteId = note.id
                            },
                            onImageTap = { note, _, _ ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                overlayThreadNoteId = note.id
                            },
                            onOpenImageViewer = { urls, index ->
                                appViewModel.openImageViewer(urls, index)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, index ->
                                appViewModel.openVideoViewer(urls, index)
                                navController.navigate("video_viewer")
                            },
                            onScrollToTop = {
                                coroutineScope.launch {
                                    dashboardListState.animateScrollToItem(0)
                                }
                            },
                            listState = dashboardListState,
                            feedStateViewModel = feedStateViewModel,
                            accountStateViewModel = accountStateViewModel,
                            relayRepository = relayRepository,
                            onLoginClick = {
                                val loginIntent = accountStateViewModel.loginWithAmber()
                                onAmberLogin(loginIntent)
                            },
                            initialTopAppBarState = topAppBarState,
                            isDashboardVisible = (currentRoute == "dashboard"),
                            onQrClick = { navController.navigate("user_qr") }
                        )

                        // Intercept system back gesture when overlay thread is showing
                        BackHandler(enabled = overlayThreadNoteId != null) {
                            overlayThreadNoteId = null
                        }

                        // Thread overlay: feed stays underneath so slide-back reveals it; slide in from right like nav thread.
                        // Keep last overlay note in state so exit animation has content to run on (don't clear content before exit).
                        val overlayNote = appState.selectedNote
                        val showThreadOverlay = overlayThreadNoteId != null && overlayNote != null && overlayNote.id == overlayThreadNoteId
                        var lastOverlayNoteId by remember { mutableStateOf<String?>(null) }
                        var lastOverlayNote by remember { mutableStateOf<com.example.views.data.Note?>(null) }
                        if (showThreadOverlay && overlayThreadNoteId != null && overlayNote != null) {
                            lastOverlayNoteId = overlayThreadNoteId
                            lastOverlayNote = overlayNote
                        }
                        val contentNoteId = if (showThreadOverlay) overlayThreadNoteId else lastOverlayNoteId
                        val contentNote = if (showThreadOverlay) overlayNote else lastOverlayNote
                        AnimatedVisibility(
                            visible = showThreadOverlay,
                            enter = slideInHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            ) { fullWidth -> fullWidth },
                            exit = slideOutHorizontally(
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            ) { fullWidth -> fullWidth }
                        ) {
                            if (contentNoteId != null && contentNote != null) {
                                val noteId = contentNoteId
                                val relayUrls = appState.threadRelayUrls?.takeIf { it.isNotEmpty() } ?: fallbackRelayUrls
                                val savedScrollState = threadStateHolder.getScrollState(noteId)
                                val threadListState = rememberLazyListState(
                                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                                )
                                val commentStates = threadStateHolder.getCommentStates(noteId)
                                var expandedControlsCommentId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                                }
                                var expandedControlsReplyId by remember {
                                    mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                                }
                                val threadTopAppBarState = rememberTopAppBarState()
                                val authState by accountStateViewModel.authState.collectAsState()
                                DisposableEffect(noteId) {
                                    onDispose {
                                        threadStateHolder.saveScrollState(noteId, threadListState)
                                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                                        threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                                    }
                                }
                                ThreadSlideBackBox(onBack = { overlayThreadNoteId = null }) {
                                    ModernThreadViewScreen(
                                        note = contentNote,
                                        comments = emptyList(),
                                        listState = threadListState,
                                        commentStates = commentStates,
                                        expandedControlsCommentId = expandedControlsCommentId,
                                        onExpandedControlsChange = { expandedControlsCommentId = if (expandedControlsCommentId == it) null else it },
                                        expandedControlsReplyId = expandedControlsReplyId,
                                        onExpandedControlsReplyChange = { replyId ->
                                            expandedControlsReplyId = if (expandedControlsReplyId == replyId) null else replyId
                                        },
                                        topAppBarState = threadTopAppBarState,
                                        replyKind = 1,
                                        relayUrls = relayUrls,
                                        cacheRelayUrls = cacheRelayUrls,
                                        onBackClick = { overlayThreadNoteId = null },
                                        onProfileClick = { navController.navigateToProfile(it) },
                                        onImageTap = { _, urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer")
                                        },
                                        onOpenImageViewer = { urls, idx ->
                                            appViewModel.openImageViewer(urls, idx)
                                            navController.navigate("image_viewer")
                                        },
                                        onVideoClick = { urls, idx ->
                                            appViewModel.openVideoViewer(urls, idx)
                                            navController.navigate("video_viewer")
                                        },
                                        onReact = { note, emoji ->
                                            val error = accountStateViewModel.sendReaction(note, emoji)
                                            if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                        },
                                        onCustomZapSend = { note, amount, zapType, msg ->
                                            val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                            if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        },
                                        onZap = { nId, amount ->
                                            if (contentNote != null && contentNote.id == nId) {
                                                val err = accountStateViewModel.sendZap(contentNote, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                                        zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                                        myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                                        onLoginClick = {
                                            val loginIntent = accountStateViewModel.loginWithAmber()
                                            onAmberLogin(loginIntent)
                                        },
                                        isGuest = authState.isGuest,
                                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                                        userAvatarUrl = authState.userProfile?.picture,
                                        accountNpub = currentAccount?.npub,
                                        onHeaderProfileClick = {
                                            authState.userProfile?.pubkey?.let { navController.navigateToProfile(it) }
                                        },
                                        onHeaderAccountsClick = { },
                                        onHeaderQrCodeClick = { navController.navigate("user_qr") },
                                        onHeaderSettingsClick = { navController.navigate("settings") }
                                    )
                                }
                            }
                        }
                    }
                }

                // Thread view - Can navigate to profiles and other threads (from notifications, topics, profile)
                composable(
                        route = "thread/{noteId}?replyKind={replyKind}&highlightReplyId={highlightReplyId}",
                        arguments = listOf(
                            navArgument("noteId") { type = NavType.StringType },
                            navArgument("replyKind") {
                                type = NavType.IntType
                                defaultValue = 1 // Default to Kind 1 (home feed)
                            },
                            navArgument("highlightReplyId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        ),
                        enterTransition = {
                            slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            )
                        },
                        exitTransition = { null },
                        popEnterTransition = { null },
                        popExitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            )
                        }
                ) { backStackEntry ->
                    val noteId = backStackEntry.arguments?.getString("noteId") ?: return@composable
                    val replyKind = backStackEntry.arguments?.getInt("replyKind") ?: 1
                    val highlightReplyId = backStackEntry.arguments?.getString("highlightReplyId")
                    val context = LocalContext.current

                    // Get note from AppViewModel's selected note (no fake fallback)
                    val appState by appViewModel.appState.collectAsState()
                    val note = appState.selectedNote
                    if (note == null || note.id != noteId) {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                        return@composable
                    }

                    val authState by accountStateViewModel.authState.collectAsState()

                    // Get relay URLs for thread replies: use feed's relays when opened from topics/dashboard, else default category
                    val storageManager = remember { com.example.views.repository.RelayStorageManager(context) }
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val fallbackRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            val categories = storageManager.loadCategories(pubkey)
                            val favoriteCategory = categories.firstOrNull { it.isFavorite }
                                ?: categories.firstOrNull { it.isDefault }
                            favoriteCategory?.relays?.map { it.url } ?: emptyList()
                        } ?: emptyList()
                    }
                    val relayUrls = appState.threadRelayUrls?.takeIf { it.isNotEmpty() } ?: fallbackRelayUrls
                    val cacheRelayUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadCacheRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    // Live reply list is driven by repliesState (Kind1RepliesViewModel / ThreadRepliesViewModel).
                    val sampleComments = emptyList<com.example.views.ui.screens.CommentThread>()

                        // Restore scroll state for this specific thread
                        val savedScrollState = threadStateHolder.getScrollState(noteId)
                        val threadListState =
                                rememberLazyListState(
                                        initialFirstVisibleItemIndex =
                                                savedScrollState.firstVisibleItemIndex,
                                        initialFirstVisibleItemScrollOffset =
                                                savedScrollState.firstVisibleItemScrollOffset
                                )

                        // Get comment states for this specific thread
                        val commentStates = threadStateHolder.getCommentStates(noteId)
                        var expandedControlsCommentId by remember {
                            mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                        }
                        var expandedControlsReplyId by remember {
                            mutableStateOf(threadStateHolder.getExpandedReplyControls(noteId))
                        }

                        // Thread gets its own TopAppBarState so scrolling thread doesn't mutate
                        // the feed's header state — prevents header flash on gesture back
                        val threadTopAppBarState = rememberTopAppBarState()

                        // Save scroll state when leaving the screen
                        DisposableEffect(noteId) {
                            onDispose {
                                threadStateHolder.saveScrollState(noteId, threadListState)
                                threadStateHolder.setExpandedControls(
                                        noteId,
                                        expandedControlsCommentId
                                )
                                threadStateHolder.setExpandedReplyControls(noteId, expandedControlsReplyId)
                            }
                        }

                    ThreadSlideBackBox(onBack = { navController.popBackStack() }) {
                    ModernThreadViewScreen(
                            note = note,
                            comments = sampleComments, // preview-only; live content from repliesState
                            listState = threadListState,
                            commentStates = commentStates,
                            expandedControlsCommentId = expandedControlsCommentId,
                            onExpandedControlsChange = { commentId ->
                                expandedControlsCommentId =
                                        if (expandedControlsCommentId == commentId) null
                                        else commentId
                            },
                            expandedControlsReplyId = expandedControlsReplyId,
                            onExpandedControlsReplyChange = { replyId ->
                                expandedControlsReplyId =
                                        if (expandedControlsReplyId == replyId) null
                                        else replyId
                            },
                            topAppBarState = threadTopAppBarState,
                            replyKind = replyKind,
                            highlightReplyId = highlightReplyId,
                            relayUrls = relayUrls,
                            cacheRelayUrls = cacheRelayUrls,
                            onBackClick = { navController.popBackStack() },
                            onLike = { /* TODO: Handle like */},
                            onShare = { /* TODO: Handle share */},
                            onComment = { /* TODO: Handle comment */},
                            onProfileClick = { authorId ->
                                // Navigate to profile - adds to backstack
                                navController.navigateToProfile(authorId)
                            },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { nId, amount ->
                                if (note.id == nId) {
                                    val err = accountStateViewModel.sendZap(note, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSendZap = { targetNote, amount ->
                                val err = accountStateViewModel.sendZap(targetNote, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            zapInProgressNoteIds = accountStateViewModel.zapInProgressNoteIds.collectAsState().value,
                            zappedNoteIds = accountStateViewModel.zappedNoteIds.collectAsState().value,
                            myZappedAmountByNoteId = accountStateViewModel.zappedAmountByNoteId.collectAsState().value,
                            onCommentLike = { /* TODO: Handle comment like */},
                            onCommentReply = { /* Handled inside ModernThreadViewScreen when replyKind==1111 */},
                            onPublishThreadReply = if (replyKind == 1111) { { rootId, rootPubkey, parentId, parentPubkey, content ->
                                accountStateViewModel.publishThreadReply(rootId, rootPubkey, parentId, parentPubkey, content)
                            } } else null,
                            onOpenReplyCompose = if (replyKind == 1111) { { rootId, rootPubkey, parentId, parentPubkey, replyToNote ->
                                appViewModel.setReplyToNote(replyToNote)
                                val enc = { s: String? -> android.net.Uri.encode(s ?: "") }
                                navController.navigate("reply_compose?rootId=${enc(rootId)}&rootPubkey=${enc(rootPubkey)}&parentId=${enc(parentId)}&parentPubkey=${enc(parentPubkey)}")
                            } } else null,
                            onLoginClick = {
                                val loginIntent = accountStateViewModel.loginWithAmber()
                                onAmberLogin(loginIntent)
                            },
                            isGuest = authState.isGuest,
                            userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                            userAvatarUrl = authState.userProfile?.picture,
                            accountNpub = currentAccount?.npub,
                            onHeaderProfileClick = {
                                authState.userProfile?.pubkey?.let { pubkey ->
                                    navController.navigateToProfile(pubkey)
                                }
                            },
                            onHeaderAccountsClick = { },
                            onHeaderQrCodeClick = { navController.navigate("user_qr") },
                            onHeaderSettingsClick = { navController.navigate("settings") }
                    )
                    }
                }

                // User QR code (npub) — from header profile menu
                composable("user_qr") {
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    QrCodeScreen(
                        npub = currentAccount?.npub,
                        onBack = { navController.popBackStack() }
                    )
                }

                // Full-screen image viewer (Save, HD, back)
                composable("image_viewer") {
                    val appState by appViewModel.appState.collectAsState()
                    val urls = appState.imageViewerUrls
                    if (urls != null) {
                        ImageContentViewerScreen(
                            urls = urls,
                            initialIndex = appState.imageViewerInitialIndex,
                            onBackClick = {
                                appViewModel.clearImageViewer()
                                navController.popBackStack()
                            }
                        )
                    }
                }

                // Full-screen video viewer
                composable("video_viewer") {
                    val appState by appViewModel.appState.collectAsState()
                    val urls = appState.videoViewerUrls
                    if (urls != null) {
                        VideoContentViewerScreen(
                            urls = urls,
                            initialIndex = appState.videoViewerInitialIndex,
                            onBackClick = {
                                appViewModel.clearVideoViewer()
                                navController.popBackStack()
                            }
                        )
                    }
                }

                // Profile view - Can navigate to threads and other profiles
                composable(
                        route = "profile/{authorId}",
                        arguments = listOf(navArgument("authorId") { type = NavType.StringType }),
                        enterTransition = {
                            // Override: Shared X-axis forward (no fade to prevent doubling)
                            slideIntoContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                    animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
                            )
                        },
                        exitTransition = {
                            // Override: No exit animation when going forward
                            null
                        },
                        popEnterTransition = {
                            // Override: No enter animation when coming back
                            null
                        },
                        popExitTransition = {
                            // Override: Shared X-axis back (no fade to prevent doubling)
                            slideOutOfContainer(
                                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                                    animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
                            )
                        }
                ) { backStackEntry ->
                    val authorId =
                            backStackEntry.arguments?.getString("authorId") ?: return@composable

                    val dashboardViewModel: DashboardViewModel = viewModel()
                    val dashboardState by dashboardViewModel.uiState.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val storageManager = remember(context) { RelayStorageManager(context) }
                    val cacheUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            storageManager.loadCacheRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    // Resolve author from profile cache (kind-0); use normalized hex for lookup so npub/hex both match
                    val profileCache = ProfileMetadataCache.getInstance()
                    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
                    var author by remember(authorId) {
                        mutableStateOf(profileCache.resolveAuthor(cacheKey))
                    }
                    LaunchedEffect(authorId) {
                        if (profileCache.getAuthor(cacheKey) == null && cacheUrls.isNotEmpty()) {
                            profileCache.requestProfiles(listOf(cacheKey), cacheUrls)
                        }
                    }
                    LaunchedEffect(authorId) {
                        profileCache.profileUpdated
                            .filter { it == cacheKey }
                            .collect { profileCache.getAuthor(cacheKey)?.let { a -> author = a } }
                    }

                    val authorNotes = dashboardState.notes.filter { it.author.id.lowercase() == author.id.lowercase() }
                    val profileListState = rememberLazyListState()
                    val followList = dashboardState.followList
                    val isFollowing = followList.isNotEmpty() && author.id.lowercase() in followList.map { it.lowercase() }.toSet()
                    var profileRelayUrlToShowInfo by remember { mutableStateOf<String?>(null) }
                    val zapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val zappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val zappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = authorNotes,
                            listState = profileListState,
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { noteId, amount ->
                                val n = authorNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            },
                            isZapInProgress = { id -> id in zapInProgressIds },
                            isZapped = { id -> id in zappedIds },
                            myZappedAmountForNote = { id -> zappedAmountByNoteId[id] },
                            overrideReplyCountForNote = { id -> replyCountByNoteId[id] },
                            countsForNote = { id -> noteCountsByNoteId[id] },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onProfileClick = { newAuthorId ->
                                navController.navigateToProfile(newAuthorId)
                            },
                            onRelayClick = { profileRelayUrlToShowInfo = it },
                            onNavigateTo = { /* Not needed with NavController */ },
                            accountNpub = currentAccount?.npub,
                            isFollowing = isFollowing,
                            onFollowClick = {
                                val targetHex = normalizeAuthorIdForCache(author.id)
                                val error = if (isFollowing) {
                                    accountStateViewModel.unfollowUser(targetHex)
                                } else {
                                    accountStateViewModel.followUser(targetHex)
                                }
                                if (error != null) {
                                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                } else {
                                    // Refresh follow list so the button updates
                                    currentAccount?.toHexKey()?.let { pubkey ->
                                        dashboardViewModel.loadFollowList(pubkey, cacheUrls, forceRefresh = true)
                                    }
                                }
                            },
                            onMessageClick = { /* TODO: Open DM */ }
                    )
                    profileRelayUrlToShowInfo?.let { url ->
                        RelayInfoDialog(
                            relayUrl = url,
                            onDismiss = { profileRelayUrlToShowInfo = null }
                        )
                    }
                }

                // User's own profile: resolve from ProfileMetadataCache so kind-0 shows when loaded
                composable("user_profile") {
                    val authState by accountStateViewModel.authState.collectAsState()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val navContext = LocalContext.current
                    val navStorageManager = remember(navContext) { RelayStorageManager(navContext) }
                    val currentUserPubkey = authState.userProfile?.pubkey ?: "guest"
                    val cacheUrls = remember(currentAccount) {
                        currentAccount?.toHexKey()?.let { pubkey ->
                            navStorageManager.loadCacheRelays(pubkey).map { it.url }
                        } ?: emptyList()
                    }

                    val fallbackAuthor = authState.userProfile?.let { userProfile ->
                        Author(
                            id = userProfile.pubkey,
                            username = userProfile.name ?: "user",
                            displayName = userProfile.displayName ?: userProfile.name ?: "User",
                            avatarUrl = userProfile.picture,
                            isVerified = false
                        )
                    } ?: Author(
                        id = "guest",
                        username = "guest",
                        displayName = "Guest User",
                        avatarUrl = null,
                        isVerified = false
                    )
                    val userCacheKey = remember(currentUserPubkey) {
                        if (currentUserPubkey == "guest") null else normalizeAuthorIdForCache(currentUserPubkey)
                    }
                    var author by remember(currentUserPubkey) {
                        mutableStateOf(
                            if (currentUserPubkey == "guest") fallbackAuthor
                            else ProfileMetadataCache.getInstance().resolveAuthor(userCacheKey!!)
                        )
                    }
                    LaunchedEffect(currentUserPubkey) {
                        if (userCacheKey != null && ProfileMetadataCache.getInstance().getAuthor(userCacheKey) == null && cacheUrls.isNotEmpty()) {
                            ProfileMetadataCache.getInstance().requestProfiles(listOf(userCacheKey), cacheUrls)
                        }
                    }
                    LaunchedEffect(currentUserPubkey) {
                        if (userCacheKey == null) return@LaunchedEffect
                        ProfileMetadataCache.getInstance().profileUpdated
                            .filter { it == userCacheKey }
                            .collect { ProfileMetadataCache.getInstance().getAuthor(userCacheKey)?.let { a -> author = a } }
                    }

                    val userNotes = emptyList<com.example.views.data.Note>()
                    val userProfileListState = rememberLazyListState()
                    var userProfileRelayUrlToShowInfo by remember { mutableStateOf<String?>(null) }
                    val userZapInProgressIds by accountStateViewModel.zapInProgressNoteIds.collectAsState()
                    val userZappedIds by accountStateViewModel.zappedNoteIds.collectAsState()
                    val userZappedAmountByNoteId by accountStateViewModel.zappedAmountByNoteId.collectAsState()

                    ProfileScreen(
                            author = author,
                            authorNotes = userNotes,
                            listState = userProfileListState,
                            onBackClick = { navController.popBackStack() },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onReact = { note, emoji ->
                                val error = accountStateViewModel.sendReaction(note, emoji)
                                if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            },
                            onCustomZapSend = { note, amount, zapType, msg ->
                                val err = accountStateViewModel.sendZap(note, amount, zapType, msg)
                                if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                            },
                            onZap = { noteId, amount ->
                                val n = userNotes.find { it.id == noteId }
                                if (n != null) {
                                    val err = accountStateViewModel.sendZap(n, amount, com.example.views.repository.ZapType.PUBLIC, "")
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            },
                            isZapInProgress = { id -> id in userZapInProgressIds },
                            isZapped = { id -> id in userZappedIds },
                            myZappedAmountForNote = { id -> userZappedAmountByNoteId[id] },
                            overrideReplyCountForNote = { id -> replyCountByNoteId[id] },
                            countsForNote = { id -> noteCountsByNoteId[id] },
                            onImageTap = { _, urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onOpenImageViewer = { urls, idx ->
                                appViewModel.openImageViewer(urls, idx)
                                navController.navigate("image_viewer")
                            },
                            onVideoClick = { urls, idx ->
                                appViewModel.openVideoViewer(urls, idx)
                                navController.navigate("video_viewer")
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            onRelayClick = { userProfileRelayUrlToShowInfo = it },
                            accountNpub = currentAccount?.npub,
                            onNavigateTo = { /* Not needed with NavController */ },
                            isFollowing = false,
                            onFollowClick = { },
                            onMessageClick = { }
                    )
                    userProfileRelayUrlToShowInfo?.let { url ->
                        RelayInfoDialog(
                            relayUrl = url,
                            onDismiss = { userProfileRelayUrlToShowInfo = null }
                        )
                    }
                }

                // Settings — feed and relay connections persist; no disconnect when visiting settings.
                composable("settings") {
                    SettingsScreen(
                            onBackClick = { navController.popBackStack() },
                            onNavigateTo = { screen ->
                                when (screen) {
                                    "appearance" -> navController.navigate("settings/appearance")
                                    "account_preferences" ->
                                            navController.navigate("settings/account_preferences")
                                    "about" -> navController.navigate("settings/about")
                                    "debug_follow_list" -> navController.navigate("debug_follow_list")
                                }
                            },
                            onBugReportClick = {
                                val intent =
                                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                                intent.data =
                                        android.net.Uri.parse(
                                                "https://github.com/TekkadanPlays/ribbit-android/issues"
                                        )
                                context.startActivity(intent)
                            }
                    )
                }

                // Settings sub-screens
                composable("settings/appearance") {
                    AppearanceSettingsScreen(onBackClick = { navController.popBackStack() })
                }

                composable("settings/account_preferences") {
                    AccountPreferencesScreen(
                            onBackClick = { navController.popBackStack() },
                            accountStateViewModel = accountStateViewModel
                    )
                }

                composable("settings/about") {
                    AboutScreen(onBackClick = { navController.popBackStack() })
                }

                composable("debug_follow_list") {
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
                    val pubkey = currentAccount?.toHexKey()
                    DebugFollowListScreen(
                        currentAccountPubkey = pubkey,
                        onBackClick = { navController.popBackStack() }
                    )
                }

                // Relay management
                // Relay Management - Can navigate back to dashboard
                composable("relays") {
                    RelayManagementScreen(
                            onBackClick = {
                                navController.navigate("dashboard") {
                                    popUpTo("dashboard") { inclusive = false }
                                }
                            },
                            relayRepository = relayRepository,
                            accountStateViewModel = accountStateViewModel,
                            topAppBarState = topAppBarState,
                            onOpenRelayLog = { relayUrl ->
                                val encoded = java.net.URLEncoder.encode(relayUrl, "UTF-8")
                                navController.navigate("relay_log/$encoded")
                            }
                    )
                }

                // Relay activity log screen
                composable(
                    "relay_log/{relayUrl}",
                    arguments = listOf(navArgument("relayUrl") { type = NavType.StringType })
                ) { backStackEntry ->
                    val relayUrl = java.net.URLDecoder.decode(
                        backStackEntry.arguments?.getString("relayUrl") ?: "",
                        "UTF-8"
                    )
                    RelayLogScreen(
                        relayUrl = relayUrl,
                        onBack = { navController.popBackStack() }
                    )
                }

                // Notifications - Can navigate to threads and profiles
                composable("notifications") {
                    NotificationsScreen(
                            onBackClick = {
                                navController.navigate("dashboard") {
                                    popUpTo("dashboard") { inclusive = false }
                                }
                            },
                            onNoteClick = { note ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(null)
                                navController.navigateToThread(note.id, 1)
                            },
                            onOpenThreadForRootId = { rootNoteId, replyKind, replyNoteId ->
                                // Navigate immediately so thread screen appears without blocking on fetch
                                navController.navigateToThread(rootNoteId, replyKind, replyNoteId)
                                coroutineScope.launch(Dispatchers.IO) {
                                    var note = NotesRepository.getInstance().getNoteFromCache(rootNoteId)
                                    if (note == null) {
                                        val pubkey = currentAccount?.toHexKey() ?: return@launch
                                        val categories = storageManager.loadCategories(pubkey)
                                        val relayUrls = categories.flatMap { it.relays }.map { it.url }.distinct()
                                        if (relayUrls.isNotEmpty()) {
                                            note = NotesRepository.getInstance().fetchNoteById(rootNoteId, relayUrls)
                                        }
                                    }
                                    if (note != null) {
                                        withContext(Dispatchers.Main.immediate) {
                                            appViewModel.updateSelectedNote(note)
                                            appViewModel.updateThreadRelayUrls(null)
                                        }
                                    }
                                }
                            },
                            onLike = { /* TODO: Handle like */},
                            onShare = { /* TODO: Handle share */},
                            onComment = { /* TODO: Handle comment */},
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            topAppBarState = topAppBarState
                    )
                }

                // Topics - Kind 11 topics with kind 1111 replies
                composable("topics") {
                    TopicsScreen(
                            onNavigateTo = { destination ->
                                navController.navigate(destination)
                            },
                            onThreadClick = { note, relayUrls ->
                                appViewModel.updateSelectedNote(note)
                                appViewModel.updateThreadRelayUrls(relayUrls)
                                navController.navigateToThread(note.id, 1111)
                            },
                            onProfileClick = { authorId ->
                                navController.navigateToProfile(authorId)
                            },
                            listState = topicsListState,
                            feedStateViewModel = feedStateViewModel,
                            appViewModel = appViewModel,
                            relayRepository = relayRepository,
                            accountStateViewModel = accountStateViewModel,
                            onLoginClick = {
                                val loginIntent = accountStateViewModel.loginWithAmber()
                                onAmberLogin(loginIntent)
                            },
                            initialTopAppBarState = topAppBarState,
                            onQrClick = { navController.navigate("user_qr") },
                            onNavigateToCreateTopic = { hashtag ->
                                val encoded = android.net.Uri.encode(hashtag ?: "")
                                navController.navigate("compose_topic?hashtag=$encoded")
                            }
                    )
                }

                composable("compose") {
                    ComposeNoteScreen(onBack = { navController.popBackStack() })
                }

                composable(
                    route = "compose_topic?hashtag={hashtag}",
                    arguments = listOf(
                        navArgument("hashtag") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val hashtagArg = backStackEntry.arguments?.getString("hashtag").orEmpty()
                    val initialHashtag = hashtagArg.takeIf { it.isNotEmpty() }
                    ComposeTopicScreen(
                        initialHashtag = initialHashtag,
                        onPublish = { title, content, tags ->
                            accountStateViewModel.publishTopic(title, content, tags)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = "reply_compose?rootId={rootId}&rootPubkey={rootPubkey}&parentId={parentId}&parentPubkey={parentPubkey}",
                    arguments = listOf(
                        navArgument("rootId") { type = NavType.StringType },
                        navArgument("rootPubkey") { type = NavType.StringType },
                        navArgument("parentId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("parentPubkey") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val appState by appViewModel.appState.collectAsState()
                    val rootId = backStackEntry.arguments?.getString("rootId") ?: return@composable
                    val rootPubkey = backStackEntry.arguments?.getString("rootPubkey") ?: return@composable
                    val parentId = backStackEntry.arguments?.getString("parentId").orEmpty().takeIf { it.isNotEmpty() }
                    val parentPubkey = backStackEntry.arguments?.getString("parentPubkey").orEmpty().takeIf { it.isNotEmpty() }
                    ReplyComposeScreen(
                        replyToNote = appState.replyToNote,
                        rootId = rootId,
                        rootPubkey = rootPubkey,
                        parentId = parentId,
                        parentPubkey = parentPubkey,
                        onPublish = { rId, rPk, pId, pPk, content ->
                            accountStateViewModel.publishThreadReply(rId, rPk, pId, pPk, content)
                        },
                        onBack = {
                            appViewModel.setReplyToNote(null)
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}

/** Navigation extension functions for type-safe navigation */
private fun NavController.navigateToProfile(authorId: String) {
    navigate("profile/$authorId")
}

/**
 * Push thread screen onto back stack. Back pops to the previous destination (dashboard, topics, or
 * another thread) so feed state and scroll position are preserved without bleed.
 */
private fun NavController.navigateToThread(noteId: String, replyKind: Int = 1, highlightReplyId: String? = null) {
    val suffix = highlightReplyId?.let { "&highlightReplyId=${android.net.Uri.encode(it)}" } ?: ""
    navigate("thread/$noteId?replyKind=$replyKind$suffix")
}
