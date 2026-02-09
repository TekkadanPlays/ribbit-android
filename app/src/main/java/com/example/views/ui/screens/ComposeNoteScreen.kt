package com.example.views.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.example.views.data.UserRelay
import com.example.views.viewmodel.AccountStateViewModel

/**
 * Note composition screen. User types content and taps Publish to open relay selection.
 * Outbox relays are selected by default; after confirming, the kind-1 note is signed and sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeNoteScreen(
    onBack: () -> Unit,
    accountStateViewModel: AccountStateViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    var content by remember { mutableStateOf("") }
    var showRelayPicker by remember { mutableStateOf(false) }
    val outboxRelays = remember(currentAccount?.npub) {
        accountStateViewModel.getOutboxRelaysForPublish()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New note") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .padding(vertical = 16.dp),
                placeholder = { Text("What's on your mind?") },
                minLines = 6,
                maxLines = 20
            )
            Button(
                onClick = { showRelayPicker = true },
                modifier = Modifier.padding(top = 8.dp),
                enabled = content.isNotBlank()
            ) {
                Text("Publish")
            }
        }
    }

    if (showRelayPicker) {
        RelayPickerDialog(
            relays = outboxRelays,
            initialSelectedUrls = outboxRelays.map { it.url }.toSet(),
            onDismiss = { showRelayPicker = false },
            onConfirm = { selectedUrls ->
                showRelayPicker = false
                val err = accountStateViewModel.publishKind1(content, selectedUrls)
                if (err != null) {
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                } else {
                    onBack()
                }
            }
        )
    }
}

@Composable
private fun RelayPickerDialog(
    relays: List<UserRelay>,
    initialSelectedUrls: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selectedUrls by remember(relays) { mutableStateOf(initialSelectedUrls) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish to relays") },
        text = {
            if (relays.isEmpty()) {
                Text("Add outbox relays in Settings to publish notes.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    relays.forEach { relay ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedUrls.contains(relay.url),
                                onCheckedChange = { checked ->
                                    selectedUrls = if (checked) {
                                        selectedUrls + relay.url
                                    } else {
                                        selectedUrls - relay.url
                                    }
                                }
                            )
                            Text(
                                text = relay.displayName.ifBlank { relay.url },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (relays.isEmpty()) {
                TextButton(onClick = onDismiss) { Text("OK") }
            } else {
                Button(
                    onClick = { onConfirm(selectedUrls) },
                    enabled = selectedUrls.isNotEmpty()
                ) {
                    Text("Publish")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
