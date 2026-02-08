package com.example.views.repository

import android.util.Log
import com.example.views.relay.RelayConnectionStateMachine
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fetches kind-3 (contact list) for a user and returns the set of followed pubkeys (p-tags).
 * Uses in-memory cache with TTL to avoid refetching on every screen/effect run.
 * Ensures we use the newest kind-3 across all relays: collect all kind-3 events in the timeout
 * window, then pick the single latest by createdAt (so the user's "write" relay's newer list wins).
 *
 * Also supports follow/unfollow by building a new kind-3 event, signing via Amber, and publishing.
 */
object ContactListRepository {

    private const val TAG = "ContactListRepository"
    /** Collect window so multiple relays can respond; we then pick latest by createdAt. */
    private const val KIND3_FETCH_TIMEOUT_MS = 2500L
    /** Cache TTL: 2 min so we don't rely on stale follow lists; forceRefresh on Following pull. */
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    private data class CacheEntry(val pubkey: String, val followSet: Set<String>, val timestampMs: Long)
    @Volatile
    private var cacheEntry: CacheEntry? = null

    /** The raw kind-3 Event most recently fetched for the current user, used to build follow/unfollow updates. */
    @Volatile
    private var latestKind3Event: Event? = null

    /**
     * Return cached follow list if present and not stale. Lock-free read.
     */
    fun getCachedFollowList(pubkey: String): Set<String>? {
        val entry = cacheEntry ?: return null
        if (entry.pubkey != pubkey) return null
        if (System.currentTimeMillis() - entry.timestampMs >= CACHE_TTL_MS) return null
        return entry.followSet
    }

    /**
     * Invalidate cache (e.g. after user follows/unfollows). Call when follow list may have changed.
     */
    fun invalidateCache(pubkey: String?) {
        if (pubkey == null) { cacheEntry = null; latestKind3Event = null }
        else if (cacheEntry?.pubkey == pubkey) { cacheEntry = null; latestKind3Event = null }
    }

    /**
     * Fetch kind-3 for the given pubkey from the given relays (e.g. cache + outbox); return set of p-tag pubkeys (hex 64).
     * Uses in-memory cache: if a valid cache exists for this pubkey, returns it without network. Otherwise fetches and caches.
     * @param forceRefresh if true, skip cache and refetch (e.g. pull-to-refresh on Following tab).
     */
    suspend fun fetchFollowList(pubkey: String, relayUrls: List<String>, forceRefresh: Boolean = false): Set<String> {
        if (relayUrls.isEmpty()) return emptySet()
        if (!forceRefresh) {
            getCachedFollowList(pubkey)?.let { cached ->
                Log.d(TAG, "Kind-3 cache hit for ${pubkey.take(8)}... (${cached.size} follows)")
                return cached
            }
        }
        val distinctUrls = relayUrls.distinct()
        Log.d(TAG, "Fetching kind-3 for ${pubkey.take(8)}... from ${distinctUrls.size} relay(s) (include write/outbox relays for latest list)")
        return try {
            val filter = Filter(
                kinds = listOf(3),
                authors = listOf(pubkey),
                limit = 20
            )
            val collected = CopyOnWriteArrayList<Event>()
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(distinctUrls, filter) { event ->
                if (event.kind == 3) collected.add(event)
            }
            delay(KIND3_FETCH_TIMEOUT_MS)
            handle.cancel()
            val event = collected.maxByOrNull { it.createdAt } ?: return emptySet()
            latestKind3Event = event
            val pubkeys = event.tags
                .map { it.toList() }
                .filter { it.isNotEmpty() && it[0] == "p" }
                .mapNotNull { list ->
                    val pk = list.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (pk.length == 64 && pk.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) pk.lowercase() else null
                }
                .toSet()
            cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
            Log.d(TAG, "Kind-3 parsed ${pubkeys.size} follows for ${pubkey.take(8)}...")
            pubkeys
        } catch (e: Exception) {
            Log.e(TAG, "Kind-3 fetch failed: ${e.message}", e)
            getCachedFollowList(pubkey) ?: emptySet()
        }
    }

    /**
     * Follow a user by building a new kind-3 event with the p-tag added, signing, and publishing.
     * Returns null on success, or an error message.
     */
    suspend fun follow(
        myPubkey: String,
        targetPubkey: String,
        signer: NostrSigner,
        outboxRelays: Set<NormalizedRelayUrl>,
        cacheRelayUrls: List<String>
    ): String? {
        return try {
            // Ensure we have the latest kind-3 event
            if (latestKind3Event == null || cacheEntry?.pubkey != myPubkey) {
                fetchFollowList(myPubkey, cacheRelayUrls + outboxRelays.map { it.toString() }, forceRefresh = true)
            }

            val existing = latestKind3Event
            val signed: ContactListEvent = if (existing != null) {
                // Re-wrap the raw Event as a ContactListEvent so we can use the helper
                val existingContact = ContactListEvent(
                    id = existing.id,
                    pubKey = existing.pubKey,
                    createdAt = existing.createdAt,
                    tags = existing.tags,
                    content = existing.content,
                    sig = existing.sig
                )
                ContactListEvent.followUser(existingContact, targetPubkey.lowercase(), signer)
            } else {
                // No existing list — create from scratch with just this follow
                val tags = arrayOf(arrayOf("p", targetPubkey.lowercase()))
                signer.sign<ContactListEvent>(
                    com.vitorpamplona.quartz.utils.TimeUtils.now(),
                    ContactListEvent.KIND,
                    tags,
                    ""
                )
            }

            // Publish to outbox relays
            Log.d(TAG, "Publishing follow kind-3 to ${outboxRelays.size} relays")
            RelayConnectionStateMachine.getInstance().nostrClient.send(signed, outboxRelays)

            // Update local cache
            latestKind3Event = signed
            val currentFollows = cacheEntry?.followSet?.toMutableSet() ?: mutableSetOf()
            currentFollows.add(targetPubkey.lowercase())
            cacheEntry = CacheEntry(myPubkey, currentFollows, System.currentTimeMillis())
            Log.d(TAG, "Followed ${targetPubkey.take(8)}... — now following ${currentFollows.size}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Follow failed: ${e.message}", e)
            "Follow failed: ${e.message}"
        }
    }

    /**
     * Unfollow a user by building a new kind-3 event with the p-tag removed, signing, and publishing.
     * Returns null on success, or an error message.
     */
    suspend fun unfollow(
        myPubkey: String,
        targetPubkey: String,
        signer: NostrSigner,
        outboxRelays: Set<NormalizedRelayUrl>,
        cacheRelayUrls: List<String>
    ): String? {
        return try {
            // Ensure we have the latest kind-3 event
            if (latestKind3Event == null || cacheEntry?.pubkey != myPubkey) {
                fetchFollowList(myPubkey, cacheRelayUrls + outboxRelays.map { it.toString() }, forceRefresh = true)
            }

            val existing = latestKind3Event
                ?: return "Cannot unfollow — no existing contact list found"

            val existingContact = ContactListEvent(
                id = existing.id,
                pubKey = existing.pubKey,
                createdAt = existing.createdAt,
                tags = existing.tags,
                content = existing.content,
                sig = existing.sig
            )
            val signed = ContactListEvent.unfollowUser(existingContact, targetPubkey.lowercase(), signer)

            // Publish to outbox relays
            Log.d(TAG, "Publishing unfollow kind-3 to ${outboxRelays.size} relays")
            RelayConnectionStateMachine.getInstance().nostrClient.send(signed, outboxRelays)

            // Update local cache
            latestKind3Event = signed
            val currentFollows = cacheEntry?.followSet?.toMutableSet() ?: mutableSetOf()
            currentFollows.remove(targetPubkey.lowercase())
            cacheEntry = CacheEntry(myPubkey, currentFollows, System.currentTimeMillis())
            Log.d(TAG, "Unfollowed ${targetPubkey.take(8)}... — now following ${currentFollows.size}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unfollow failed: ${e.message}", e)
            "Unfollow failed: ${e.message}"
        }
    }
}
