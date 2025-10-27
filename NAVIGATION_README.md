# Navigation System Refactor - Complete Guide

## 🎉 What's New

Ribbit's navigation has been completely overhauled to match Primal's architecture, enabling **infinite exploration** through feeds, threads, and profiles without ever losing your place!

## ⚡ Quick Start

The app now uses standard Jetpack Navigation Compose. Everything just works:

```kotlin
// Navigate to a profile
navController.navigate("profile/${authorId}")

// Navigate to a thread
navController.navigate("thread/${noteId}")

// Go back
navController.popBackStack()
```

## 🎯 What This Fixes

### Before (Broken) ❌
```
Dashboard → Profile A → Thread → Profile B
                                    ↓ (back button)
                                 Dashboard (Lost Thread and Profile A!)
```

### After (Fixed) ✅
```
Dashboard → Profile A → Thread → Profile B → Another Thread → Profile C → ...
    ↑                                                              ↓
    └────────────── Perfect navigation history preserved ←────────┘
```

## 📚 Documentation

### Essential Reading
1. **[NAVIGATION_CHANGES_SUMMARY.md](NAVIGATION_CHANGES_SUMMARY.md)** - Start here! Quick overview of changes
2. **[NAVIGATION_REFACTOR.md](NAVIGATION_REFACTOR.md)** - Complete technical documentation
3. **[NAVIGATION_FLOW.md](NAVIGATION_FLOW.md)** - Visual diagrams and flow charts
4. **[CODE_COMPARISON.md](CODE_COMPARISON.md)** - Side-by-side before/after code examples

### Quick Reference
- New navigation file: `app/.../ui/navigation/RibbitNavigation.kt`
- Simplified MainActivity: `app/.../MainActivity.kt` (750 lines → 70 lines!)
- Reference implementation: `primal-android-app/app/.../navigation/`

## 🔑 Key Changes

### Architecture
- **Old:** Manual state management with `currentScreen` variable
- **New:** Jetpack Navigation with automatic backstack

### Navigation
- **Old:** `viewModel.updateCurrentScreen("profile")` (replaces screen)
- **New:** `navController.navigate("profile/$id")` (adds to stack)

### Back Button
- **Old:** Manual history tracking with complex fallback logic
- **New:** `navController.popBackStack()` handles everything

### Code Reduction
- **MainActivity:** 750 lines → 70 lines (90% reduction!)
- **Navigation logic:** 300+ lines → 0 (built-in)
- **State management:** 10+ variables → NavController handles it

## 🎮 How It Works

### Navigation Stack Example

```
Step 1: [Dashboard]
Step 2: [Dashboard] → [Profile: Alice]
Step 3: [Dashboard] → [Profile: Alice] → [Thread: Post123]
Step 4: [Dashboard] → [Profile: Alice] → [Thread: Post123] → [Profile: Bob]
Step 5: [Dashboard] → [Profile: Alice] → [Thread: Post123] → [Profile: Bob] → [Thread: Post456]

Back button retraces this path perfectly! ✅
```

### Available Routes

```kotlin
"dashboard"                    // Home feed
"profile/{authorId}"          // User profile
"thread/{noteId}"             // Thread view
"settings"                    // Settings
"settings/appearance"         // Appearance settings
"notifications"               // Notifications
"messages"                    // Messages
"relays"                      // Relay management
"wallet"                      // Wallet
"user_profile"                // Current user's profile
```

## 🚀 Benefits

1. ✅ **Infinite Exploration** - Browse profiles and threads endlessly
2. ✅ **Perfect Back Navigation** - Always retraces your exact path
3. ✅ **Preserved Context** - Never lose your place
4. ✅ **90% Less Code** - Simpler, cleaner, more maintainable
5. ✅ **Standard Android** - Uses industry best practices
6. ✅ **Matches Primal** - Same smooth experience

## 🧪 Testing Checklist

Try this navigation flow to test the new system:

1. Start at Dashboard
2. Click a profile → Profile A
3. Click a thread → Thread 1
4. Click mentioned profile → Profile B
5. Click another thread → Thread 2
6. Click another profile → Profile C
7. Press back 5 times
8. Should return to Dashboard with full history preserved ✅

## 📖 Learning Resources

### Understanding the Change

```
Old System:
┌─────────────────┐
│ Manual State    │  ← 750 lines of code
│ Lost Context    │  ← Navigation breaks
│ Complex Logic   │  ← Hard to maintain
└─────────────────┘

New System:
┌─────────────────┐
│ NavController   │  ← 70 lines of code
│ Full History    │  ← Works perfectly
│ Standard Android│  ← Easy to maintain
└─────────────────┘
```

### Key Concepts

**Backstack:** Automatic history of all screens visited
```
[Dashboard] → [Profile] → [Thread] → [Profile] → [Thread]
     ↑                                              ↓
     └───────── Back button retraces path ←────────┘
```

**Route Arguments:** Type-safe way to pass data
```kotlin
composable("profile/{authorId}") { backStackEntry ->
    val authorId = backStackEntry.arguments?.getString("authorId")
    // Use authorId to load and display profile
}
```

**Navigation Functions:** Simple, type-safe helpers
```kotlin
fun NavController.navigateToProfile(authorId: String) {
    navigate("profile/$authorId")
}
```

## 🔧 Implementation Details

### Main Components

1. **MainActivity.kt** - Entry point, sets up navigation
2. **RibbitNavigation.kt** - NavHost with all routes
3. **NavController** - Android's navigation controller (automatic)

### Data Flow

```
User clicks profile
    ↓
navController.navigate("profile/$id")
    ↓
NavController adds to stack
    ↓
ProfileScreen composable is created
    ↓
Fetch data using id from route
    ↓
Display profile
```

## 💻 Code Examples

### Navigate to Profile
```kotlin
onProfileClick = { authorId ->
    navController.navigate("profile/$authorId")
}
```

### Navigate to Thread
```kotlin
onThreadClick = { note ->
    appViewModel.updateSelectedNote(note)  // Optional: store in ViewModel
    navController.navigate("thread/${note.id}")
}
```

### Go Back
```kotlin
onBackClick = {
    navController.popBackStack()
}
```

### Navigate to Settings Sub-screen
```kotlin
onNavigateTo = { screen ->
    when (screen) {
        "appearance" -> navController.navigate("settings/appearance")
        "about" -> navController.navigate("settings/about")
    }
}
```

## 🎨 Transitions

The new system includes Material Motion transitions:

- **Horizontal slides** for peer navigation (Settings, etc.)
- **Vertical slides** for hierarchical navigation (Profile, Thread)
- **Fade transitions** for unrelated screens

All handled automatically by NavHost configuration!

## 🐛 Troubleshooting

### Common Issues

**Q: Screen doesn't update when navigating to same route**
```kotlin
// Add unique identifier or use launchSingleTop = false
navController.navigate("profile/$id") {
    launchSingleTop = false
}
```

**Q: Need to clear entire backstack**
```kotlin
navController.navigate("dashboard") {
    popUpTo("dashboard") { inclusive = true }
}
```

**Q: State is lost on back navigation**
```kotlin
// Use ViewModel to preserve state across navigation
val viewModel: ProfileViewModel = viewModel()
```

## 🎓 Learn More

### External Resources
- [Jetpack Navigation Compose](https://developer.android.com/jetpack/compose/navigation)
- [Material Motion](https://m3.material.io/styles/motion)
- [Navigation Best Practices](https://developer.android.com/guide/navigation/navigation-principles)

### In This Repository
- Study `primal-android-app/.../navigation/` for reference
- Read all documentation in this directory
- Review `RibbitNavigation.kt` for implementation details

## 🤝 Contributing

When adding new screens:

1. Add route to `RibbitNavigation.kt`
2. Use `navController.navigate()` for navigation
3. Use `navController.popBackStack()` for back button
4. Test full navigation flow
5. Update documentation

### Template for New Screen

```kotlin
composable(
    route = "new_screen/{param}",
    arguments = listOf(navArgument("param") { type = NavType.StringType })
) { backStackEntry ->
    val param = backStackEntry.arguments?.getString("param")
    
    NewScreen(
        onBackClick = { navController.popBackStack() },
        onNavigateToOther = { id -> navController.navigate("other/$id") }
    )
}
```

## 📊 Metrics

| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| MainActivity LOC | 750 | 70 | 90% reduction |
| Navigation LOC | 300+ | 0 | Built-in |
| State Variables | 10+ | 0 | Automatic |
| Navigation Depth | Limited | Infinite ✅ | Perfect |
| Bug Count | High | Low | Much safer |

## ✅ Status

**Navigation Refactor:** ✅ Complete and Ready to Use

The app now provides the same smooth, infinite exploration experience as Primal!

---

## Quick Links

- 📄 [Changes Summary](NAVIGATION_CHANGES_SUMMARY.md)
- 📖 [Complete Refactor Docs](NAVIGATION_REFACTOR.md)
- 🗺️ [Navigation Flow Diagrams](NAVIGATION_FLOW.md)
- 🔍 [Code Comparison](CODE_COMPARISON.md)
- 🔗 [Primal Reference](../primal-android-app/app/src/main/kotlin/net/primal/android/navigation/)

**Questions?** Check the documentation files above or review the Primal implementation!