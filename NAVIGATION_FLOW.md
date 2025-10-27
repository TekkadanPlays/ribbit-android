# Navigation Flow Diagram

## 🗺️ Complete Navigation Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          MainActivity                                │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                    RibbitNavigation                            │ │
│  │                  (NavHost + NavController)                     │ │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  │ manages
                                  ▼
        ┌────────────────────────────────────────────────┐
        │         Navigation Backstack (Automatic)        │
        │  ┌──────────────────────────────────────────┐  │
        │  │  [Screen 5] ← Current Screen             │  │
        │  │  [Screen 4]                              │  │
        │  │  [Screen 3]                              │  │
        │  │  [Screen 2]                              │  │
        │  │  [Screen 1] ← Start Destination          │  │
        │  └──────────────────────────────────────────┘  │
        └────────────────────────────────────────────────┘
```

## 📱 Screen Navigation Map

```
                    ┌─────────────┐
                    │  Dashboard  │ (Start)
                    │  (Feed)     │
                    └──────┬──────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
         ▼                 ▼                 ▼
    ┌─────────┐      ┌─────────┐      ┌──────────┐
    │ Profile │      │ Thread  │      │ Settings │
    │  View   │      │  View   │      │          │
    └────┬────┘      └────┬────┘      └────┬─────┘
         │                │                 │
         │                │                 ▼
         │                │           ┌──────────┐
         │                │           │Appearance│
         │                │           └──────────┘
         │                │
         └────────┬───────┘
                  │
         ┌────────┴────────┐
         │                 │
         ▼                 ▼
    ┌─────────┐      ┌─────────┐
    │ Profile │      │ Thread  │
    │  View   │      │  View   │
    └────┬────┘      └────┬────┘
         │                │
         └────────┬───────┘
                  │
                  ▼
             (Infinite Loop)
```

## 🔄 Example: Deep Profile Exploration

### Navigation Path
```
Step 1: Dashboard (Feed)
   │
   │ User clicks on Alice's profile
   ▼
Step 2: Profile: Alice
   │
   │ User clicks on Alice's post
   ▼
Step 3: Thread: Alice's post
   │
   │ User clicks on Bob (mentioned in comments)
   ▼
Step 4: Profile: Bob
   │
   │ User clicks on Bob's post
   ▼
Step 5: Thread: Bob's post
   │
   │ User clicks on Carol (Bob's friend)
   ▼
Step 6: Profile: Carol
   │
   │ User clicks on Carol's thread
   ▼
Step 7: Thread: Carol's post
```

### Backstack at Each Step

```
Step 1:  [Dashboard]

Step 2:  [Dashboard] → [Profile:Alice]

Step 3:  [Dashboard] → [Profile:Alice] → [Thread:Post1]

Step 4:  [Dashboard] → [Profile:Alice] → [Thread:Post1] → [Profile:Bob]

Step 5:  [Dashboard] → [Profile:Alice] → [Thread:Post1] → [Profile:Bob] → [Thread:Post2]

Step 6:  [Dashboard] → [Profile:Alice] → [Thread:Post1] → [Profile:Bob] → [Thread:Post2] → [Profile:Carol]

Step 7:  [Dashboard] → [Profile:Alice] → [Thread:Post1] → [Profile:Bob] → [Thread:Post2] → [Profile:Carol] → [Thread:Post3]
```

### Back Button Navigation
```
Current: Thread:Post3
   │ ← Back pressed
   ▼
Profile:Carol
   │ ← Back pressed
   ▼
Thread:Post2
   │ ← Back pressed
   ▼
Profile:Bob
   │ ← Back pressed
   ▼
Thread:Post1
   │ ← Back pressed
   ▼
Profile:Alice
   │ ← Back pressed
   ▼
Dashboard (Home) ✅
```

## 🎯 Navigation Routes

### Route Definitions

```kotlin
// Primary Routes
"dashboard"                    → Home feed
"profile/{authorId}"          → User profile
"thread/{noteId}"             → Thread/conversation view

// Secondary Routes
"settings"                    → Settings screen
"settings/appearance"         → Appearance settings
"settings/about"              → About screen
"relays"                      → Relay management
"notifications"               → Notifications
"messages"                    → Messages/DMs
"wallet"                      → Wallet
"user_profile"                → Current user's profile
```

### Navigation Functions

```kotlin
// Type-safe navigation helpers
navController.navigate("profile/${authorId}")
navController.navigate("thread/${noteId}")
navController.popBackStack()
```

## 🔀 Old vs New Architecture

### Old System (Broken)
```
┌─────────────────────────┐
│      MainActivity       │
│  ┌───────────────────┐  │
│  │  AppState         │  │
│  │  ┌─────────────┐  │  │
│  │  │currentScreen│  │  │  ← Single state variable
│  │  └─────────────┘  │  │
│  │  ┌─────────────┐  │  │
│  │  │   history   │  │  │  ← Manual tracking
│  │  │   (manual)  │  │  │
│  │  └─────────────┘  │  │
│  └───────────────────┘  │
│                         │
│  AnimatedContent {      │
│    when (currentScreen) │  ← Screen replacement
│      "dashboard" → ...  │
│      "profile" → ...    │
│      "thread" → ...     │
│  }                      │
└─────────────────────────┘

Problems:
❌ Screen replacement instead of stacking
❌ Lost navigation context
❌ Manual history prone to bugs
❌ 750 lines of complex logic
```

### New System (Fixed)
```
┌─────────────────────────────┐
│       MainActivity          │
│  ┌───────────────────────┐  │
│  │  RibbitNavigation     │  │
│  │  ┌─────────────────┐  │  │
│  │  │  NavController  │  │  │  ← Android manages state
│  │  │   (Automatic)   │  │  │
│  │  └─────────────────┘  │  │
│  │                       │  │
│  │  NavHost {            │  │
│  │    composable(...)    │  │  ← Proper screen stacking
│  │    composable(...)    │  │
│  │  }                    │  │
│  └───────────────────────┘  │
└─────────────────────────────┘

Benefits:
✅ Automatic backstack management
✅ Full navigation context preserved
✅ Standard Android patterns
✅ 70 lines of clean code
```

## 🎨 Transition Animations

### Horizontal Slide (Settings, Navigation)
```
[Current Screen] ←→ [New Screen]
     Slides out         Slides in
```

### Vertical Slide (Thread, Profile)
```
[Current Screen]
       ↕
[New Screen]
 (Drill down)
```

### Implementation
```kotlin
NavHost(
    enterTransition = { slideIn + fadeIn },
    exitTransition = { slideOut + fadeOut },
    popEnterTransition = { slideIn (reversed) },
    popExitTransition = { slideOut (reversed) }
)
```

## 📊 State Management Flow

```
┌──────────────┐         ┌──────────────┐
│   Screen A   │  nav()  │   Screen B   │
│              │ ──────→ │              │
│ [View Model] │         │ [View Model] │
│      ↓       │         │      ↓       │
│   [State]    │         │   [State]    │
└──────────────┘         └──────────────┘
       │                        │
       │                        │
       └────────┬───────────────┘
                │
                ▼
       ┌─────────────────┐
       │  NavController  │
       │   (Backstack)   │
       └─────────────────┘
```

## 🚀 Benefits Visualization

### Navigation Depth Comparison

```
Old System:
Dashboard → Profile → Lost Context ❌
            (Can't go deeper without losing history)

New System:
Dashboard → Profile → Thread → Profile → Thread → Profile → ... ∞ ✅
         (Infinite exploration with full history)
```

### Code Complexity

```
Old MainActivity:        New MainActivity:
├── 750 lines           ├── 70 lines
├── Manual state        ├── NavController
├── Manual history      ├── Automatic
├── Complex logic       ├── Simple
└── Error-prone         └── Reliable
```

## 🎯 Key Takeaway

```
┌─────────────────────────────────────────────┐
│  Before: Manual State = Broken Navigation   │
│  After:  NavController = Perfect Navigation │
│                                             │
│  Result: Same experience as Primal app! 🎉  │
└─────────────────────────────────────────────┘
```

## 📚 Related Documentation

- `NAVIGATION_REFACTOR.md` - Complete technical details
- `NAVIGATION_CHANGES_SUMMARY.md` - Quick reference guide
- `primal-android-app/.../navigation/` - Reference implementation