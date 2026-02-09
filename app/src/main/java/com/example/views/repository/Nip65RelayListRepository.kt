package com.example.views.repository

import android.util.Log
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.relay.TemporarySubscriptionHandle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NIP-65 Relay List Metadata (kind 10002).
 * Fetches the user's advertised relay list from cache relays and exposes
 * read/write relay URLs. Used for outbox model: read relays are where
 * the user reads from (inbox), write relays are where the user publishes to (outbox).
 *
 * Also provides dedicated indexer relay URLs for counts subscriptions
 * (kind-7 reactions, kind-9735 zap receipts) so we don't burden the
 * main feed relays with counts filters.
 */
object Nip65RelayListRepository {

    private const val TAG = "Nip65RelayListRepo"
    private const val KIND_RELAY_LIST = 10002
    private const val FETCH_TIMEOUT_MS = 8_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Read relays from the user's kind-10002 (where the user reads / inbox). */
    private val _readRelays = MutableStateFlow<List<String>>(emptyList())
    val readRelays: StateFlow<List<String>> = _readRelays.asStateFlow()

    /** Write relays from the user's kind-10002 (where the user publishes / outbox). */
    private val _writeRelays = MutableStateFlow<List<String>>(emptyList())
    val writeRelays: StateFlow<List<String>> = _writeRelays.asStateFlow()

    /** All relays from the user's kind-10002 (read + write + both). */
    private val _allRelays = MutableStateFlow<List<String>>(emptyList())
    val allRelays: StateFlow<List<String>> = _allRelays.asStateFlow()

    /** Whether we've fetched the relay list at least once for the current user. */
    private val _hasFetched = MutableStateFlow(false)
    val hasFetched: StateFlow<Boolean> = _hasFetched.asStateFlow()

    @Volatile
    private var currentPubkey: String? = null
    @Volatile
    private var fetchHandle: TemporarySubscriptionHandle? = null

    /**
     * Well-known indexer/aggregator relays that specialize in reactions and zap receipts.
     * Used as fallback when the user has no kind-10002 or as supplement.
     */
    private val FALLBACK_INDEXER_RELAYS = listOf(
        "wss://relay.nostr.band",
        "wss://cache2.primal.net/v1",
        "wss://relay.damus.io"
    )

    /**
     * Get the best relay URLs for counts subscriptions (kind-7, kind-9735).
     * Prefers the user's NIP-65 read relays (where reactions/zaps are sent to them),
     * supplemented by well-known indexer relays for broader coverage.
     */
    fun getCountsRelayUrls(): List<String> {
        val nip65Read = _readRelays.value
        val combined = (nip65Read + FALLBACK_INDEXER_RELAYS).distinct()
        return if (combined.isNotEmpty()) combined else FALLBACK_INDEXER_RELAYS
    }

    /**
     * Fetch kind-10002 for the given pubkey from cache relays.
     * Call once after login or when the user changes.
     */
    fun fetchRelayList(pubkeyHex: String, cacheRelayUrls: List<String>) {
        if (cacheRelayUrls.isEmpty()) {
            Log.w(TAG, "No cache relays to fetch kind-10002")
            return
        }
        if (pubkeyHex == currentPubkey && _hasFetched.value) {
            Log.d(TAG, "Already fetched kind-10002 for ${pubkeyHex.take(8)}...")
            return
        }
        currentPubkey = pubkeyHex
        fetchHandle?.cancel()

        scope.launch {
            try {
                Log.d(TAG, "Fetching kind-10002 for ${pubkeyHex.take(8)}... from ${cacheRelayUrls.size} cache relays")

                val filter = Filter(
                    kinds = listOf(KIND_RELAY_LIST),
                    authors = listOf(pubkeyHex),
                    limit = 1
                )

                var bestEvent: Event? = null
                val handle = RelayConnectionStateMachine.getInstance()
                    .requestTemporarySubscription(cacheRelayUrls, filter) { event ->
                        if (event.kind == KIND_RELAY_LIST && event.pubKey == pubkeyHex) {
                            val current = bestEvent
                            if (current == null || event.createdAt > current.createdAt) {
                                bestEvent = event
                            }
                        }
                    }
                fetchHandle = handle

                delay(FETCH_TIMEOUT_MS)
                handle.cancel()
                fetchHandle = null

                val event = bestEvent
                if (event != null) {
                    parseRelayListEvent(event)
                    Log.d(TAG, "Kind-10002 parsed: ${_readRelays.value.size} read, ${_writeRelays.value.size} write, ${_allRelays.value.size} total")
                } else {
                    Log.d(TAG, "No kind-10002 found for ${pubkeyHex.take(8)}..., using fallback indexer relays")
                    _readRelays.value = emptyList()
                    _writeRelays.value = emptyList()
                    _allRelays.value = emptyList()
                }
                _hasFetched.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch kind-10002: ${e.message}", e)
                _hasFetched.value = true
            }
        }
    }

    /**
     * Parse a kind-10002 event's "r" tags into read/write relay lists.
     * Tag format: ["r", "wss://relay.example.com", "read"|"write"|""]
     * No marker or empty = both read and write.
     */
    private fun parseRelayListEvent(event: Event) {
        val readUrls = mutableListOf<String>()
        val writeUrls = mutableListOf<String>()
        val allUrls = mutableListOf<String>()

        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "r" && tag[1].isNotBlank()) {
                val url = tag[1].trim()
                val marker = tag.getOrNull(2)?.lowercase()?.trim()
                allUrls.add(url)
                when (marker) {
                    "read" -> readUrls.add(url)
                    "write" -> writeUrls.add(url)
                    else -> {
                        // No marker = both
                        readUrls.add(url)
                        writeUrls.add(url)
                    }
                }
            }
        }

        _readRelays.value = readUrls.distinct()
        _writeRelays.value = writeUrls.distinct()
        _allRelays.value = allUrls.distinct()
    }

    /**
     * Clear state (e.g. on logout or account switch).
     */
    fun clear() {
        fetchHandle?.cancel()
        fetchHandle = null
        currentPubkey = null
        _readRelays.value = emptyList()
        _writeRelays.value = emptyList()
        _allRelays.value = emptyList()
        _hasFetched.value = false
    }
}
