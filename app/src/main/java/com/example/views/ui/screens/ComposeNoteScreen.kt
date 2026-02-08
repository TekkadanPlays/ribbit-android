package com.example.views.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Note composition screen for typing up posts.
 * No publish logic yet; prepared for future integration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeNoteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var content by remember { mutableStateOf("") }

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
            // Publish button placeholder – no logic yet
            TextButton(
                onClick = { /* TODO: publish */ },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Publish (coming soon)")
            }
        }
    }
}
