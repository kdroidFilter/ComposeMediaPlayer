package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders a video player surface that displays and controls video playback.
 *
 * @param playerState The state of the video player, which manages playback controls,
 *                    video position, volume, and other related properties.
 * @param modifier    The modifier to be applied to the video player surface for
 *                    layout and styling adjustments.
 * @param overlay     Optional composable content to be displayed on top of the video surface.
 *                    This can be used to add custom controls, information, or any UI elements.
 */
@Composable
expect fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {}
)
