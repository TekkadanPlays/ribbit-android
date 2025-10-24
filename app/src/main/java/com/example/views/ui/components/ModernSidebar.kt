package com.example.views.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.views.viewmodel.RelayManagementViewModel
import com.example.views.repository.RelayRepository
import com.example.views.data.RelayConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernSidebar(
    drawerState: DrawerState,
    onItemClick: (String) -> Unit = {},
    authState: com.example.views.data.AuthState? = null,
    relays: List<com.example.views.data.UserRelay> = emptyList(),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp)
            ) {
                SidebarContent(
                    onItemClick = { itemId ->
                        onItemClick(itemId)
                        scope.launch { drawerState.close() }
                    },
                    authState = authState,
                    relays = relays
                )
            }
        },
        modifier = modifier
    ) {
        content()
    }
}

@Composable
private fun SidebarContent(
    onItemClick: (String) -> Unit,
    authState: com.example.views.data.AuthState? = null,
    relays: List<com.example.views.data.UserRelay> = emptyList(),
    modifier: Modifier = Modifier
) {
    val isSignedIn = authState?.isAuthenticated == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // User profile section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary // Use theme color
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = authState?.userProfile?.displayNameOrName?.take(2)?.uppercase() ?: "GU",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = authState?.userProfile?.displayNameOrName ?: "Guest User",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Text(
                        text = if (authState?.userProfile?.name != null) "@${authState.userProfile.name}" else "Guest",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Relay information section
        RelayInfoSection(
            onItemClick = onItemClick,
            relays = relays
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}


private data class ModernSidebarMenuItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val badge: String? = null,
    val hasArrow: Boolean = false
)

private fun getModernMenuItems(isSignedIn: Boolean): List<ModernSidebarMenuItem> {
    return if (isSignedIn) {
        // Signed in: show Logout and Settings only
        listOf(
            ModernSidebarMenuItem("logout", "Logout", Icons.Outlined.Logout),
            ModernSidebarMenuItem("settings", "Settings", Icons.Outlined.Settings)
        )
    } else {
        // Guest: show Login and Settings only
        listOf(
            ModernSidebarMenuItem("login", "Log In", Icons.Outlined.Login),
            ModernSidebarMenuItem("settings", "Settings", Icons.Outlined.Settings)
        )
    }
}

@Composable
private fun RelayInfoSection(
    onItemClick: (String) -> Unit,
    relays: List<com.example.views.data.UserRelay>,
    modifier: Modifier = Modifier
) {
    // Section header
    Text(
        text = "Relays",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    
    if (relays.isEmpty()) {
        // Show empty state with link to manage relays
        NavigationDrawerItem(
            label = { Text("No relays added") },
            selected = false,
            icon = { 
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Add Relays"
                ) 
            },
            onClick = { onItemClick("relays") },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    } else {
        // Show relay list
        relays.forEach { relay ->
            RelayListItem(
                relay = relay,
                onClick = { onItemClick("relays") }
            )
        }
    }
}

@Composable
private fun RelayListItem(
    relay: com.example.views.data.UserRelay,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationDrawerItem(
        label = { 
            Column {
                Text(
                    text = relay.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = relay.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        },
        selected = false,
        icon = { 
            Icon(
                imageVector = Icons.Outlined.Router,
                contentDescription = "Relay",
                tint = if (relay.isOnline) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) 
        },
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@Preview(showBackground = true)
@Composable
fun ModernSidebarPreview() {
    MaterialTheme {
        ModernSidebar(
            drawerState = rememberDrawerState(initialValue = DrawerValue.Open),
            onItemClick = {},
            relays = listOf(
                com.example.views.data.UserRelay(
                    url = "wss://relay.damus.io",
                    read = true,
                    write = true,
                    isOnline = true
                ),
                com.example.views.data.UserRelay(
                    url = "wss://nos.lol",
                    read = true,
                    write = true,
                    isOnline = false
                )
            )
        ) {
            // Empty content for preview
        }
    }
}
