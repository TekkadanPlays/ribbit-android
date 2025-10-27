package com.example.views.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.offset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveHeader(
    title: String = "ribbit",
    isSearchMode: Boolean = false,
    showBackArrow: Boolean = false,
    searchQuery: TextFieldValue = TextFieldValue(""),
    onSearchQueryChange: (TextFieldValue) -> Unit = {},
    onMenuClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    onMoreOptionClick: (String) -> Unit = {},
    onBackClick: () -> Unit = {},
    onClearSearch: () -> Unit = {},
    onLoginClick: (() -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    currentFeedView: String = "Home",
    onFeedViewChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Feed dropdown state
    var feedDropdownExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (feedDropdownExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "caret_rotation"
    )

    // Show keyboard when search mode is activated
    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
            if (isSearchMode) {
                // Search mode - just a simple text field
                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = {
                        Text(
                            "Search notes...",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.text.isNotEmpty()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp) // Add padding to match title spacing
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            } else {
                // Normal mode - show title with or without dropdown based on context
                if (showBackArrow) {
                    // Thread view - just show title without dropdown
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Dashboard view - show title with dropdown
                    Box {
                        Row(
                            modifier = Modifier
                                .clickable { feedDropdownExpanded = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Feed view selector",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(rotationAngle)
                            )
                        }

                        // Feed view dropdown menu
                        DropdownMenu(
                            expanded = feedDropdownExpanded,
                            onDismissRequest = { feedDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Home") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Home,
                                        contentDescription = null,
                                        tint = if (currentFeedView == "Home") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    onFeedViewChange("Home")
                                    feedDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Popular") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.TrendingUp,
                                        contentDescription = null,
                                        tint = if (currentFeedView == "Popular") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    onFeedViewChange("Popular")
                                    feedDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Latest") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Schedule,
                                        contentDescription = null,
                                        tint = if (currentFeedView == "Latest") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = {
                                    onFeedViewChange("Latest")
                                    feedDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            if (isSearchMode || showBackArrow) {
                // Search mode or back arrow mode - show back button
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // Normal mode - show hamburger menu
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (isSearchMode) {
                // Search mode - no actions, just the clear button in the text field
            } else {
                // Normal mode - show search, filter, and profile avatar
                Row {
                    // Search button
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }


                    // Profile avatar or login button
                    if (isGuest) {
                        // Guest mode - show login icon
                        IconButton(onClick = onLoginClick ?: {}) {
                            Icon(
                                imageVector = Icons.Outlined.Login,
                                contentDescription = "Log In",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        // Signed in - show user avatar with dropdown menu
                        var showMenu by remember { mutableStateOf(false) }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = userDisplayName?.take(1)?.uppercase() ?: "U",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = DpOffset(x = (-8).dp, y = 8.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Profile") },
                                    onClick = {
                                        showMenu = false
                                        onProfileClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Person,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Accounts") },
                                    onClick = {
                                        showMenu = false
                                        onAccountsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.AccountCircle,
                                            contentDescription = null
                                        )
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        showMenu = false
                                        onSettingsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Settings,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        scrollBehavior = scrollBehavior,
        modifier = modifier
    )
}
