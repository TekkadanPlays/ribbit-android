package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.example.views.ui.components.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.views.cache.Nip11CacheManager
import com.example.views.data.RelayInformation
import com.example.views.relay.LogType
import com.example.views.relay.RelayLogBuffer
import com.example.views.relay.RelayLogEntry

/** Types we show by default (connection lifecycle + errors/notices). RECEIVED/EOSE/SENT are hidden unless verbose. */
private val RELEVANT_LOG_TYPES = setOf(
    LogType.CONNECTING,
    LogType.CONNECTED,
    LogType.DISCONNECTED,
    LogType.ERROR,
    LogType.NOTICE
)

/**
 * Relay detail screen: NIP-11 info at top, then a short activity log (connection state and notices only by default).
 * Styled to match app theme; inspired by Amethyst's clean relay page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayLogScreen(
    relayUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val nip11 = remember(context) { Nip11CacheManager.getInstance(context) }
    var relayInfo by remember(relayUrl) { mutableStateOf<RelayInformation?>(nip11.getCachedRelayInfo(relayUrl)) }
    LaunchedEffect(relayUrl) {
        nip11.getRelayInfo(relayUrl).let { info -> relayInfo = info }
    }

    val allLogs by RelayLogBuffer.getLogsForRelay(relayUrl).collectAsState()
    var showVerbose by remember { mutableStateOf(false) }
    val filteredLogs = remember(allLogs, showVerbose) {
        if (showVerbose) allLogs else allLogs.filter { it.type in RELEVANT_LOG_TYPES }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Text(
                            text = relayInfo?.name?.take(24) ?: relayUrl.takeAfterLastSlash(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { RelayLogBuffer.clearLogsForRelay(relayUrl) }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Clear logs"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            item(key = "nip11_info") {
                Nip11InfoBlock(relayUrl = relayUrl, info = relayInfo)
            }
            item(key = "health_stats") {
                val healthMap by com.example.views.relay.RelayHealthTracker.healthByRelay.collectAsState()
                val health = healthMap[relayUrl]
                if (health != null && health.connectionAttempts > 0) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Health",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                HealthStat("Connections", "${health.connectionAttempts}")
                                HealthStat("Failures", "${health.connectionFailures}")
                                HealthStat("Failure Rate", "${(health.failureRate * 100).toInt()}%")
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                HealthStat("Avg Latency", if (health.avgLatencyMs > 0) "${health.avgLatencyMs}ms" else "—")
                                HealthStat("Events", "${health.eventsReceived}")
                                HealthStat("Consec. Fails", "${health.consecutiveFailures}")
                            }
                            if (health.isFlagged || health.isBlocked) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val statusText = when {
                                        health.isBlocked -> "Blocked"
                                        health.isFlagged -> "Flagged — unreliable"
                                        else -> ""
                                    }
                                    val statusColor = if (health.isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = statusColor.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = statusColor,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    if (health.isBlocked) {
                                        TextButton(
                                            onClick = { com.example.views.relay.RelayHealthTracker.unblockRelay(relayUrl) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) { Text("Unblock", style = MaterialTheme.typography.labelSmall) }
                                    } else if (health.isFlagged) {
                                        TextButton(
                                            onClick = { com.example.views.relay.RelayHealthTracker.blockRelay(relayUrl) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) { Text("Block", style = MaterialTheme.typography.labelSmall) }
                                        TextButton(
                                            onClick = { com.example.views.relay.RelayHealthTracker.unflagRelay(relayUrl) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) { Text("Dismiss", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                            if (health.lastError != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Last error: ${health.lastError}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            item(key = "activity_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilterChip(
                        selected = showVerbose,
                        onClick = { showVerbose = !showVerbose },
                        label = { Text("Verbose") }
                    )
                }
            }
            if (filteredLogs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (allLogs.isEmpty()) "No activity yet" else "No connection or notice events",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(filteredLogs, key = { "${it.timestamp}-${it.type}-${it.message.hashCode()}" }) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

private fun String.takeAfterLastSlash(): String = substringAfterLast('/')

@Composable
private fun Nip11InfoBlock(
    relayUrl: String,
    info: RelayInformation?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (info?.icon != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(info.icon).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Router,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info?.name ?: relayUrl,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = relayUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                info?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc.take(120).let { if (desc.length > 120) "$it…" else it },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: RelayLogEntry) {
    val (iconTint, label) = when (entry.type) {
        LogType.CONNECTING -> MaterialTheme.colorScheme.primary to "Connecting"
        LogType.CONNECTED -> MaterialTheme.colorScheme.primary to "Connected"
        LogType.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Disconnected"
        LogType.ERROR -> MaterialTheme.colorScheme.error to "Error"
        LogType.NOTICE -> MaterialTheme.colorScheme.tertiary to "Notice"
        LogType.SENT -> MaterialTheme.colorScheme.secondary to "Sent"
        LogType.RECEIVED -> MaterialTheme.colorScheme.onSurfaceVariant to "Received"
        LogType.EOSE -> MaterialTheme.colorScheme.onSurfaceVariant to "EOSE"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.formattedTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = iconTint.copy(alpha = 0.12f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = iconTint,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HealthStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
