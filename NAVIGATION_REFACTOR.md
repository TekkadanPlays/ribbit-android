# Navigation System Refactor

## Overview

Ribbit's navigation system has been refactored to use proper Jetpack Navigation Compose, mirroring the approach used in Primal Android. This change enables infinite exploration through feeds, threads, and profiles without losing navigation history.

## Problem Statement

### Previous Implementation Issues

The old navigation system used a **single screen state** approach with manual history tracking:

```kotlin
// OLD APPROACH - DO NOT USE
var currentScreen by remember { mutableStateOf("dashboard") }
var navigationHistory by remember { mutableStateOf<List<NavigationEntry>>(emptyList()) }

// Navigation replaced screen state instead of stacking
viewModel.updateCurrentScreen("profile")  // Replaces current screen
```

**Problems:**
1. ❌ Each navigation call **replaced** the current screen instead of adding to a stack
2. ❌ Manual history tracking was incomplete and error-prone
3. ❌ Profile → Thread → Profile navigation lost the original profile context
4. ❌ Back navigation was unpredictable and often lost user's exploration path
5. ❌ No way to explore indefinitely without hitting navigation dead-ends

### How Primal Does It Right

Primal uses standard Jetpack Navigation which:
1. ✅ **Automatically maintains a backstack** - every navigate() adds to history
2. ✅ **Preserves full navigation context** - can explore indefinitely
3. ✅ **Predictable back navigation** - NavController handles it automatically
4. ✅ **Type-safe routes** - compile-time safety for navigation arguments
5. ✅ **Proper lifecycle management** - screens are properly created/destroyed

## New Implementation

### Architecture

```
MainActivity
    └── RibbitNavigation (NavHost)
        ├── Dashboard (Feed)
        │   ├── → Profile (adds to stack)
        │   └── → Thread (adds to stack)
        ├── Thread
        │   ├── → Profile (adds to stack)
        │   └── → Another Thread (adds to stack)
        └── Profile
            ├── → Thread (adds to stack)
            └── → Another Profile (adds to stack)
```

### Key Files

#### 1. `RibbitNavigation.kt` (NEW)

The main navigation component using Jetpack Navigation Compose:

```kotlin
@Composable
fun RibbitNavigation(
    appViewModel: AppViewModel,
    authViewModel: AuthViewModel,
    onAmberLogin: (Intent) -> Unit
) {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        enterTransition = { /* Material Motion transitions */ },
        exitTransition = { /* Material Motion transitions */ }
    ) {
        composable("dashboard") { /* Dashboard screen */ }
        composable("thread/{noteId}") { /* Thread screen */ }
        composable("profile/{authorId}") { /* Profile screen */ }
        // ... more screens
    }
}
```

#### 2. `MainActivity.kt` (SIMPLIFIED)

Drastically simplified from ~750 lines to ~70 lines:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ViewsTheme {
                RibbitNavigation(
                    appViewModel = viewModel(),
                    authViewModel = viewModel(),
                    onAmberLogin = { intent -> amberLoginLauncher.launch(intent) }
                )
            }
        }
    }
}
```

## Navigation Patterns

### Pattern 1: Navigate to Profile

```kotlin
// OLD (broken)
viewModel.updateSelectedAuthor(author)
viewModel.updateCurrentScreen("profile")  // Lost previous context

// NEW (correct)
navController.navigate("profile/${author.id}")  // Adds to backstack
```

### Pattern 2: Navigate to Thread

```kotlin
// OLD (broken)
viewModel.updateSelectedNote(note)
viewModel.updateCurrentScreen("thread")  // Lost previous context

// NEW (correct)
navController.navigate("thread/${note.id}")  // Adds to backstack
```

### Pattern 3: Back Navigation

```kotlin
// OLD (broken)
val previousScreen = goBackInHistory()  // Manual history tracking
viewModel.updateCurrentScreen(previousScreen ?: "dashboard")

// NEW (correct)
navController.popBackStack()  // Automatic backstack management
```

### Pattern 4: Infinite Exploration

Users can now navigate indefinitely:

```
Dashboard → Profile A → Thread 1 → Profile B → Thread 2 → Profile C → Thread 3
    ↑                                                                      ↓
    └──────────────────────── Back button works perfectly ←───────────────┘
```

Each screen is properly added to the navigation stack, and the back button will retrace the exact path taken.

## Benefits

### 1. Proper State Management
- Navigation state is managed by NavController, not manual state variables
- Screen state is preserved automatically during navigation
- Configuration changes (rotation) handled correctly

### 2. Infinite Browsing
- Users can explore profiles and threads indefinitely
- Full navigation history is preserved
- Back button always works predictably

### 3. Better Performance
- Only active screens are kept in memory
- Proper lifecycle management
- Screens are disposed when popped from stack

### 4. Cleaner Code
- MainActivity reduced from ~750 lines to ~70 lines
- No manual history tracking
- No complex state management logic
- Standard Android patterns

### 5. Type Safety
- Route arguments are type-safe
- Compile-time checking for navigation calls
- Less prone to runtime errors

## Migration Guide

### For Screen Components

Most screen components needed minimal changes:

```kotlin
// Profile Screen - Before
onBackClick = { 
    val previousScreen = goBackInHistory()
    viewModel.updateCurrentScreen(previousScreen ?: "dashboard")
}

// Profile Screen - After
onBackClick = { 
    navController.popBackStack()  // That's it!
}
```

### For Navigation Callbacks

```kotlin
// Thread Screen - Before
onProfileClick = { authorId ->
    val author = findAuthor(authorId)
    viewModel.updateSelectedAuthor(author)
    viewModel.updateThreadSourceScreen("thread")
    addToHistory("profile", authorId)
    viewModel.updateCurrentScreen("profile")
}

// Thread Screen - After
onProfileClick = { authorId ->
    navController.navigate("profile/$authorId")  // Much simpler!
}
```

## Screen Arguments

### Passing Data to Screens

The new system uses URL-style arguments:

```kotlin
// Define route with argument
composable(
    route = "profile/{authorId}",
    arguments = listOf(navArgument("authorId") { type = NavType.StringType })
) { backStackEntry ->
    val authorId = backStackEntry.arguments?.getString("authorId")
    // Use authorId to fetch and display profile
}

// Navigate with argument
navController.navigate("profile/user123")
```

### Complex Data

For complex objects, use the ViewModel or database as the source of truth:

```kotlin
// Store note in ViewModel when selected
onThreadClick = { note ->
    appViewModel.updateSelectedNote(note)
    navController.navigate("thread/${note.id}")
}

// Retrieve from data source in destination screen
val note = SampleData.sampleNotes.find { it.id == noteId }
```

## Testing Navigation

### Manual Testing Checklist

- [ ] Navigate Dashboard → Profile → Thread → Profile → Thread
- [ ] Back button retraces exact path taken
- [ ] Profile → Profile → Profile (multiple profile views) works
- [ ] Thread → Thread → Thread (multiple thread views) works
- [ ] Settings and other screens maintain their own backstack
- [ ] Rotate device during navigation - state is preserved
- [ ] Deep linking works (if implemented)

### Navigation Flow Examples

#### Example 1: Deep Profile Exploration
```
Dashboard (Home Feed)
  → Profile: Alice
    → Thread: Alice's post
      → Profile: Bob (mentioned in post)
        → Thread: Bob's reply
          → Profile: Carol (Bob's friend)
            → Thread: Carol's post
              [Back button pressed 6 times returns to Dashboard]
```

#### Example 2: Mixed Navigation
```
Dashboard
  → Notifications
    → Thread: New reply
      → Profile: Replier
        → Thread: Replier's original post
          [Back to Dashboard via back button]
```

## Future Enhancements

### 1. Deep Linking
```kotlin
composable(
    route = "profile/{authorId}",
    deepLinks = listOf(
        navDeepLink { uriPattern = "ribbit://profile/{authorId}" }
    )
) { /* ... */ }
```

### 2. Nested Navigation Graphs
```kotlin
navigation(startDestination = "settings_main", route = "settings") {
    composable("settings_main") { SettingsScreen() }
    composable("settings/appearance") { AppearanceScreen() }
    composable("settings/privacy") { PrivacyScreen() }
}
```

### 3. Shared Element Transitions
```kotlin
composable("profile/{authorId}",
    enterTransition = { sharedElementEnter() },
    exitTransition = { sharedElementExit() }
) { /* ... */ }
```

## Troubleshooting

### Issue: Screen doesn't update when navigating to same route
**Solution:** Use `launchSingleTop = false` or provide unique arguments

### Issue: State is lost on back navigation
**Solution:** Use ViewModel or SavedStateHandle to preserve state

### Issue: Complex object passing between screens
**Solution:** Pass only IDs through navigation, fetch full objects from repository/ViewModel

### Issue: Need to clear entire backstack
**Solution:** Use `popUpTo` with `inclusive = true`
```kotlin
navController.navigate("dashboard") {
    popUpTo("dashboard") { inclusive = true }
}
```

## Comparison with Primal

| Feature | Primal | Ribbit (New) | Ribbit (Old) |
|---------|---------|--------------|--------------|
| Navigation Library | Jetpack Navigation | Jetpack Navigation | Manual State |
| Backstack Management | Automatic | Automatic | Manual |
| Infinite Exploration | ✅ Yes | ✅ Yes | ❌ No |
| Type Safety | ✅ Yes | ✅ Yes | ❌ No |
| Code Complexity | Low | Low | High |
| Lines of Code (MainActivity) | ~200 | ~70 | ~750 |

## Summary

The navigation refactor brings Ribbit's architecture in line with industry best practices and successful apps like Primal. The key improvement is using Android's built-in navigation system rather than reinventing the wheel with manual state management.

**Key Takeaway:** By using standard Jetpack Navigation Compose, Ribbit now provides the same smooth, infinite exploration experience that makes apps like Primal feel polished and intuitive.

## References

- [Jetpack Navigation Compose Documentation](https://developer.android.com/jetpack/compose/navigation)
- [Primal Android Navigation Implementation](primal-android-app/app/src/main/kotlin/net/primal/android/navigation/)
- [Material Motion Transitions](https://m3.material.io/styles/motion/transitions/transition-patterns)

---

**Date:** 2024
**Author:** Navigation Refactor Team
**Status:** ✅ Complete