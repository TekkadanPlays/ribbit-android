package com.example.views.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.views.data.RelayCategory
import com.example.views.data.UserRelay
import com.example.views.viewmodel.FeedState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSidebar(
    drawerState: DrawerState,
    relayCategories: List<RelayCategory> = emptyList(),
    feedState: FeedState = FeedState(),
    selectedDisplayName: String = "All Relays",
    onItemClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit = {},
    onQrClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    relayCategories = relayCategories,
                    expandedCategories = feedState.expandedCategories,
                    selectedDisplayName = selectedDisplayName,
                    onItemClick = onItemClick,
                    onToggleCategory = onToggleCategory,
                    onQrClick = onQrClick,
                    onClose = {
                        scope.launch {
                            drawerState.close()
                        }
                    }
                )
            }
        },
        modifier = modifier
    ) {
        content()
    }
}

@Composable
private fun DrawerContent(
    relayCategories: List<RelayCategory>,
    expandedCategories: Set<String>,
    selectedDisplayName: String,
    onItemClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onQrClick: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
            .padding(bottom = 80.dp) // Clear of bottom nav bar
    ) {
        // Header with selected relay/category and QR button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Relay Categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Current: $selectedDisplayName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(
                onClick = {
                    onQrClick()
                    onClose()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCode2,
                    contentDescription = "My QR code"
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Global feed button — use theme primary (dull sage), not bright green
        val isGlobalSelected = selectedDisplayName == "Global"
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clickable {
                    onItemClick("global")
                    onClose()
                },
            shape = RoundedCornerShape(12.dp),
            color = if (isGlobalSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Global feed",
                    modifier = Modifier.size(24.dp),
                    tint = if (isGlobalSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = "Global",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isGlobalSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Relay Categories Section
        if (relayCategories.isNotEmpty()) {
            RelayCategoriesSection(
                categories = relayCategories,
                expandedCategories = expandedCategories,
                onCategoryClick = { categoryId ->
                    onItemClick("relay_category:$categoryId")
                    onClose()
                },
                onRelayClick = { relayUrl ->
                    onItemClick("relay:$relayUrl")
                    onClose()
                },
                onToggleCategory = onToggleCategory
            )
        } else {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No relay categories yet.\nCreate one in Relay Management.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun RelayCategoriesSection(
    categories: List<RelayCategory>,
    expandedCategories: Set<String>,
    onCategoryClick: (String) -> Unit,
    onRelayClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        categories.forEach { category ->
            val isExpanded = expandedCategories.contains(category.id)

            // Category header - click to load all relays, icon to expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Load all relays in this category
                        onCategoryClick(category.id)
                    }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${category.name} (${category.relays.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = {
                        onToggleCategory(category.id)
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded relay list
            if (isExpanded) {
                if (category.relays.isEmpty()) {
                    Text(
                        text = "No relays in this category",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 8.dp)
                    )
                } else {
                    category.relays.forEach { relay ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRelayClick(relay.url) },
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 48.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Circle,
                                    contentDescription = null,
                                    modifier = Modifier.size(6.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = relay.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
