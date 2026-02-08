package com.example.views.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.views.data.RelayCategory
import com.example.views.data.DefaultRelayCategories
import com.example.views.data.UserRelay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Manages relay storage per user (pubkey/npub).
 * Stores relay categories and personal relay configurations separately for each account.
 *
 * Storage Structure:
 * - relay_categories_{pubkey} → List<RelayCategory> (General tab categories)
 * - relay_personal_outbox_{pubkey} → List<UserRelay> (Personal tab - Outbox)
 * - relay_personal_inbox_{pubkey} → List<UserRelay> (Personal tab - Inbox)
 * - relay_personal_cache_{pubkey} → List<UserRelay> (Personal tab - Cache)
 */
class RelayStorageManager(val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("relay_storage", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    companion object {
        private const val KEY_CATEGORIES = "relay_categories"
        private const val KEY_PERSONAL_OUTBOX = "relay_personal_outbox"
        private const val KEY_PERSONAL_INBOX = "relay_personal_inbox"
        private const val KEY_PERSONAL_CACHE = "relay_personal_cache"

        /** Strip trailing slash from relay URLs so display/storage is consistent across the app. */
        fun normalizeRelayUrl(url: String): String = url.trim().removeSuffix("/")
    }

    private fun normalizeRelay(relay: UserRelay): UserRelay = relay.copy(url = normalizeRelayUrl(relay.url))
    private fun normalizeRelays(relays: List<UserRelay>): List<UserRelay> = relays.map { normalizeRelay(it) }
    private fun normalizeCategories(categories: List<RelayCategory>): List<RelayCategory> =
        categories.map { it.copy(relays = normalizeRelays(it.relays)) }

    // ====== General Tab - Relay Categories ======

    /**
     * Save relay categories for a specific user
     */
    fun saveCategories(pubkey: String, categories: List<RelayCategory>) {
        val key = "${KEY_CATEGORIES}_${pubkey}"
        val wrapper = RelayCategoriesWrapper(normalizeCategories(categories))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load relay categories for a specific user
     * Returns default category if no data exists
     */
    fun loadCategories(pubkey: String): List<RelayCategory> {
        val key = "${KEY_CATEGORIES}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<RelayCategoriesWrapper>(jsonString)
                normalizeCategories(wrapper.categories)
            } catch (e: Exception) {
                // If deserialization fails, return default
                DefaultRelayCategories.getAllDefaultCategories()
            }
        } else {
            DefaultRelayCategories.getAllDefaultCategories()
        }
    }

    // ====== Personal Tab - Outbox Relays ======

    /**
     * Save outbox relays for publishing notes
     */
    fun saveOutboxRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_OUTBOX}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load outbox relays
     */
    fun loadOutboxRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_OUTBOX}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
                normalizeRelays(wrapper.relays)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // ====== Personal Tab - Inbox Relays ======

    /**
     * Save inbox relays for receiving DMs and notifications
     */
    fun saveInboxRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_INBOX}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load inbox relays
     */
    fun loadInboxRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_INBOX}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
                normalizeRelays(wrapper.relays)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // ====== Personal Tab - Cache Relays ======

    /**
     * Save cache relays for fetching profiles and metadata
     */
    fun saveCacheRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_CACHE}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load cache relays
     */
    fun loadCacheRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_CACHE}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
                normalizeRelays(wrapper.relays)
            } catch (e: Exception) {
                getDefaultCacheRelays()
            }
        } else {
            // First time loading - return and save default cache relays
            val defaults = getDefaultCacheRelays()
            saveCacheRelays(pubkey, defaults)
            defaults
        }
    }

    /**
     * Get default cache relays for fetching profiles and metadata
     */
    private fun getDefaultCacheRelays(): List<UserRelay> {
        return listOf(
            UserRelay(
                url = "wss://purplepag.es",
                read = true,
                write = false
            ),
            UserRelay(
                url = "wss://user.kindpag.es",
                read = true,
                write = false
            ),
            UserRelay(
                url = "wss://indexer.coracle.social",
                read = true,
                write = false
            ),
            UserRelay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false
            )
        )
    }

    // ====== Utility Methods ======

    /**
     * Get all relay URLs from all categories and personal relays for a user
     * Useful for connecting to all user's relays at once
     */
    fun getAllRelayUrls(pubkey: String): Set<String> {
        val categories = loadCategories(pubkey)
        val outbox = loadOutboxRelays(pubkey)
        val inbox = loadInboxRelays(pubkey)
        val cache = loadCacheRelays(pubkey)

        val allRelays = mutableSetOf<String>()

        // Add from categories
        categories.forEach { category ->
            category.relays.forEach { relay ->
                allRelays.add(relay.url)
            }
        }

        // Add from personal relays
        outbox.forEach { allRelays.add(it.url) }
        inbox.forEach { allRelays.add(it.url) }
        cache.forEach { allRelays.add(it.url) }

        return allRelays
    }

    /**
     * Clear all relay data for a specific user
     */
    fun clearUserData(pubkey: String) {
        prefs.edit()
            .remove("${KEY_CATEGORIES}_${pubkey}")
            .remove("${KEY_PERSONAL_OUTBOX}_${pubkey}")
            .remove("${KEY_PERSONAL_INBOX}_${pubkey}")
            .remove("${KEY_PERSONAL_CACHE}_${pubkey}")
            .apply()
    }

    /**
     * Check if user has any saved relay data
     */
    fun hasUserData(pubkey: String): Boolean {
        return prefs.contains("${KEY_CATEGORIES}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_OUTBOX}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_INBOX}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_CACHE}_${pubkey}")
    }
}

// ====== Serialization Wrappers ======

@Serializable
private data class RelayCategoriesWrapper(
    val categories: List<RelayCategory>
)

@Serializable
private data class UserRelayListWrapper(
    val relays: List<UserRelay>
)
