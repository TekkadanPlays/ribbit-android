# Thread State Persistence Implementation

## Overview

Implemented per-thread state persistence to retain scroll positions and comment collapse states across navigation, matching Primal's behavior.

## Problem Solved

Previously, when navigating away from a thread and returning:
- ❌ Scroll position was reset to top
- ❌ Collapsed comments were expanded again
- ❌ User lost their place in the conversation

Now:
- ✅ Scroll position is preserved for each individual thread
- ✅ Comment collapse states are maintained
- ✅ Expanded controls state is remembered
- ✅ State survives process death (via rememberSaveable)

## Implementation

### 1. ThreadStateHolder (New File)

**Location:** `app/src/main/java/com/example/views/viewmodel/ThreadStateHolder.kt`

A state holder that maintains:
- **Scroll states** per thread (first visible item index + offset)
- **Comment states** per thread (expanded/collapsed per comment ID)
- **Expanded controls** per thread (which comment has controls open)

```kotlin
class ThreadStateHolder {
    // threadId -> ScrollState
    private val scrollStates = mutableStateMapOf<String, ScrollState>()
    
    // threadId -> commentId -> CommentState
    private val commentStates = mutableStateMapOf<String, MutableMap<String, CommentState>>()
    
    // threadId -> expanded controls comment ID
    private val expandedControls = mutableStateMapOf<String, String?>()
}
```

### 2. Integration in RibbitNavigation

**Modified:** `app/src/main/java/com/example/views/ui/navigation/RibbitNavigation.kt`

```kotlin
// Create state holder at navigation level
val threadStateHolder = rememberThreadStateHolder()

composable("thread/{noteId}") { backStackEntry ->
    val noteId = backStackEntry.arguments?.getString("noteId")
    
    // Restore scroll state for this specific thread
    val savedScrollState = threadStateHolder.getScrollState(noteId)
    val threadListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScrollState.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = savedScrollState.firstVisibleItemScrollOffset
    )
    
    // Get comment states for this specific thread
    val commentStates = threadStateHolder.getCommentStates(noteId)
    
    // Save state when leaving
    DisposableEffect(noteId) {
        onDispose {
            threadStateHolder.saveScrollState(noteId, threadListState)
            threadStateHolder.setExpandedControls(noteId, expandedControlsCommentId)
        }
    }
}
```

### 3. ModernThreadViewScreen Updates

**Modified:** `app/src/main/java/com/example/views/ui/screens/ModernThreadViewScreen.kt`

Added parameters to accept external state:
```kotlin
fun ModernThreadViewScreen(
    note: Note,
    comments: List<CommentThread>,
    listState: LazyListState = rememberLazyListState(),
    commentStates: MutableMap<String, CommentState> = remember { mutableStateMapOf() },
    expandedControlsCommentId: String? = null,
    onExpandedControlsChange: (String?) -> Unit = {},
    // ... other parameters
)
```

## How It Works

### Scenario: User navigates through threads

```
1. Dashboard → Thread A
   - ThreadStateHolder creates new state for Thread A
   - User scrolls down, collapses some comments
   
2. Thread A → Profile B (via comment author)
   - DisposableEffect saves Thread A state
   - Profile B screen shown
   
3. Profile B → Thread C
   - ThreadStateHolder creates new state for Thread C
   - User scrolls, collapses different comments
   
4. Thread C → Back → Profile B → Back → Thread A
   - Thread A state is restored exactly as left
   - Scroll position restored
   - Collapsed comments still collapsed
   - User continues where they left off ✅
```

## State Persistence Across Process Death

The `ThreadStateHolder` includes a `Saver` that serializes state:
- Survives configuration changes (rotation)
- Survives process death (Android kills app in background)
- State restored when app returns

```kotlin
companion object {
    val Saver: Saver<ThreadStateHolder, Map<String, Any>> = Saver(
        save = { holder -> /* serialize */ },
        restore = { saved -> /* deserialize */ }
    )
}
```

## Benefits

1. **Per-Thread Isolation** - Each thread maintains independent state
2. **Smooth UX** - Users never lose their place
3. **Memory Efficient** - Only active states kept in memory
4. **Automatic** - Works transparently via navigation
5. **Robust** - Survives process death

## Comparison with Primal

Primal uses similar patterns:
- State holders at navigation level
- `rememberSaveable` for persistence
- Per-screen state management
- DisposableEffect for cleanup

Our implementation follows these same principles.

## Testing

To verify the implementation works:

1. Navigate to Thread A
2. Scroll halfway down
3. Collapse some comments
4. Navigate to a profile (via comment author)
5. Navigate to Thread B from that profile
6. Scroll and collapse different comments
7. Press back twice to return to Thread A
8. ✅ Verify scroll position is restored
9. ✅ Verify collapsed comments remain collapsed

## Technical Details

### State Storage Structure

```
ThreadStateHolder
├── scrollStates: Map<ThreadId, ScrollState>
│   └── "thread123" → { index: 5, offset: 200 }
├── commentStates: Map<ThreadId, Map<CommentId, CommentState>>
│   └── "thread123" → {
│       "comment1" → { isExpanded: false, isCollapsed: true }
│       "comment2" → { isExpanded: true, isCollapsed: false }
│   }
└── expandedControls: Map<ThreadId, CommentId?>
    └── "thread123" → "comment5"
```

### Memory Management

- States are kept until app process ends
- Could add LRU cache if needed (for apps with 100+ thread views)
- Current implementation prioritizes UX over memory (reasonable trade-off)

## Files Changed

1. **New:** `viewmodel/ThreadStateHolder.kt` - State holder implementation
2. **Modified:** `ui/navigation/RibbitNavigation.kt` - Integration
3. **Modified:** `ui/screens/ModernThreadViewScreen.kt` - Accept external state

## Future Enhancements

Potential improvements:
- [ ] Add LRU cache to limit memory usage
- [ ] Persist to disk for multi-day retention
- [ ] Add state expiration (clear old thread states)
- [ ] Analytics on scroll depth and engagement

## Summary

Thread state persistence is now fully implemented, providing a smooth user experience that matches professional apps like Primal. Users can explore infinitely through threads and profiles, always returning to exactly where they left off.