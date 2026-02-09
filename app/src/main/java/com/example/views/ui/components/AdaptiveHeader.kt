package com.example.views.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.views.viewmodel.HomeSortOrder
import com.example.views.viewmodel.TopicsSortOrder

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
    onQrCodeClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    currentFeedView: String = "Home",
    onFeedViewChange: (String) -> Unit = {},
    // Home feed filter: All vs Following (slide-out under title); Edit for future custom feeds
    isFollowingFilter: Boolean = true,
    onFollowingFilterChange: ((Boolean) -> Unit)? = null,
    onEditFeedClick: (() -> Unit)? = null,
    // Home sort: Latest (default) / Popular
    homeSortOrder: HomeSortOrder = HomeSortOrder.Latest,
    onHomeSortOrderChange: ((HomeSortOrder) -> Unit)? = null,
    // Topics feed: All (default) / Following + Latest/Popular (when non-null)
    isTopicsFollowingFilter: Boolean = false,
    onTopicsFollowingFilterChange: ((Boolean) -> Unit)? = null,
    topicsSortOrder: TopicsSortOrder = TopicsSortOrder.Latest,
    onTopicsSortOrderChange: ((TopicsSortOrder) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Feed title dropdown / slide-out state (filter options)
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

    // Auto-collapse filter dropdown when header begins to hide from scroll
    val collapsedFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    LaunchedEffect(collapsedFraction) {
        if (collapsedFraction > 0.05f && feedDropdownExpanded) {
            feedDropdownExpanded = false
        }
    }

    val hasFilterSlideOut = !showBackArrow && !isSearchMode &&
        (onFollowingFilterChange != null || onTopicsFollowingFilterChange != null)

    Column(modifier = modifier) {
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
                            .padding(start = 8.dp)
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
                    // Title: just text, or clickable title + arrow when filter slide-out is below
                    if (showBackArrow) {
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
                        Row(
                            modifier = Modifier
                                .clickable { feedDropdownExpanded = !feedDropdownExpanded }
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
                                contentDescription = "Feed filter options",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(rotationAngle)
                            )
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
                                contentDescription = "Login with Amber",
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
                                    if (!userAvatarUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = userAvatarUrl,
                                            contentDescription = "Profile",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = userDisplayName?.take(1)?.uppercase() ?: "U",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
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
            scrollBehavior = scrollBehavior
        )
        // Filter row slides out from full header (below bar), in line with rest of header — not trapped in title
        if (hasFilterSlideOut) {
            AnimatedVisibility(
                visible = feedDropdownExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // All / Following (Home or Topics)
                        if (onFollowingFilterChange != null) {
                            FilterChip(
                                selected = !isFollowingFilter,
                                onClick = { onFollowingFilterChange(false) },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = isFollowingFilter,
                                onClick = { onFollowingFilterChange(true) },
                                label = { Text("Following") }
                            )
                        }
                        if (onTopicsFollowingFilterChange != null) {
                            FilterChip(
                                selected = !isTopicsFollowingFilter,
                                onClick = { onTopicsFollowingFilterChange(false) },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = isTopicsFollowingFilter,
                                onClick = { onTopicsFollowingFilterChange(true) },
                                label = { Text("Following") }
                            )
                        }
                        // Home: Latest | Popular
                        if (onHomeSortOrderChange != null) {
                            FilterChip(
                                selected = homeSortOrder == HomeSortOrder.Latest,
                                onClick = { onHomeSortOrderChange(HomeSortOrder.Latest) },
                                label = { Text("Latest") }
                            )
                            FilterChip(
                                selected = homeSortOrder == HomeSortOrder.Popular,
                                onClick = { onHomeSortOrderChange(HomeSortOrder.Popular) },
                                label = { Text("Popular") }
                            )
                        }
                        // Topics: Latest | Popular (Favorites removed — unused)
                        if (onTopicsSortOrderChange != null) {
                            FilterChip(
                                selected = topicsSortOrder == TopicsSortOrder.Latest,
                                onClick = { onTopicsSortOrderChange(TopicsSortOrder.Latest) },
                                label = { Text("Latest") }
                            )
                            FilterChip(
                                selected = topicsSortOrder == TopicsSortOrder.Popular,
                                onClick = { onTopicsSortOrderChange(TopicsSortOrder.Popular) },
                                label = { Text("Popular") }
                            )
                        }
                        if (onEditFeedClick != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = onEditFeedClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = "Edit feed / custom filters (coming soon)",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
