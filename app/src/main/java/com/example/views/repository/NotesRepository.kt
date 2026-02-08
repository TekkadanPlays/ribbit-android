package com.example.views.repository

import android.content.Context
import android.util.Log
import com.example.views.data.Note
import com.example.views.data.Author
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.cache.ThreadReplyCache
import com.example.views.utils.Nip10ReplyDetector
import com.example.views.utils.Nip19QuoteParser
import com.example.views.utils.UrlDetector
import com.example.views.utils.extractPubkeysFromContent
import com.example.views.utils.normalizeAuthorIdForCache
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.example.views.ribbit.tsm.BuildConfig
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.HashSet

/** Separate counts of pending new notes for All vs Following. Nonce ensures StateFlow always emits on update. */
data class NewNotesCounts(val all: Int, val following: Int, val nonce: Long = 0L)

/** Debug-only: session counts of event content (md, img, vid, gif, imeta, emoji) for in-app event stats. */
data class DebugEventStatsSnapshot(
    val total: Int,
    val mdCount: Int,
    val imgCount: Int,
    val vidCount: Int,
    val gifCount: Int,
    val imetaCount: Int,
    val emojiCount: Int
) {
    fun mdPct(): Int = if (total == 0) 0 else (mdCount * 100 / total)
    fun imgPct(): Int = if (total == 0) 0 else (imgCount * 100 / total)
    fun vidPct(): Int = if (total == 0) 0 else (vidCount * 100 / total)
    fun gifPct(): Int = if (total == 0) 0 else (gifCount * 100 / total)
    fun imetaPct(): Int = if (total == 0) 0 else (imetaCount * 100 / total)
    fun emojiPct(): Int = if (total == 0) 0 else (emojiCount * 100 / total)
}

/** Lightweight feed session lifecycle so UI and re-subscribe logic don't fight (e.g. return from notifications vs refresh). */
enum class FeedSessionState { Idle, Loading, Live, Refreshing }

/**
 * Repository for fetching and managing Nostr notes using the shared RelayConnectionStateMachine.
 * Does not own a NostrClient; uses requestFeedChange so switching feeds only updates subscription (no full reconnect).
 *
 * **Singleton**: only one kind-1 handler is ever registered. Dashboard uses this single instance.
 *
 * **Subscription ownership**: This repo is the single owner of the main kind-1 subscription. It sets
 * [RelayConnectionStateMachine.resumeSubscriptionProvider] so on app resume the state machine re-applies
 * (relayUrls, kind1Filter) without clearing the feed. When the user returns to the dashboard with notes already
 * loaded, the UI should call [setDisplayFilterOnly] only (not [loadNotesFromFavoriteCategory]) to avoid
 * re-requesting the subscription and screwing up the feed. Pull-to-refresh calls [applyPendingNotes] and
 * requestRetry(); it does not re-subscribe. [refresh] re-subscribes for a full re-fetch.
 */
class NotesRepository private constructor() {

    private val relayStateMachine = RelayConnectionStateMachine.getInstance()
    private val profileCache = ProfileMetadataCache.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cacheRelayUrls = listOf<String>()

    init {
        if (BuildConfig.DEBUG) {
            Log.i("RibbitEvent", "Monitor enabled: kind-1 events will be logged here. Run: adb logcat -s RibbitEvent")
            Log.i(TAG, "RibbitEvent monitor enabled (debug). Use logcat -s RibbitEvent or -s NotesRepository")
        }
        relayStateMachine.registerKind1Handler { event, relayUrl ->
            scope.launch {
                processEventMutex.withLock { handleEventInternal(event, relayUrl) }
            }
        }
        startProfileUpdateCoalescer()
    }

    /** Coalesce profileUpdated emissions and apply author updates in batches to avoid O(notes) work per profile. */
    private fun startProfileUpdateCoalescer() {
        scope.launch {
            val batch = mutableSetOf<String>()
            var flushJob: Job? = null
            profileCache.profileUpdated.collect { pubkey ->
                batch.add(pubkey.lowercase())
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(PROFILE_UPDATE_DEBOUNCE_MS)
                    val snapshot = batch.toSet()
                    batch.clear()
                    flushJob = null
                    if (snapshot.isNotEmpty()) updateAuthorsInNotesBatch(snapshot)
                }
            }
        }
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches (from RelayStorageManager.loadCacheRelays). Call when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    // All notes (with relayUrl set when received); filtered by connectedRelays for display
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    private val _displayedNotes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _displayedNotes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Debug-only: event content stats for this session (total, md, img, vid, gif, imeta, emoji). Only updated when BuildConfig.DEBUG. */
    private val _debugEventStats = MutableStateFlow(DebugEventStatsSnapshot(0, 0, 0, 0, 0, 0, 0))
    val debugEventStats: StateFlow<DebugEventStatsSnapshot> = _debugEventStats.asStateFlow()

    private var connectedRelays = listOf<String>()
    private var subscriptionRelays = listOf<String>()

    /** When non-null and followFilterEnabled, only notes whose author.id is in this set are shown. Volatile so relay callback thread sees latest. */
    @Volatile
    private var followFilter: Set<String>? = null
    @Volatile
    private var followFilterEnabled: Boolean = false

    /** Last applied kind-1 filter (authors) when Following was active; used on resume when follow list is temporarily empty so All notes do not bleed into Following. */
    @Volatile
    private var lastAppliedKind1Filter: Filter? = null

    /** Serializes event processing so follow filter and displayed notes stay consistent; avoids blocking WebSocket thread. */
    private val processEventMutex = Mutex()

    /** Debounced display update: one run after event burst settles so UI stays smooth under high throughput. */
    private var displayUpdateJob: Job? = null
    private var countsSubscriptionJob: Job? = null

    /** Batched kind-0 profile requests: uncached authors are added here and fetched in batches to avoid flooding relays and speed up feed resolution. */
    private val pendingProfilePubkeys = Collections.synchronizedSet(HashSet<String>())
    private var profileBatchJob: Job? = null
    private val PROFILE_BATCH_DELAY_MS = 50L
    private val PROFILE_BATCH_SIZE = 60
    /** Debounce window for coalescing profileUpdated before applying to notes list (one list update per batch). */
    private val PROFILE_UPDATE_DEBOUNCE_MS = 80L

    // Feed cutoff: only notes with timestamp <= this are shown; everything else builds up in pending until refresh
    private var feedCutoffTimestampMs: Long = 0L
    private var latestNoteTimestampAtOpen: Long = 0L
    private var initialLoadComplete: Boolean = false
    private val _pendingNewNotes = mutableListOf<Note>()
    private val pendingNotesLock = Any()
    private val _newNotesCounts = MutableStateFlow(NewNotesCounts(0, 0))
    val newNotesCounts: StateFlow<NewNotesCounts> = _newNotesCounts.asStateFlow()

    /** Feed session state for UI and to avoid redundant load on tab return (Idle -> Loading -> Live; Refreshing during applyPendingNotes/refresh). */
    private val _feedSessionState = MutableStateFlow(FeedSessionState.Idle)
    val feedSessionState: StateFlow<FeedSessionState> = _feedSessionState.asStateFlow()

    /** Optional context for feed cache persistence so notes survive process death. Set from MainActivity. */
    @Volatile private var appContext: Context? = null
    private var feedCacheSaveJob: Job? = null
    private val feedCacheJson = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "NotesRepository"
        /** Max notes kept in memory; oldest dropped to keep feed bounded (scroll/layout performance). */
        private const val MAX_NOTES_IN_MEMORY = 1000
        /** Limit for following feed; relays return only notes from followed authors so we can ask for more. */
        private const val FOLLOWING_FEED_LIMIT = 1000
        private const val FEED_SINCE_DAYS = 7
        /** Debounce display updates so hundreds of events/sec don't thrash the UI. */
        private const val DISPLAY_UPDATE_DEBOUNCE_MS = 50L
        private const val FEED_CACHE_PREFS = "notes_feed_cache"
        private const val FEED_CACHE_KEY = "feed_notes"
        private const val FEED_CACHE_FOLLOWING_KEY = "feed_notes_following"
        private const val FEED_LAST_MODE_KEY = "feed_last_mode"
        private const val FEED_CACHE_MAX = 200

        @Volatile
        private var instance: NotesRepository? = null
        fun getInstance(): NotesRepository =
            instance ?: synchronized(this) { instance ?: NotesRepository().also { instance = it } }
    }

    /**
     * Call once from MainActivity.onCreate so the feed can be persisted and restored across app restarts.
     * Restores last saved notes if current feed is empty (e.g. after process death). The save job
     * is not cancelled on app pause. On cold start the dashboard re-applies the subscription when
     * currentAccount and relayCategories are ready.
     */
    fun prepareFeedCache(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { loadFeedCacheFromDisk() }
        feedCacheSaveJob = scope.launch {
            _notes.collect { list ->
                delay(2000)
                if (list.isEmpty()) return@collect
                val isFollowing = followFilterEnabled && !followFilter.isNullOrEmpty()
                saveFeedCacheToDisk(list.take(FEED_CACHE_MAX), isFollowing)
            }
        }
    }

    private suspend fun loadFeedCacheFromDisk() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val prefs = ctx.getSharedPreferences(FEED_CACHE_PREFS, Context.MODE_PRIVATE)
                val lastMode = prefs.getString(FEED_LAST_MODE_KEY, "all")
                val primaryKey = if (lastMode == "following") FEED_CACHE_FOLLOWING_KEY else FEED_CACHE_KEY
                var json = prefs.getString(primaryKey, null)
                if (json == null) {
                    val fallbackKey = if (primaryKey == FEED_CACHE_KEY) FEED_CACHE_FOLLOWING_KEY else FEED_CACHE_KEY
                    json = prefs.getString(fallbackKey, null)
                }
                val list = json?.let { feedCacheJson.decodeFromString<List<Note>>(it) } ?: return@withContext
                if (list.isNotEmpty() && _notes.value.isEmpty()) {
                    _notes.value = list
                    _displayedNotes.value = list
                    initialLoadComplete = true
                    _feedSessionState.value = FeedSessionState.Live
                    Log.d(TAG, "Restored ${list.size} notes from feed cache")
                    // Re-resolve authors from the (now-loaded) profile cache so restored
                    // notes render with display names and avatars immediately.
                    refreshAuthorsFromCache()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load feed cache failed: ${e.message}", e)
            }
        }
    }

    private suspend fun saveFeedCacheToDisk(notes: List<Note>, isFollowingMode: Boolean) {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val json = feedCacheJson.encodeToString(notes)
                val key = if (isFollowingMode) FEED_CACHE_FOLLOWING_KEY else FEED_CACHE_KEY
                val mode = if (isFollowingMode) "following" else "all"
                ctx.getSharedPreferences(FEED_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key, json)
                    .putString(FEED_LAST_MODE_KEY, mode)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "Save feed cache failed: ${e.message}", e)
            }
        }
    }

    /** Build kind-1 filter for current mode: when Following is on and we have follows, filter by authors; else null (global). */
    private fun buildKind1FilterForSubscription(): Filter? {
        if (!followFilterEnabled) return null
        val authors = followFilter ?: return null
        if (authors.isEmpty()) return null
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400L * FEED_SINCE_DAYS
        return Filter(
            kinds = listOf(1),
            authors = authors.toList(),
            limit = FOLLOWING_FEED_LIMIT,
            since = sevenDaysAgo
        )
    }

    /** Returns (relayUrls, kind1Filter) for resume so Following filter is never lost. Called by state machine on app resume. When Following is on but follow list is temporarily empty, use last applied filter so All notes do not bleed in. */
    private fun getSubscriptionForResume(): Pair<List<String>, Filter?> {
        val filter = buildKind1FilterForSubscription()
        return subscriptionRelays to (
            if (filter != null) filter
            else if (followFilterEnabled && (followFilter == null || followFilter!!.isEmpty())) lastAppliedKind1Filter
            else null
        )
    }

    /** Push current subscription to state machine (global or following). Call after setFollowFilter or when (re)subscribing. */
    private fun applySubscriptionToStateMachine(relayUrls: List<String>) {
        val filter = buildKind1FilterForSubscription()
        if (filter != null) lastAppliedKind1Filter = filter else lastAppliedKind1Filter = null
        relayStateMachine.requestFeedChange(relayUrls, filter)
    }

    /** Schedule a single display update after the event burst settles (smooth UI under high throughput). */
    private fun scheduleDisplayUpdate() {
        displayUpdateJob?.cancel()
        displayUpdateJob = scope.launch {
            delay(DISPLAY_UPDATE_DEBOUNCE_MS)
            updateDisplayedNotes()
            displayUpdateJob = null
        }
    }

    /**
     * Set the relay set used for the shared subscription (all user relays). Call once on load.
     * Registers as the resume subscription provider so app resume always re-applies the correct Following filter.
     */
    fun setSubscriptionRelays(allUserRelayUrls: List<String>) {
        if (allUserRelayUrls.sorted() == subscriptionRelays.sorted()) return
        Log.d(TAG, "Subscription relays set: ${allUserRelayUrls.size} relays (stay connected to all)")
        subscriptionRelays = allUserRelayUrls
        relayStateMachine.resumeSubscriptionProvider = { getSubscriptionForResume() }
        _feedSessionState.value = FeedSessionState.Idle
    }

    /**
     * Set display filter only (sidebar selection). Does NOT change subscription or follow/reply filters;
     * only updates which relays' notes are shown. Follow and reply filters are preserved and re-applied.
     * Normalizes URLs so they match note.relayUrl from the state machine (avoids blank feed when switching).
     */
    fun connectToRelays(displayFilterUrls: List<String>) {
        val normalized = displayFilterUrls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }.distinct()
        connectedRelays = if (normalized.isNotEmpty()) normalized else displayFilterUrls
        Log.d(TAG, "Display filter: ${connectedRelays.size} relay(s)")
        updateDisplayedNotes()
    }

    /**
     * Set follow filter: when enabled and followList is non-null, only notes from authors in followList are shown.
     * Pubkeys are normalized to lowercase so matching is case-insensitive (kind-3 vs event.pubKey can differ).
     * Re-subscribes with authors filter when Following is on so relays return follower notes directly (no in-memory churn).
     * Bleed prevention: (1) In handleEventInternal we drop notes whose author is not in followSet when followFilterEnabled;
     * (2) updateDisplayedNotes() filters displayed list by currentFollowFilter; (3) getSubscriptionForResume uses
     * lastAppliedKind1Filter when follow list is temporarily empty so All notes do not bleed into Following on resume.
     */
    fun setFollowFilter(followList: Set<String>?, enabled: Boolean) {
        // When Following is on but list is null or empty, treat as no filter (show all) so we never show zero notes
        val effective = followList?.map { it.lowercase() }?.toSet()?.takeIf { it.isNotEmpty() }
        followFilter = effective
        followFilterEnabled = enabled
        profileCache.setPinnedPubkeys(if (enabled && !followFilter.isNullOrEmpty()) followFilter else null)
        updateDisplayedNotes()
        if (subscriptionRelays.isNotEmpty()) {
            applySubscriptionToStateMachine(subscriptionRelays)
            val mode = if (enabled && !followFilter.isNullOrEmpty()) "following (${followFilter!!.size} authors)" else "global"
            Log.d(TAG, "Re-subscribed: $mode")
        }
    }

    private fun updateDisplayedNotes() {
        try {
            val connectedSet = connectedRelays.toSet()
            val relayMatch: (Note) -> Boolean = { note ->
                val urls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                urls.isEmpty() || urls.any { url ->
                    url in connectedSet || RelayUrlNormalizer.normalizeOrNull(url)?.url in connectedSet
                }
            }
            var filtered = if (connectedRelays.isEmpty()) _notes.value else _notes.value.filter(relayMatch)
            val currentFollowFilter = followFilter
            val followEnabled = followFilterEnabled
            if (followEnabled && currentFollowFilter != null) {
                filtered = filtered.filter { note -> normalizeAuthorIdForCache(note.author.id) in currentFollowFilter }
            }
            filtered = filtered.filter { note -> !note.isReply }
            _displayedNotes.value = filtered.toList()
            updateDisplayedNewNotesCount()
            // Debounce counts subscription so we don't re-subscribe on every note; cap at 150 note IDs
            countsSubscriptionJob?.cancel()
            countsSubscriptionJob = scope.launch {
                delay(600)
                val ids = _displayedNotes.value.take(150).map { it.id }.toSet()
                NoteCountsRepository.setNoteIdsOfInterest(ids)
                countsSubscriptionJob = null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "updateDisplayedNotes failed: ${e.message}", e)
        }
    }

    /** Pending new notes counts for All and Following (by current relay set); separate so both filters show correct counts. */
    private fun updateDisplayedNewNotesCount() {
        try {
            if (connectedRelays.isEmpty()) {
                _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
                return
            }
            val connectedSet = connectedRelays.toSet()
            val filter = followFilter
            var countAll = 0
            var countFollowing = 0
            val pendingSnapshot = synchronized(pendingNotesLock) { _pendingNewNotes.toList() }
            val relayMatch: (Note) -> Boolean = { note ->
                val urls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                urls.isEmpty() || urls.any { url -> url in connectedSet || RelayUrlNormalizer.normalizeOrNull(url)?.url in connectedSet }
            }
            for (note in pendingSnapshot) {
                val relayOk = relayMatch(note)
                val notReply = !note.isReply
                if (!relayOk || !notReply) continue
                countAll++
                val followOk = filter != null && note.author.id.lowercase() in filter
                if (followOk) countFollowing++
            }
            _newNotesCounts.value = NewNotesCounts(countAll, countFollowing, System.currentTimeMillis())
        } catch (e: Throwable) {
            Log.e(TAG, "updateDisplayedNewNotesCount failed: ${e.message}", e)
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        }
    }

    /**
     * Disconnect from all relays (e.g. on screen exit). Delegates to shared state machine.
     */
    fun disconnectAll() {
        Log.d(TAG, "Disconnecting from all relays")
        relayStateMachine.resumeSubscriptionProvider = null
        relayStateMachine.requestDisconnect()
        connectedRelays = emptyList()
        subscriptionRelays = emptyList()
        followFilter = null
        followFilterEnabled = false
        lastAppliedKind1Filter = null
        _feedSessionState.value = FeedSessionState.Idle
        _notes.value = emptyList()
        _displayedNotes.value = emptyList()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        feedCutoffTimestampMs = 0L
        latestNoteTimestampAtOpen = 0L
        initialLoadComplete = false
    }

    /**
     * Ensure subscription to kind-1 notes for ALL user relays. Pass allUserRelayUrls (not sidebar selection).
     * Display filter is set separately via connectToRelays(displayFilterUrls).
     */
    suspend fun ensureSubscriptionToNotes(allUserRelayUrls: List<String>, limit: Int = 100) {
        if (allUserRelayUrls.isEmpty()) {
            Log.w(TAG, "No relays configured")
            return
        }
        setSubscriptionRelays(allUserRelayUrls)
        if (_notes.value.isNotEmpty()) {
            // Resume: keep existing feed; set new cutoff so notes arriving after resume go to pending (not into feed) until user refreshes.
            Log.d(TAG, "Restoring subscription for ${allUserRelayUrls.size} relays (keeping ${_notes.value.size} notes)")
            feedCutoffTimestampMs = System.currentTimeMillis()
            applySubscriptionToStateMachine(allUserRelayUrls)
            return
        }
        subscribeToNotes(limit)
    }

    /**
     * Subscribe to kind-1 notes from ALL subscription relays. Uses subscriptionRelays (all user relays), not display filter.
     * Sets feed cutoff at connection start: only notes with timestamp <= cutoff are shown; newer notes build up in pending until refresh.
     */
    suspend fun subscribeToNotes(limit: Int = 100) {
        if (subscriptionRelays.isEmpty()) {
            Log.w(TAG, "No subscription relays set")
            _isLoading.value = false
            initialLoadComplete = true
            return
        }

        _isLoading.value = true
        _feedSessionState.value = FeedSessionState.Loading
        _error.value = null
        _notes.value = emptyList()
        _displayedNotes.value = emptyList()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false

        // Cutoff set exactly when we start the subscription: only notes older than this moment are shown in the feed
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs

        try {
            applySubscriptionToStateMachine(subscriptionRelays)
            delay(80) // Minimal yield so first events can be processed
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            _feedSessionState.value = FeedSessionState.Live
            updateDisplayedNotes()
            Log.d(TAG, "Subscription active for ${subscriptionRelays.size} relays (feed cutoff at $feedCutoffTimestampMs)")
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to notes: ${e.message}", e)
            _error.value = "Failed to load notes: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
            _feedSessionState.value = FeedSessionState.Live
            updateDisplayedNotes()
        }
    }

    /**
     * Subscribe to notes from a specific relay only. Uses requestFeedChange (subscription swap, no disconnect).
     */
    suspend fun subscribeToRelayNotes(relayUrl: String, limit: Int = 100) {
        _isLoading.value = true
        _error.value = null
        _notes.value = emptyList()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs

        try {
            relayStateMachine.requestFeedChange(listOf(relayUrl))
            connectedRelays = listOf(relayUrl)
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            Log.d(TAG, "Subscription active for relay: $relayUrl (state machine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes from relay: ${e.message}", e)
            _error.value = "Failed to load notes from relay: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
        }
    }

    /**
     * Subscribe to notes from a specific author (for announcements).
     */
    suspend fun subscribeToAuthorNotes(relayUrls: List<String>, authorPubkey: String, limit: Int = 50) {
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relays provided for author subscription")
            return
        }

        _isLoading.value = true
        _error.value = null
        _notes.value = emptyList()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs

        try {
            val filter = Filter(
                kinds = listOf(1),
                authors = listOf(authorPubkey),
                limit = limit
            )
            relayStateMachine.requestFeedChange(relayUrls, filter) { event ->
                scope.launch { processEventMutex.withLock { handleEventInternal(event, "") } }
            }
            connectedRelays = relayUrls
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            Log.d(TAG, "Author subscription active (state machine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to author notes: ${e.message}", e)
            _error.value = "Failed to load author notes: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
        }
    }

    /**
     * Handle incoming event from relay.
     * During initial load: notes with timestamp <= cutoff go to feed; newer go to pending.
     * After initial load: only notes newer than the current top (latest in feed) go to pending and count as "new".
     * Late-arriving OLD notes (timestamp <= top) are merged into the feed in sorted order so the feed expands
     * to the historical side without inflating the "new" counter or pushing truly new notes down.
     */
    /** Debug-only: log a one-line summary of each incoming kind-1 event. Uses INFO level so it shows even when debug logs are filtered. */
    private fun logIncomingEventSummary(event: Event, relayUrl: String) {
        if (!eventLoggedOnce) {
            eventLoggedOnce = true
            Log.i("RibbitEvent", "Monitor active: first kind-1 received (you should see one line per note from here)")
            Log.i(TAG, "RibbitEvent: first kind-1 received")
        }
        val id = event.id.take(8)
        val relay = relayUrl.takeLast(30).takeLast(25)
        val contentLen = event.content.length
        val eCount = event.tags.count { it.isNotEmpty() && it[0] == "e" }
        val pCount = event.tags.count { it.isNotEmpty() && it[0] == "p" }
        val tCount = event.tags.count { it.isNotEmpty() && it[0] == "t" }
        val imeta = event.tags.count { it.isNotEmpty() && it[0] == "imeta" }
        val emoji = event.tags.count { it.isNotEmpty() && it[0] == "emoji" }
        val urls = UrlDetector.findUrls(event.content)
        val imageCount = urls.count { UrlDetector.isImageUrl(it) }
        val videoCount = urls.count { UrlDetector.isVideoUrl(it) }
        val gifLike = urls.any { it.contains(".gif", ignoreCase = true) }
        val content = event.content
        val hasMarkdown = content.contains("**") || content.contains("##") || content.contains("```") ||
            Regex("\\[.+?]\\(.+?\\)").containsMatchIn(content)
        val line = "id=$id relay=…$relay len=$contentLen e=$eCount p=$pCount t=$tCount imeta=$imeta emoji=$emoji urls=${urls.size} img=$imageCount vid=$videoCount gif=$gifLike md=$hasMarkdown"
        Log.i("RibbitEvent", line)
        eventCountForSampling++
        if (eventCountForSampling % 20 == 0) {
            Log.d(TAG, "Event sample: $line")
        }
        updateDebugEventStats(hasMarkdown, imageCount > 0, videoCount > 0, gifLike, imeta > 0, emoji > 0)
    }

    private var eventLoggedOnce = false
    private var eventCountForSampling = 0
    private var debugStatsTotal = 0
    private var debugStatsMd = 0
    private var debugStatsImg = 0
    private var debugStatsVid = 0
    private var debugStatsGif = 0
    private var debugStatsImeta = 0
    private var debugStatsEmoji = 0

    private fun updateDebugEventStats(md: Boolean, img: Boolean, vid: Boolean, gif: Boolean, imeta: Boolean, emoji: Boolean) {
        debugStatsTotal++
        if (md) debugStatsMd++
        if (img) debugStatsImg++
        if (vid) debugStatsVid++
        if (gif) debugStatsGif++
        if (imeta) debugStatsImeta++
        if (emoji) debugStatsEmoji++
        _debugEventStats.value = DebugEventStatsSnapshot(
            debugStatsTotal, debugStatsMd, debugStatsImg, debugStatsVid, debugStatsGif, debugStatsImeta, debugStatsEmoji
        )
    }

    /** Keep only the newest notes to cap memory; list must be sorted by timestamp descending. */
    private fun trimNotesToCap(notes: List<Note>): List<Note> =
        if (notes.size <= MAX_NOTES_IN_MEMORY) notes else notes.take(MAX_NOTES_IN_MEMORY)

    private suspend fun handleEventInternal(event: Event, relayUrl: String) {
        try {
            if (event.kind == 1) {
                if (BuildConfig.DEBUG) logIncomingEventSummary(event, relayUrl)
                val note = convertEventToNote(event, relayUrl)
                val followSet = followFilter
                if (followFilterEnabled && followSet != null && note.author.id.lowercase() !in followSet) return
                if (note.isReply) {
                    Nip10ReplyDetector.getRootId(event)?.let { rootId ->
                        ThreadReplyCache.addReply(rootId, note)
                    }
                }
                val currentNotes = _notes.value
                val existingIndex = currentNotes.indexOfFirst { it.id == note.id }
                if (existingIndex >= 0) {
                    val existing = currentNotes[existingIndex]
                    val existingUrls = existing.relayUrls.ifEmpty { listOfNotNull(existing.relayUrl) }
                    val newRelayUrls = (existingUrls + listOfNotNull(note.relayUrl)).distinct().filter { it.isNotBlank() }
                    if (newRelayUrls != existingUrls) {
                        val updated = existing.copy(relayUrls = newRelayUrls)
                        val newList = currentNotes.toMutableList().apply { set(existingIndex, updated) }
                        _notes.value = newList
                        updateDisplayedNotes()
                    }
                    return
                }
                val alreadyPending = synchronized(pendingNotesLock) { _pendingNewNotes.any { it.id == note.id } }
                if (alreadyPending) return

                val cutoff = feedCutoffTimestampMs
                val isOlderThanCutoff = cutoff <= 0L || note.timestamp <= cutoff

                if (!initialLoadComplete) {
                    if (isOlderThanCutoff) {
                        val newNotes = trimNotesToCap((currentNotes + note).sortedByDescending { it.timestamp })
                        _notes.value = newNotes
                        updateDisplayedNotes()
                        if (newNotes.size % 50 == 0) {
                            Log.d(TAG, "Initial load: ${newNotes.size} notes so far")
                        }
                    } else {
                        synchronized(pendingNotesLock) { _pendingNewNotes.add(note) }
                        updateDisplayedNewNotesCount()
                        val pendingSize = synchronized(pendingNotesLock) { _pendingNewNotes.size }
                        if (pendingSize % 10 == 0) {
                            Log.d(TAG, "Pending during initial load: $pendingSize notes")
                        }
                    }
                } else {
                    // After initial load: only count as "new" (pending) if note is actually newer than the top of the feed.
                    val topTimestamp = currentNotes.maxOfOrNull { it.timestamp } ?: latestNoteTimestampAtOpen
                    if (note.timestamp <= topTimestamp) {
                        // Late-arriving old note: merge into feed in sorted order (expand history, no "new" count).
                        val merged = trimNotesToCap((currentNotes + note).distinctBy { it.id }.sortedByDescending { it.timestamp })
                        _notes.value = merged
                        updateDisplayedNotes()
                        if (merged.size % 50 == 0) {
                            Log.d(TAG, "Merged late-arriving (total so far: ${merged.size})")
                        }
                    } else {
                        synchronized(pendingNotesLock) { _pendingNewNotes.add(note) }
                        updateDisplayedNewNotesCount()
                        val c = _newNotesCounts.value
                        val pendingSize = synchronized(pendingNotesLock) { _pendingNewNotes.size }
                        if (pendingSize % 10 == 0) {
                            Log.d(TAG, "Pending new notes: $pendingSize total (all=${c.all} following=${c.following})")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling event: ${e.message}", e)
        }
    }

    /**
     * Schedule a batched kind-0 fetch so we don't fire one request per note. Runs after a short delay and fetches up to PROFILE_BATCH_SIZE authors at once.
     */
    private fun scheduleBatchProfileRequest(profileRelayUrls: List<String>) {
        profileBatchJob?.cancel()
        profileBatchJob = scope.launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val batch = synchronized(pendingProfilePubkeys) {
                pendingProfilePubkeys.take(PROFILE_BATCH_SIZE).also { pendingProfilePubkeys.removeAll(it.toSet()) }
            }
            if (batch.isNotEmpty() && profileRelayUrls.isNotEmpty()) {
                try {
                    profileCache.requestProfiles(batch, profileRelayUrls)
                } catch (e: Throwable) {
                    Log.e(TAG, "Batch profile request failed: ${e.message}", e)
                }
            }
            if (pendingProfilePubkeys.isNotEmpty()) {
                val urls = (cacheRelayUrls + subscriptionRelays).distinct().filter { it.isNotBlank() }
                if (urls.isNotEmpty()) scheduleBatchProfileRequest(urls)
            }
            profileBatchJob = null
        }
    }

    private fun convertEventToNote(event: Event, relayUrl: String): Note {
        val storedRelayUrl = relayUrl.ifEmpty { null }
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        val profileRelayUrls = (cacheRelayUrls + subscriptionRelays).distinct().filter { it.isNotBlank() }
        if (profileCache.getAuthor(pubkeyHex) == null && profileRelayUrls.isNotEmpty()) {
            pendingProfilePubkeys.add(pubkeyHex.lowercase())
            scheduleBatchProfileRequest(profileRelayUrls)
        }
        // Request kind-0 for pubkeys mentioned in content (npub + hex) so @mentions resolve to display names
        extractPubkeysFromContent(event.content).forEach { hex ->
            if (profileCache.getAuthor(hex) == null && profileRelayUrls.isNotEmpty()) {
                pendingProfilePubkeys.add(hex.lowercase())
            }
        }
        if (pendingProfilePubkeys.isNotEmpty() && profileRelayUrls.isNotEmpty()) scheduleBatchProfileRequest(profileRelayUrls)
        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }
        val mediaUrls = UrlDetector.findUrls(event.content).filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }.distinct()
        val quotedEventIds = Nip19QuoteParser.extractQuotedEventIds(event.content)
        val isReply = Nip10ReplyDetector.isReply(event)
        val relayUrls = if (storedRelayUrl != null) listOf(storedRelayUrl) else emptyList()
        return Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            quotedEventIds = quotedEventIds,
            relayUrl = storedRelayUrl,
            relayUrls = relayUrls,
            isReply = isReply
        )
    }

    fun clearNotes() {
        _notes.value = emptyList()
        _displayedNotes.value = emptyList()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        feedCutoffTimestampMs = 0L
        latestNoteTimestampAtOpen = 0L
        initialLoadComplete = false
        _feedSessionState.value = FeedSessionState.Idle
    }

    /**
     * Merge pending new notes into the visible list, update baseline, clear pending.
     * Call from pull-to-refresh so the user sees the held-back notes.
     */
    fun applyPendingNotes() {
        val toMerge = synchronized(pendingNotesLock) {
            if (_pendingNewNotes.isEmpty()) emptyList()
            else {
                val list = _pendingNewNotes.toList()
                _pendingNewNotes.clear()
                list
            }
        }
        if (toMerge.isEmpty()) return
        _feedSessionState.value = FeedSessionState.Refreshing
        val pendingCount = toMerge.size
        val merged = trimNotesToCap((_notes.value + toMerge).distinctBy { it.id }.sortedByDescending { it.timestamp })
        _notes.value = merged
        updateDisplayedNotes()
        latestNoteTimestampAtOpen = merged.maxOfOrNull { it.timestamp } ?: 0L
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        _feedSessionState.value = FeedSessionState.Live
        Log.d(TAG, "Applied $pendingCount pending notes (total: ${merged.size})")
    }

    /**
     * Refresh: merge pending notes into the feed and re-apply subscription (no clear).
     * Does not call subscribeToNotes() so the feed is not wiped; avoids the "recount" where total drops and rolls back up.
     */
    suspend fun refresh() {
        if (subscriptionRelays.isEmpty()) {
            Log.w(TAG, "Refresh skipped: no subscription relays")
            updateDisplayedNotes()
            return
        }
        Log.d(TAG, "Refresh: applying pending and re-subscribing (keeping ${_notes.value.size} notes)")
        applyPendingNotes()
        applySubscriptionToStateMachine(subscriptionRelays)
    }

    fun getConnectedRelays(): List<String> = connectedRelays

    fun isConnected(): Boolean = connectedRelays.isNotEmpty()

    /**
     * Get a note by id from the in-memory feed cache (e.g. when opening thread from notification).
     * Returns null if not in current feed.
     */
    fun getNoteFromCache(noteId: String): Note? = _notes.value.find { it.id == noteId }

    /**
     * Fetch a single note by id from relays (one-off subscription). Use when opening thread from
     * reply notification and the root note is not in the feed cache.
     */
    suspend fun fetchNoteById(noteId: String, relayUrls: List<String>): Note? {
        if (relayUrls.isEmpty()) return null
        val filter = Filter(kinds = listOf(1), ids = listOf(noteId), limit = 1)
        var fetched: Note? = null
        val relayUrl = relayUrls.firstOrNull() ?: ""
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscription(relayUrls, filter) { ev ->
            if (ev.kind == 1) fetched = convertEventToNote(ev, relayUrl)
        }
        delay(3000)
        handle.cancel()
        return fetched
    }

    /**
     * Apply author updates for a batch of pubkeys in one pass. Called by profile update coalescer.
     * One map over notes and pending instead of one per profile.
     */
    private fun updateAuthorsInNotesBatch(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        scope.launch {
            processEventMutex.withLock {
                try {
                    val authorMap = pubkeys.mapNotNull { key ->
                        profileCache.getAuthor(key)?.let { key to it }
                    }.toMap()
                    if (authorMap.isEmpty()) return@launch
                    _notes.value = _notes.value.map { note ->
                        val key = normalizeAuthorIdForCache(note.author.id)
                        authorMap[key]?.let { note.copy(author = it) } ?: note
                    }
                    synchronized(pendingNotesLock) {
                        val updated = _pendingNewNotes.map { note ->
                            val key = normalizeAuthorIdForCache(note.author.id)
                            authorMap[key]?.let { note.copy(author = it) } ?: note
                        }
                        _pendingNewNotes.clear()
                        _pendingNewNotes.addAll(updated)
                    }
                    updateDisplayedNotes()
                } catch (e: Throwable) {
                    Log.e(TAG, "updateAuthorsInNotesBatch failed: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Update notes for a single author (e.g. from external caller). Batched updates are handled internally by the coalescer.
     */
    fun updateAuthorInNotes(pubkey: String) {
        updateAuthorsInNotesBatch(setOf(pubkey.lowercase()))
    }

    /**
     * Re-resolve all authors in the current feed from the profile cache and update notes.
     * Call after a bulk profile load (e.g. debug "Fetch all") so the home feed reflects new cache data.
     * Does not depend on profileUpdated emissions (which can be dropped when many profiles load at once).
     */
    fun refreshAuthorsFromCache() {
        scope.launch {
            processEventMutex.withLock {
                try {
                    val allAuthorIds = mutableSetOf<String>().apply {
                        _notes.value.forEach { add(normalizeAuthorIdForCache(it.author.id)) }
                        synchronized(pendingNotesLock) {
                            _pendingNewNotes.forEach { add(normalizeAuthorIdForCache(it.author.id)) }
                        }
                    }
                    if (allAuthorIds.isEmpty()) return@launch
                    val authorMap = allAuthorIds.mapNotNull { key ->
                        profileCache.getAuthor(key)?.let { key to it }
                    }.toMap()
                    if (authorMap.isEmpty()) return@launch
                    _notes.value = _notes.value.map { note ->
                        val key = normalizeAuthorIdForCache(note.author.id)
                        authorMap[key]?.let { note.copy(author = it) } ?: note
                    }
                    synchronized(pendingNotesLock) {
                        val updated = _pendingNewNotes.map { note ->
                            val key = normalizeAuthorIdForCache(note.author.id)
                            authorMap[key]?.let { note.copy(author = it) } ?: note
                        }
                        _pendingNewNotes.clear()
                        _pendingNewNotes.addAll(updated)
                    }
                    updateDisplayedNotes()
                    Log.d(TAG, "refreshAuthorsFromCache: updated feed with ${authorMap.size} profiles")
                } catch (e: Throwable) {
                    Log.e(TAG, "refreshAuthorsFromCache failed: ${e.message}", e)
                }
            }
        }
    }
}
