package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs


/**
 * A composable function that provides a surface for rendering video frames
 * within the Windows video player. It adjusts to size changes and ensures the video
 * is displayed properly with respect to its aspect ratio.
 *
 * @param playerState The state of the Windows video player, used to manage video playback and rendering.
 * @param modifier The modifier to be used to adjust the layout or styling of the composable.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */
@Composable
fun WindowsVideoPlayerSurface(
    playerState: WindowsVideoPlayerState,
    modifier: Modifier = Modifier,
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
        if (playerState.hasMedia) {
            // Force recomposition when currentFrameState changes
            val currentFrame by remember(playerState) { playerState.currentFrameState }

            currentFrame?.let { frame ->
                Canvas(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(playerState.aspectRatio),
                ) {
                    drawImage(
                        image = frame,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
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

        // Render the overlay content on top of the video
        overlay()
    }

    if (playerState.isFullscreen && !isInFullscreenWindow) {
        openFullscreenWindow(playerState)
    }
}
