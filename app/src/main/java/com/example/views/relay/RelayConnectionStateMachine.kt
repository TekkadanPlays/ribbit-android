package com.example.views.relay

import android.util.Log
import com.tinder.StateMachine
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClientSubscription
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.IRequestListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vitorpamplona.quartz.utils.RandomInstance
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * States for the relay connection lifecycle.
 * Avoids full disconnect+reconnect when only the feed/subscription changes.
 */
sealed class RelayState {
    object Disconnected : RelayState()
    object Connecting : RelayState()
    object Connected : RelayState()
    object Subscribed : RelayState()
    /** Connection or subscription failed; retry with backoff or user-triggered. */
    data class ConnectFailed(val message: String?) : RelayState()
}

/** Optional error details for UI (e.g. "Connection failed" + message). */
data class ConnectionError(val message: String?, val isConnect: Boolean)

/** Per-relay connection status for UI (e.g. "3/5 relays connected"). */
enum class RelayEndpointStatus { Connecting, Connected, Failed }

/**
 * Handle for a one-off subscription created via requestTemporarySubscription.
 * Call cancel() when done (e.g. thread screen closed, timeout) to avoid duplicate connections.
 */
interface TemporarySubscriptionHandle {
    fun cancel()
}

private class TemporarySubscriptionHandleImpl(private val subscription: NostrClientSubscription) : TemporarySubscriptionHandle {
    override fun cancel() = subscription.destroy()
}

private object NoOpTemporaryHandle : TemporarySubscriptionHandle {
    override fun cancel() {}
}

/** Current subscription config (relayUrls + kind1Filter + countsNoteIds) for idempotent feed change and UI. */
data class CurrentSubscription(
    val relayUrls: List<String>,
    val kind1Filter: Filter?,
    val countsNoteIds: Set<String> = emptySet()
)

/**
 * Events that drive the relay connection state machine.
 */
sealed class RelayEvent {
    data class ConnectRequested(val relayUrls: List<String>) : RelayEvent()
    object Connected : RelayEvent()
    data class ConnectFailed(val message: String?) : RelayEvent()
    object RetryRequested : RelayEvent()
    data class FeedChangeRequested(
        val relayUrls: List<String>,
        val customFilter: Filter? = null,
        val customOnEvent: ((Event) -> Unit)? = null,
        /** When set, used for kind-1 in default path (relay-aware); e.g. authors=followList for Following feed. */
        val kind1Filter: Filter? = null,
        /** When non-empty, subscribe to kind-7 and kind-9735 for these note IDs (reactions/zaps). */
        val countsNoteIds: Set<String>? = null
    ) : RelayEvent()
    object DisconnectRequested : RelayEvent()
}

/**
 * Side effects executed on transition (connect client, update subscription, disconnect).
 */
sealed class RelaySideEffect {
    data class ConnectRelays(val relayUrls: List<String>) : RelaySideEffect()
    object OnConnected : RelaySideEffect()
    object ScheduleRetry : RelaySideEffect()
    data class UpdateSubscription(
        val relayUrls: List<String>,
        val customFilter: Filter? = null,
        val customOnEvent: ((Event) -> Unit)? = null,
        val kind1Filter: Filter? = null,
        val countsNoteIds: Set<String>? = null
    ) : RelaySideEffect()
    object DisconnectClient : RelaySideEffect()
}

/**
 * Single shared component that owns one NostrClient and drives it with a Tinder State Machine.
 * On feed/relay switch we only update the subscription (no disconnect), so re-connectivity is fast.
 *
 * ## EOSE (End of Stored Events)
 * Relays send EOSE when they have finished sending stored events for a subscription. EOSE does
 * **not** close the connection or the subscription: the WebSocket stays open and the relay
 * continues to deliver live events. We do not stop retrieval on EOSE; that would be incorrect.
 *
 * ## Retrieval lifecycle (avoid careless start/stop)
 * - **Startup**: NotesRepository calls [requestFeedChange] (or connect then feed change) once relays
 *   and follow filter are known; we open one REQ subscription across all user relays.
 * - **Refresh**: Pull-to-refresh re-applies the same subscription (idempotent) and NotesRepository
 *   merges pending notes; we do not disconnect.
 * - **Wake/Resume**: [requestReconnectOnResume] re-applies the subscription from
 *   [resumeSubscriptionProvider] (e.g. NotesRepository) so Following filter and relay set are
 *   preserved without reconnecting from scratch.
 * - **User config**: Relay or follow filter changes call [requestFeedChange]; we update the
 *   subscription only (no full disconnect).
 * - **Organic Nostr**: The client sends REQ; each relay responds with events then EOSE per sub_id.
 *   We rely on Quartz's NostrClient for per-relay sockets; aggregate state is [RelayState].
 *
 * ## Per-relay awareness
 * We expose [perRelayState] (Connecting/Connected/Failed per URL) for UI (e.g. "3/5 relays").
 *
 * ## Subscription ownership
 * The primary main-feed subscription is owned by [NotesRepository] (singleton). Only NotesRepository
 * should call [requestFeedChange] for the main feed. TopicsRepository may call
 * requestFeedChange(relayUrls, getCurrentKind1Filter()) to preserve the kind-1 filter when opening
 * Topics. One-off or long-lived auxiliary subscriptions (thread replies, notifications, profile/contact
 * fetches) use [requestTemporarySubscription] and do not replace the main subscription.
 */
class RelayConnectionStateMachine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val okHttpClient = OkHttpClient.Builder().build()
    private val socketBuilder = BasicOkHttpWebSocket.Builder { _ -> okHttpClient }
    val nostrClient: NostrClient = NostrClient(socketBuilder, scope)

    private var mainFeedSubscription: NostrClientSubscription? = null
    private var currentSubId: String? = null

    private val _state = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _connectionError = MutableStateFlow<ConnectionError?>(null)
    val connectionError: StateFlow<ConnectionError?> = _connectionError.asStateFlow()

    /** Stored when connecting/subscribing so RetryRequested can reuse. */
    @Volatile var pendingRelayUrlsForRetry: List<String> = emptyList()
        private set

    @Volatile private var pendingKind1FilterForRetry: Filter? = null
    @Volatile private var pendingCountsNoteIdsForRetry: Set<String> = emptySet()
    @Volatile private var pendingWasSubscribe = false

    private var retryAttempt = 0

    /** Current subscription (relayUrls + kind1Filter + countsNoteIds) for idempotent feed change and UI. */
    private val _currentSubscription = MutableStateFlow(CurrentSubscription(emptyList(), null, emptySet()))
    val currentSubscription: StateFlow<CurrentSubscription> = _currentSubscription.asStateFlow()

    /** Per-relay status for UI (e.g. "3/5 relays"). Updated when we subscribe (Connecting) and when events arrive (Connected); Failed on ConnectFailed. */
    private val _perRelayState = MutableStateFlow<Map<String, RelayEndpointStatus>>(emptyMap())
    val perRelayState: StateFlow<Map<String, RelayEndpointStatus>> = _perRelayState.asStateFlow()

    private val stateMachine = StateMachine.create<RelayState, RelayEvent, RelaySideEffect> {
        initialState(RelayState.Disconnected)

        state<RelayState.Disconnected> {
            on<RelayEvent.ConnectRequested> {
                transitionTo(RelayState.Connecting, RelaySideEffect.ConnectRelays(it.relayUrls))
            }
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter, it.countsNoteIds)
                )
            }
        }

        state<RelayState.Connecting> {
            on<RelayEvent.Connected> {
                transitionTo(RelayState.Connected, RelaySideEffect.OnConnected)
            }
            on<RelayEvent.ConnectFailed> {
                transitionTo(RelayState.ConnectFailed(it.message), RelaySideEffect.ScheduleRetry)
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.ConnectFailed> {
            on<RelayEvent.RetryRequested> {
                val urls = pendingRelayUrlsForRetry
                if (urls.isEmpty()) {
                    transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
                } else if (pendingWasSubscribe) {
                    transitionTo(
                        RelayState.Subscribed,
                        RelaySideEffect.UpdateSubscription(urls, null, null, pendingKind1FilterForRetry, pendingCountsNoteIdsForRetry)
                    )
                } else {
                    transitionTo(RelayState.Connecting, RelaySideEffect.ConnectRelays(urls))
                }
            }
            on<RelayEvent.FeedChangeRequested> {
                // Retry by going to Subscribed with the requested config
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter, it.countsNoteIds)
                )
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.Connected> {
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter, it.countsNoteIds)
                )
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.Subscribed> {
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter, it.countsNoteIds)
                )
            }
            on<RelayEvent.ConnectFailed> {
                transitionTo(RelayState.ConnectFailed(it.message), RelaySideEffect.ScheduleRetry)
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        onTransition { transition ->
            if (transition is StateMachine.Transition.Valid) {
                _state.value = transition.toState
                when (val effect = transition.sideEffect) {
                    is RelaySideEffect.ConnectRelays -> {
                        pendingRelayUrlsForRetry = effect.relayUrls
                        pendingWasSubscribe = false
                        executeConnectRelays(effect.relayUrls)
                    }
                    is RelaySideEffect.OnConnected -> {
                        _connectionError.value = null
                        retryAttempt = 0
                    }
                    is RelaySideEffect.ScheduleRetry -> {
                        _perRelayState.value = _perRelayState.value.mapValues { RelayEndpointStatus.Failed }
                        executeScheduleRetry()
                    }
                    is RelaySideEffect.UpdateSubscription -> {
                        pendingRelayUrlsForRetry = effect.relayUrls
                        pendingKind1FilterForRetry = effect.kind1Filter
                        pendingCountsNoteIdsForRetry = effect.countsNoteIds ?: emptySet()
                        pendingWasSubscribe = true
                        executeUpdateSubscription(
                            effect.relayUrls,
                            effect.customFilter,
                            effect.customOnEvent,
                            effect.kind1Filter,
                            effect.countsNoteIds
                        )
                    }
                    is RelaySideEffect.DisconnectClient -> {
                        pendingRelayUrlsForRetry = emptyList()
                        retryAttempt = 0
                        _connectionError.value = null
                        _perRelayState.value = emptyMap()
                        executeDisconnect()
                    }
                    null -> { }
                }
            }
        }
    }

    private fun executeConnectRelays(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        relayUrls.forEach { RelayLogBuffer.logConnecting(it) }
        scope.launch {
            try {
                // Bootstrap subscription so NostrClient adds relays to pool, then connect
                val bootstrapFilter = Filter(kinds = listOf(1), limit = 1)
                val relayFilters = relayUrls.associate { NormalizedRelayUrl(it) to listOf(bootstrapFilter) }
                mainFeedSubscription?.destroy()
                mainFeedSubscription = NostrClientSubscription(
                    client = nostrClient,
                    filter = { relayFilters },
                    onEvent = { }
                )
                nostrClient.connect()
                delay(400)
                _connectionError.value = null
                retryAttempt = 0
                relayUrls.forEach { RelayLogBuffer.logConnected(it) }
                stateMachine.transition(RelayEvent.Connected)
            } catch (e: Exception) {
                Log.e(TAG, "Connect relays failed: ${e.message}", e)
                relayUrls.forEach { RelayLogBuffer.logError(it, e.message ?: "Connection failed") }
                _connectionError.value = ConnectionError(e.message, true)
                stateMachine.transition(RelayEvent.ConnectFailed(e.message))
            }
        }
    }

    private fun executeScheduleRetry() {
        if (pendingRelayUrlsForRetry.isEmpty() || retryAttempt >= Companion.MAX_RETRIES) {
            Log.d(TAG, "No retry: empty pending or max retries ($retryAttempt)")
            return
        }
        retryAttempt++
        val delayMs = when (retryAttempt) {
            1 -> Companion.RETRY_DELAY_MS_FIRST
            2 -> Companion.RETRY_DELAY_MS_SECOND
            else -> Companion.RETRY_DELAY_MS_SECOND
        }
        Log.d(TAG, "Scheduling retry $retryAttempt in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            stateMachine.transition(RelayEvent.RetryRequested)
        }
    }

    private fun executeUpdateSubscription(
        relayUrls: List<String>,
        customFilter: Filter? = null,
        customOnEvent: ((Event) -> Unit)? = null,
        kind1Filter: Filter? = null,
        countsNoteIds: Set<String>? = null
    ) {
        scope.launch {
            try {
                currentSubId?.let { nostrClient.close(it) }
                currentSubId = null
                mainFeedSubscription?.destroy()
                mainFeedSubscription = null
                if (relayUrls.isEmpty()) return@launch
                _perRelayState.value = relayUrls.associateWith { RelayEndpointStatus.Connecting }
                val relayFilters: Map<NormalizedRelayUrl, List<Filter>>
                if (customFilter != null && customOnEvent != null) {
                    relayFilters = relayUrls.associate { NormalizedRelayUrl(it) to listOf(customFilter) }
                    val onEvent = customOnEvent
                    mainFeedSubscription = NostrClientSubscription(
                        client = nostrClient,
                        filter = { relayFilters },
                        onEvent = onEvent
                    )
                    currentSubscriptionRelayUrls = relayUrls
                    currentKind1Filter = null
                    currentCountsNoteIds = emptySet()
                    _currentSubscription.value = CurrentSubscription(relayUrls, null, emptySet())
                    Log.d(TAG, "Subscription updated for ${relayUrls.size} relays (custom filter)")
                } else {
                    val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400 * 7
                    val filterKind1 = kind1Filter ?: Filter(
                        kinds = listOf(1),
                        limit = GLOBAL_FEED_LIMIT,
                        since = sevenDaysAgo
                    )
                    val filterKind11 = Filter(kinds = listOf(11), limit = 100, since = sevenDaysAgo)
                    // Counts (kind-7, kind-9735) are now handled by NoteCountsRepository via
                    // a dedicated temporary subscription to NIP-65 indexer relays. We no longer
                    // piggyback counts filters on the main feed subscription.
                    val countsIds = countsNoteIds?.takeIf { it.isNotEmpty() } ?: emptySet()
                    val allFilters = listOf(filterKind1, filterKind11)
                    relayFilters = relayUrls.associate { NormalizedRelayUrl(it) to allFilters }
                    currentSubId = RandomInstance.randomChars(10)
                    val subId = currentSubId!!
                    nostrClient.openReqSubscription(subId, relayFilters, object : IRequestListener {
                        override fun onEvent(
                            event: Event,
                            isLive: Boolean,
                            relay: NormalizedRelayUrl,
                            forFilters: List<Filter>?,
                        ) {
                            _perRelayState.value = _perRelayState.value + (relay.url to RelayEndpointStatus.Connected)
                            when (event.kind) {
                                1 -> {
                                    onKind1WithRelay?.invoke(event, relay.url)
                                    if (countsIds.isNotEmpty()) {
                                        val rootId = com.example.views.utils.Nip10ReplyDetector.getRootId(event)
                                        if (rootId != null && rootId in countsIds) {
                                            com.example.views.repository.ReplyCountCache.incrementForReply(event.id, rootId)
                                        }
                                    }
                                }
                                11 -> onKind11?.invoke(event)
                                7, 9735 -> com.example.views.repository.NoteCountsRepository.onCountsEvent(event)
                                else -> { }
                            }
                        }
                    })
                    val mode = if (kind1Filter != null) "following (authors filter)" else "global"
                    Log.d(TAG, "Subscription updated for ${relayUrls.size} relays (kind-1 + kind-11${if (countsIds.isNotEmpty()) " + counts(${countsIds.size})" else ""}, $mode)")
                }
                currentSubscriptionRelayUrls = relayUrls
                currentKind1Filter = kind1Filter
                currentCountsNoteIds = countsNoteIds?.toSet() ?: emptySet()
                _currentSubscription.value = CurrentSubscription(relayUrls, kind1Filter, currentCountsNoteIds)
                _connectionError.value = null
                retryAttempt = 0
                nostrClient.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Update subscription failed: ${e.message}", e)
                _connectionError.value = ConnectionError(e.message, false)
                _perRelayState.value = _perRelayState.value.mapValues { RelayEndpointStatus.Failed }
                stateMachine.transition(RelayEvent.ConnectFailed(e.message))
            }
        }
    }

    /** Last relay set and kind-1 filter used for subscription; preserved so Topics does not overwrite Following mode. */
    @Volatile private var currentSubscriptionRelayUrls: List<String> = emptyList()
    @Volatile private var currentKind1Filter: Filter? = null
    @Volatile private var currentCountsNoteIds: Set<String> = emptySet()

    /** Current kind-1 filter (e.g. authors for Following). Use when setting subscription from Topics to preserve feed mode. */
    fun getCurrentKind1Filter(): Filter? = currentKind1Filter

    @Volatile private var onKind1WithRelay: ((Event, String) -> Unit)? = null
    @Volatile private var onKind11: ((Event) -> Unit)? = null

    fun registerKind1Handler(handler: (Event, String) -> Unit) {
        onKind1WithRelay = handler
    }

    fun registerKind11Handler(handler: (Event) -> Unit) {
        onKind11 = handler
    }

    private fun executeDisconnect() {
        scope.launch {
            try {
                currentSubId?.let { nostrClient.close(it) }
                currentSubId = null
                mainFeedSubscription?.destroy()
                mainFeedSubscription = null
                currentSubscriptionRelayUrls = emptyList()
                currentKind1Filter = null
                currentCountsNoteIds = emptySet()
                _currentSubscription.value = CurrentSubscription(emptyList(), null, emptySet())
                _perRelayState.value = emptyMap()
                nostrClient.disconnect()
                Log.d(TAG, "Disconnected from all relays")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed: ${e.message}", e)
            }
        }
    }

    /** Request connection to relays (moves to Connecting then Connected). */
    fun requestConnect(relayUrls: List<String>) {
        stateMachine.transition(RelayEvent.ConnectRequested(relayUrls))
    }

    /** Request feed/subscription with combined kind-1 + kind-11; dispatches to registered handlers. */
    fun requestFeedChange(relayUrls: List<String>) {
        requestFeedChange(relayUrls, null)
    }

    /**
     * Request feed with optional kind-1 filter (e.g. authors=followList for Following).
     * Uses relay-aware subscription so note.relayUrl is set. Pass null for global feed.
     * [countsNoteIds] when non-empty adds kind-7 and kind-9735 filters for those note IDs.
     * Skips transition if subscription is unchanged (idempotent).
     */
    fun requestFeedChange(relayUrls: List<String>, kind1Filter: Filter?, countsNoteIds: Set<String>? = null) {
        if (relayUrls.isEmpty()) return
        val cur = currentSubscriptionRelayUrls
        val counts = countsNoteIds?.toSet() ?: emptySet()
        if (cur.sorted() == relayUrls.sorted() && kind1FiltersEqual(kind1Filter, currentKind1Filter) && currentCountsNoteIds == counts) {
            Log.d(TAG, "Subscription unchanged (${relayUrls.size} relays), skipping")
            return
        }
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, null, null, kind1Filter, counts.takeIf { it.isNotEmpty() }))
    }

    /**
     * Update only the counts subscription (kind-7, kind-9735) for the given note IDs.
     * Uses current relay URLs and kind-1 filter. Call when feed note IDs change (e.g. from NoteCountsRepository).
     */
    fun requestFeedChangeWithCounts(countsNoteIds: Set<String>) {
        val cur = _currentSubscription.value
        if (cur.relayUrls.isEmpty()) return
        requestFeedChange(cur.relayUrls, cur.kind1Filter, countsNoteIds)
    }

    /**
     * Compares kind-1 feed intent for idempotence. We intentionally do not compare [Filter.since]:
     * returning to Home rebuilds the filter with a new sevenDaysAgo, which would otherwise force
     * a reconnect every time. Same authors + limit = same feed; no need to tear down the subscription.
     */
    private fun kind1FiltersEqual(a: Filter?, b: Filter?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.authors == b.authors && a.limit == b.limit
    }

    /** Request feed with custom filter (e.g. author notes); replaces combined subscription while active. */
    fun requestFeedChange(relayUrls: List<String>, filter: Filter, onEvent: (Event) -> Unit) {
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, filter, onEvent, null, null))
    }

    /** Request full disconnect (e.g. on screen exit or app shutdown). */
    fun requestDisconnect() {
        stateMachine.transition(RelayEvent.DisconnectRequested)
    }

    /** User- or pull-to-refresh–triggered retry when in ConnectFailed. Resets backoff and sends RetryRequested. */
    fun requestRetry() {
        if (_state.value is RelayState.ConnectFailed && pendingRelayUrlsForRetry.isNotEmpty()) {
            retryAttempt = 0
            stateMachine.transition(RelayEvent.RetryRequested)
        }
    }

    /**
     * When set, used on resume to get (relayUrls, kind1Filter) from the source of truth (e.g. NotesRepository)
     * so the Following filter is never lost. If null, falls back to stored _currentSubscription.
     */
    @Volatile
    var resumeSubscriptionProvider: (() -> Pair<List<String>, Filter?>)? = null

    /**
     * Re-apply current subscription when app is resumed (e.g. after screen lock or switching apps).
     * Uses resumeSubscriptionProvider when set so the Following filter always comes from the repo (no bleed).
     * Bypasses idempotent check so connection and note aggregation resume even if params are unchanged.
     */
    fun requestReconnectOnResume() {
        val cur = _currentSubscription.value
        val (relayUrls, kind1Filter) = resumeSubscriptionProvider?.invoke()?.takeIf { it.first.isNotEmpty() }
            ?: (cur.relayUrls to cur.kind1Filter)
        if (relayUrls.isEmpty()) return
        Log.d(TAG, "App resumed: re-applying subscription to ${relayUrls.size} relays (following=${kind1Filter != null})")
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, null, null, kind1Filter, null))
    }

    /**
     * One-off subscription using the shared NostrClient. Use for thread replies so we don't open
     * duplicate connections to the same relays. Call handle.cancel() when done (thread closed, timeout).
     */
    fun requestTemporarySubscription(
        relayUrls: List<String>,
        filter: Filter,
        onEvent: (Event) -> Unit
    ): TemporarySubscriptionHandle {
        if (relayUrls.isEmpty()) return NoOpTemporaryHandle
        val relayFilters = relayUrls.associate { NormalizedRelayUrl(it) to listOf(filter) }
        val subscription = NostrClientSubscription(
            client = nostrClient,
            filter = { relayFilters },
            onEvent = onEvent
        )
        nostrClient.connect()
        return TemporarySubscriptionHandleImpl(subscription)
    }

    /**
     * One-off subscription with multiple filters (e.g. kind-7 + kind-9735 for counts).
     * All filters are sent to every relay in the list.
     */
    fun requestTemporarySubscription(
        relayUrls: List<String>,
        filters: List<Filter>,
        onEvent: (Event) -> Unit
    ): TemporarySubscriptionHandle {
        if (relayUrls.isEmpty() || filters.isEmpty()) return NoOpTemporaryHandle
        val relayFilters = relayUrls.associate { NormalizedRelayUrl(it) to filters }
        val subscription = NostrClientSubscription(
            client = nostrClient,
            filter = { relayFilters },
            onEvent = onEvent
        )
        nostrClient.connect()
        return TemporarySubscriptionHandleImpl(subscription)
    }

    companion object {
        private const val TAG = "RelayConnectionStateMachine"
        /** Default kind-1 limit for global feed; higher = more notes, slightly slower first load. */
        private const val GLOBAL_FEED_LIMIT = 800
        private const val RETRY_DELAY_MS_FIRST = 2_000L
        private const val RETRY_DELAY_MS_SECOND = 5_000L
        private const val MAX_RETRIES = 3

        /** Single shared instance for the app so all feed screens use one NostrClient. */
        @Volatile
        private var instance: RelayConnectionStateMachine? = null
        fun getInstance(): RelayConnectionStateMachine =
            instance ?: synchronized(this) { instance ?: RelayConnectionStateMachine().also { instance = it } }
    }
}
