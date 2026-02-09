package com.example.views.repository

import android.content.Context
import android.util.Log
import com.example.views.data.Author
import com.example.views.relay.RelayConnectionStateMachine
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
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
        private const val MAX_ENTRIES = 800

        /** When over this size, we evict even pinned (follow-list) profiles to avoid unbounded growth. */
        private const val HARD_CAP = 1200

        /** Size to trim to when app is in background. */
        const val TRIM_SIZE_BACKGROUND = 300

        private const val PROFILE_CACHE_PREFS = "profile_metadata_cache"
        private const val PROFILE_CACHE_KEY = "profiles_json"
        private const val PROFILE_CREATED_AT_KEY = "profiles_created_at_json"
        /** Max profiles saved to disk. Keep reasonable for SharedPreferences size. */
        private const val DISK_CACHE_MAX = 300
        /** Debounce delay before writing profile cache to disk after an update. */
        private const val DISK_SAVE_DEBOUNCE_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    /** Large buffer so bulk loads (e.g. debug "Fetch all") don't drop emissions before coalescer can apply to feed. */
    private val _profileUpdated = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 2048)
    val profileUpdated: SharedFlow<String> = _profileUpdated.asSharedFlow()

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
        scope.launch {
            _profileUpdated.emit(key)
        }
        scheduleDiskSave()
    }

    /**
     * Fetch kind-0 for pubkeys from cache relays. Uses a dedicated client so main feed is not replaced.
     */
    suspend fun requestProfiles(pubkeys: List<String>, cacheRelayUrls: List<String>) {
        if (pubkeys.isEmpty() || cacheRelayUrls.isEmpty()) return
        val uncached = pubkeys.filter { cache[normalizeKey(it)] == null }
        if (uncached.isEmpty()) return

        Log.d(TAG, "Fetching kind-0 for ${uncached.size} pubkeys from ${cacheRelayUrls.size} cache relays")
        try {
            val filter = Filter(
                kinds = listOf(0),
                authors = uncached,
                limit = uncached.size
            )
            val received = AtomicInteger(0)
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(cacheRelayUrls, filter) { event ->
                if (event.kind == 0) {
                    val author = parseKind0(event)
                    if (author != null && putProfileIfNewer(event.pubKey, author, event.createdAt)) {
                        received.incrementAndGet()
                    }
                }
            }
            val timeoutMs = if (uncached.size > BULK_THRESHOLD) KIND0_BULK_FETCH_TIMEOUT_MS else KIND0_FETCH_TIMEOUT_MS
            delay(timeoutMs)
            handle.cancel()
            Log.d(TAG, "Kind-0 fetch done: ${received.get()} profiles")
        } catch (e: Exception) {
            Log.e(TAG, "Kind-0 fetch failed: ${e.message}", e)
        }
    }

    private fun parseKind0(event: Event): Author? {
        return try {
            val parsed = json.decodeFromString<Kind0Content>(event.content)
            val key = normalizeKey(event.pubKey)
            val fallbackShort = event.pubKey.take(8) + "..."
            // NIP-01: display_name is display name; name is username. Prefer display_name for display, name for username.
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
            Log.w(TAG, "Parse kind-0 failed: ${e.message}")
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

                val profiles: Map<String, Author> = json.decodeFromString(profilesJson)
                val createdAts: Map<String, Long> = if (createdAtJson != null) {
                    json.decodeFromString(createdAtJson)
                } else {
                    emptyMap()
                }

                if (profiles.isEmpty()) return@withContext

                synchronized(cache) {
                    for ((key, author) in profiles) {
                        val normalized = normalizeKey(key)
                        if (cache[normalized] == null) {
                            cache[normalized] = author
                            createdAts[normalized]?.let { profileCreatedAt[normalized] = it }
                        }
                    }
                }
                Log.d(TAG, "Restored ${profiles.size} profiles from disk cache")
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
                synchronized(cache) {
                    val pinned = cache.entries.filter { it.key in pinnedPubkeys }
                    val rest = cache.entries.filter { it.key !in pinnedPubkeys }
                    val combined = (pinned + rest).takeLast(DISK_CACHE_MAX)
                    snapshot = combined.associate { it.key to it.value }
                    createdAtSnapshot = combined.mapNotNull { entry ->
                        profileCreatedAt[entry.key]?.let { entry.key to it }
                    }.toMap()
                }

                val profilesJson = json.encodeToString(snapshot)
                val createdAtJson = json.encodeToString(createdAtSnapshot)
                ctx.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PROFILE_CACHE_KEY, profilesJson)
                    .putString(PROFILE_CREATED_AT_KEY, createdAtJson)
                    .apply()
                Log.d(TAG, "Saved ${snapshot.size} profiles to disk cache")
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
