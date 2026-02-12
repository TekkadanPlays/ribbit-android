package com.example.views.repository

import android.content.Context
import android.util.Log
import com.example.views.data.Author
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory cache for NIP-01 kind-0 profile metadata. Fetches from cache relays via a dedicated
 * NostrClient so the main feed subscription is not disrupted. Repositories resolve Author from
 * this cache and request fetches on miss; when kind-0 arrives we emit so UI can refresh.
 * Bounded with LRU eviction; supports trim for memory pressure.
 *
 * Disk persistence: call [init] from MainActivity.onCreate so profiles survive process death.
 * On cold start, profiles are loaded from SharedPreferences before relay fetches begin, so
 * cached notes render with display names and avatars immediately.
 */
class ProfileMetadataCache {

    companion object {
        @Volatile
        private var instance: ProfileMetadataCache? = null
        fun getInstance(): ProfileMetadataCache =
            instance ?: synchronized(this) { instance ?: ProfileMetadataCache().also { instance = it } }
        internal const val TAG = "ProfileMetadataCache"
        internal const val KIND0_FETCH_TIMEOUT_MS = 5000L
        /** Longer timeout for bulk follow-list profile fetches so slow relays can respond. */
        private const val KIND0_BULK_FETCH_TIMEOUT_MS = 12_000L
        /** Above this many pubkeys we use the bulk timeout. */
        private const val BULK_THRESHOLD = 50
        private const val MAX_ENTRIES = 2000

        /** When over this size, we evict even pinned (follow-list) profiles to avoid unbounded growth. */
        private const val HARD_CAP = 3000

        /** Size to trim to when app is in background. */
        const val TRIM_SIZE_BACKGROUND = 800

        private const val PROFILE_CACHE_PREFS = "profile_metadata_cache"
        private const val PROFILE_CACHE_KEY = "profiles_json"
        private const val PROFILE_CREATED_AT_KEY = "profiles_created_at_json"
        private const val OUTBOX_RELAYS_KEY = "outbox_relays_json"
        private const val PROFILE_FETCHED_AT_KEY = "profiles_fetched_at_json"
        /** Max profiles saved to disk. */
        private const val DISK_CACHE_MAX = 1500
        /** Debounce delay before writing profile cache to disk after an update. */
        private const val DISK_SAVE_DEBOUNCE_MS = 2000L
        /** Profiles older than this are considered stale and will be re-fetched when encountered. */
        private const val PROFILE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    /** Shared OkHttp client for direct WebSocket kind-0 fetches. */
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Persistent WebSocket pool for kind-0 fetches ────────────────────────
    /** Map of relay URL → open WebSocket. Connections are reused across batches. */
    private val wsPool = ConcurrentHashMap<String, WebSocket>()
    /** Track which relay connections are ready (onOpen received). */
    private val wsReady = ConcurrentHashMap<String, Boolean>()
    /** Current subscription ID per relay so we can CLOSE before sending a new REQ. */
    private val wsCurrentSubId = ConcurrentHashMap<String, String>()

    // ── Internal batching: accumulate pubkeys from all callers into one batch ──
    private val internalPendingPubkeys = Collections.synchronizedSet(HashSet<String>())
    private var internalPendingRelays = listOf<String>()
    private var internalBatchScheduleJob: Job? = null
    private var internalBatchFetchJob: Job? = null
    private val INTERNAL_BATCH_DELAY_MS = 400L
    private val INTERNAL_BATCH_SIZE = 80
    /** Shared counter for profiles received across all pool connections. */
    private val poolReceived = AtomicInteger(0)

    /** Application context for SharedPreferences persistence. Set via [init]. */
    @Volatile private var appContext: Context? = null
    /** Debounced disk save job. */
    private var diskSaveJob: Job? = null

    /** Pubkeys (lowercase) that should not be evicted by LRU when under HARD_CAP (e.g. follow list). */
    private val pinnedPubkeys = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Set pubkeys to protect from eviction (e.g. people the user follows). Pass null or empty to clear.
     * Pinned entries are still evicted when cache size exceeds [HARD_CAP].
     */
    fun setPinnedPubkeys(pubkeys: Set<String>?) {
        val normalized = pubkeys?.map { it.lowercase() }?.toSet() ?: emptySet()
        pinnedPubkeys.clear()
        pinnedPubkeys.addAll(normalized)
    }

    // LRU order for eviction; actual data in cache + profileCreatedAt.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Author>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Author>): Boolean {
                if (size <= MAX_ENTRIES) return false
                if (eldest.key in pinnedPubkeys && size <= HARD_CAP) return false
                profileCreatedAt.remove(eldest.key)
                return true
            }
        }
    )
    /** Store createdAt per pubkey so we only keep the latest kind-0 when multiple relays send profiles. */
    private val profileCreatedAt = ConcurrentHashMap<String, Long>()
    /** Wall-clock time (System.currentTimeMillis) when each profile was last fetched from network.
     *  Used for TTL-based stale refresh: profiles older than PROFILE_TTL_MS are re-requested. */
    private val profileFetchedAt = ConcurrentHashMap<String, Long>()
    /** Per-user outbox relay URLs (NIP-65 write relays). Persisted alongside profiles. */
    private val outboxRelayCache = ConcurrentHashMap<String, List<String>>()
    /** Large buffer so bulk loads (e.g. debug "Fetch all") don't drop emissions before coalescer can apply to feed. */
    private val _profileUpdated = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 2048)
    val profileUpdated: SharedFlow<String> = _profileUpdated.asSharedFlow()

    /** Flips to true once the disk cache has been restored. UI components can key on this to rebuild
     *  content that was rendered with placeholder authors before the disk cache was ready. */
    private val _diskCacheRestored = MutableStateFlow(false)
    val diskCacheRestored: StateFlow<Boolean> = _diskCacheRestored.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private fun normalizeKey(pubkey: String): String = pubkey.lowercase()

    /**
     * Sanitize kind-0 string: trim, strip control/non-printable chars, collapse whitespace.
     * Returns null if result is blank or only "null" literal.
     */
    private fun sanitizeKind0String(s: String?, maxLen: Int = Int.MAX_VALUE): String? {
        if (s == null) return null
        val trimmed = s.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        val noControl = trimmed
            .filter { c -> c.code >= 32 && c.code != 0xFFFD }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noControl.isBlank()) return null
        return noControl.take(maxLen)
    }

    fun getAuthor(pubkey: String): Author? = cache[normalizeKey(pubkey)]

    /** Check if a cached profile is stale (older than PROFILE_TTL_MS). Returns true if missing or stale. */
    fun isProfileStale(pubkey: String): Boolean {
        val key = normalizeKey(pubkey)
        if (cache[key] == null) return true
        val fetchedAt = profileFetchedAt[key] ?: return true
        return (System.currentTimeMillis() - fetchedAt) > PROFILE_TTL_MS
    }

    /** Store outbox relay URLs for a user (NIP-65 write relays). */
    fun setOutboxRelays(pubkey: String, relayUrls: List<String>) {
        outboxRelayCache[normalizeKey(pubkey)] = relayUrls
        scheduleDiskSave()
    }

    /** Get cached outbox relay URLs for a user. Returns empty list if not cached. */
    fun getOutboxRelays(pubkey: String): List<String> =
        outboxRelayCache[normalizeKey(pubkey)] ?: emptyList()

    /**
     * Resolve author: cached or placeholder. Call requestProfiles(listOf(pubkey)) to fetch if missing.
     * Uses lowercase pubkey for cache key so feed updates match when kind-0 arrives (relays may send different casing).
     */
    fun resolveAuthor(pubkey: String): Author {
        val key = normalizeKey(pubkey)
        return cache[key] ?: Author(
            id = key,
            username = pubkey.take(8) + "...",
            displayName = pubkey.take(8) + "...",
            avatarUrl = null,
            isVerified = false
        )
    }

    /**
     * Put profile only if we don't have one or this event is newer (by createdAt).
     * Prioritizes the latest kind-0 when multiple relays send profile events.
     * Stores and emits under lowercase pubkey so feed update matches all notes for this user.
     */
    fun putProfileIfNewer(pubkey: String, author: Author?, createdAt: Long): Boolean {
        if (author == null) return false
        val key = normalizeKey(pubkey)
        val existingAt = profileCreatedAt[key] ?: 0L
        if (createdAt < existingAt) return false
        cache[key] = author
        profileCreatedAt[key] = createdAt
        profileFetchedAt[key] = System.currentTimeMillis()
        scope.launch {
            _profileUpdated.emit(key)
        }
        scheduleDiskSave()
        return true
    }

    fun putProfile(pubkey: String, author: Author) {
        val key = normalizeKey(pubkey)
        cache[key] = author
        profileCreatedAt[key] = Long.MAX_VALUE
        profileFetchedAt[key] = System.currentTimeMillis()
        scope.launch {
            _profileUpdated.emit(key)
        }
        scheduleDiskSave()
    }

    /**
     * Public API: accumulate pubkeys into an internal batch and schedule a debounced fetch.
     * All callers (NotesRepository, Kind1RepliesRepository, NotificationsRepository, etc.)
     * funnel through here. The actual WebSocket fetch fires once after the debounce settles,
     * preventing socket exhaustion from dozens of 1-pubkey callers each opening 6 connections.
     */
    suspend fun requestProfiles(pubkeys: List<String>, cacheRelayUrls: List<String>) {
        if (pubkeys.isEmpty() || cacheRelayUrls.isEmpty()) return
        // Fetch profiles that are missing OR stale (older than TTL)
        val needed = pubkeys.filter { pk ->
            val key = normalizeKey(pk)
            cache[key] == null || isProfileStale(pk)
        }
        if (needed.isEmpty()) return

        internalPendingPubkeys.addAll(needed.map { normalizeKey(it) })
        if (cacheRelayUrls.isNotEmpty()) internalPendingRelays = cacheRelayUrls

        scheduleInternalBatch()
    }

    /**
     * Schedule the internal batch fetch. Resets the debounce timer on each call so pubkeys
     * accumulate. In-flight fetches are never cancelled.
     */
    private fun scheduleInternalBatch() {
        internalBatchScheduleJob?.cancel()
        internalBatchScheduleJob = scope.launch {
            delay(INTERNAL_BATCH_DELAY_MS)
            // Wait for any in-flight fetch to finish
            internalBatchFetchJob?.join()
            internalBatchFetchJob = scope.launch {
                while (internalPendingPubkeys.isNotEmpty()) {
                    val batch = synchronized(internalPendingPubkeys) {
                        internalPendingPubkeys.take(INTERNAL_BATCH_SIZE).also { internalPendingPubkeys.removeAll(it.toSet()) }
                    }
                    if (batch.isEmpty()) break
                    val relays = internalPendingRelays
                    if (relays.isEmpty()) {
                        internalPendingPubkeys.addAll(batch)
                        break
                    }
                    fetchProfilesBatch(batch, relays)
                    if (internalPendingPubkeys.isNotEmpty()) delay(300)
                }
                internalBatchFetchJob = null
            }
            internalBatchScheduleJob = null
        }
    }

    /**
     * Actual WebSocket fetch for a batch of pubkeys using the persistent connection pool.
     */
    private suspend fun fetchProfilesBatch(pubkeys: List<String>, cacheRelayUrls: List<String>) {
        val uncached = pubkeys.filter { cache[normalizeKey(it)] == null }
        if (uncached.isEmpty()) return

        val fallbackRelays = listOf("wss://purplepag.es", "wss://relay.damus.io", "wss://nos.lol")
        val allRelays = (cacheRelayUrls + fallbackRelays).distinct()

        Log.d(TAG, "Fetching kind-0 for ${uncached.size} pubkeys from ${allRelays.size} relays (pool)")
        val beforeCount = poolReceived.get()

        val subId = "prof_" + System.currentTimeMillis().toString(36)
        val filterObj = JSONObject().apply {
            put("kinds", JSONArray().apply { put(0) })
            put("authors", JSONArray().apply { uncached.forEach { put(it) } })
            put("limit", uncached.size)
        }
        val reqJson = JSONArray().apply {
            put("REQ")
            put(subId)
            put(filterObj)
        }.toString()

        for (relayUrl in allRelays) {
            val existingWs = wsPool[relayUrl]
            if (existingWs != null && wsReady[relayUrl] == true) {
                val prevSub = wsCurrentSubId[relayUrl]
                if (prevSub != null) {
                    try { existingWs.send(JSONArray().apply { put("CLOSE"); put(prevSub) }.toString()) } catch (_: Exception) {}
                }
                wsCurrentSubId[relayUrl] = subId
                try { existingWs.send(reqJson) } catch (_: Exception) {
                    wsPool.remove(relayUrl)
                    wsReady.remove(relayUrl)
                    wsCurrentSubId.remove(relayUrl)
                    openPoolConnection(relayUrl, subId, reqJson)
                }
            } else if (existingWs == null) {
                openPoolConnection(relayUrl, subId, reqJson)
            }
        }

        val timeoutMs = if (uncached.size > BULK_THRESHOLD) KIND0_BULK_FETCH_TIMEOUT_MS else KIND0_FETCH_TIMEOUT_MS
        delay(timeoutMs)
        val batchReceived = poolReceived.get() - beforeCount
        Log.d(TAG, "Kind-0 fetch done: $batchReceived/${uncached.size} profiles from ${allRelays.size} relays")
    }

    /**
     * Open a new persistent WebSocket connection to a relay and add it to the pool.
     * The connection stays open for reuse by subsequent requestProfiles calls.
     * Uses the shared [poolReceived] counter so all batches see the same count.
     */
    private fun openPoolConnection(relayUrl: String, subId: String, reqJson: String) {
        try {
            val httpUrl = relayUrl.replace("wss://", "https://").replace("ws://", "http://")
            val request = Request.Builder().url(httpUrl).build()
            val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wsReady[relayUrl] = true
                    wsCurrentSubId[relayUrl] = subId
                    webSocket.send(reqJson)
                    Log.d(TAG, "Pool WS open: $relayUrl")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = JSONArray(text)
                        val type = arr.getString(0)
                        if (type == "EVENT" && arr.length() >= 3) {
                            val eventJson = arr.getJSONObject(2)
                            val kind = eventJson.optInt("kind", -1)
                            if (kind == 0) {
                                val pubkey = eventJson.optString("pubkey", "")
                                val content = eventJson.optString("content", "")
                                val createdAt = eventJson.optLong("created_at", 0L)
                                if (pubkey.isNotBlank() && content.isNotBlank()) {
                                    val author = parseKind0Content(pubkey, content)
                                    if (author != null && putProfileIfNewer(pubkey, author, createdAt)) {
                                        poolReceived.incrementAndGet()
                                    }
                                }
                            }
                        }
                        // Don't close on EOSE — keep connection alive for reuse
                    } catch (e: Exception) {
                        Log.w(TAG, "Pool WS parse error ($relayUrl): ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Pool WS fail ($relayUrl): ${t.message}")
                    wsPool.remove(relayUrl)
                    wsReady.remove(relayUrl)
                    wsCurrentSubId.remove(relayUrl)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    wsPool.remove(relayUrl)
                    wsReady.remove(relayUrl)
                    wsCurrentSubId.remove(relayUrl)
                }
            })
            wsPool[relayUrl] = ws
        } catch (e: Exception) {
            Log.w(TAG, "Pool WS connect error ($relayUrl): ${e.message}")
        }
    }

    /**
     * Parse kind-0 content JSON into an Author, without needing a Quartz Event object.
     */
    private fun parseKind0Content(pubkey: String, content: String): Author? {
        return try {
            val parsed = json.decodeFromString<Kind0Content>(content)
            val key = normalizeKey(pubkey)
            val fallbackShort = pubkey.take(8) + "..."
            val displayName = sanitizeKind0String(parsed.display_name, 64)
                ?: sanitizeKind0String(parsed.name, 64)
                ?: fallbackShort
            val username = sanitizeKind0String(parsed.name, 16)
                ?: sanitizeKind0String(parsed.display_name, 16)
                ?: fallbackShort
            val picture = sanitizeKind0String(parsed.picture, 512)
            val about = sanitizeKind0String(parsed.about, 500)
            val nip05 = sanitizeKind0String(parsed.nip05, 128)
            val website = sanitizeKind0String(parsed.website, 256)
            val lud16 = sanitizeKind0String(parsed.lud16, 128)
            val banner = sanitizeKind0String(parsed.banner, 512)
            val pronouns = sanitizeKind0String(parsed.pronouns, 32)
            Author(
                id = key,
                username = username,
                displayName = displayName,
                avatarUrl = picture?.takeIf { it.isNotBlank() },
                isVerified = false,
                about = about?.takeIf { it.isNotBlank() },
                nip05 = nip05?.takeIf { it.isNotBlank() },
                website = website?.takeIf { it.isNotBlank() },
                lud16 = lud16?.takeIf { it.isNotBlank() },
                banner = banner?.takeIf { it.isNotBlank() },
                pronouns = pronouns?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Parse kind-0 content failed: ${e.message}")
            null
        }
    }

    /**
     * Reduce cache to at most maxEntries (LRU eviction). Pinned pubkeys are evicted last.
     * Thread-safe.
     */
    fun trimToSize(maxEntries: Int) {
        synchronized(cache) {
            val keysByAge = cache.keys.toList()
            for (key in keysByAge) {
                if (cache.size <= maxEntries) break
                if (key in pinnedPubkeys) continue
                cache.remove(key)
                profileCreatedAt.remove(key)
            }
        }
    }

    // ── Disk persistence ────────────────────────────────────────────────────

    /**
     * Initialize disk persistence. Call once from MainActivity.onCreate so profiles survive
     * process death. Loads any previously saved profiles into the in-memory cache immediately.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { loadProfileCacheFromDisk() }
    }

    /** Schedule a debounced save after a profile update. */
    private fun scheduleDiskSave() {
        if (appContext == null) return
        diskSaveJob?.cancel()
        diskSaveJob = scope.launch {
            delay(DISK_SAVE_DEBOUNCE_MS)
            saveProfileCacheToDisk()
        }
    }

    private suspend fun loadProfileCacheFromDisk() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val prefs = ctx.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE)
                val profilesJson = prefs.getString(PROFILE_CACHE_KEY, null) ?: return@withContext
                val createdAtJson = prefs.getString(PROFILE_CREATED_AT_KEY, null)
                val fetchedAtJson = prefs.getString(PROFILE_FETCHED_AT_KEY, null)
                val outboxJson = prefs.getString(OUTBOX_RELAYS_KEY, null)

                val profiles: Map<String, Author> = json.decodeFromString(profilesJson)
                val createdAts: Map<String, Long> = if (createdAtJson != null) {
                    json.decodeFromString(createdAtJson)
                } else {
                    emptyMap()
                }
                val fetchedAts: Map<String, Long> = if (fetchedAtJson != null) {
                    try { json.decodeFromString(fetchedAtJson) } catch (_: Exception) { emptyMap() }
                } else {
                    emptyMap()
                }
                val outboxRelays: Map<String, List<String>> = if (outboxJson != null) {
                    try { json.decodeFromString(outboxJson) } catch (_: Exception) { emptyMap() }
                } else {
                    emptyMap()
                }

                if (profiles.isEmpty()) return@withContext

                val restoredKeys = mutableListOf<String>()
                synchronized(cache) {
                    for ((key, author) in profiles) {
                        val normalized = normalizeKey(key)
                        if (cache[normalized] == null) {
                            cache[normalized] = author
                            createdAts[normalized]?.let { profileCreatedAt[normalized] = it }
                            fetchedAts[normalized]?.let { profileFetchedAt[normalized] = it }
                            restoredKeys.add(normalized)
                        }
                    }
                }
                // Restore outbox relay cache
                for ((key, relays) in outboxRelays) {
                    val normalized = normalizeKey(key)
                    if (outboxRelayCache[normalized] == null) {
                        outboxRelayCache[normalized] = relays
                    }
                }
                Log.d(TAG, "Restored ${profiles.size} profiles, ${outboxRelays.size} outbox relay sets from disk cache (${restoredKeys.size} new)")
                // Signal UI components to rebuild with real display names.
                // We use diskCacheRestored (StateFlow) instead of emitting profileUpdated
                // for every key, which would flood reply ViewModels and cause race conditions.
                _diskCacheRestored.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Load profile cache from disk failed: ${e.message}", e)
            }
        }
    }

    private suspend fun saveProfileCacheToDisk() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                // Take a snapshot of the most recent entries (pinned first, then LRU order)
                val snapshot: Map<String, Author>
                val createdAtSnapshot: Map<String, Long>
                val fetchedAtSnapshot: Map<String, Long>
                val outboxSnapshot: Map<String, List<String>>
                synchronized(cache) {
                    val pinned = cache.entries.filter { it.key in pinnedPubkeys }
                    val rest = cache.entries.filter { it.key !in pinnedPubkeys }
                    val combined = (pinned + rest).takeLast(DISK_CACHE_MAX)
                    val keys = combined.map { it.key }.toSet()
                    snapshot = combined.associate { it.key to it.value }
                    createdAtSnapshot = combined.mapNotNull { entry ->
                        profileCreatedAt[entry.key]?.let { entry.key to it }
                    }.toMap()
                    fetchedAtSnapshot = combined.mapNotNull { entry ->
                        profileFetchedAt[entry.key]?.let { entry.key to it }
                    }.toMap()
                    outboxSnapshot = outboxRelayCache.filter { it.key in keys }
                }

                val profilesJson = json.encodeToString(snapshot)
                val createdAtJson = json.encodeToString(createdAtSnapshot)
                val fetchedAtJson = json.encodeToString(fetchedAtSnapshot)
                val outboxJson = json.encodeToString(outboxSnapshot)
                ctx.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PROFILE_CACHE_KEY, profilesJson)
                    .putString(PROFILE_CREATED_AT_KEY, createdAtJson)
                    .putString(PROFILE_FETCHED_AT_KEY, fetchedAtJson)
                    .putString(OUTBOX_RELAYS_KEY, outboxJson)
                    .apply()
                Log.d(TAG, "Saved ${snapshot.size} profiles, ${outboxSnapshot.size} outbox relay sets to disk cache")
            } catch (e: Exception) {
                Log.e(TAG, "Save profile cache to disk failed: ${e.message}", e)
            }
        }
    }

    @Serializable
    private data class Kind0Content(
        val name: String? = null,
        val display_name: String? = null,
        val picture: String? = null,
        val about: String? = null,
        val nip05: String? = null,
        val website: String? = null,
        val lud16: String? = null,
        val banner: String? = null,
        val pronouns: String? = null
    )
}
