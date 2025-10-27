package com.example.views.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.views.data.SampleData
import com.example.views.repository.RelayRepository
import com.example.views.ui.screens.AboutScreen
import com.example.views.ui.screens.AccountPreferencesScreen
import com.example.views.ui.screens.AppearanceSettingsScreen
import com.example.views.ui.screens.DashboardScreen
import com.example.views.ui.screens.ModernThreadViewScreen
import com.example.views.ui.screens.NotificationsScreen
import com.example.views.ui.screens.ProfileScreen
import com.example.views.ui.screens.RelayManagementScreen
import com.example.views.ui.screens.SettingsScreen
import com.example.views.ui.screens.createSampleCommentThreads
import com.example.views.viewmodel.AppViewModel
import com.example.views.viewmodel.AuthViewModel
import com.example.views.viewmodel.rememberThreadStateHolder

/**
 * Main navigation composable for Ribbit app using Jetpack Navigation.
 * This provides proper backstack management like Primal, allowing infinite
 * exploration through feeds, threads, and profiles without losing history.
 */
@Composable
fun RibbitNavigation(
    appViewModel: AppViewModel,
    accountStateViewModel: com.example.views.viewmodel.AccountStateViewModel,
    onAmberLogin: (android.content.Intent) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val relayRepository = remember { RelayRepository(context) }

    // Thread state holder - persists scroll positions and comment states per thread
    val threadStateHolder = rememberThreadStateHolder()

    // Track scroll states for different screens
    val feedListState = rememberLazyListState()

    NavHost(
        navController = navController,
        startDestination = "dashboard",
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
            ) + fadeIn(animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
            ) + fadeOut(animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = MaterialMotion.EasingStandardDecelerate)
            ) + fadeIn(animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(300, easing = MaterialMotion.EasingStandardAccelerate)
            ) + fadeOut(animationSpec = tween(300))
        }
    ) {
        // Dashboard - Home feed
        composable("dashboard") {
            val appState by appViewModel.appState.collectAsState()

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
                        "wallet" -> navController.navigate("wallet")
                        "user_profile" -> navController.navigate("user_profile")
                    }
                },
                onThreadClick = { note ->
                    navController.navigateToThread(note.id)
                    appViewModel.updateSelectedNote(note)
                },
                onScrollToTop = { /* Handled in DashboardScreen */ },
                accountStateViewModel = accountStateViewModel,
                relayRepository = relayRepository,
                onLoginClick = {
                    val loginIntent = accountStateViewModel.loginWithAmber()
                    onAmberLogin(loginIntent)
                },
                onTopAppBarStateChange = { /* TopAppBar state if needed */ },
                listState = feedListState
            )
        }

        // Thread view - Can navigate to profiles and other threads
        composable(
            route = "thread/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.StringType })
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId") ?: return@composable
            val note = SampleData.sampleNotes.find { it.id == noteId }

            if (note != null) {
                val sampleComments = createSampleCommentThreads()

                // Restore scroll state for this specific thread
                val savedScrollState = threadStateHolder.getScrollState(noteId)
                val threadListState = rememberLazyListState(
                    initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
                    initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
                )

                // Get comment states for this specific thread
                val commentStates = threadStateHolder.getCommentStates(noteId)
                var expandedControlsCommentId by remember {
                    mutableStateOf(threadStateHolder.getExpandedControls(noteId))
                }

                // Save scroll state when leaving the screen
                DisposableEffect(noteId) {
                    onDispose {
                        threadStateHolder.saveScrollState(noteId, threadListState)
                        threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
                    }
                }

                ModernThreadViewScreen(
                    note = note,
                    comments = sampleComments,
                    listState = threadListState,
                    commentStates = commentStates,
                    expandedControlsCommentId = expandedControlsCommentId,
                    onExpandedControlsChange = { commentId ->
                        expandedControlsCommentId = if (expandedControlsCommentId == commentId) null else commentId
                    },
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onLike = { /* TODO: Handle like */ },
                    onShare = { /* TODO: Handle share */ },
                    onComment = { /* TODO: Handle comment */ },
                    onProfileClick = { authorId ->
                        // Navigate to profile - adds to backstack
                        navController.navigateToProfile(authorId)
                    },
                    onCommentLike = { /* TODO: Handle comment like */ },
                    onCommentReply = { /* TODO: Handle comment reply */ }
                )
            }
        }

        // Profile view - Can navigate to threads and other profiles
        composable(
            route = "profile/{authorId}",
            arguments = listOf(navArgument("authorId") { type = NavType.StringType })
        ) { backStackEntry ->
            val authorId = backStackEntry.arguments?.getString("authorId") ?: return@composable
            val author = SampleData.sampleNotes.find { it.author.id == authorId }?.author

            if (author != null) {
                val authorNotes = SampleData.sampleNotes.filter { it.author.id == author.id }
                val profileListState = rememberLazyListState()

                ProfileScreen(
                    author = author,
                    authorNotes = authorNotes,
                    listState = profileListState,
                    onBackClick = {
                        navController.popBackStack()
                    },
                    onNoteClick = { note ->
                        // Navigate to thread - adds to backstack
                        navController.navigateToThread(note.id)
                        appViewModel.updateSelectedNote(note)
                    },
                    onProfileClick = { newAuthorId ->
                        // Navigate to another profile - adds to backstack
                        // This allows infinite profile browsing with full history
                        navController.navigateToProfile(newAuthorId)
                    },
                    onNavigateTo = { /* Not needed with NavController */ }
                )
            }
        }

        // User's own profile
        composable("user_profile") {
            val authState by accountStateViewModel.authState.collectAsState()
            val currentUser = authState.userProfile?.let { userProfile ->
                com.example.views.data.Author(
                    id = userProfile.pubkey,
                    username = userProfile.name ?: "user",
                    displayName = userProfile.displayName ?: userProfile.name ?: "User",
                    avatarUrl = userProfile.picture,
                    isVerified = false
                )
            } ?: com.example.views.data.Author(
                id = "guest",
                username = "guest",
                displayName = "Guest User",
                avatarUrl = null,
                isVerified = false
            )

            val userNotes = emptyList<com.example.views.data.Note>()
            val userProfileListState = rememberLazyListState()

            ProfileScreen(
                author = currentUser,
                authorNotes = userNotes,
                listState = userProfileListState,
                onBackClick = {
                    navController.popBackStack()
                },
                onNoteClick = { note ->
                    navController.navigateToThread(note.id)
                    appViewModel.updateSelectedNote(note)
                },
                onProfileClick = { authorId ->
                    navController.navigateToProfile(authorId)
                },
                onNavigateTo = { /* Not needed with NavController */ }
            )
        }

        // Settings
        composable("settings") {
            SettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNavigateTo = { screen ->
                    when (screen) {
                        "appearance" -> navController.navigate("settings/appearance")
                        "account_preferences" -> navController.navigate("settings/account_preferences")
                        "about" -> navController.navigate("settings/about")
                    }
                },
                onBugReportClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    intent.data = android.net.Uri.parse("https://github.com/TekkadanPlays/ribbit-android/issues")
                    context.startActivity(intent)
                }
            )
        }

        // Settings sub-screens
        composable("settings/appearance") {
            AppearanceSettingsScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        composable("settings/account_preferences") {
            AccountPreferencesScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                accountStateViewModel = accountStateViewModel
            )
        }

        composable("settings/about") {
            AboutScreen(
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }

        // Relay management
        composable("relays") {
            RelayManagementScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                relayRepository = relayRepository
            )
        }

        // Notifications - Can navigate to threads and profiles
        composable("notifications") {
            NotificationsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNoteClick = { note ->
                    navController.navigateToThread(note.id)
                    appViewModel.updateSelectedNote(note)
                },
                onLike = { /* TODO: Handle like */ },
                onShare = { /* TODO: Handle share */ },
                onComment = { /* TODO: Handle comment */ },
                onProfileClick = { authorId ->
                    navController.navigateToProfile(authorId)
                }
            )
        }

        // Messages - Can navigate to threads and profiles
        composable("messages") {
            NotificationsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNoteClick = { note ->
                    navController.navigateToThread(note.id)
                    appViewModel.updateSelectedNote(note)
                },
                onLike = { /* TODO: Handle like */ },
                onShare = { /* TODO: Handle share */ },
                onComment = { /* TODO: Handle comment */ },
                onProfileClick = { authorId ->
                    navController.navigateToProfile(authorId)
                }
            )
        }

        // Wallet - Can navigate to threads and profiles
        composable("wallet") {
            NotificationsScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onNoteClick = { note ->
                    navController.navigateToThread(note.id)
                    appViewModel.updateSelectedNote(note)
                },
                onLike = { /* TODO: Handle like */ },
                onShare = { /* TODO: Handle share */ },
                onComment = { /* TODO: Handle comment */ },
                onProfileClick = { authorId ->
                    navController.navigateToProfile(authorId)
                }
            )
        }
    }
}

/**
 * Navigation extension functions for type-safe navigation
 */
private fun NavController.navigateToProfile(authorId: String) {
    navigate("profile/$authorId")
}

private fun NavController.navigateToThread(noteId: String) {
    navigate("thread/$noteId")
}
