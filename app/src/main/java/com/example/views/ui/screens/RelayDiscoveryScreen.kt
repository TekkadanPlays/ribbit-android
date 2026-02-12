package com.example.views.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.views.cache.Nip11CacheManager
import com.example.views.data.DiscoveredRelay
import com.example.views.data.RelayType
import com.example.views.repository.Nip66RelayDiscoveryRepository

/**
 * NIP-66 Relay Discovery screen. Displays relays discovered from relay monitors
 * (kind 30166 events), categorized by their `T` tag relay type. Users can browse
 * relays by type, see latency data, supported NIPs, and add relays to their config.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayDiscoveryScreen(
    onBackClick: () -> Unit,
    onRelayClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val discoveredRelays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()
    val isLoading by Nip66RelayDiscoveryRepository.isLoading.collectAsState()
    val hasFetched by Nip66RelayDiscoveryRepository.hasFetched.collectAsState()

    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // Filter state
    var selectedTypeFilter by remember { mutableStateOf<RelayType?>(null) }

    // Group relays by type
    data class CategorizedDiscovery(
        val search: List<DiscoveredRelay>,
        val outbox: List<DiscoveredRelay>,
        val inbox: List<DiscoveredRelay>,
        val privateInbox: List<DiscoveredRelay>,
        val directory: List<DiscoveredRelay>,
        val other: List<DiscoveredRelay>,
        val all: List<DiscoveredRelay>
    )

    val categorized = remember(discoveredRelays, selectedTypeFilter) {
        val all = discoveredRelays.values.toList()
            .sortedWith(compareByDescending<DiscoveredRelay> { it.monitorCount }.thenBy { it.url })

        if (selectedTypeFilter != null) {
            val filtered = all.filter { selectedTypeFilter in it.types }
            CategorizedDiscovery(
                search = emptyList(), outbox = emptyList(), inbox = emptyList(),
                privateInbox = emptyList(), directory = emptyList(), other = emptyList(),
                all = filtered
            )
        } else {
            val search = all.filter { it.isSearch }
            val outbox = all.filter { RelayType.PUBLIC_OUTBOX in it.types }
            val inbox = all.filter { RelayType.PUBLIC_INBOX in it.types }
            val privateInbox = all.filter { RelayType.PRIVATE_INBOX in it.types }
            val directory = all.filter { RelayType.DIRECTORY in it.types }
            // "Other" = relays with no recognized type, or types not in the above
            val categorizedUrls = (search + outbox + inbox + privateInbox + directory).map { it.url }.toSet()
            val other = all.filter { it.url !in categorizedUrls }
            CategorizedDiscovery(search, outbox, inbox, privateInbox, directory, other, all)
        }
    }

    // Section expand state
    var searchExpanded by remember { mutableStateOf(true) }
    var outboxExpanded by remember { mutableStateOf(true) }
    var inboxExpanded by remember { mutableStateOf(true) }
    var privateInboxExpanded by remember { mutableStateOf(false) }
    var directoryExpanded by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = "discover relays",
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
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Summary ──
            item(key = "discovery_summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiscoveryStatPill(
                        label = "Relays",
                        value = "${discoveredRelays.size}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    DiscoveryStatPill(
                        label = "Search",
                        value = "${categorized.search.size}",
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    DiscoveryStatPill(
                        label = "Outbox",
                        value = "${categorized.outbox.size}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    DiscoveryStatPill(
                        label = "Inbox",
                        value = "${categorized.inbox.size}",
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Type filter chips ──
            item(key = "type_filter_chips") {
                val chipTypes = listOf(
                    null to "All",
                    RelayType.SEARCH to "Search",
                    RelayType.PUBLIC_OUTBOX to "Outbox",
                    RelayType.PUBLIC_INBOX to "Inbox",
                    RelayType.PRIVATE_INBOX to "Private Inbox",
                    RelayType.DIRECTORY to "Directory",
                    RelayType.ARCHIVAL to "Archival",
                    RelayType.BROADCAST to "Broadcast"
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(chipTypes.size) { index ->
                        val (type, label) = chipTypes[index]
                        FilterChip(
                            selected = selectedTypeFilter == type,
                            onClick = { selectedTypeFilter = type },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Loading / Empty ──
            if (isLoading && discoveredRelays.isEmpty()) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(40.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Discovering relays...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (hasFetched && discoveredRelays.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Explore,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No relays discovered yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Relay monitors haven't published discovery data to your connected relays",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = {
                                Nip66RelayDiscoveryRepository.init(context)
                                val fallbackRelays = listOf(
                                    "wss://purplepag.es",
                                    "wss://relay.nostr.band",
                                    "wss://user.kindpag.es"
                                )
                                Nip66RelayDiscoveryRepository.fetchRelayDiscovery(
                                    fallbackRelays,
                                    emptyList()
                                )
                            }
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            }

            // ── Filtered view (single list) ──
            if (selectedTypeFilter != null && categorized.all.isNotEmpty()) {
                items(
                    items = categorized.all,
                    key = { "filtered_${it.url}" }
                ) { relay ->
                    DiscoveredRelayRow(
                        relay = relay,
                        nip11 = nip11,
                        onClick = { onRelayClick(relay.url) }
                    )
                }
            }

            // ── Categorized sections (no filter) ──
            if (selectedTypeFilter == null) {
                // Search / Indexer
                if (categorized.search.isNotEmpty()) {
                    item(key = "section_search") {
                        DiscoverySectionHeader(
                            title = "Search / Indexer",
                            count = categorized.search.size,
                            icon = Icons.Outlined.Search,
                            expanded = searchExpanded,
                            onToggle = { searchExpanded = !searchExpanded }
                        )
                    }
                    if (searchExpanded) {
                        items(
                            items = categorized.search,
                            key = { "search_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }

                // Public Outbox
                if (categorized.outbox.isNotEmpty()) {
                    item(key = "section_outbox") {
                        DiscoverySectionHeader(
                            title = "Public Outbox",
                            count = categorized.outbox.size,
                            icon = Icons.Outlined.Upload,
                            expanded = outboxExpanded,
                            onToggle = { outboxExpanded = !outboxExpanded }
                        )
                    }
                    if (outboxExpanded) {
                        items(
                            items = categorized.outbox,
                            key = { "outbox_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }

                // Public Inbox
                if (categorized.inbox.isNotEmpty()) {
                    item(key = "section_inbox") {
                        DiscoverySectionHeader(
                            title = "Public Inbox",
                            count = categorized.inbox.size,
                            icon = Icons.Outlined.Download,
                            expanded = inboxExpanded,
                            onToggle = { inboxExpanded = !inboxExpanded }
                        )
                    }
                    if (inboxExpanded) {
                        items(
                            items = categorized.inbox,
                            key = { "inbox_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }

                // Private Inbox
                if (categorized.privateInbox.isNotEmpty()) {
                    item(key = "section_private_inbox") {
                        DiscoverySectionHeader(
                            title = "Private Inbox",
                            count = categorized.privateInbox.size,
                            icon = Icons.Outlined.Lock,
                            expanded = privateInboxExpanded,
                            onToggle = { privateInboxExpanded = !privateInboxExpanded }
                        )
                    }
                    if (privateInboxExpanded) {
                        items(
                            items = categorized.privateInbox,
                            key = { "private_inbox_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }

                // Directory
                if (categorized.directory.isNotEmpty()) {
                    item(key = "section_directory") {
                        DiscoverySectionHeader(
                            title = "Directory",
                            count = categorized.directory.size,
                            icon = Icons.Outlined.Folder,
                            expanded = directoryExpanded,
                            onToggle = { directoryExpanded = !directoryExpanded }
                        )
                    }
                    if (directoryExpanded) {
                        items(
                            items = categorized.directory,
                            key = { "directory_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }

                // Other / Uncategorized
                if (categorized.other.isNotEmpty()) {
                    item(key = "section_other") {
                        DiscoverySectionHeader(
                            title = "Other",
                            count = categorized.other.size,
                            icon = Icons.Outlined.MoreHoriz,
                            expanded = otherExpanded,
                            onToggle = { otherExpanded = !otherExpanded }
                        )
                    }
                    if (otherExpanded) {
                        items(
                            items = categorized.other,
                            key = { "other_${it.url}" }
                        ) { relay ->
                            DiscoveredRelayRow(
                                relay = relay,
                                nip11 = nip11,
                                onClick = { onRelayClick(relay.url) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Components ──

@Composable
private fun DiscoverySectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "chevron"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onToggle
            )
            .padding(top = 12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

@Composable
private fun DiscoveredRelayRow(
    relay: DiscoveredRelay,
    nip11: Nip11CacheManager,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Get NIP-11 display name
    val nip11Info = remember(relay.url) { nip11.getCachedRelayInfo(relay.url) }
    val displayName = nip11Info?.name
        ?: relay.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/")
    val iconUrl = nip11Info?.icon ?: nip11Info?.image

    // Type chips text
    val typeLabels = relay.types.map { it.displayName }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Relay icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(40.dp)
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(iconUrl)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = displayName,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // Type tags row
                if (typeLabels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        typeLabels.take(3).forEach { label ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (typeLabels.size > 3) {
                            Text(
                                text = "+${typeLabels.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // Latency info
                val rttParts = buildList {
                    relay.avgRttOpen?.let { add("open: ${it}ms") }
                    relay.avgRttRead?.let { add("read: ${it}ms") }
                    relay.avgRttWrite?.let { add("write: ${it}ms") }
                }
                if (rttParts.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = rttParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            // Monitor count badge
            if (relay.monitorCount > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${relay.monitorCount}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (relay.monitorCount == 1) "monitor" else "monitors",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryStatPill(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
