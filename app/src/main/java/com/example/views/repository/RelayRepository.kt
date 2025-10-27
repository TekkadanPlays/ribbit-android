package com.example.views.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.example.views.data.UserRelay
import com.example.views.data.RelayInformation
import com.example.views.data.RelayConnectionStatus
import com.example.views.data.RelayHealth
import com.example.views.cache.Nip11CacheManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Repository for managing user relays and NIP-11 information
 */
class RelayRepository(private val context: Context) {
    companion object {
        private const val TAG = "RelayRepository"
        private const val RELAYS_PREFS = "user_relays"
        private const val RELAYS_KEY = "relays_list"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(RELAYS_PREFS, Context.MODE_PRIVATE)
    private val nip11CacheManager = Nip11CacheManager(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // StateFlow for reactive UI updates
    private val _relays = MutableStateFlow<List<UserRelay>>(emptyList())
    val relays: StateFlow<List<UserRelay>> = _relays
    
    // StateFlow for connection status updates
    private val _connectionStatus = MutableStateFlow<Map<String, RelayConnectionStatus>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, RelayConnectionStatus>> = _connectionStatus
    
    init {
        loadRelaysFromStorage()
        // Start background refresh of stale NIP-11 data
        startBackgroundRefresh()
    }
    
    /**
     * Add a new relay to the user's list
     */
    suspend fun addRelay(url: String, read: Boolean = true, write: Boolean = true): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            // Normalize URL
            val normalizedUrl = normalizeRelayUrl(url)
            
            // Check if relay already exists
            val existingRelays = _relays.value
            if (existingRelays.any { it.url == normalizedUrl }) {
                return@withContext Result.failure(Exception("Relay already exists"))
            }
            
            // Check cache first for immediate NIP-11 info
            val cachedInfo = nip11CacheManager.getCachedRelayInfo(normalizedUrl)
            
            // Create new relay with cached NIP-11 info if available
            val newRelay = UserRelay(
                url = normalizedUrl,
                read = read,
                write = write,
                addedAt = System.currentTimeMillis(),
                info = cachedInfo,
                isOnline = cachedInfo != null,
                lastChecked = if (cachedInfo != null) System.currentTimeMillis() else 0L
            )
            
            // Add to list immediately for fast UI response
            val updatedRelays = existingRelays + newRelay
            _relays.value = updatedRelays
            
            // Persist to storage
            saveRelaysToStorage(updatedRelays)
            
            Log.d(TAG, "✅ Added relay: $normalizedUrl ${if (cachedInfo != null) "(with cached NIP-11)" else "(fetching NIP-11)"}")
            
            // Fetch fresh NIP-11 information in background if not cached or stale
            if (cachedInfo == null || nip11CacheManager.getStaleRelayUrls().contains(normalizedUrl)) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val freshInfo = nip11CacheManager.getRelayInfo(normalizedUrl, forceRefresh = true)
                        
                        // Update the relay with fresh NIP-11 info
                        val currentRelays = _relays.value
                        val relayIndex = currentRelays.indexOfFirst { it.url == normalizedUrl }
                        if (relayIndex != -1) {
                            val updatedRelay = currentRelays[relayIndex].copy(
                                info = freshInfo,
                                isOnline = freshInfo != null,
                                lastChecked = System.currentTimeMillis()
                            )
                            val updatedList = currentRelays.toMutableList().apply {
                                set(relayIndex, updatedRelay)
                            }
                            _relays.value = updatedList
                            saveRelaysToStorage(updatedList)
                            Log.d(TAG, "✅ Updated relay with fresh NIP-11 info: $normalizedUrl")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to fetch NIP-11 info for $normalizedUrl: ${e.message}")
                    }
                }
            }
            
            Result.success(newRelay)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to add relay: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Remove a relay from the user's list
     */
    suspend fun removeRelay(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val updatedRelays = existingRelays.filter { it.url != url }
            
            if (updatedRelays.size == existingRelays.size) {
                return@withContext Result.failure(Exception("Relay not found"))
            }
            
            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)
            
            Log.d(TAG, "🗑️ Removed relay: $url")
            Result.success(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove relay: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Update relay settings (read/write permissions)
     */
    suspend fun updateRelaySettings(url: String, read: Boolean, write: Boolean): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val relayIndex = existingRelays.indexOfFirst { it.url == url }
            
            if (relayIndex == -1) {
                return@withContext Result.failure(Exception("Relay not found"))
            }
            
            val updatedRelay = existingRelays[relayIndex].copy(read = read, write = write)
            val updatedRelays = existingRelays.toMutableList().apply {
                set(relayIndex, updatedRelay)
            }
            
            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)
            
            Log.d(TAG, "✅ Updated relay settings: $url (read=$read, write=$write)")
            Result.success(updatedRelay)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update relay settings: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Refresh relay information (NIP-11)
     */
    suspend fun refreshRelayInfo(url: String): Result<UserRelay> = withContext(Dispatchers.IO) {
        try {
            val existingRelays = _relays.value
            val relayIndex = existingRelays.indexOfFirst { it.url == url }
            
            if (relayIndex == -1) {
                return@withContext Result.failure(Exception("Relay not found"))
            }
            
            val relay = existingRelays[relayIndex]
            
            // Force refresh from cache manager
            val freshInfo = nip11CacheManager.getRelayInfo(url, forceRefresh = true)
            
            val updatedRelay = relay.copy(
                info = freshInfo,
                isOnline = freshInfo != null,
                lastChecked = System.currentTimeMillis()
            )
            
            val updatedRelays = existingRelays.toMutableList().apply {
                set(relayIndex, updatedRelay)
            }
            
            _relays.value = updatedRelays
            saveRelaysToStorage(updatedRelays)
            
            Log.d(TAG, "🔄 Refreshed relay info: $url")
            Result.success(updatedRelay)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to refresh relay info: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Test relay connection
     */
    suspend fun testRelayConnection(url: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Update connection status to connecting
            updateConnectionStatus(url, RelayConnectionStatus.CONNECTING)
            
            // Convert WebSocket URL to HTTP for NIP-11 check
            val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
            
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            val isConnected = response.isSuccessful
            updateConnectionStatus(url, if (isConnected) RelayConnectionStatus.CONNECTED else RelayConnectionStatus.ERROR)
            
            Log.d(TAG, "🔍 Tested relay connection: $url -> $isConnected")
            Result.success(isConnected)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to test relay connection: ${e.message}", e)
            updateConnectionStatus(url, RelayConnectionStatus.ERROR)
            Result.failure(e)
        }
    }
    
    /**
     * Get relay health status
     */
    fun getRelayHealth(relay: UserRelay): RelayHealth {
        return when {
            !relay.isOnline -> RelayHealth.CRITICAL
            relay.lastChecked == 0L -> RelayHealth.UNKNOWN
            System.currentTimeMillis() - relay.lastChecked > 300000 -> RelayHealth.WARNING // 5 minutes
            else -> RelayHealth.HEALTHY
        }
    }
    
    /**
     * Get NIP-11 cache manager for external access
     */
    fun getNip11CacheManager(): Nip11CacheManager = nip11CacheManager
    
    /**
     * Start background refresh of stale NIP-11 data
     */
    private fun startBackgroundRefresh() {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                // Small delay to let the app initialize
                kotlinx.coroutines.delay(2000)
                
                // Refresh stale relay information
                nip11CacheManager.refreshStaleRelays(
                    scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                ) {
                    Log.d(TAG, "🎉 Background NIP-11 refresh completed")
                }
                
                // Preload relay info for current relays
                val currentRelays = _relays.value
                val relayUrls = currentRelays.map { it.url }
                nip11CacheManager.preloadRelayInfo(relayUrls, kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Background refresh failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * Normalize relay URL
     */
    private fun normalizeRelayUrl(url: String): String {
        return when {
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            url.startsWith("https://") -> url.replace("https://", "wss://")
            url.startsWith("http://") -> url.replace("http://", "ws://")
            else -> "wss://$url"
        }
    }
    
    /**
     * Update connection status for a relay
     */
    private fun updateConnectionStatus(url: String, status: RelayConnectionStatus) {
        val currentStatus = _connectionStatus.value.toMutableMap()
        currentStatus[url] = status
        _connectionStatus.value = currentStatus
    }
    
    /**
     * Load relays from persistent storage
     */
    private fun loadRelaysFromStorage() {
        try {
            val relaysJson = sharedPrefs.getString(RELAYS_KEY, null)
            if (relaysJson != null) {
                val relays = JSON.decodeFromString<List<UserRelay>>(relaysJson)
                _relays.value = relays
                Log.d(TAG, "💾 Loaded ${relays.size} relays from storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load relays from storage: ${e.message}", e)
        }
    }
    
    /**
     * Save relays to persistent storage
     */
    private fun saveRelaysToStorage(relays: List<UserRelay>) {
        try {
            val relaysJson = JSON.encodeToString(relays)
            sharedPrefs.edit()
                .putString(RELAYS_KEY, relaysJson)
                .apply()
            Log.d(TAG, "💾 Saved ${relays.size} relays to storage")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save relays to storage: ${e.message}", e)
        }
    }
    
    /**
     * Clear all relays
     */
    suspend fun clearAllRelays(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            _relays.value = emptyList()
            _connectionStatus.value = emptyMap()
            sharedPrefs.edit().clear().apply()
            Log.d(TAG, "🧹 Cleared all relays")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear relays: ${e.message}", e)
            Result.failure(e)
        }
    }
}
