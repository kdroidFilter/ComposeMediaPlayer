package io.github.kdroidfilter.composemediaplayer

import android.util.TypedValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer

@UnstableApi
@Composable
actual fun VideoPlayerSurface(playerState: VideoPlayerState, modifier: Modifier) {
    val context = LocalContext.current

    // Handle fullscreen mode
    val currentContext = rememberUpdatedState(context)
    val currentPlayerState = rememberUpdatedState(playerState)

    LaunchedEffect(playerState.isFullscreen) {
        val ctx = currentContext.value
        val state = currentPlayerState.value

        // Only launch fullscreen activity if we're not already in a FullscreenPlayerActivity
        if (state.isFullscreen && ctx !is FullscreenPlayerActivity) {
            // Register the player state and launch fullscreen activity
            VideoPlayerStateRegistry.registerState(state)
            FullscreenPlayerActivity.launch(ctx, state)
        }
    }

    // Clean up when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            // If we're in fullscreen mode, exit it
            if (playerState.isFullscreen) {
                playerState.isFullscreen = false
                VideoPlayerStateRegistry.clearRegisteredState()
            }
        }
    }

    // In the original activity, only show the video player when not in fullscreen mode
    // In the FullscreenPlayerActivity, always show the video player
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val isFullscreenActivity = context is FullscreenPlayerActivity
        if (playerState.hasMedia && (isFullscreenActivity || !playerState.isFullscreen)) {
            AndroidView(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(playerState.aspectRatio),
                factory = { context ->
                    // Create PlayerView with subtitles support
                    PlayerView(context).apply {
                        // Attach the player from the state
                        player = playerState.exoPlayer
                        useController = false
                        defaultArtwork = null
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        // Set resize mode based on context
                        resizeMode = if (context is FullscreenPlayerActivity) {
                            // In fullscreen mode, fill the screen
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        } else {
                            // In normal mode, maintain aspect ratio
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }

                        // Disable native subtitle view since we're using Compose-based subtitles
                        subtitleView?.visibility = android.view.View.GONE

                        // Attach this view to the player state
                        playerState.attachPlayerView(this)
                    }
                },
                update = { playerView ->
                    // No need to update anything here since we're only showing
                    // the video player when not in fullscreen mode
                }
            )

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
                // Calculate current time in milliseconds
                val currentTimeMs = (playerState.sliderPos / 1000f * 
                    playerState.durationText.toTimeMs()).toLong()

                // Calculate duration in milliseconds
                val durationMs = playerState.durationText.toTimeMs()

                ComposeSubtitleLayer(
                    currentTimeMs = currentTimeMs,
                    durationMs = durationMs,
                    isPlaying = playerState.isPlaying,
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle
                )
            }
        }
    }
}

/**
 * Converts a time string in the format "mm:ss" or "hh:mm:ss" to milliseconds.
 */
private fun String.toTimeMs(): Long {
    val parts = this.split(":")
    return when (parts.size) {
        2 -> {
            // Format: "mm:ss"
            val minutes = parts[0].toLongOrNull() ?: 0
            val seconds = parts[1].toLongOrNull() ?: 0
            (minutes * 60 + seconds) * 1000
        }
        3 -> {
            // Format: "hh:mm:ss"
            val hours = parts[0].toLongOrNull() ?: 0
            val minutes = parts[1].toLongOrNull() ?: 0
            val seconds = parts[2].toLongOrNull() ?: 0
            (hours * 3600 + minutes * 60 + seconds) * 1000
        }
        else -> 0
    }
}
