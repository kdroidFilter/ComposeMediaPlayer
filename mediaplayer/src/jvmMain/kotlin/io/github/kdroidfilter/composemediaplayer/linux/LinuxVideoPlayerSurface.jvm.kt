package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
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
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */

@Composable
fun LinuxVideoPlayerSurface(
    playerState: LinuxVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false
) {

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Only render video in this surface if we're not in fullscreen mode or if this is the fullscreen window
        if (playerState.hasMedia && (!playerState.isFullscreen || isInFullscreenWindow)) {
            Canvas(
                modifier = contentScale.toCanvasModifier(
                    aspectRatio = playerState.aspectRatio,
                    width = playerState.metadata.width,
                    height = playerState.metadata.height
                )
            ) {
                playerState.currentFrame?.let { frame ->
                    drawScaledImage(
                        image        = frame,
                        dstSize      = IntSize(size.width.toInt(), size.height.toInt()),
                        contentScale = contentScale
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

        // Render the overlay content on top of the video with fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale)
    }
}
