package com.example.views.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.views.data.AccountInfo
import com.example.views.viewmodel.AccountStateViewModel

/**
 * Bottom sheet for switching between multiple Nostr accounts.
 * Based on Amethyst's account switching UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitchBottomSheet(
    accountStateViewModel: AccountStateViewModel,
    onDismiss: () -> Unit = {},
    onAddAccount: () -> Unit = {}
) {
    val savedAccounts by accountStateViewModel.savedAccounts.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val scrollState = rememberScrollState()

    var showLogoutDialog by remember { mutableStateOf<AccountInfo?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Switch Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            HorizontalDivider()

            // Account list
            if (savedAccounts.isEmpty()) {
                // No accounts saved
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No accounts saved",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Sign in with Amber to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                savedAccounts.forEach { account ->
                    AccountListItem(
                        account = account,
                        isCurrentAccount = account.npub == currentAccount?.npub,
                        onAccountClick = {
                            accountStateViewModel.switchToAccount(account)
                            onDismiss()
                        },
                        onLogoutClick = {
                            showLogoutDialog = account
                        }
                    )
                }
            }

            HorizontalDivider()

            // Add account button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddAccount() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add account",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add Another Account",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // Logout confirmation dialog
    showLogoutDialog?.let { account ->
        AlertDialog(
            onDismissRequest = { showLogoutDialog = null },
            title = { Text("Log Out") },
            text = {
                Text("Are you sure you want to log out of ${account.getDisplayNameOrNpub()}?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountStateViewModel.logoutAccount(account)
                        showLogoutDialog = null
                    }
                ) {
                    Text("Log Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountListItem(
    account: AccountInfo,
    isCurrentAccount: Boolean,
    onAccountClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAccountClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = account.displayName?.take(1)?.uppercase()
                    ?: account.toShortNpub().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Account info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = account.displayName ?: account.toShortNpub(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = account.toShortNpub(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (account.isExternalSigner) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• Amber",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Active indicator
        if (isCurrentAccount) {
            Icon(
                imageVector = Icons.Default.RadioButtonChecked,
                contentDescription = "Active account",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Logout button
        IconButton(onClick = onLogoutClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Log out",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
