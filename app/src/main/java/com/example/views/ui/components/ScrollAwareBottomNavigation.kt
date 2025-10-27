package com.example.views.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
fun ScrollAwareBottomNavigationBar(
    currentDestination: String,
    onDestinationClick: (String) -> Unit,
    isVisible: Boolean = true,
    notificationCount: Int = 0,
    topAppBarState: TopAppBarState,
    modifier: Modifier = Modifier
) {
    val localDensity = LocalDensity.current
    
    // Calculate bottom bar height based on scroll state
    var bottomBarInitialHeight by remember { mutableStateOf(0.dp) }
    val bottomBarMeasureHeightModifier = Modifier.onGloballyPositioned { layoutCoordinates ->
        bottomBarInitialHeight = with(localDensity) { layoutCoordinates.size.height.toDp() }
    }
    
    val bottomBarRealHeight by remember(topAppBarState) {
        derivedStateOf {
            with(localDensity) {
                ((1 - topAppBarState.collapsedFraction) * bottomBarInitialHeight.roundToPx()).toDp()
            }
        }
    }
    
    val isBottomBarVisible by remember {
        derivedStateOf {
            bottomBarInitialHeight.isZeroOrNavigationBarFullHeight() || bottomBarRealHeight > 0.dp
        }
    }
    
    val focusModeOn by remember(topAppBarState) {
        derivedStateOf { topAppBarState.collapsedFraction > 0.5f }
    }
    
    AnimatedVisibility(
        visible = isBottomBarVisible && isVisible,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        NavigationBar(
            modifier = modifier
                .height(72.dp)
                .then(
                    if (bottomBarInitialHeight.isZeroOrNavigationBarFullHeight()) {
                        bottomBarMeasureHeightModifier
                    } else {
                        Modifier
                    }
                )
                .offset {
                    IntOffset(
                        x = 0.dp.roundToPx(),
                        y = if (bottomBarInitialHeight > 0.dp) {
                            bottomBarInitialHeight - bottomBarRealHeight
                        } else {
                            0.dp
                        }.roundToPx(),
                    )
                }
                .graphicsLayer {
                    // Fade out the navigation bar as it hides
                    alpha = 1f - (topAppBarState.collapsedFraction * 0.3f).coerceIn(0f, 1f)
                }
        ) {
            BottomNavDestinations.entries.forEach { destination ->
                NavigationBarItem(
                    icon = {
                        if (destination.route == "notifications" && notificationCount > 0) {
                            Box {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                    tint = if (currentDestination == destination.route) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Badge(
                                    modifier = Modifier.offset(x = 8.dp, y = (-4).dp),
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ) {
                                    Text(
                                        text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint = if (currentDestination == destination.route) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    },
                    selected = currentDestination == destination.route,
                    onClick = { onDestinationClick(destination.route) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent // Remove the oval background
                    )
                )
            }
        }
    }
}

private fun Dp.isZeroOrNavigationBarFullHeight(): Boolean =
    this == 0.dp || (this >= (72.dp - 1.dp) && this <= (72.dp + 1.dp))
