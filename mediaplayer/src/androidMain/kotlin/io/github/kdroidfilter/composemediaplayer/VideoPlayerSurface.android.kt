package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs

@UnstableApi
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit
) {
    if (playerState.isFullscreen) {
        // Use FullScreenLayout for fullscreen mode
        FullScreenLayout(
            modifier = Modifier,
            onDismissRequest = { playerState.toggleFullscreen() }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoPlayerContent(
                    playerState = playerState,
                    modifier = Modifier.fillMaxHeight(),
                    overlay = overlay
                )
            }
        }
    } else {
        // Regular non-fullscreen display
        VideoPlayerContent(
            playerState = playerState,
            modifier = modifier,
            overlay = overlay
        )
    }
}

@UnstableApi
@Composable
private fun VideoPlayerContent(
    playerState: VideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
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

                        AspectRatioFrameLayout.RESIZE_MODE_FIT

                        // Disable native subtitle view since we're using Compose-based subtitles
                        subtitleView?.visibility = android.view.View.GONE

                        // Attach this view to the player state
                        playerState.attachPlayerView(this)
                    }
                },
                update = { playerView ->
                    // Update is handled by the player state
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
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor
                )
            }
        }

        // Render the overlay content on top of the video
        overlay()
    }
}
