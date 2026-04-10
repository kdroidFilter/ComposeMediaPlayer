package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
 * A composable function that renders a video player surface using a native GStreamer
 * player via JNI with offscreen rendering.
 *
 * @param playerState The state object that encapsulates the native GStreamer player logic.
 * @param modifier An optional `Modifier` for customizing the layout and appearance.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 * @param overlay Optional composable content to be displayed on top of the video surface.
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
        modifier = modifier.onSizeChanged { size ->
            playerState.onResized(size.width, size.height)
        },
        contentAlignment = Alignment.Center
    ) {
        // Only render video in this surface if we're not in fullscreen mode or if this is the fullscreen window
        if (playerState.hasMedia && (!playerState.isFullscreen || isInFullscreenWindow)) {
            // Force recomposition when currentFrameState changes
            val currentFrame by remember(playerState) { playerState.currentFrameState }

            currentFrame?.let { frame ->
                Canvas(
                    modifier = contentScale.toCanvasModifier(
                        playerState.aspectRatio,
                        playerState.metadata.width,
                        playerState.metadata.height
                    ),
                ) {
                    drawScaledImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        contentScale = contentScale
                    )
                }
            }

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
                val currentTimeMs = (playerState.sliderPos / 1000f *
                        playerState.durationText.toTimeMs()).toLong()
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
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale)
    }
}
