package com.example.cybin.relay

import android.util.Log
import com.example.cybin.core.CybinUtils
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "CybinRelayClient"

// ── Listener interfaces ─────────────────────────────────────────────────────

/** Connection lifecycle events for a single relay. */
interface RelayConnectionListener {
    fun onConnecting(url: String) {}
    fun onConnected(url: String) {}
    fun onDisconnected(url: String) {}
    fun onError(url: String, message: String) {}
    fun onAuth(url: String, challenge: String) {}
    fun onOk(url: String, eventId: String, success: Boolean, message: String) {}
}

/** Subscription event callback. */
fun interface SubscriptionEventListener {
    fun onEvent(event: Event, relay: String)
}

// ── Subscription ─────────────────────────────────────────────────────────────

/**
 * A subscription managed by the relay pool. Tracks which relays it's active on
 * and the filters being used.
 */
class CybinSubscription internal constructor(
    val id: String,
    internal val relayFilters: Map<String, List<Filter>>,
    internal val onEvent: SubscriptionEventListener,
    private val pool: CybinRelayPool,
) {
    /** Close this subscription on all relays. */
    fun close() {
        pool.closeSubscription(this)
    }
}

// ── Single relay connection ──────────────────────────────────────────────────

/**
 * Manages a single WebSocket connection to a Nostr relay using Ktor.
 */
internal class RelayConnection(
    val url: String,
    private val httpClient: HttpClient,
    private val scope: CoroutineScope,
    private val onMessage: (String, NostrProtocol.RelayMessage) -> Unit,
    private val onConnecting: () -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null
    private val sendChannel = Channel<String>(Channel.BUFFERED)
    private var sendJob: Job? = null
    @Volatile var isConnected = false
        private set

    /** Active subscription IDs on this relay → their REQ messages (for renewFilters). */
    internal val activeSubscriptions = ConcurrentHashMap<String, String>()

    fun connect() {
        if (isConnected || session != null) return
        onConnecting()
        scope.launch {
            try {
                val wsUrl = url.replace("wss://", "https://").replace("ws://", "http://")
                val ws = httpClient.webSocketSession(wsUrl)
                session = ws
                isConnected = true
                onConnected()

                // Start send pump
                sendJob = scope.launch {
                    for (msg in sendChannel) {
                        try {
                            ws.send(Frame.Text(msg))
                        } catch (e: Exception) {
                            Log.w(TAG, "[$url] Send failed: ${e.message}")
                            break
                        }
                    }
                }

                // Receive loop
                receiveJob = scope.launch {
                    try {
                        for (frame in ws.incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val parsed = NostrProtocol.parseRelayMessage(text)
                                onMessage(text, parsed)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "[$url] Receive error: ${e.message}")
                        }
                    } finally {
                        handleDisconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$url] Connect failed: ${e.message}")
                isConnected = false
                session = null
                onError(e.message ?: "Connection failed")
            }
        }
    }

    fun send(message: String) {
        if (!isConnected) return
        sendChannel.trySend(message)
    }

    fun disconnect() {
        isConnected = false
        receiveJob?.cancel()
        sendJob?.cancel()
        scope.launch {
            try { session?.close() } catch (_: Exception) {}
            session = null
        }
        activeSubscriptions.clear()
        onDisconnected()
    }

    /** Re-send all active REQ messages (e.g. after NIP-42 auth). */
    fun renewFilters() {
        for ((_, reqMsg) in activeSubscriptions) {
            send(reqMsg)
        }
    }

    private fun handleDisconnect() {
        if (isConnected) {
            isConnected = false
            session = null
            onDisconnected()
        }
    }
}

// ── Relay pool ───────────────────────────────────────────────────────────────

/**
 * Manages connections to multiple Nostr relays and routes subscriptions/events.
 * Replacement for Quartz's NostrClient.
 */
class CybinRelayPool(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val subscriptions = ConcurrentHashMap<String, CybinSubscription>()
    private val listeners = CopyOnWriteArrayList<RelayConnectionListener>()
    private val mutex = Mutex()

    /** Register a connection lifecycle listener. */
    fun addListener(listener: RelayConnectionListener) {
        listeners.add(listener)
    }

    /** Unregister a connection lifecycle listener. */
    fun removeListener(listener: RelayConnectionListener) {
        listeners.remove(listener)
    }

    /** Get or create a connection to a relay URL. */
    private fun getOrCreateConnection(url: String): RelayConnection {
        return connections.getOrPut(url) {
            RelayConnection(
                url = url,
                httpClient = httpClient,
                scope = scope,
                onMessage = { raw, msg -> handleMessage(url, raw, msg) },
                onConnecting = { listeners.forEach { it.onConnecting(url) } },
                onConnected = { listeners.forEach { it.onConnected(url) } },
                onDisconnected = { listeners.forEach { it.onDisconnected(url) } },
                onError = { message -> listeners.forEach { it.onError(url, message) } },
            )
        }
    }

    /** Connect to all relays that have active subscriptions. */
    fun connect() {
        for ((_, conn) in connections) {
            if (!conn.isConnected) {
                conn.connect()
            }
        }
    }

    /** Disconnect from all relays. */
    fun disconnect() {
        for ((_, conn) in connections) {
            conn.disconnect()
        }
        connections.clear()
        subscriptions.clear()
    }

    /**
     * Open a subscription with per-relay filters.
     *
     * @param relayFilters Map of relay URL → list of filters for that relay.
     * @param onEvent Callback for received events (includes source relay URL).
     * @return A [CybinSubscription] handle. Call [CybinSubscription.close] when done.
     */
    fun subscribe(
        relayFilters: Map<String, List<Filter>>,
        onEvent: SubscriptionEventListener,
    ): CybinSubscription {
        val subId = CybinUtils.randomChars(10)
        val sub = CybinSubscription(subId, relayFilters, onEvent, this)
        subscriptions[subId] = sub

        for ((url, filters) in relayFilters) {
            val conn = getOrCreateConnection(url)
            val reqMsg = NostrProtocol.buildReq(subId, *filters.toTypedArray())
            conn.activeSubscriptions[subId] = reqMsg
            if (conn.isConnected) {
                conn.send(reqMsg)
            } else {
                conn.connect()
                // REQ will be sent when connection opens (via pendingSubscriptions in connect callback)
                scope.launch {
                    // Wait for connection, then send REQ
                    var attempts = 0
                    while (!conn.isConnected && attempts < 50) {
                        delay(100)
                        attempts++
                    }
                    if (conn.isConnected) {
                        conn.send(reqMsg)
                    }
                }
            }
        }

        return sub
    }

    /**
     * Open a subscription with the same filters on all specified relays.
     */
    fun subscribe(
        relayUrls: List<String>,
        filters: List<Filter>,
        onEvent: SubscriptionEventListener,
    ): CybinSubscription {
        val relayFilters = relayUrls.associateWith { filters }
        return subscribe(relayFilters, onEvent)
    }

    /**
     * Open a raw subscription with a specific ID (for the main feed subscription
     * managed by the RSM). Caller manages the subscription ID.
     */
    fun openSubscription(
        subscriptionId: String,
        relayFilters: Map<String, List<Filter>>,
        onEvent: SubscriptionEventListener,
    ): CybinSubscription {
        val sub = CybinSubscription(subscriptionId, relayFilters, onEvent, this)
        subscriptions[subscriptionId] = sub

        for ((url, filters) in relayFilters) {
            val conn = getOrCreateConnection(url)
            val reqMsg = NostrProtocol.buildReq(subscriptionId, *filters.toTypedArray())
            conn.activeSubscriptions[subscriptionId] = reqMsg
            if (conn.isConnected) {
                conn.send(reqMsg)
            } else {
                conn.connect()
                scope.launch {
                    var attempts = 0
                    while (!conn.isConnected && attempts < 50) {
                        delay(100)
                        attempts++
                    }
                    if (conn.isConnected) {
                        conn.send(reqMsg)
                    }
                }
            }
        }

        return sub
    }

    /** Close a subscription on all relays. */
    internal fun closeSubscription(sub: CybinSubscription) {
        subscriptions.remove(sub.id)
        val closeMsg = NostrProtocol.buildClose(sub.id)
        for ((url, _) in sub.relayFilters) {
            connections[url]?.let { conn ->
                conn.activeSubscriptions.remove(sub.id)
                conn.send(closeMsg)
            }
        }
    }

    /** Close a subscription by ID. */
    fun closeSubscription(subscriptionId: String) {
        subscriptions.remove(subscriptionId)?.let { closeSubscription(it) }
            ?: run {
                // No tracked subscription — just send CLOSE to all connected relays
                val closeMsg = NostrProtocol.buildClose(subscriptionId)
                for ((_, conn) in connections) {
                    conn.activeSubscriptions.remove(subscriptionId)
                    if (conn.isConnected) conn.send(closeMsg)
                }
            }
    }

    /** Send a signed event to the specified relays. */
    fun send(event: Event, relayUrls: Set<String>) {
        val eventMsg = NostrProtocol.buildEvent(event)
        for (url in relayUrls) {
            val conn = getOrCreateConnection(url)
            if (conn.isConnected) {
                conn.send(eventMsg)
            } else {
                conn.connect()
                scope.launch {
                    var attempts = 0
                    while (!conn.isConnected && attempts < 50) {
                        delay(100)
                        attempts++
                    }
                    if (conn.isConnected) {
                        conn.send(eventMsg)
                    }
                }
            }
        }
    }

    /** Send a raw string message to a specific relay (e.g. AUTH response). */
    fun sendToRelay(url: String, message: String) {
        connections[url]?.send(message)
    }

    /** Re-send all active REQ messages on a specific relay (e.g. after NIP-42 auth). */
    fun renewFilters(url: String) {
        connections[url]?.renewFilters()
    }

    /** Handle an incoming message from a relay. */
    private fun handleMessage(relayUrl: String, raw: String, msg: NostrProtocol.RelayMessage) {
        when (msg) {
            is NostrProtocol.RelayMessage.EventMsg -> {
                val sub = subscriptions[msg.subscriptionId]
                if (sub != null) {
                    sub.onEvent.onEvent(msg.event, relayUrl)
                }
            }
            is NostrProtocol.RelayMessage.EndOfStoredEvents -> {
                // EOSE — subscription stays open for live events
            }
            is NostrProtocol.RelayMessage.Ok -> {
                listeners.forEach { it.onOk(relayUrl, msg.eventId, msg.success, msg.message) }
            }
            is NostrProtocol.RelayMessage.Notice -> {
                Log.d(TAG, "[$relayUrl] NOTICE: ${msg.message}")
            }
            is NostrProtocol.RelayMessage.Auth -> {
                listeners.forEach { it.onAuth(relayUrl, msg.challenge) }
            }
            is NostrProtocol.RelayMessage.Unknown -> {
                Log.d(TAG, "[$relayUrl] Unknown message: ${raw.take(100)}")
            }
        }
    }
}
