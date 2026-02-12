package com.example.views.ui.components

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton pool of ExoPlayer instances keyed by URL.
 * Allows the same player to be shared between inline feed and fullscreen views
 * so video doesn't stutter or restart on fullscreen toggle.
 *
 * Flow:
 * 1. Feed player calls [acquire] — creates a new player or returns existing one.
 * 2. On fullscreen, feed calls [detach] (keeps player alive but marks it unowned).
 * 3. Fullscreen calls [acquire] — gets the same player, no re-buffer.
 * 4. On exit fullscreen, fullscreen calls [detach], feed calls [acquire] again.
 * 5. When the composable truly disposes (leaves composition), call [release].
 */
object SharedPlayerPool {

    private data class Entry(
        val player: ExoPlayer,
        var ownerCount: Int = 0
    )

    private val pool = ConcurrentHashMap<String, Entry>()

    /**
     * Acquire a player for [url]. If one already exists in the pool, return it.
     * Otherwise create a new one. Increments the owner count.
     */
    fun acquire(context: Context, url: String): ExoPlayer {
        val entry = pool.getOrPut(url) {
            val player = ExoPlayer.Builder(context.applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = false
            }
            Entry(player, 0)
        }
        entry.ownerCount++
        return entry.player
    }

    /**
     * Detach from a player without releasing it.
     * Decrements owner count. Player stays in pool for the next [acquire].
     */
    fun detach(url: String) {
        val entry = pool[url] ?: return
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
    }

    /**
     * Release a player and remove it from the pool.
     * Only releases if owner count is zero (no other view is using it).
     * Returns true if the player was actually released.
     */
    fun release(url: String): Boolean {
        val entry = pool[url] ?: return false
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
        if (entry.ownerCount <= 0) {
            pool.remove(url)
            VideoPositionCache.set(url, entry.player.currentPosition)
            entry.player.release()
            return true
        }
        return false
    }

    /**
     * Force-release a player regardless of owner count.
     */
    fun forceRelease(url: String) {
        val entry = pool.remove(url) ?: return
        VideoPositionCache.set(url, entry.player.currentPosition)
        entry.player.release()
    }

    /**
     * Check if a player exists in the pool for [url].
     */
    fun has(url: String): Boolean = pool.containsKey(url)

    /**
     * Get the player for [url] without changing ownership. Returns null if not pooled.
     */
    fun peek(url: String): ExoPlayer? = pool[url]?.player
}
