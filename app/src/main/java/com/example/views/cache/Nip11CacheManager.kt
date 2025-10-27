package com.example.views.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.example.views.data.RelayInformation
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Manages persistent caching of NIP-11 relay information with 24-hour expiration
 */
class Nip11CacheManager(private val context: Context) {
    companion object {
        private const val TAG = "Nip11CacheManager"
        private const val CACHE_PREFS = "nip11_cache"
        private const val CACHE_DATA_KEY = "cache_data"
        private const val CACHE_TIMESTAMPS_KEY = "cache_timestamps"
        private const val CACHE_EXPIRY_HOURS = 24
        private val JSON = Json { ignoreUnknownKeys = true }
    }
    
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // In-memory cache for fast access
    private val memoryCache = mutableMapOf<String, CachedRelayInfo>()
    
    init {
        loadCacheFromStorage()
    }
    
    /**
     * Get relay information from cache or fetch if expired/missing
     */
    suspend fun getRelayInfo(url: String, forceRefresh: Boolean = false): RelayInformation? = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = normalizeRelayUrl(url)
            
            // Check if we have valid cached data
            if (!forceRefresh) {
                val cached = memoryCache[normalizedUrl]
                if (cached != null && !isExpired(cached.timestamp)) {
                    Log.d(TAG, "📋 Using cached NIP-11 data for $normalizedUrl")
                    return@withContext cached.info
                }
            }
            
            // Fetch fresh data
            Log.d(TAG, "🌐 Fetching fresh NIP-11 data for $normalizedUrl")
            val freshInfo = fetchRelayInfoFromNetwork(normalizedUrl)
            
            // Cache the result
            if (freshInfo != null) {
                cacheRelayInfo(normalizedUrl, freshInfo)
            }
            
            freshInfo
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to get relay info for $url: ${e.message}")
            null
        }
    }
    
    /**
     * Get relay information from cache only (no network fetch)
     */
    fun getCachedRelayInfo(url: String): RelayInformation? {
        val normalizedUrl = normalizeRelayUrl(url)
        val cached = memoryCache[normalizedUrl]
        return if (cached != null && !isExpired(cached.timestamp)) {
            cached.info
        } else {
            null
        }
    }
    
    /**
     * Check if relay info exists in cache (even if expired)
     */
    fun hasCachedRelayInfo(url: String): Boolean {
        val normalizedUrl = normalizeRelayUrl(url)
        return memoryCache.containsKey(normalizedUrl)
    }
    
    /**
     * Get all cached relay URLs (for background refresh)
     */
    fun getAllCachedRelayUrls(): List<String> {
        return memoryCache.keys.toList()
    }
    
    /**
     * Get relays that need refresh (older than 24 hours)
     */
    fun getStaleRelayUrls(): List<String> {
        val now = System.currentTimeMillis()
        return memoryCache.filter { (_, cached) ->
            isExpired(cached.timestamp)
        }.keys.toList()
    }
    
    /**
     * Refresh stale relay information in background
     */
    fun refreshStaleRelays(scope: CoroutineScope, onComplete: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                val staleUrls = getStaleRelayUrls()
                Log.d(TAG, "🔄 Refreshing ${staleUrls.size} stale relay info entries")
                
                staleUrls.forEach { url ->
                    try {
                        val freshInfo = fetchRelayInfoFromNetwork(url)
                        if (freshInfo != null) {
                            cacheRelayInfo(url, freshInfo)
                            Log.d(TAG, "✅ Refreshed NIP-11 data for $url")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to refresh $url: ${e.message}")
                    }
                }
                
                onComplete?.invoke()
                Log.d(TAG, "🎉 Background refresh completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Background refresh failed: ${e.message}", e)
                onComplete?.invoke()
            }
        }
    }
    
    /**
     * Preload relay information for a list of URLs
     */
    fun preloadRelayInfo(urls: List<String>, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            urls.forEach { url ->
                if (!hasCachedRelayInfo(url) || getStaleRelayUrls().contains(url)) {
                    try {
                        getRelayInfo(url)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to preload $url: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Clear expired entries from cache
     */
    fun clearExpiredEntries() {
        val now = System.currentTimeMillis()
        val expiredUrls = memoryCache.filter { (_, cached) ->
            isExpired(cached.timestamp)
        }.keys.toList()
        
        expiredUrls.forEach { url ->
            memoryCache.remove(url)
        }
        
        if (expiredUrls.isNotEmpty()) {
            saveCacheToStorage()
            Log.d(TAG, "🧹 Cleared ${expiredUrls.size} expired cache entries")
        }
    }
    
    /**
     * Clear all cache data
     */
    fun clearAllCache() {
        memoryCache.clear()
        sharedPrefs.edit().clear().apply()
        Log.d(TAG, "🧹 Cleared all NIP-11 cache data")
    }
    
    /**
     * Fetch relay information from network
     */
    private suspend fun fetchRelayInfoFromNetwork(url: String): RelayInformation? = withContext(Dispatchers.IO) {
        try {
            // Convert WebSocket URL to HTTP for NIP-11
            val httpUrl = url.replace("wss://", "https://").replace("ws://", "http://")
            
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                if (responseBody.startsWith("{")) {
                    val relayInfo = JSON.decodeFromString<RelayInformation>(responseBody)
                    Log.d(TAG, "✅ Fetched NIP-11 info for $url: ${relayInfo.name}")
                    return@withContext relayInfo
                }
            }
            
            Log.w(TAG, "⚠️ Invalid response for $url")
            null
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Network error for $url: ${e.message}")
            null
        }
    }
    
    /**
     * Cache relay information
     */
    private fun cacheRelayInfo(url: String, info: RelayInformation) {
        val cached = CachedRelayInfo(
            url = url,
            info = info,
            timestamp = System.currentTimeMillis()
        )
        memoryCache[url] = cached
        saveCacheToStorage()
    }
    
    /**
     * Check if cached data is expired
     */
    private fun isExpired(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val expiryTime = CACHE_EXPIRY_HOURS * 60 * 60 * 1000L
        return (now - timestamp) > expiryTime
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
     * Load cache from persistent storage
     */
    private fun loadCacheFromStorage() {
        try {
            val cacheDataJson = sharedPrefs.getString(CACHE_DATA_KEY, null)
            val timestampsJson = sharedPrefs.getString(CACHE_TIMESTAMPS_KEY, null)
            
            if (cacheDataJson != null && timestampsJson != null) {
                val cacheData = JSON.decodeFromString<Map<String, RelayInformation>>(cacheDataJson)
                val timestamps = JSON.decodeFromString<Map<String, Long>>(timestampsJson)
                
                cacheData.forEach { (url, info) ->
                    val timestamp = timestamps[url] ?: 0L
                    memoryCache[url] = CachedRelayInfo(url, info, timestamp)
                }
                
                Log.d(TAG, "💾 Loaded ${memoryCache.size} cached relay info entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load cache from storage: ${e.message}", e)
        }
    }
    
    /**
     * Save cache to persistent storage
     */
    private fun saveCacheToStorage() {
        try {
            val cacheData = memoryCache.mapValues { it.value.info }
            val timestamps = memoryCache.mapValues { it.value.timestamp }
            
            val cacheDataJson = JSON.encodeToString(cacheData)
            val timestampsJson = JSON.encodeToString(timestamps)
            
            sharedPrefs.edit()
                .putString(CACHE_DATA_KEY, cacheDataJson)
                .putString(CACHE_TIMESTAMPS_KEY, timestampsJson)
                .apply()
                
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save cache to storage: ${e.message}", e)
        }
    }
    
    /**
     * Data class for cached relay information
     */
    private data class CachedRelayInfo(
        val url: String,
        val info: RelayInformation,
        val timestamp: Long
    )
}
