package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Renders a video player surface that displays and controls video playback.
 *
 * @param playerState The state of the video player, which manages playback controls,
 *                    video position, volume, and other related properties.
 * @param modifier    The modifier to be applied to the video player surface for
 *                    layout and styling adjustments.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param surfaceType  Specifies the type of surface to be used for video playback.
 *                    Applicable only for Android target.
 * @param overlay     Optional composable content to be displayed on top of the video surface.
 *                    This can be used to add custom controls, information, or any UI elements.
 */
@Composable
expect fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.Surface,
    overlay: @Composable () -> Unit = {}
)
