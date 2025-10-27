# Integration Guide: Multi-Account System

## Quick Start

This guide shows exactly what to change to integrate the new multi-account system.

## Step 1: Update MainActivity

**File:** `app/src/main/java/com/example/views/MainActivity.kt`

Replace the entire file with:

```kotlin
package com.example.views

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.views.ui.navigation.RibbitNavigation
import com.example.views.ui.theme.ViewsTheme
import com.example.views.viewmodel.AccountStateViewModel
import com.example.views.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {

    private val amberLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onAmberLoginResult?.invoke(result.resultCode, result.data)
    }

    private var onAmberLoginResult: ((Int, Intent?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            ViewsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val appViewModel: AppViewModel = viewModel()
                    val accountStateViewModel: AccountStateViewModel = viewModel()

                    onAmberLoginResult = { resultCode, data ->
                        accountStateViewModel.handleAmberLoginResult(resultCode, data)
                    }

                    RibbitNavigation(
                        appViewModel = appViewModel,
                        accountStateViewModel = accountStateViewModel,
                        onAmberLogin = { intent -> amberLoginLauncher.launch(intent) }
                    )
                }
            }
        }
    }
}
```

## Step 2: Update RibbitNavigation

**File:** `app/src/main/java/com/example/views/ui/navigation/RibbitNavigation.kt`

Change the function signature from:

```kotlin
@Composable
fun RibbitNavigation(
    appViewModel: AppViewModel,
    authViewModel: AuthViewModel,  // OLD
    onAmberLogin: (Intent) -> Unit
)
```

To:

```kotlin
@Composable
fun RibbitNavigation(
    appViewModel: AppViewModel,
    accountStateViewModel: AccountStateViewModel,  // NEW
    onAmberLogin: (Intent) -> Unit
)
```

Update the Dashboard composable:

```kotlin
composable("dashboard") {
    val appState by appViewModel.appState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()  // NEW

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
        accountStateViewModel = accountStateViewModel,  // NEW
        onLoginClick = {
            val loginIntent = accountStateViewModel.loginWithAmber()  // NEW
            onAmberLogin(loginIntent)
        },
        onTopAppBarStateChange = { /* TopAppBar state if needed */ },
        listState = feedListState
    )
}
```

## Step 3: Update DashboardScreen

**File:** `app/src/main/java/com/example/views/ui/screens/DashboardScreen.kt`

Change the function signature:

```kotlin
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
    accountStateViewModel: AccountStateViewModel = viewModel(),  // NEW - Changed from authViewModel
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()  // NEW
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
```

Add account switcher state:

```kotlin
// Add after drawer state
var showAccountSwitcher by remember { mutableStateOf(false) }
```

Update the avatar click handler in AdaptiveHeader:

```kotlin
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
    onAvatarClick = {
        // NEW - Show account switcher
        if (!authState.isGuest) {
            showAccountSwitcher = true
        }
    },
    isGuest = authState.isGuest,
    userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
    userAvatarUrl = authState.userProfile?.picture,
    scrollBehavior = scrollBehavior,
    currentFeedView = currentFeedView,
    onFeedViewChange = { newFeedView -> currentFeedView = newFeedView }
)
```

Add the account switcher at the end of DashboardScreen (before the last closing brace):

```kotlin
// Account switcher bottom sheet
if (showAccountSwitcher) {
    AccountSwitchBottomSheet(
        accountStateViewModel = accountStateViewModel,
        onDismiss = { showAccountSwitcher = false },
        onAddAccount = {
            showAccountSwitcher = false
            onLoginClick?.invoke()
        }
    )
}
```

## Step 4: Update ModernSidebar

**File:** `app/src/main/java/com/example/views/ui/components/ModernSidebar.kt`

The sidebar already supports the new system. Just make sure the `onItemClick` handler in DashboardScreen includes:

```kotlin
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
                // NEW - Logout current account
                accountStateViewModel.logoutCurrentAccount()
            }
            "settings" -> {
                onNavigateTo("settings")
            }
            else -> viewModel.onSidebarItemClick(itemId)
        }
    },
    authState = authState,
    modifier = modifier
)
```

## Step 5: Remove Old AuthViewModel (Optional)

You can now delete or deprecate the old `AuthViewModel.kt` file since `AccountStateViewModel` replaces it completely.

**File to deprecate:** `app/src/main/java/com/example/views/viewmodel/AuthViewModel.kt`

## Testing Checklist

After integration, test:

1. **First Login**
   - [ ] App starts in guest mode
   - [ ] Click login icon
   - [ ] Amber opens and authenticates
   - [ ] Return to app shows avatar

2. **Account Switcher**
   - [ ] Click avatar → Bottom sheet opens
   - [ ] Shows current account with checkmark
   - [ ] Click "Add Another Account"
   - [ ] Authenticate with different npub
   - [ ] Both accounts now in list

3. **Switching**
   - [ ] Click different account in list
   - [ ] UI updates instantly
   - [ ] Avatar shows new account initial

4. **Persistence**
   - [ ] Close app
   - [ ] Reopen app
   - [ ] Still signed in
   - [ ] All accounts still available

5. **Logout**
   - [ ] Open account switcher
   - [ ] Click logout on account
   - [ ] Confirm in dialog
   - [ ] Account removed from list

## Summary of Changes

### New Files Created
- `AccountStateViewModel.kt` - Multi-account manager
- `AccountInfo.kt` - Account data class
- `AccountSwitchBottomSheet.kt` - Account switching UI

### Files Modified
- `MainActivity.kt` - Uses AccountStateViewModel instead of AuthViewModel
- `RibbitNavigation.kt` - Passes AccountStateViewModel to screens
- `DashboardScreen.kt` - Shows account switcher, handles multi-account
- `ModernSidebar.kt` - Already supports the new system

### Key Differences

| Old System | New System |
|------------|------------|
| Single AuthViewModel | AccountStateViewModel |
| One account at a time | Multiple accounts |
| Manual state in ViewModel | Persisted in SharedPreferences |
| No account switching | Bottom sheet switcher |
| Lost on app restart | Persists across restarts |

## Troubleshooting

**Problem:** UI doesn't update after login
**Solution:** Make sure you're passing the SAME AccountStateViewModel instance to all screens

**Problem:** Accounts don't persist
**Solution:** Check SharedPreferences permissions and JSON serialization

**Problem:** Avatar click doesn't work
**Solution:** Verify `onAvatarClick` is wired to show account switcher

**Problem:** Can't switch accounts
**Solution:** Ensure AccountStateViewModel is obtained with `viewModel()` at Activity level

## Next Steps

After integration, you can:
1. Test thoroughly with multiple accounts
2. Add profile picture loading
3. Implement per-account settings
4. Add account sync features
5. Enhance the account switcher UI

The new system provides a solid foundation for advanced multi-account features!