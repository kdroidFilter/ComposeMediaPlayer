@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.WebElementView
import org.w3c.dom.HTMLVideoElement

@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit
) {
    if (playerState.hasMedia) {
        var videoElement by remember { mutableStateOf<HTMLVideoElement?>(null) }
        var videoRatio by remember { mutableStateOf<Float?>(null) }
        var useCors by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        // State for CORS mode changes
        var lastPosition by remember { mutableStateOf(0.0) }
        var wasPlaying by remember { mutableStateOf(false) }
        var lastPlaybackSpeed by remember { mutableStateOf(1.0f) }

        // Shared effects
        VideoPlayerEffects(
            playerState = playerState,
            videoElement = videoElement,
            scope = scope,
            useCors = useCors,
            onLastPositionChange = { lastPosition = it },
            onWasPlayingChange = { wasPlaying = it },
            onLastPlaybackSpeedChange = { lastPlaybackSpeed = it },
            lastPosition = lastPosition,
            wasPlaying = wasPlaying,
            lastPlaybackSpeed = lastPlaybackSpeed
        )

        VideoVolumeAndSpeedEffects(
            playerState = playerState,
            videoElement = videoElement
        )

        // Video content layout with WebElementView
        VideoContentLayout(
            playerState = playerState,
            modifier = modifier,
            videoRatio = videoRatio,
            contentScale = contentScale,
            overlay = overlay
        ) {
            key(useCors) {
                WebElementView(
                    factory = {
                        createVideoElement(useCors).apply {
                            setupMetadataListener(playerState) { ratio ->
                                videoRatio = ratio
                            }
                            setupVideoElement(
                                video = this,
                                playerState = playerState,
                                scope = scope,
                                enableAudioDetection = true,
                                useCors = useCors,
                                onCorsError = { useCors = false }
                            )
                        }
                    },
                    modifier = if (playerState.isFullscreen) Modifier.fillMaxSize() else modifier,
                    update = { video ->
                        videoElement = video
                        video.applyInteropBehindCanvas()
                        video.applyContentScale(contentScale, videoRatio)
                    },
                    onRelease = { video ->
                        video.safePause()
                        videoElement = null
                    }
                )
            }
        }
    }
}
