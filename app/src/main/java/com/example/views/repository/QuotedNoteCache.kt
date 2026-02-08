package com.example.views.repository

import android.util.Log
import com.example.views.data.QuotedNoteMeta
import com.example.views.relay.RelayConnectionStateMachine
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Fetches quoted kind-1 events by id from cache relays and returns minimal metadata.
 * Bounded with LRU eviction; supports trim for memory pressure.
 */
object QuotedNoteCache {

    private const val TAG = "QuotedNoteCache"
    private const val FETCH_TIMEOUT_MS = 4000L
    private const val SNIPPET_MAX_LEN = 150
    private const val MAX_ENTRIES = 200

    /** Size to trim to when UI is hidden. */
    const val TRIM_SIZE_UI_HIDDEN = 100

    /** Size to trim to when app is in background. */
    const val TRIM_SIZE_BACKGROUND = 50

    // LRU map: access order, eldest evicted when over MAX_ENTRIES.
    private val memoryCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, QuotedNoteMeta>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuotedNoteMeta>): Boolean =
                size > MAX_ENTRIES
        }
    )
    private val defaultCacheRelays = listOf(
        "wss://purplepag.es",
        "wss://user.kindpag.es",
        "wss://indexer.coracle.social"
    )

    /**
     * Get quoted note metadata by event id (hex). Returns from memory cache or fetches from cache relays.
     */
    suspend fun get(eventId: String): QuotedNoteMeta? {
        if (eventId.isBlank() || eventId.length != 64) return null
        memoryCache[eventId]?.let { return it }
        return fetchAndCache(eventId)
    }

    private suspend fun fetchAndCache(eventId: String): QuotedNoteMeta? {
        return try {
            val filter = Filter(
                kinds = listOf(1),
                ids = listOf(eventId),
                limit = 1
            )
            val eventRef = AtomicReference<Event?>(null)
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(defaultCacheRelays, filter) { e ->
                if (e.kind == 1 && e.id == eventId) eventRef.set(e)
            }
            delay(FETCH_TIMEOUT_MS)
            handle.cancel()
            eventRef.get()?.let { e ->
                val meta = QuotedNoteMeta(
                    eventId = e.id,
                    authorId = e.pubKey,
                    contentSnippet = e.content.take(SNIPPET_MAX_LEN).let { if (e.content.length > SNIPPET_MAX_LEN) "$it…" else it }
                )
                memoryCache[eventId] = meta
                meta
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quoted note fetch failed for ${eventId.take(8)}: ${e.message}")
            null
        }
    }

    fun clear() {
        memoryCache.clear()
    }

    /**
     * Reduce cache to at most maxEntries (LRU eviction).
     * Thread-safe.
     */
    fun trimToSize(maxEntries: Int) {
        synchronized(memoryCache) {
            while (memoryCache.size > maxEntries && memoryCache.isNotEmpty()) {
                val eldest = memoryCache.keys.iterator().next()
                memoryCache.remove(eldest)
            }
        }
    }
}
