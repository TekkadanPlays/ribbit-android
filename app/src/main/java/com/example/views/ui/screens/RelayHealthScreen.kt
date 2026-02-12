package com.example.views.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.views.cache.Nip11CacheManager
import com.example.views.data.RelayInformation
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.RelayEndpointStatus
import com.example.views.relay.RelayHealthInfo
import com.example.views.relay.RelayHealthTracker
import com.example.views.data.RelayType
import com.example.views.repository.Nip65RelayListRepository
import com.example.views.repository.Nip66RelayDiscoveryRepository

/**
 * Relay Health dashboard screen. Shows all relays the app has interacted with,
 * their health metrics, and flagged/blocked status. Relays are categorized into
 * Indexer (collapsed by default), Outbox, Inbox, and Other sections. Each relay
 * shows its NIP-11 icon with a color-coded border indicating connection status.
 * Provides a prominent button to open the full Relay Manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayHealthScreen(
    onBackClick: () -> Unit,
    onOpenRelayManager: () -> Unit,
    onOpenRelayDiscovery: () -> Unit = {},
    onOpenRelayLog: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val healthMap by RelayHealthTracker.healthByRelay.collectAsState()
    val flaggedRelays by RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by RelayHealthTracker.blockedRelays.collectAsState()
    val perRelayState by RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()

    // NIP-65 relay categories
    val outboxUrls by Nip65RelayListRepository.writeRelays.collectAsState()
    val inboxUrls by Nip65RelayListRepository.readRelays.collectAsState()

    // NIP-66 relay discovery — use T tag to identify Search/Indexer relays
    val discoveredRelays by Nip66RelayDiscoveryRepository.discoveredRelays.collectAsState()

    // Categorize relays
    data class CategorizedRelays(
        val indexer: List<RelayHealthInfo>,
        val outbox: List<RelayHealthInfo>,
        val inbox: List<RelayHealthInfo>,
        val other: List<RelayHealthInfo>
    )

    val categorized = remember(healthMap, outboxUrls, inboxUrls, discoveredRelays) {
        val all = healthMap.values.toList()
        val outboxSet = outboxUrls.toSet()
        val inboxSet = inboxUrls.toSet()

        // NIP-66: relay URLs that monitors tagged as Search/Indexer
        val nip66SearchUrls = discoveredRelays.values
            .filter { it.isSearch }
            .map { it.url.trim().removeSuffix("/").lowercase() }
            .toSet()

        val sortSpec = compareByDescending<RelayHealthInfo> { it.isFlagged }
            .thenByDescending { it.isBlocked }
            .thenByDescending { it.eventsReceived }
            .thenBy { it.url }

        // NIP-65 outbox/inbox takes priority over NIP-66 type categorization
        val outbox = all.filter { it.url in outboxSet }.sortedWith(sortSpec)
        val inbox = all.filter { it.url in inboxSet && it.url !in outboxSet }.sortedWith(sortSpec)
        val nip65Urls = outboxSet + inboxSet
        val normalizedUrl = { url: String -> url.trim().removeSuffix("/").lowercase() }
        val indexer = all.filter { normalizedUrl(it.url) in nip66SearchUrls && it.url !in nip65Urls }.sortedWith(sortSpec)
        val indexerUrls = indexer.map { it.url }.toSet()
        val categorizedUrls = nip65Urls + indexerUrls
        val other = all.filter { it.url !in categorizedUrls }.sortedWith(sortSpec)

        CategorizedRelays(indexer, outbox, inbox, other)
    }

    val connectedCount = perRelayState.count { it.value == RelayEndpointStatus.Connected || it.value == RelayEndpointStatus.Connecting }
    val totalTracked = healthMap.size

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    // Section expand state — indexer collapsed by default
    var indexerExpanded by remember { mutableStateOf(false) }
    var outboxExpanded by remember { mutableStateOf(true) }
    var inboxExpanded by remember { mutableStateOf(true) }
    var otherExpanded by remember { mutableStateOf(true) }

    // NIP-11 cache
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = "relay health",
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
            // ── Relay Manager CTA ──
            item(key = "relay_manager_card") {
                Surface(
                    onClick = onOpenRelayManager,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Relay Manager",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Configure outbox, inbox & indexing relays",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // ── Discover Relays CTA ──
            item(key = "discover_relays_card") {
                Surface(
                    onClick = onOpenRelayDiscovery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Explore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Discover Relays",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Browse relays by type, latency & features",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // ── Summary Stats Row ──
            item(key = "summary") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill(
                        label = "Connected",
                        value = "$connectedCount",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Tracked",
                        value = "$totalTracked",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Flagged",
                        value = "${flaggedRelays.size}",
                        color = if (flaggedRelays.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Blocked",
                        value = "${blockedRelays.size}",
                        color = if (blockedRelays.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Empty State ──
            if (healthMap.isEmpty()) {
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
                                    Icons.Outlined.Public,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No relay activity yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Health data will appear as relays connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Indexer Relays (collapsed by default) ──
            if (categorized.indexer.isNotEmpty()) {
                item(key = "section_indexer") {
                    SectionHeader(
                        title = "Indexer Relays",
                        count = categorized.indexer.size,
                        icon = Icons.Outlined.Storage,
                        expanded = indexerExpanded,
                        onToggle = { indexerExpanded = !indexerExpanded }
                    )
                }
                if (indexerExpanded) {
                    items(categorized.indexer, key = { "idx_${it.url}" }) { health ->
                        RelayHealthRow(
                            health = health,
                            liveStatus = perRelayState[health.url],
                            nip11 = nip11,
                            onClick = { onOpenRelayLog(health.url) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Outbox Relays ──
            if (categorized.outbox.isNotEmpty()) {
                item(key = "section_outbox") {
                    SectionHeader(
                        title = "Outbox Relays",
                        count = categorized.outbox.size,
                        icon = Icons.Outlined.Upload,
                        expanded = outboxExpanded,
                        onToggle = { outboxExpanded = !outboxExpanded }
                    )
                }
                if (outboxExpanded) {
                    items(categorized.outbox, key = { "out_${it.url}" }) { health ->
                        RelayHealthRow(
                            health = health,
                            liveStatus = perRelayState[health.url],
                            nip11 = nip11,
                            onClick = { onOpenRelayLog(health.url) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Inbox Relays ──
            if (categorized.inbox.isNotEmpty()) {
                item(key = "section_inbox") {
                    SectionHeader(
                        title = "Inbox Relays",
                        count = categorized.inbox.size,
                        icon = Icons.Outlined.Download,
                        expanded = inboxExpanded,
                        onToggle = { inboxExpanded = !inboxExpanded }
                    )
                }
                if (inboxExpanded) {
                    items(categorized.inbox, key = { "in_${it.url}" }) { health ->
                        RelayHealthRow(
                            health = health,
                            liveStatus = perRelayState[health.url],
                            nip11 = nip11,
                            onClick = { onOpenRelayLog(health.url) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            // ── Other Relays ──
            if (categorized.other.isNotEmpty()) {
                item(key = "section_other") {
                    SectionHeader(
                        title = "Other Relays",
                        count = categorized.other.size,
                        icon = Icons.Outlined.Public,
                        expanded = otherExpanded,
                        onToggle = { otherExpanded = !otherExpanded }
                    )
                }
                if (otherExpanded) {
                    items(categorized.other, key = { "oth_${it.url}" }) { health ->
                        RelayHealthRow(
                            health = health,
                            liveStatus = perRelayState[health.url],
                            nip11 = nip11,
                            onClick = { onOpenRelayLog(health.url) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

// ── Collapsible section header ──

@Composable
private fun SectionHeader(
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
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

// ── Pulsing status dot (for connecting relays) ──

@Composable
private fun PulsingDot(
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

// ── NIP-11 relay icon with color-coded border ──

@Composable
private fun RelayIcon(
    relayUrl: String,
    borderColor: Color,
    isConnecting: Boolean,
    nip11: Nip11CacheManager,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var iconUrl by remember(relayUrl) { mutableStateOf(nip11.getCachedRelayInfo(relayUrl)?.icon) }
    LaunchedEffect(relayUrl) {
        if (iconUrl.isNullOrBlank()) {
            nip11.getRelayInfo(relayUrl)?.icon?.let { iconUrl = it }
        }
    }

    // Pulsing border for connecting state
    val borderAlpha = if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "iconPulse_$relayUrl")
        val a by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "iconBorderAlpha"
        )
        a
    } else 1f

    Box(
        modifier = modifier
            .size(size)
            .border(
                width = 2.dp,
                color = borderColor.copy(alpha = borderAlpha),
                shape = CircleShape
            )
            .padding(2.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        if (!iconUrl.isNullOrBlank()) {
            var loadFailed by remember(iconUrl) { mutableStateOf(false) }
            if (!loadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(iconUrl)
                        .crossfade(true)
                        .size(72)
                        .memoryCacheKey("relay_health_icon_$relayUrl")
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = "Relay icon",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    onError = { loadFailed = true }
                )
            } else {
                Icon(
                    Icons.Outlined.Router,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.5f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Icon(
                Icons.Outlined.Router,
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Stat pill (summary row) ──

@Composable
private fun StatPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// ── Relay row with NIP-11 icon ──

@Composable
private fun RelayHealthRow(
    health: RelayHealthInfo,
    liveStatus: RelayEndpointStatus?,
    nip11: Nip11CacheManager,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Connecting means "subscribed, awaiting first event" — treat as connected (green)
    val isLive = liveStatus == RelayEndpointStatus.Connected || liveStatus == RelayEndpointStatus.Connecting

    val surfaceColor = when {
        health.isBlocked -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        health.isFlagged -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surface
    }

    val borderColor = when {
        health.isBlocked -> MaterialTheme.colorScheme.error
        isLive -> MaterialTheme.colorScheme.primary
        liveStatus == RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error
        health.isFlagged -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    // Get NIP-11 display name
    val relayInfo = remember(health.url) { nip11.getCachedRelayInfo(health.url) }
    val displayName = relayInfo?.name?.takeIf { it.isNotBlank() }
        ?: health.url.removePrefix("wss://").removePrefix("ws://")

    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = onClick
                ),
            color = surfaceColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RelayIcon(
                    relayUrl = health.url,
                    borderColor = borderColor,
                    isConnecting = false,
                    nip11 = nip11,
                    size = 36.dp
                )

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (health.eventsReceived > 0) {
                            MetricLabel(
                                icon = Icons.Outlined.Email,
                                text = formatCount(health.eventsReceived)
                            )
                        }
                        if (health.avgLatencyMs > 0) {
                            MetricLabel(
                                icon = Icons.Outlined.Speed,
                                text = "${health.avgLatencyMs}ms"
                            )
                        }
                        if (health.connectionFailures > 0) {
                            MetricLabel(
                                icon = Icons.Outlined.Warning,
                                text = "${health.connectionFailures}",
                                tint = if (health.isFlagged) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Status badge + actions
                if (health.isBlocked) {
                    StatusBadge(
                        text = "Blocked",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { RelayHealthTracker.unblockRelay(health.url) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.LockOpen,
                            contentDescription = "Unblock",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (health.isFlagged) {
                    StatusBadge(
                        text = "Flagged",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = { RelayHealthTracker.blockRelay(health.url) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = "Block",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    IconButton(
                        onClick = { RelayHealthTracker.unflagRelay(health.url) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
        // Subtle divider between rows, indented past the icon
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

// ── Metric label with tiny icon ──

@Composable
private fun MetricLabel(
    icon: ImageVector,
    text: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = tint.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

// ── Status badge pill ──

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ── Format large numbers ──

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}M"
    count >= 1_000 -> "${count / 1_000}K"
    else -> "$count"
}
