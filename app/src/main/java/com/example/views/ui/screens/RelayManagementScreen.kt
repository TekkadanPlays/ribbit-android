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
import kotlinx.coroutines.Dispatchers
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

// Helper function to create relay with NIP-11 info
private fun createRelayWithNip11Info(
    url: String,
    read: Boolean = true,
    write: Boolean = true,
    nip11CacheManager: com.example.views.cache.Nip11CacheManager
): UserRelay {
    val normalizedUrl = normalizeRelayUrl(url)
    val cachedInfo = nip11CacheManager.getCachedRelayInfo(normalizedUrl)
    
    return UserRelay(
        url = normalizedUrl,
        read = read,
        write = write,
        addedAt = System.currentTimeMillis(),
        info = cachedInfo,
        isOnline = cachedInfo != null,
        lastChecked = if (cachedInfo != null) System.currentTimeMillis() else 0L
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManagementScreen(
    onBackClick: () -> Unit,
    relayRepository: RelayRepository,
    modifier: Modifier = Modifier
) {
    val nip11CacheManager = relayRepository.getNip11CacheManager()
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
    
    // Input field visibility state
    var showOutboxInput by remember { mutableStateOf(false) }
    var showInboxInput by remember { mutableStateOf(false) }
    var showCacheInput by remember { mutableStateOf(false) }
    
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
                title = { 
                    Text(
                        text = "relays",
                        fontWeight = FontWeight.Bold
                    )
                },
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp)
                    ) {
                        // Add consistent top spacing
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Outbox Relays
                        RelayCategorySectionWithAddButton(
                            title = "Outbox Relays",
                            description = "Relays for publishing your notes",
                            relays = outboxRelays,
                            onRemoveRelay = { url ->
                                outboxRelays = outboxRelays.filter { it.url != url }
                            },
                            showInput = showOutboxInput,
                            onToggleInput = { showOutboxInput = !showOutboxInput },
                            relayUrl = outboxRelayUrl,
                            onRelayUrlChange = { outboxRelayUrl = it },
                            onAddRelay = {
                                if (outboxRelayUrl.isNotBlank()) {
                                    val normalizedUrl = normalizeRelayUrl(outboxRelayUrl)
                                    if (isDuplicateRelay(normalizedUrl, outboxRelays)) {
                                        toastMessage = "${normalizedUrl} already exists in Outbox Relays"
                                        showToast = true
                                    } else {
                                        val newRelay = createRelayWithNip11Info(
                                            url = normalizedUrl,
                                            read = true,
                                            write = true,
                                            nip11CacheManager = nip11CacheManager
                                        )
                                        outboxRelays = outboxRelays + newRelay
                                        outboxRelayUrl = ""
                                        showOutboxInput = false
                                        
                                        // Fetch fresh NIP-11 info in background if not cached
                                        if (newRelay.info == null) {
                                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                                val freshInfo = nip11CacheManager.getRelayInfo(normalizedUrl, forceRefresh = true)
                                                if (freshInfo != null) {
                                                    val updatedRelay = newRelay.copy(
                                                        info = freshInfo,
                                                        isOnline = true,
                                                        lastChecked = System.currentTimeMillis()
                                                    )
                                                    outboxRelays = outboxRelays.map { if (it.url == normalizedUrl) updatedRelay else it }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            isLoading = uiState.isLoading
                        )
                        
                        HorizontalDivider(
                            thickness = 1.dp, 
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        // Inbox Relays
                        RelayCategorySectionWithAddButton(
                            title = "Inbox Relays",
                            description = "Relays for receiving notes from others",
                            relays = inboxRelays,
                            onRemoveRelay = { url ->
                                inboxRelays = inboxRelays.filter { it.url != url }
                            },
                            showInput = showInboxInput,
                            onToggleInput = { showInboxInput = !showInboxInput },
                            relayUrl = inboxRelayUrl,
                            onRelayUrlChange = { inboxRelayUrl = it },
                            onAddRelay = {
                                if (inboxRelayUrl.isNotBlank()) {
                                    val normalizedUrl = normalizeRelayUrl(inboxRelayUrl)
                                    if (isDuplicateRelay(normalizedUrl, inboxRelays)) {
                                        toastMessage = "${normalizedUrl} already exists in Inbox Relays"
                                        showToast = true
                                    } else {
                                        val newRelay = createRelayWithNip11Info(
                                            url = normalizedUrl,
                                            read = true,
                                            write = true,
                                            nip11CacheManager = nip11CacheManager
                                        )
                                        inboxRelays = inboxRelays + newRelay
                                        inboxRelayUrl = ""
                                        showInboxInput = false
                                        
                                        // Fetch fresh NIP-11 info in background if not cached
                                        if (newRelay.info == null) {
                                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                                val freshInfo = nip11CacheManager.getRelayInfo(normalizedUrl, forceRefresh = true)
                                                if (freshInfo != null) {
                                                    val updatedRelay = newRelay.copy(
                                                        info = freshInfo,
                                                        isOnline = true,
                                                        lastChecked = System.currentTimeMillis()
                                                    )
                                                    inboxRelays = inboxRelays.map { if (it.url == normalizedUrl) updatedRelay else it }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            isLoading = uiState.isLoading
                        )
                        
                        HorizontalDivider(
                            thickness = 1.dp, 
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        // Cache Relays
                        RelayCategorySectionWithAddButton(
                            title = "Cache Relays",
                            description = "Relays for caching and backup",
                            relays = cacheRelays,
                            onRemoveRelay = { url ->
                                cacheRelays = cacheRelays.filter { it.url != url }
                            },
                            showInput = showCacheInput,
                            onToggleInput = { showCacheInput = !showCacheInput },
                            relayUrl = cacheRelayUrl,
                            onRelayUrlChange = { cacheRelayUrl = it },
                            onAddRelay = {
                                if (cacheRelayUrl.isNotBlank()) {
                                    val normalizedUrl = normalizeRelayUrl(cacheRelayUrl)
                                    if (isDuplicateRelay(normalizedUrl, cacheRelays)) {
                                        toastMessage = "${normalizedUrl} already exists in Cache Relays"
                                        showToast = true
                                    } else {
                                        val newRelay = createRelayWithNip11Info(
                                            url = normalizedUrl,
                                            read = true,
                                            write = true,
                                            nip11CacheManager = nip11CacheManager
                                        )
                                        cacheRelays = cacheRelays + newRelay
                                        cacheRelayUrl = ""
                                        showCacheInput = false
                                        
                                        // Fetch fresh NIP-11 info in background if not cached
                                        if (newRelay.info == null) {
                                            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                                                val freshInfo = nip11CacheManager.getRelayInfo(normalizedUrl, forceRefresh = true)
                                                if (freshInfo != null) {
                                                    val updatedRelay = newRelay.copy(
                                                        info = freshInfo,
                                                        isOnline = true,
                                                        lastChecked = System.currentTimeMillis()
                                                    )
                                                    cacheRelays = cacheRelays.map { if (it.url == normalizedUrl) updatedRelay else it }
                                                }
                                            }
                                        }
                                    }
                                }
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
                            isLoading = uiState.isLoading
                        )
                        
                        // Add consistent bottom spacing
                        Spacer(modifier = Modifier.height(8.dp))
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
                        val defaultRelay = createRelayWithNip11Info(
                            url = "wss://nos.lol",
                            read = true,
                            write = true,
                            nip11CacheManager = nip11CacheManager
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
                .focusRequester(focusRequester ?: remember { FocusRequester() })
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
private fun RelayCategorySectionWithAddButton(
    title: String,
    description: String,
    relays: List<UserRelay> = emptyList(),
    onRemoveRelay: (String) -> Unit = {},
    showInput: Boolean,
    onToggleInput: () -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onAddRelay: () -> Unit,
    onAddDefault: (() -> Unit)? = null,
    isLoading: Boolean
) {
    val focusManager = LocalFocusManager.current
    Column {
        // Category Header with Add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
            
            // Add button
            Row {
                // Default button (only for cache relays)
                onAddDefault?.let { addDefault ->
                    OutlinedButton(
                        onClick = addDefault,
                        enabled = !isLoading,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Default")
                    }
                }
                
                // Add button
                IconButton(
                    onClick = onToggleInput,
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = if (showInput) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (showInput) "Cancel" else "Add Relay"
                    )
                }
            }
        }
        
        // Relay Input (shown/hidden based on state)
        if (showInput) {
            RelayAddSectionNoPadding(
                relayUrl = relayUrl,
                onRelayUrlChange = onRelayUrlChange,
                onAddRelay = {
                    onAddRelay()
                    focusManager.clearFocus()
                },
                isLoading = isLoading,
                placeholder = "relay.example.com"
            )
        }
        
        // Add spacing below input when no relays are listed
        if (relays.isEmpty() && !showInput) {
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
    
    // Info tray
    RelayInfoTray(
        relay = relay,
        onDismiss = { showInfoSheet = false },
        isVisible = showInfoSheet
    )
}


@Composable
private fun RelayInfoTray(
    relay: UserRelay,
    onDismiss: () -> Unit,
    isVisible: Boolean
) {
    if (isVisible) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Relay Information",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Relay name and URL
                    Text(
                        text = relay.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Relay information
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
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    
                    relay.software?.let { software ->
                        Column {
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
                            Spacer(modifier = Modifier.height(12.dp))
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
                            Spacer(modifier = Modifier.height(12.dp))
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
