@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVPlayerViewController
import platform.UIKit.*

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState, 
    modifier: Modifier,
    overlay: @Composable () -> Unit
) {
    VideoPlayerSurfaceImpl(playerState, modifier, overlay, isInFullscreenView = false)
}

@OptIn(ExperimentalForeignApi::class)
@Composable
fun VideoPlayerSurfaceImpl(
    playerState: VideoPlayerState, 
    modifier: Modifier,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false
) {
    // Create and store the AVPlayerViewController
    val avPlayerViewController = remember {
        AVPlayerViewController().apply {
            showsPlaybackControls = false
        }
    }

    // Cleanup when deleting the view
    DisposableEffect(Unit) {
        onDispose {
            Logger.d { "[VideoPlayerSurface] Disposing" }
            playerState.pause()
            avPlayerViewController.removeFromParentViewController()
        }
    }

    // Update the player when it changes
    DisposableEffect(playerState.player) {
        Logger.d{"Video Player updated"}
        avPlayerViewController.player = playerState.player
        onDispose { }
    }
    if (playerState.hasMedia) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {

            UIKitView(
            modifier = Modifier.fillMaxHeight().aspectRatio(playerState.videoAspectRatio.toFloat()),
                factory = {
                    UIView().apply {
                        backgroundColor = UIColor.blackColor
                        clipsToBounds = true

                        avPlayerViewController.view.translatesAutoresizingMaskIntoConstraints = false
                        addSubview(avPlayerViewController.view)

                        NSLayoutConstraint.activateConstraints(
                            listOf(
                                avPlayerViewController.view.topAnchor.constraintEqualToAnchor(this.topAnchor),
                                avPlayerViewController.view.leadingAnchor.constraintEqualToAnchor(this.leadingAnchor),
                                avPlayerViewController.view.trailingAnchor.constraintEqualToAnchor(this.trailingAnchor),
                                avPlayerViewController.view.bottomAnchor.constraintEqualToAnchor(this.bottomAnchor)
                            )
                        )
                        Logger.d { "View configurated" }
                    }
                },
                update = { containerView ->
                    // Hide or show the view depending on the presence of media
                    containerView.hidden = !playerState.hasMedia

                    containerView.setNeedsLayout()
                    containerView.layoutIfNeeded()
                    avPlayerViewController.view.setFrame(containerView.bounds)
                }
            )

            // Render the overlay content on top of the video
            overlay()
        }
    }

    // Handle fullscreen mode
    if (playerState.isFullscreen && !isInFullscreenView) {
        openFullscreenView(playerState) { state, mod, inFullscreen ->
            VideoPlayerSurfaceImpl(state, mod, overlay, inFullscreen)
        }
    }
}
