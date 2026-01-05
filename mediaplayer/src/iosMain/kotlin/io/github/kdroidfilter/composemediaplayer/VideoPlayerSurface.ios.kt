@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.cValue
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.Foundation.NSCoder
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit
) {
    // Set pauseOnDispose to false to prevent pausing during screen rotation
    VideoPlayerSurfaceImpl(playerState, modifier, contentScale, overlay, isInFullscreenView = false, pauseOnDispose = false)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
fun VideoPlayerSurfaceImpl(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false,
    pauseOnDispose: Boolean = true
) {
    // Cleanup when deleting the view
    DisposableEffect(Unit) {
        onDispose {
            Logger.d { "[VideoPlayerSurface] Disposing" }
            // Only pause if pauseOnDispose is true (prevents pausing during rotation or fullscreen transitions)
            if (pauseOnDispose) {
                Logger.d { "[VideoPlayerSurface] Pausing on dispose" }
                playerState.pause()
            } else {
                Logger.d { "[VideoPlayerSurface] Not pausing on dispose (rotation or fullscreen transition)" }
            }
        }
    }

    val currentPlayer = (playerState as? DefaultVideoPlayerState)?.player

    if (playerState.hasMedia) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {

            // Use the contentScale parameter to adjust the view's size and scaling behavior
            UIKitView(
                modifier = contentScale.toCanvasModifier(
                    aspectRatio =
                        if (playerState is DefaultVideoPlayerState)
                            playerState.videoAspectRatio.toFloat()
                        else 16.0f / 9.0f,
                    width = playerState.metadata.width,
                    height = playerState.metadata.height
                ),
                factory = {
                    PlayerUIView(frame = cValue<CGRect>()).apply {
                        player = currentPlayer
                        backgroundColor = UIColor.blackColor
                        clipsToBounds = true
                    }
                },
                update = { playerView ->
                    playerView.player = currentPlayer

                    // Hide or show the view depending on the presence of media
                    playerView.hidden = !playerState.hasMedia

                    // Update the videoGravity when contentScale changes
                    val videoGravity = when (contentScale) {
                        ContentScale.Crop,
                        ContentScale.FillHeight -> AVLayerVideoGravityResizeAspectFill   // ⬅️ changement
                        ContentScale.FillWidth -> AVLayerVideoGravityResizeAspectFill   // (même logique)
                        ContentScale.FillBounds -> AVLayerVideoGravityResize             // pas d’aspect-ratio
                        ContentScale.Fit,
                        ContentScale.Inside -> AVLayerVideoGravityResizeAspect

                        else -> AVLayerVideoGravityResizeAspect
                    }
                    playerView.videoGravity = videoGravity

                    Logger.d { "View configured with contentScale: $contentScale, videoGravity: $videoGravity" }
                },
                onRelease = { playerView ->
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

            // Render the overlay content on top of the video with fillMaxSize modifier
            // to ensure it takes the full height of the parent Box
            Box(modifier = Modifier.fillMaxSize()) {
                overlay()
            }
        }
    }

    // Handle fullscreen mode
    if (playerState.isFullscreen && !isInFullscreenView) {
        openFullscreenView(playerState) { state, mod, inFullscreen ->
            // Set pauseOnDispose to false to prevent pausing during fullscreen transitions
            VideoPlayerSurfaceImpl(state, mod, contentScale, overlay, inFullscreen, pauseOnDispose = false)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PlayerUIView : UIView {
    companion object : UIViewMeta() {
        override fun layerClass(): ObjCClass = AVPlayerLayer
    }

    constructor(frame: CValue<CGRect>) : super(frame)
    constructor(coder: NSCoder) : super(coder)

    var player: AVPlayer?
        get() = (layer as? AVPlayerLayer)?.player
        set(value) {
            (layer as? AVPlayerLayer)?.player = value
        }

    var videoGravity: String?
        get() = (layer as? AVPlayerLayer)?.videoGravity
        set(value) {
            (layer as? AVPlayerLayer)?.videoGravity = value
        }
}

