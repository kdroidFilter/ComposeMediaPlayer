package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs


/**
 * A composable function that renders a video player surface using GStreamer with offscreen rendering.
 *
 * This function creates a video rendering area using a Compose Canvas to draw video frames
 * that are rendered offscreen by GStreamer. This approach avoids the rendering issues
 * that can occur when using SwingPanel, especially with overlapping UI elements.
 *
 * @param playerState The state object that encapsulates the GStreamer player logic,
 *                    including playback control, timeline management, and video frames.
 * @param modifier An optional `Modifier` for customizing the layout and appearance of the
 *                 composable container. Defaults to an empty `Modifier`.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */

@Composable
fun LinuxVideoPlayerSurface(
    playerState: LinuxVideoPlayerState,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false
) {
    Box(
        modifier = modifier.onSizeChanged {
            // If needed, add resize handling here
        },
        contentAlignment = Alignment.Center
    ) {
        if (playerState.hasMedia) {
            Canvas(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(playerState.aspectRatio)
            ) {
                // Draw the current frame if available
                playerState.currentFrame?.let { frame ->
                    drawImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                    )
                }
            }

            // Add Compose-based subtitle layer
            // Always render the subtitle layer, but let it handle visibility internally
            // This ensures it's properly recomposed when subtitles are enabled during playback
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

        // Render the overlay content on top of the video
        overlay()
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState)
    }
}
