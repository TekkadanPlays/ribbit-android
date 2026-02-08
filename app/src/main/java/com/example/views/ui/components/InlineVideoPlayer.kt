package com.example.views.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Inline video player for feed content.
 * Uses Media3 ExoPlayer and releases resources on dispose.
 */
@Composable
fun InlineVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false
) {
    val context = LocalContext.current
    val player = remember(url) { buildPlayer(context, url, autoPlay) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                this.player = player
            }
        }
    )
}

private fun buildPlayer(context: Context, url: String, autoPlay: Boolean): ExoPlayer {
    val player = ExoPlayer.Builder(context).build()
    val mediaItem = MediaItem.fromUri(url)
    player.setMediaItem(mediaItem)
    player.prepare()
    player.playWhenReady = autoPlay
    return player
}
