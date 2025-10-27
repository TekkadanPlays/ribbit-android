# Avatar and Sidebar Improvements

## Overview

Updated the avatar and sidebar UI to provide better visual feedback for authentication state and simplified navigation options.

## Changes Made

### 1. Avatar Display in Header

**Location:** `app/src/main/java/com/example/views/ui/components/AdaptiveHeader.kt`

#### Guest Mode (Not Signed In)
- Shows a **Login icon** from Material Icons (`Icons.Outlined.Login`)
- Clicking the icon triggers the login flow
- No user avatar displayed

#### Signed In Mode
- Shows **user avatar** with first initial in a colored circle
- Displays the first letter of user's display name
- Clicking the avatar navigates to the user's own profile page
- Future: Can be updated to load actual profile picture from URL

```kotlin
// Guest Mode
IconButton(onClick = onLoginClick ?: {}) {
    Icon(
        imageVector = Icons.Outlined.Login,
        contentDescription = "Log In",
        tint = MaterialTheme.colorScheme.onSurface
    )
}

// Signed In Mode
IconButton(onClick = onAvatarClick) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = userDisplayName?.take(1)?.uppercase() ?: "U",
            style = MaterialTheme.typography.labelMedium
        )
    }
}
```

### 2. Simplified Sidebar

**Location:** `app/src/main/java/com/example/views/ui/components/ModernSidebar.kt`

#### Removed Items
- ❌ Home (accessible via bottom nav)
- ❌ My Profile (accessible via avatar click)
- ❌ Relays (accessible via settings)
- ❌ Bookmarks (accessible via bottom nav)
- ❌ Lists (not yet implemented)

#### Guest Mode Sidebar
Only shows:
- ✅ **Log In** - Triggers authentication flow
- ✅ **Settings** - Access app settings

#### Signed In Mode Sidebar
Only shows:
- ✅ **Logout** - Sign out of account
- ✅ **Settings** - Access app settings

```kotlin
private fun getModernMenuItems(isSignedIn: Boolean): List<ModernSidebarMenuItem> {
    return if (isSignedIn) {
        listOf(
            ModernSidebarMenuItem("logout", "Logout", Icons.Outlined.Logout),
            ModernSidebarMenuItem("settings", "Settings", Icons.Outlined.Settings)
        )
    } else {
        listOf(
            ModernSidebarMenuItem("login", "Log In", Icons.Outlined.Login),
            ModernSidebarMenuItem("settings", "Settings", Icons.Outlined.Settings)
        )
    }
}
```

### 3. Dashboard Integration

**Location:** `app/src/main/java/com/example/views/ui/screens/DashboardScreen.kt`

#### Updates
- Passes `authState` to `AdaptiveHeader`
- Provides `userDisplayName` and `userAvatarUrl` from auth state
- Handles `onAvatarClick` to navigate to user profile when signed in
- Handles logout action in sidebar (navigates to settings for now)

```kotlin
AdaptiveHeader(
    // ... other params
    onLoginClick = onLoginClick,
    onAvatarClick = {
        if (!authState.isGuest) {
            onNavigateTo("user_profile")
        }
    },
    isGuest = authState.isGuest,
    userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
    userAvatarUrl = authState.userProfile?.picture,
    // ... other params
)
```

## User Experience

### Before
- Avatar always showed generic "U" icon regardless of login state
- Clicking avatar did nothing
- Sidebar had many redundant navigation options
- Confusing for users to understand auth state

### After
- **Clear visual indicator** of authentication state
- **Login icon** for guests makes it obvious how to sign in
- **Avatar** for signed-in users shows personalization
- **Click avatar** to view your own profile (intuitive)
- **Minimal sidebar** with only essential actions
- **Cleaner UI** with less clutter

## Navigation Flow

### Guest User
```
Dashboard (Guest)
    ├── Click Login Icon → Amber Authentication
    └── Click Hamburger → Sidebar
        ├── Log In → Amber Authentication
        └── Settings → Settings Screen
```

### Signed In User
```
Dashboard (Signed In)
    ├── Click Avatar → User Profile (Own)
    └── Click Hamburger → Sidebar
        ├── Logout → Sign out
        └── Settings → Settings Screen
```

## Benefits

1. **Clearer Authentication State** - Users immediately see if they're logged in
2. **Intuitive Actions** - Login icon for guests, avatar for users
3. **Direct Profile Access** - Click avatar to see your own profile
4. **Simplified Navigation** - Only essential items in sidebar
5. **Less Clutter** - Removed redundant navigation options
6. **Consistent Patterns** - Matches common app UX patterns

## Future Enhancements

- [ ] Load actual profile pictures from URLs (avatar images)
- [ ] Add profile picture upload functionality
- [ ] Implement proper logout flow with confirmation dialog
- [ ] Add loading state for avatar image
- [ ] Cache avatar images for performance
- [ ] Add fallback for network failures

## Testing

To verify the implementation:

1. **Guest Mode**
   - Open app as guest
   - Verify login icon is shown in header (not avatar)
   - Click login icon → should trigger login
   - Open sidebar → should only show "Log In" and "Settings"

2. **Signed In Mode**
   - Log in with Amber
   - Verify avatar shows user's initial
   - Click avatar → should navigate to user profile
   - Open sidebar → should only show "Logout" and "Settings"

3. **Navigation**
   - Click logout in sidebar → handled appropriately
   - All other navigation options work from bottom nav bar
   - Profile accessible via avatar click

## Files Modified

1. `ui/components/AdaptiveHeader.kt` - Avatar display logic
2. `ui/components/ModernSidebar.kt` - Simplified menu items
3. `ui/screens/DashboardScreen.kt` - Integration with auth state

## Summary

The avatar and sidebar improvements provide a cleaner, more intuitive user experience that clearly communicates authentication state and simplifies navigation. Users can now easily access their profile via the avatar, and the sidebar focuses on essential authentication and settings actions only.