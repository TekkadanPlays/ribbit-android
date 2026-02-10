package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.example.views.ui.components.SageLoadingIndicator
import com.example.views.data.UserRelay
import com.example.views.data.RelayHealth
import com.example.views.data.RelayConnectionStatus
import com.example.views.data.RelayCategory
import com.example.views.data.DefaultRelayCategories
import com.example.views.repository.RelayRepository
import com.example.views.repository.RelayStorageManager
import com.example.views.viewmodel.RelayManagementViewModel
import com.example.views.viewmodel.AccountStateViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

// Helper function to normalize relay URL (remove trailing slash)
private fun normalizeRelayUrl(url: String): String {
    val trimmed = url.trim().removeSuffix("/")
    return when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        trimmed.startsWith("https://") -> trimmed.replace("https://", "wss://")
        trimmed.startsWith("http://") -> trimmed.replace("http://", "ws://")
        else -> "wss://$trimmed"
    }
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
    nip11Retriever: com.example.views.cache.nip11.Nip11CachedRetriever
): UserRelay {
    val normalizedUrl = normalizeRelayUrl(url)
    val cachedInfo = nip11Retriever.getFromCache(normalizedUrl)

    return UserRelay(
        url = normalizedUrl,
        read = read,
        write = write,
        addedAt = System.currentTimeMillis(),
        info = cachedInfo,
        isOnline = false,
        lastChecked = System.currentTimeMillis()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManagementScreen(
    onBackClick: () -> Unit,
    relayRepository: RelayRepository,
    accountStateViewModel: AccountStateViewModel,
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    onOpenRelayLog: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nip11Retriever = relayRepository.getNip11Retriever()
    val storageManager = remember { RelayStorageManager(context) }

    val viewModel: RelayManagementViewModel = viewModel {
        RelayManagementViewModel(relayRepository, storageManager)
    }

    val uiState by viewModel.uiState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

    // Load user relays when screen opens or user changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            viewModel.loadUserRelays(pubkey)
        }
    }

    // Tab state with pager
    val pagerState = rememberPagerState(pageCount = { 2 })
    val selectedTab by remember { derivedStateOf { pagerState.currentPage } }
    val coroutineScope = rememberCoroutineScope()

    // General tab - Category management state (from ViewModel)
    val relayCategories by viewModel.relayCategories.collectAsState()
    var newCategoryName by remember { mutableStateOf("") }
    var editingCategoryId by remember { mutableStateOf<String?>(null) }
    var editingCategoryName by remember { mutableStateOf("") }

    // Category-specific states (for add relay inputs)
    var categoryRelayInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var categoryInputVisibility by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var categoryExpanded by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // Personal tab relay states - get from ViewModel
    val outboxRelays by remember { derivedStateOf { uiState.outboxRelays } }
    val inboxRelays by remember { derivedStateOf { uiState.inboxRelays } }
    val cacheRelays by remember { derivedStateOf { uiState.cacheRelays } }

    var outboxRelayUrl by remember { mutableStateOf("") }
    var inboxRelayUrl by remember { mutableStateOf("") }
    var cacheRelayUrl by remember { mutableStateOf("") }

    // Input field visibility state
    var showOutboxInput by remember { mutableStateOf(false) }
    var showInboxInput by remember { mutableStateOf(false) }
    var showCacheInput by remember { mutableStateOf(false) }

    // Toast and dialog state
    var showToast by remember { mutableStateOf(false) }
    var toastMessage by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var categoryToDelete by remember { mutableStateOf<RelayCategory?>(null) }
    var showDefaultConfirmation by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Scroll behavior for collapsible top bar
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
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
                    // General Tab - Category Management
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Display all categories
                        relayCategories.forEach { category ->
                            RelayCategorySection(
                                category = category,
                                relayUrl = categoryRelayInputs[category.id] ?: "",
                                onRelayUrlChange = { newUrl ->
                                    categoryRelayInputs = categoryRelayInputs + (category.id to newUrl)
                                },
                                showInput = categoryInputVisibility[category.id] ?: false,
                                onToggleInput = {
                                    val isCurrentlyShowing = categoryInputVisibility[category.id] ?: false
                                    // Close all inputs first
                                    categoryInputVisibility = categoryInputVisibility.mapValues { false }
                                    // If it was closed, open it; if it was open, keep it closed
                                    if (!isCurrentlyShowing) {
                                        categoryInputVisibility = categoryInputVisibility + (category.id to true)
                                    }
                                },
                                onAddRelay = { url ->
                                    val normalizedUrl = normalizeRelayUrl(url)
                                    if (isDuplicateRelay(normalizedUrl, category.relays)) {
                                        toastMessage = "${normalizedUrl} already exists in ${category.name}"
                                        showToast = true
                                    } else {
                                        val newRelay = createRelayWithNip11Info(
                                            url = normalizedUrl,
                                            read = true,
                                            write = true,
                                            nip11Retriever = nip11Retriever
                                        )
                                        viewModel.addRelayToCategory(category.id, newRelay)
                                        categoryRelayInputs = categoryRelayInputs + (category.id to "")
                                        categoryInputVisibility = categoryInputVisibility + (category.id to false)
                                    }
                                },
                                onRemoveRelay = { relay ->
                                    viewModel.removeRelayFromCategory(category.id, relay.url)
                                },
                                onRenameCategory = { newName ->
                                    if (newName.isNotBlank()) {
                                        val updatedCategory = category.copy(name = newName)
                                        viewModel.updateCategory(category.id, updatedCategory)
                                        editingCategoryId = null
                                    }
                                },
                                onDeleteCategory = {
                                    categoryToDelete = category
                                    showDeleteConfirmation = true
                                },
                                isEditing = editingCategoryId == category.id,
                                onStartEditing = {
                                    editingCategoryId = category.id
                                    editingCategoryName = category.name
                                },
                                editingName = editingCategoryName,
                                onEditingNameChange = { editingCategoryName = it },
                                isLoading = uiState.isLoading,
                                isExpanded = categoryExpanded[category.id] ?: false,
                                onExpandToggle = {
                                    categoryExpanded = categoryExpanded + (category.id to !(categoryExpanded[category.id] ?: false))
                                },
                                onToggleSubscription = { categoryId ->
                                    viewModel.toggleCategorySubscription(categoryId)
                                },
                                onOpenRelayLog = onOpenRelayLog
                            )

                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }

                        // "Add New Category" button at bottom — compact, not full-width
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (newCategoryName.isBlank()) {
                                        newCategoryName = "New Category"
                                    }
                                    val newCategory = RelayCategory(
                                        name = newCategoryName,
                                        relays = emptyList()
                                    )
                                    viewModel.addCategory(newCategory)
                                    newCategoryName = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Category",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add Category")
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
                                viewModel.removeOutboxRelay(url)
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
                                            nip11Retriever = nip11Retriever
                                        )
                                        viewModel.addOutboxRelay(newRelay)
                                        outboxRelayUrl = ""
                                        showOutboxInput = false
                                    }
                                }
                            },
                            isLoading = uiState.isLoading,
                            onOpenRelayLog = onOpenRelayLog
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
                                viewModel.removeInboxRelay(url)
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
                                            nip11Retriever = nip11Retriever
                                        )
                                        viewModel.addInboxRelay(newRelay)
                                        inboxRelayUrl = ""
                                        showInboxInput = false
                                    }
                                }
                            },
                            isLoading = uiState.isLoading,
                            onOpenRelayLog = onOpenRelayLog
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
                                viewModel.removeCacheRelay(url)
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
                                            nip11Retriever = nip11Retriever
                                        )
                                        viewModel.addCacheRelay(newRelay)
                                        cacheRelayUrl = ""
                                        showCacheInput = false
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
                            isLoading = uiState.isLoading,
                            onOpenRelayLog = onOpenRelayLog
                        )

                        // Add consistent bottom spacing
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                        }
                }
            }
        }

        // Delete Category Confirmation Dialog
        if (showDeleteConfirmation && categoryToDelete != null) {
            AlertDialog(
                onDismissRequest = {
                    showDeleteConfirmation = false
                    categoryToDelete = null
                },
                title = {
                    Text("Delete Category?")
                },
                text = {
                    Column {
                        Text("Are you sure you want to delete \"${categoryToDelete?.name}\"?")
                        if (categoryToDelete?.relays?.isNotEmpty() == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "This category contains ${categoryToDelete?.relays?.size} relay(s).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            categoryToDelete?.let { category ->
                                viewModel.deleteCategory(category.id)
                            }
                            showDeleteConfirmation = false
                            categoryToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmation = false
                            categoryToDelete = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
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
                            nip11Retriever = nip11Retriever
                        )
                        viewModel.addCacheRelay(defaultRelay)
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
                            SageLoadingIndicator(
                                size = 20.dp,
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
                            SageLoadingIndicator(
                                size = 20.dp,
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
    isLoading: Boolean,
    onOpenRelayLog: (String) -> Unit = {},
    usePersonalStyle: Boolean = true
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
                if (usePersonalStyle) {
                    PersonalRelayItem(
                        relay = relay,
                        connectionStatus = RelayConnectionStatus.DISCONNECTED,
                        onOpenRelayLog = onOpenRelayLog,
                        onRemove = { onRemoveRelay(relay.url) }
                    )
                } else {
                    RelaySettingsItem(
                        relay = relay,
                        connectionStatus = RelayConnectionStatus.DISCONNECTED,
                        onOpenRelayLog = onOpenRelayLog,
                        onRemove = { onRemoveRelay(relay.url) }
                    )
                }

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
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onOpenRelayLog: (String) -> Unit = {}
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
                    onOpenRelayLog = onOpenRelayLog,
                    onRemove = { onRemoveRelay(relay.url) }
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
    onOpenRelayLog: (String) -> Unit,
    onRemove: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                showDeleteConfirm = true
                false // Don't actually dismiss yet — wait for confirmation
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp)
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onOpenRelayLog(relay.url) }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // Themed confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "Remove Relay?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text("Remove ${relay.displayName} from this list?")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onRemove()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}


/**
 * Relay item for the Personal tab — uses a delete icon button instead of swipe-to-dismiss
 * to avoid gesture conflict with HorizontalPager (swiping right to go back to General tab).
 */
@Composable
private fun PersonalRelayItem(
    relay: UserRelay,
    connectionStatus: RelayConnectionStatus,
    onOpenRelayLog: (String) -> Unit,
    onRemove: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onOpenRelayLog(relay.url) }
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Relay icon
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = relay.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = relay.url,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Remove relay",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "Remove Relay?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text("Remove ${relay.displayName} from this list?")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = relay.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onRemove()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
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

/**
 * Category section for General tab with proper UX:
 * - Tap category row to expand/collapse
 * - + button expands and shows input
 * - Pencil icon enables inline editing
 * - Count shown as "Name (5)"
 */
@Composable
private fun RelayCategorySection(
    category: RelayCategory,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    showInput: Boolean,
    onToggleInput: () -> Unit,
    onAddRelay: (String) -> Unit,
    onRemoveRelay: (UserRelay) -> Unit,
    onRenameCategory: (String) -> Unit,
    onDeleteCategory: () -> Unit,
    isEditing: Boolean,
    onStartEditing: () -> Unit,
    editingName: String,
    onEditingNameChange: (String) -> Unit,
    isLoading: Boolean,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onToggleSubscription: (String) -> Unit,
    onOpenRelayLog: (String) -> Unit = {}
) {
    val isSubscribed = category.isSubscribed
    Column {
        // Category header row (with padding)
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onExpandToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: chevron + name
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // Name with inline editing
                    if (isEditing) {
                        BasicTextField(
                            value = editingName,
                            onValueChange = onEditingNameChange,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onRenameCategory(editingName) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Save",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSubscribed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${category.relays.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right: subscribe switch
                Switch(
                    checked = isSubscribed,
                    onCheckedChange = { onToggleSubscription(category.id) },
                    modifier = Modifier.height(24.dp)
                )
            }

            // Action row (edit name, add relay) — only when expanded
            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Edit name
                    if (!isEditing) {
                        IconButton(
                            onClick = onStartEditing,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit name",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Add relay
                    IconButton(
                        onClick = {
                            if (!isExpanded) onExpandToggle()
                            onToggleInput()
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (showInput) Icons.Default.Remove else Icons.Default.Add,
                            contentDescription = if (showInput) "Hide" else "Add relay",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Expanded content: input (with padding)
            if (isExpanded && showInput) {
                Spacer(modifier = Modifier.height(8.dp))
                RelayAddSectionNoPadding(
                    relayUrl = relayUrl,
                    onRelayUrlChange = onRelayUrlChange,
                    onAddRelay = { onAddRelay(relayUrl) },
                    isLoading = isLoading,
                    placeholder = "relay.example.com"
                )
            }
        }

        // Relay list (edge-to-edge, no horizontal padding for swipe-to-dismiss)
        if (isExpanded && category.relays.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            category.relays.forEachIndexed { index, relay ->
                RelaySettingsItem(
                    relay = relay,
                    connectionStatus = RelayConnectionStatus.DISCONNECTED,
                    onOpenRelayLog = onOpenRelayLog,
                    onRemove = { onRemoveRelay(relay) }
                )
                if (index < category.relays.size - 1) {
                    HorizontalDivider(thickness = 1.dp)
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
