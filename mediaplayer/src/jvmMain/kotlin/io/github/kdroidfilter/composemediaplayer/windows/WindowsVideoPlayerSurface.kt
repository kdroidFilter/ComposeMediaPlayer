package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs


/**
 * A composable function that provides a surface for rendering video frames
 * within the Windows video player. It adjusts to size changes and ensures the video
 * is displayed properly with respect to its aspect ratio.
 *
 * @param playerState The state of the Windows video player, used to manage video playback and rendering.
 * @param modifier The modifier to be used to adjust the layout or styling of the composable.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false,
) {
    // Keep track of when this instance is first composed with this player state
    val isFirstComposition = remember(playerState) { true }

    // Only trigger resizing on first composition with this player state
    LaunchedEffect(playerState) {
        if (isFirstComposition) {
            playerState.onResized()
        }
    }

    Box(
        modifier = modifier.onSizeChanged {
            playerState.onResized()
        },
        contentAlignment = Alignment.Center
    ) {
        // Only render video in this surface if we're not in fullscreen mode or if this is the fullscreen window
        if (playerState.hasMedia && (!playerState.isFullscreen || isInFullscreenWindow)) {
            // Force recomposition when currentFrameState changes
            val currentFrame by remember(playerState) { playerState.currentFrameState }

            currentFrame?.let { frame ->
                Canvas(
                    modifier = contentScale.toCanvasModifier(playerState.aspectRatio,playerState.metadata.width,playerState.metadata.height)
                ) {
                    drawScaledImage(
                        image        = frame,
                        dstSize      = IntSize(size.width.toInt(), size.height.toInt()),
                        contentScale = contentScale
                    )
                }
            }

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

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState, contentScale = contentScale, overlay = overlay)
    }
}
