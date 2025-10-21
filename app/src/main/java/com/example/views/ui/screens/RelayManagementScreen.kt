package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.views.data.UserRelay
import com.example.views.data.RelayHealth
import com.example.views.data.RelayConnectionStatus
import com.example.views.repository.RelayRepository
import com.example.views.viewmodel.RelayManagementViewModel
import kotlinx.coroutines.launch

// Helper function to normalize relay URL (remove trailing slash)
private fun normalizeRelayUrl(url: String): String {
    return url.trim().removeSuffix("/")
}

// Helper function to check for duplicates in a category
private fun isDuplicateRelay(url: String, existingRelays: List<UserRelay>): Boolean {
    val normalizedUrl = normalizeRelayUrl(url)
    return existingRelays.any { normalizeRelayUrl(it.url) == normalizedUrl }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManagementScreen(
    onBackClick: () -> Unit,
    relayRepository: RelayRepository,
    modifier: Modifier = Modifier
) {
    val viewModel: RelayManagementViewModel = viewModel {
        RelayManagementViewModel(relayRepository)
    }
    
    val uiState by viewModel.uiState.collectAsState()
    
    // Tab state with pager
    val pagerState = rememberPagerState(pageCount = { 2 })
    val selectedTab by remember { derivedStateOf { pagerState.currentPage } }
    val coroutineScope = rememberCoroutineScope()
    
    // General tab relay state
    var generalRelayUrl by remember { mutableStateOf("") }
    
    // Personal tab relay states - each category has its own list
    var outboxRelayUrl by remember { mutableStateOf("") }
    var inboxRelayUrl by remember { mutableStateOf("") }
    var cacheRelayUrl by remember { mutableStateOf("") }
    
    // Focus management for smart scrolling
    var isAnyInputFocused by remember { mutableStateOf(false) }
    var lastFocusedCategory by remember { mutableStateOf("") }
    val outboxFocusRequester = remember { FocusRequester() }
    val inboxFocusRequester = remember { FocusRequester() }
    val cacheFocusRequester = remember { FocusRequester() }
    
    // Separate relay lists for each category
    var outboxRelays by remember { mutableStateOf<List<UserRelay>>(emptyList()) }
    var inboxRelays by remember { mutableStateOf<List<UserRelay>>(emptyList()) }
    var cacheRelays by remember { mutableStateOf<List<UserRelay>>(emptyList()) }
    
    // Toast and dialog state
    var showToast by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf("") }
    var showDefaultConfirmation by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Relays") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Publish button only on Personal tab
                    if (selectedTab == 1) {
                        IconButton(
                            onClick = { /* TODO: Implement publish functionality */ }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Publish,
                                contentDescription = "Publish"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
                    // Tab Row
                    PrimaryTabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                            text = { Text("General") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                            text = { Text("Personal") }
                        )
                    }
                    
                    // Tab Content with HorizontalPager
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> {
                    // General Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        RelayAddSection(
                            relayUrl = generalRelayUrl,
                            onRelayUrlChange = { generalRelayUrl = it },
                            onAddRelay = {
                                if (generalRelayUrl.isNotBlank()) {
                                    val normalizedUrl = normalizeRelayUrl(generalRelayUrl)
                                    if (isDuplicateRelay(normalizedUrl, uiState.relays)) {
                                        toastMessage = "${normalizedUrl} already exists in General Relays"
                                        showToast = true
                                    } else {
                                        viewModel.addRelay(normalizedUrl, true, true)
                                        generalRelayUrl = ""
                                    }
                                }
                            },
                            isLoading = uiState.isLoading,
                            placeholder = "relay.example.com"
                        )
                        
                        if (uiState.relays.isNotEmpty()) {
                            HorizontalDivider(
                                thickness = 1.dp, 
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                            
                            // Relays List
                            uiState.relays.forEachIndexed { index, relay ->
                                RelaySettingsItem(
                                    relay = relay,
                                    connectionStatus = uiState.connectionStatus[relay.url] ?: RelayConnectionStatus.DISCONNECTED,
                                    onRemove = { viewModel.removeRelay(relay.url) },
                                    onRefresh = { viewModel.refreshRelayInfo(relay.url) },
                                    onTestConnection = { viewModel.testRelayConnection(relay.url) }
                                )
                                
                                // Only add divider if not the last item
                                if (index < uiState.relays.size - 1) {
                                    HorizontalDivider(
                                        thickness = 1.dp, 
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                1 -> {
                    // Personal Tab
                    val listState = rememberLazyListState()
                    
                    // Auto-scroll to top when no input is focused
                    LaunchedEffect(isAnyInputFocused) {
                        if (!isAnyInputFocused) {
                            // Smooth scroll to top with a slight delay to ensure keyboard is dismissed
                            kotlinx.coroutines.delay(100)
                            listState.animateScrollToItem(0, scrollOffset = 0)
                        }
                    }
                    
                    // Reset scroll when adding relays (keyboard dismissal)
                    LaunchedEffect(outboxRelayUrl, inboxRelayUrl, cacheRelayUrl) {
                        if (outboxRelayUrl.isEmpty() && inboxRelayUrl.isEmpty() && cacheRelayUrl.isEmpty()) {
                            // All inputs are empty, scroll to top
                            kotlinx.coroutines.delay(200)
                            listState.animateScrollToItem(0, scrollOffset = 0)
                        }
                    }
                    
                    // Reset scroll when relays are added (indicates successful input and keyboard dismissal)
                    LaunchedEffect(outboxRelays.size, inboxRelays.size, cacheRelays.size) {
                        // Small delay to ensure smooth transition after adding relay
                        kotlinx.coroutines.delay(300)
                        listState.animateScrollToItem(0, scrollOffset = 0)
                    }
                    
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 16.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                        // Outbox Relays
                        item {
                            RelayCategorySection(
                                title = "Outbox Relays",
                                description = "Relays for publishing your notes",
                                relayUrl = outboxRelayUrl,
                                onRelayUrlChange = { outboxRelayUrl = it },
                                onAddRelay = {
                                    if (outboxRelayUrl.isNotBlank()) {
                                        val normalizedUrl = normalizeRelayUrl(outboxRelayUrl)
                                        if (isDuplicateRelay(normalizedUrl, outboxRelays)) {
                                            toastMessage = "${normalizedUrl} already exists in Outbox Relays"
                                            showToast = true
                                        } else {
                                            val newRelay = UserRelay(
                                                url = normalizedUrl,
                                                read = true,
                                                write = true
                                            )
                                            outboxRelays = outboxRelays + newRelay
                                            outboxRelayUrl = ""
                                        }
                                    }
                                },
                                relays = outboxRelays,
                                onRemoveRelay = { url ->
                                    outboxRelays = outboxRelays.filter { it.url != url }
                                },
                                isLoading = uiState.isLoading,
                                focusRequester = outboxFocusRequester,
                                onFocusChanged = { isFocused ->
                                    isAnyInputFocused = isFocused
                                    if (isFocused) {
                                        lastFocusedCategory = "outbox"
                                        coroutineScope.launch {
                                            // Scroll to Outbox category header (item 0)
                                            listState.animateScrollToItem(0, scrollOffset = 50)
                                        }
                                    }
                                }
                            )
                        }
                        
                        item {
                            HorizontalDivider(
                                thickness = 1.dp, 
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        // Inbox Relays
                        item {
                            RelayCategorySection(
                                title = "Inbox Relays",
                                description = "Relays for receiving notes from others",
                                relayUrl = inboxRelayUrl,
                                onRelayUrlChange = { inboxRelayUrl = it },
                                onAddRelay = {
                                    if (inboxRelayUrl.isNotBlank()) {
                                        val normalizedUrl = normalizeRelayUrl(inboxRelayUrl)
                                        if (isDuplicateRelay(normalizedUrl, inboxRelays)) {
                                            toastMessage = "${normalizedUrl} already exists in Inbox Relays"
                                            showToast = true
                                        } else {
                                            val newRelay = UserRelay(
                                                url = normalizedUrl,
                                                read = true,
                                                write = true
                                            )
                                            inboxRelays = inboxRelays + newRelay
                                            inboxRelayUrl = ""
                                        }
                                    }
                                },
                                relays = inboxRelays,
                                onRemoveRelay = { url ->
                                    inboxRelays = inboxRelays.filter { it.url != url }
                                },
                                isLoading = uiState.isLoading,
                                focusRequester = inboxFocusRequester,
                                onFocusChanged = { isFocused ->
                                    isAnyInputFocused = isFocused
                                    if (isFocused) {
                                        lastFocusedCategory = "inbox"
                                        coroutineScope.launch {
                                            // Scroll to Inbox category header (item 2)
                                            listState.animateScrollToItem(2, scrollOffset = 50)
                                        }
                                    }
                                }
                            )
                        }
                        
                        item {
                            HorizontalDivider(
                                thickness = 1.dp, 
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        
                        // Cache Relays
                        item {
                            RelayCategorySection(
                                title = "Cache Relays",
                                description = "Relays for caching and backup",
                                relayUrl = cacheRelayUrl,
                                onRelayUrlChange = { cacheRelayUrl = it },
                                onAddRelay = {
                                    if (cacheRelayUrl.isNotBlank()) {
                                        val normalizedUrl = normalizeRelayUrl(cacheRelayUrl)
                                        if (isDuplicateRelay(normalizedUrl, cacheRelays)) {
                                            toastMessage = "${normalizedUrl} already exists in Cache Relays"
                                            showToast = true
                                        } else {
                                            val newRelay = UserRelay(
                                                url = normalizedUrl,
                                                read = true,
                                                write = true
                                            )
                                            cacheRelays = cacheRelays + newRelay
                                            cacheRelayUrl = ""
                                        }
                                    }
                                },
                                relays = cacheRelays,
                                onRemoveRelay = { url ->
                                    cacheRelays = cacheRelays.filter { it.url != url }
                                },
                                onAddDefault = {
                                    val defaultUrl = "wss://nos.lol"
                                    if (isDuplicateRelay(defaultUrl, cacheRelays)) {
                                        toastMessage = "wss://nos.lol already exists in Cache Relays"
                                        showToast = true
                                    } else {
                                        showDefaultConfirmation = true
                                    }
                                },
                                isLoading = uiState.isLoading,
                                focusRequester = cacheFocusRequester,
                                onFocusChanged = { isFocused ->
                                    isAnyInputFocused = isFocused
                                    if (isFocused) {
                                        lastFocusedCategory = "cache"
                                        coroutineScope.launch {
                                            // Scroll to Cache category header (item 4)
                                            listState.animateScrollToItem(4, scrollOffset = 50)
                                        }
                                    }
                                }
                            )
                        }
                        
                        // Add minimum height to ensure scrollability
                        item {
                            Spacer(modifier = Modifier.height(600.dp))
                        }
                    }
                    }
                        }
                }
            }
        }
    }
    
    // Snackbar for toast messages
    LaunchedEffect(showToast) {
        if (showToast) {
            snackbarHostState.showSnackbar(toastMessage)
            showToast = false
        }
    }
    
    // Confirmation dialog for adding default relay
    if (showDefaultConfirmation) {
        AlertDialog(
            onDismissRequest = { showDefaultConfirmation = false },
            title = { Text("Add Default Cache Relay") },
            text = { Text("Add wss://nos.lol/ to your cache relays?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val defaultRelay = UserRelay(
                            url = "wss://nos.lol",
                            read = true,
                            write = true
                        )
                        cacheRelays = cacheRelays + defaultRelay
                        showDefaultConfirmation = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDefaultConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RelayAddSection(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onAddRelay: () -> Unit,
    isLoading: Boolean,
    placeholder: String = "relay.example.com"
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // URL Input
        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL") },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                            if (relayUrl.isNotBlank()) {
                                onAddRelay()
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                }
            ),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Router,
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (relayUrl.isNotBlank()) {
                    IconButton(
                        onClick = onAddRelay,
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Relay"
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun RelayAddSectionNoPadding(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onAddRelay: () -> Unit,
    isLoading: Boolean,
    placeholder: String = "relay.example.com",
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // URL Input
        OutlinedTextField(
            value = relayUrl,
            onValueChange = onRelayUrlChange,
            label = { Text("Relay URL") },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (relayUrl.isNotBlank()) {
                        onAddRelay()
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester ?: FocusRequester())
                .onFocusChanged { focusState ->
                    onFocusChanged?.invoke(focusState.isFocused)
                },
            enabled = !isLoading,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Router,
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (relayUrl.isNotBlank()) {
                    IconButton(
                        onClick = onAddRelay,
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Relay"
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun RelayCategorySection(
    title: String,
    description: String,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onAddRelay: () -> Unit,
    relays: List<UserRelay> = emptyList(),
    onRemoveRelay: (String) -> Unit = {},
    onAddDefault: (() -> Unit)? = null,
    isLoading: Boolean,
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    Column {
        // Category Header with Default button (for cache relays)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Default button (only for cache relays)
            onAddDefault?.let { addDefault ->
                OutlinedButton(
                    onClick = addDefault,
                    enabled = !isLoading,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Default")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Relay Input
        RelayAddSectionNoPadding(
            relayUrl = relayUrl,
            onRelayUrlChange = onRelayUrlChange,
            onAddRelay = onAddRelay,
            isLoading = isLoading,
            placeholder = "relay.example.com",
            focusRequester = focusRequester,
            onFocusChanged = onFocusChanged
        )
        
        // Add spacing below input when no relays are listed
        if (relays.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Relay List
        if (relays.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            
            relays.forEachIndexed { index, relay ->
                RelaySettingsItem(
                    relay = relay,
                    connectionStatus = RelayConnectionStatus.DISCONNECTED, // TODO: Track connection status per category
                    onRemove = { onRemoveRelay(relay.url) },
                    onRefresh = { /* TODO: Implement refresh for category relays */ },
                    onTestConnection = { /* TODO: Implement test for category relays */ }
                )
                
                // Only add divider if not the last item
                if (index < relays.size - 1) {
                    HorizontalDivider(
                        thickness = 1.dp, 
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySettingsItem(
    relay: UserRelay,
    connectionStatus: RelayConnectionStatus,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onTestConnection: () -> Unit
) {
    var showInfoSheet by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showInfoSheet = true }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Relay icon - use NIP-11 icon if available, otherwise router icon
        if (relay.info?.icon != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(relay.info.icon)
                    .crossfade(true)
                    .build(),
                contentDescription = "Relay icon",
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Router,
                contentDescription = null,
                tint = when (connectionStatus) {
                    RelayConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                    RelayConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
                    RelayConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    RelayConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
                }
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Relay info - just the title/URL
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = relay.url,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Action buttons
        Row {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp)
                )
            }
            
            IconButton(
                onClick = onTestConnection,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = "Test",
                    modifier = Modifier.size(20.dp)
                )
            }
            
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    
    // Info sidebar
    RelayInfoSidebar(
        relay = relay,
        onDismiss = { showInfoSheet = false },
        isVisible = showInfoSheet
    )
}


@Composable
private fun RelayInfoSidebar(
    relay: UserRelay,
    onDismiss: () -> Unit,
    isVisible: Boolean
) {
    if (isVisible) {
        Dialog(onDismissRequest = onDismiss) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Backdrop
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { onDismiss() }
                )
                
                // Sidebar content
                Card(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(400.dp)
                        .align(Alignment.CenterEnd)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 32.dp)
                    ) {
                        // Banner image with overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            // Background banner image
                            relay.info?.image?.let { imageUrl ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(imageUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Relay banner image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                
                                // Dark overlay for better text readability
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Color.Black.copy(alpha = 0.4f)
                                        )
                                )
                            }
                            
                            // If no banner, use solid color
                            if (relay.info?.image == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                )
                            }
                            
                            // Content overlay
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                // Icon or profile image
                                relay.info?.icon?.let { iconUrl ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(iconUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Relay icon",
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface)
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                                
                                Text(
                                    text = relay.displayName,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (relay.info?.image != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                
                                Text(
                                    text = relay.url,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (relay.info?.image != null) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        
                        // Relay information
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            relay.description?.let { description ->
                                Column {
                                    Text(
                                        text = "Description",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                relay.software?.let { software ->
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Software",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = software,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            
                            relay.info?.contact?.let { contact ->
                                Column {
                                    Text(
                                        text = "Contact",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = contact,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            
                            if (relay.supportedNips.isNotEmpty()) {
                                Column {
                                    Text(
                                        text = "Supported NIPs",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = relay.supportedNips.joinToString(", ") { "NIP-$it" },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RelayManagementScreenPreview() {
    MaterialTheme {
        RelayCategorySection(
            title = "Outbox Relays",
            description = "Relays for publishing your notes",
            relayUrl = "relay.example.com",
            onRelayUrlChange = {},
            onAddRelay = {},
            isLoading = false
        )
    }
}
