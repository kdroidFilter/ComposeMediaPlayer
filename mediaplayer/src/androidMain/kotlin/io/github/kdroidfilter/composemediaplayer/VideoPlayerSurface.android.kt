package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs

@UnstableApi
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit
) {
    // Use rememberSaveable to preserve fullscreen state across configuration changes
    var isFullscreen by rememberSaveable { 
        mutableStateOf(playerState.isFullscreen) 
    }
    
    // Keep the playerState.isFullscreen in sync with our saved state
    LaunchedEffect(isFullscreen) {
        if (playerState.isFullscreen != isFullscreen) {
            playerState.isFullscreen = isFullscreen
        }
    }
    
    // Listen for changes from playerState.isFullscreen
    LaunchedEffect(playerState.isFullscreen) {
        if (isFullscreen != playerState.isFullscreen) {
            isFullscreen = playerState.isFullscreen
        }
    }
    
    if (isFullscreen) {
        // Use FullScreenLayout for fullscreen mode
        FullScreenLayout(
            modifier = Modifier,
            onDismissRequest = { 
                isFullscreen = false
                // Call playerState.toggleFullscreen() to ensure proper cleanup
                playerState.toggleFullscreen()
            }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoPlayerContent(
                    playerState = playerState,
                    modifier = Modifier.fillMaxHeight(),
                    overlay = overlay,
                    contentScale = contentScale
                )
            }
        }
    } else {
        // Regular non-fullscreen display
        VideoPlayerContent(
            playerState = playerState,
            modifier = modifier,
            overlay = overlay,
            contentScale = contentScale
        )
    }
}

@UnstableApi
@Composable
private fun VideoPlayerContent(
    playerState: VideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
    contentScale: ContentScale
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            AndroidView(
                modifier =
                    contentScale.toCanvasModifier(playerState.aspectRatio,playerState.metadata.width,playerState.metadata.height),
                factory = { context ->
                    // Create PlayerView with subtitles support
                    PlayerView(context).apply {
                        // Attach the player from the state
                        player = playerState.exoPlayer
                        useController = false
                        defaultArtwork = null
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        // Map Compose ContentScale to ExoPlayer resize modes
                        resizeMode = when (contentScale) {
                            ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            ContentScale.Fit, ContentScale.Inside -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            ContentScale.FillWidth -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                            ContentScale.FillHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }

                        // Disable native subtitle view since we're using Compose-based subtitles
                        subtitleView?.visibility = android.view.View.GONE

                        // Attach this view to the player state
                        playerState.attachPlayerView(this)
                    }
                },
                update = { playerView ->
                    // Update the resize mode when contentScale changes
                    playerView.resizeMode = when (contentScale) {
                        ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        ContentScale.Fit, ContentScale.Inside -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        ContentScale.FillWidth -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                        ContentScale.FillHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onReset = { playerView ->
                    // Clean up resources when the view is recycled in a LazyList
                    playerView.player = null
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

        // Render the overlay content on top of the video with fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}
