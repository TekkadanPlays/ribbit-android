package com.example.views.ui.components

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Inline video player for feed content.
 *
 * Uses [SharedPlayerPool] so the same ExoPlayer instance transfers between
 * inline feed and fullscreen views without re-buffering or stuttering.
 *
 * In feed mode (autoPlay=false): autoplays muted with modern pill overlay controls.
 * In fullscreen mode (autoPlay=true): acquires the pooled player with matching controls.
 *
 * @param isVisible Whether this player is currently visible on screen.
 * @param onFullscreenClick Callback for fullscreen toggle (feed mode only).
 * @param onExitFullscreen Callback for exiting fullscreen (fullscreen mode only).
 */
@Composable
fun InlineVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false,
    isVisible: Boolean = true,
    onFullscreenClick: () -> Unit = {},
    onExitFullscreen: () -> Unit = {}
) {
    if (autoPlay) {
        FullVideoPlayer(url = url, modifier = modifier, isVisible = isVisible, onExitFullscreen = onExitFullscreen)
    } else {
        FeedVideoPlayer(url = url, modifier = modifier, isVisible = isVisible, onFullscreenClick = onFullscreenClick)
    }
}

/**
 * Feed-mode video: autoplays muted with modern overlay controls.
 * Uses [SharedPlayerPool] so the player survives fullscreen transitions.
 */
@Composable
private fun FeedVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onFullscreenClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var player by remember(url) { mutableStateOf<ExoPlayer?>(null) }
    var isMuted by remember(url) { mutableStateOf(true) }
    var isActuallyPlaying by remember(url) { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Create player immediately, autoplay muted
    LaunchedEffect(url) {
        if (player == null) {
            val p = SharedPlayerPool.acquire(context, url)
            val cached = VideoPositionCache.get(url)
            if (cached > 0) p.seekTo(cached)
            p.volume = 0f
            p.playWhenReady = true
            player = p
        }
    }

    // Pause/resume based on visibility
    LaunchedEffect(isVisible, player) {
        val p = player ?: return@LaunchedEffect
        if (isVisible) {
            val cached = VideoPositionCache.get(url)
            if (cached > 0 && kotlin.math.abs(p.currentPosition - cached) > 500) {
                p.seekTo(cached)
            }
            if (isActuallyPlaying) p.play()
        } else {
            VideoPositionCache.set(url, p.currentPosition)
            p.pause()
        }
    }

    DisposableEffect(url) {
        onDispose {
            player?.let { VideoPositionCache.set(url, it.currentPosition) }
            SharedPlayerPool.release(url)
            player = null
        }
    }

    Box(modifier = modifier) {
        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = player
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setOnTouchListener { v, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                    }
                },
                update = { view -> view.player = player }
            )

            // Tap to show/hide controls
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showControls = !showControls }
            )

            // Modern controls overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                VideoControlsPill(
                    isMuted = isMuted,
                    isPlaying = isActuallyPlaying,
                    onMuteToggle = {
                        isMuted = !isMuted
                        player?.volume = if (isMuted) 0f else 1f
                    },
                    onPlayPauseToggle = {
                        isActuallyPlaying = !isActuallyPlaying
                        if (isActuallyPlaying) player?.play() else player?.pause()
                    },
                    onScreenToggle = {
                        player?.let { VideoPositionCache.set(url, it.currentPosition) }
                        SharedPlayerPool.detach(url)
                        player = null
                        onFullscreenClick()
                    },
                    isFullscreen = false
                )
            }
        }
    }
}

/**
 * Fullscreen video player: acquires the pooled ExoPlayer for seamless transition.
 * Uses the same modern pill overlay controls as the feed player.
 */
@Composable
private fun FullVideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onExitFullscreen: () -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember(url) {
        val p = SharedPlayerPool.acquire(context, url)
        val cached = VideoPositionCache.get(url)
        if (cached > 0 && kotlin.math.abs(p.currentPosition - cached) > 500) {
            p.seekTo(cached)
        }
        p.playWhenReady = true
        p
    }

    var isMuted by remember(url) { mutableStateOf(player.volume == 0f) }
    var isActuallyPlaying by remember(url) { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls after 3s
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Pause/resume when paging between videos in fullscreen viewer
    LaunchedEffect(isVisible) {
        if (isVisible) {
            val cached = VideoPositionCache.get(url)
            if (cached > 0 && kotlin.math.abs(player.currentPosition - cached) > 500) {
                player.seekTo(cached)
            }
            player.play()
        } else {
            VideoPositionCache.set(url, player.currentPosition)
            player.pause()
        }
    }

    DisposableEffect(url) {
        onDispose {
            VideoPositionCache.set(url, player.currentPosition)
            SharedPlayerPool.detach(url)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { view -> view.player = player }
        )

        // Tap to show/hide controls
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { showControls = !showControls }
        )

        // Modern controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            VideoControlsPill(
                isMuted = isMuted,
                isPlaying = isActuallyPlaying,
                onMuteToggle = {
                    isMuted = !isMuted
                    player.volume = if (isMuted) 0f else 1f
                },
                onPlayPauseToggle = {
                    isActuallyPlaying = !isActuallyPlaying
                    if (isActuallyPlaying) player.play() else player.pause()
                },
                onScreenToggle = {
                    VideoPositionCache.set(url, player.currentPosition)
                    SharedPlayerPool.detach(url)
                    onExitFullscreen()
                },
                isFullscreen = true
            )
        }
    }
}

/**
 * Shared pill-shaped controls overlay used by both feed and fullscreen players.
 */
@Composable
private fun VideoControlsPill(
    isMuted: Boolean,
    isPlaying: Boolean,
    onMuteToggle: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onScreenToggle: () -> Unit,
    isFullscreen: Boolean
) {
    Row(
        modifier = Modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Play/Pause
        IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        // Mute toggle
        IconButton(onClick = onMuteToggle, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        // Fullscreen / Exit fullscreen
        IconButton(onClick = onScreenToggle, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
