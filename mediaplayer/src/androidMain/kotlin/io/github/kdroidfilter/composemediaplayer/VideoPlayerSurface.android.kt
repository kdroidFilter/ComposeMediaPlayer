package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    VideoPlayerSurfaceInternal(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        surfaceType = SurfaceType.TextureView,
    )
}

@UnstableApi
@Composable
fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.TextureView,
    overlay: @Composable () -> Unit = {},
) {
    VideoPlayerSurfaceInternal(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        surfaceType = surfaceType,
    )
}

@UnstableApi
@Composable
private fun VideoPlayerSurfaceInternal(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    overlay: @Composable () -> Unit,
) {
    if (LocalInspectionMode.current) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }

    // Single source of truth — no rememberSaveable, drive directly from playerState
    val isFullscreen = playerState.isFullscreen
    val isPipFullScreen = (playerState as? DefaultVideoPlayerState)?.isPipFullScreen ?: false

    AutoPipEffect(playerState = playerState)

    // Exit fullscreen when returning from PiP
    LaunchedEffect(playerState.isPipActive) {
        (playerState as? DefaultVideoPlayerState)?.let { playerState ->
            if (!playerState.isPipActive && playerState.isPipFullScreen) {
                delay(300)
                playerState.togglePipFullScreen()
            }
        }
    }

    DisposableEffect(playerState) {
        onDispose {
            try {
                // Detach the view from the player
                if (playerState is DefaultVideoPlayerState) {
                    playerState.attachPlayerView(null)
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error detaching PlayerView on dispose: ${e.message}" }
            }
        }
    }

    if (isFullscreen || isPipFullScreen) {
        FullScreenLayout(
            modifier = Modifier,
            onDismissRequest = {
                // Call playerState.toggleFullscreen() to ensure proper cleanup
                playerState.toggleFullscreen()
            },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            ) {
                VideoPlayerContent(
                    playerState = playerState,
                    modifier = Modifier.fillMaxHeight(),
                    overlay = overlay,
                    contentScale = contentScale,
                    surfaceType = surfaceType,
                )
            }
        }
    } else {
        VideoPlayerContent(
            playerState = playerState,
            modifier = modifier,
            overlay = overlay,
            contentScale = contentScale,
            surfaceType = surfaceType,
        )
    }
}

@UnstableApi
@Composable
private fun VideoPlayerContent(
    playerState: VideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.hasMedia) {
            AndroidView(
                modifier =
                    contentScale.toCanvasModifier(
                        playerState.aspectRatio,
                        playerState.metadata.width,
                        playerState.metadata.height,
                    ),
                factory = { context ->
                    try {
                        // Create PlayerView with the appropriate surface type

                        createPlayerViewWithSurfaceType(context, surfaceType).apply {
                            if (playerState is DefaultVideoPlayerState) {
                                // Attach this view to the player state
                                playerState.attachPlayerView(this)

                                if (playerState.exoPlayer != null) {
                                    // Attach the player from the state
                                    player = playerState.exoPlayer
                                }
                            }

                            useController = false
                            defaultArtwork = null
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                            // Map ContentScale to ExoPlayer resize modes
                            resizeMode = mapContentScaleToResizeMode(contentScale)

                            // Disable the native subtitle view since we use Compose-based subtitles
                            subtitleView?.visibility = android.view.View.GONE
                        }
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error creating PlayerView: ${e.message}" }
                        // Return an empty view in case of error
                        PlayerView(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    }
                },
                update = { playerView ->
                    try {
                        // Verify that the player is still valid before updating
                        if (playerState is DefaultVideoPlayerState &&
                            playerState.exoPlayer != null &&
                            playerView.player != null
                        ) {
                            // Update the resize mode when contentScale changes
                            playerView.resizeMode = mapContentScaleToResizeMode(contentScale)
                        }
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error updating PlayerView: ${e.message}" }
                    }
                },
                onReset = { playerView ->
                    try {
                        // Clean up resources when the view is recycled in a LazyList
                        playerView.player = null
                        playerView.onPause()
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error resetting PlayerView: ${e.message}" }
                    }
                },
                onRelease = { playerView ->
                    try {
                        // Fully clean up the view on release
                        playerView.player = null
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error releasing PlayerView: ${e.message}" }
                    }
                },
            )

            // Add a Compose-based subtitle layer
            if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
                // Calculate the current time in milliseconds
                val currentTimeMs =
                    remember(playerState.sliderPos, playerState.durationText) {
                        (playerState.sliderPos / 1000f * playerState.durationText.toTimeMs()).toLong()
                    }

                // Calculate the duration in milliseconds
                val durationMs =
                    remember(playerState.durationText) {
                        playerState.durationText.toTimeMs()
                    }

                ComposeSubtitleLayer(
                    currentTimeMs = currentTimeMs,
                    durationMs = durationMs,
                    isPlaying = playerState.isPlaying,
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor,
                )
            }
        }

        // Render the overlay content above the video with the fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}

@OptIn(UnstableApi::class)
private fun mapContentScaleToResizeMode(contentScale: ContentScale): Int =
    when (contentScale) {
        ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ContentScale.Fit, ContentScale.Inside -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ContentScale.FillWidth -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        ContentScale.FillHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

@OptIn(UnstableApi::class)
private fun createPlayerViewWithSurfaceType(
    context: Context,
    surfaceType: SurfaceType,
): PlayerView =
    try {
        // First try to inflate the custom layouts
        val layoutId =
            when (surfaceType) {
                SurfaceType.SurfaceView -> R.layout.player_view_surface
                SurfaceType.TextureView -> R.layout.player_view_texture
            }

        LayoutInflater.from(context).inflate(layoutId, null) as PlayerView
    } catch (e: Exception) {
        androidVideoLogger.e { "Error inflating PlayerView layout: ${e.message}, creating programmatically" }

        // Create PlayerView programmatically to avoid missing resource issues
        try {
            PlayerView(context).apply {
                // Fully disable controls to avoid inflating the controls layout
                useController = false

                // Configure the surface type programmatically
                when (surfaceType) {
                    SurfaceType.TextureView -> {
                        // Use TextureView if available
                        videoSurfaceView?.let { view ->
                            if (view is TextureView) {
                                androidVideoLogger.d { "Using TextureView" }
                            }
                        }
                    }

                    SurfaceType.SurfaceView -> {
                        // SurfaceView is the default
                        androidVideoLogger.d { "Using SurfaceView" }
                    }
                }

                // Disable features that could cause issues
                controllerAutoShow = false
                controllerHideOnTouch = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        } catch (e2: Exception) {
            androidVideoLogger.e { "Error creating PlayerView programmatically: ${e2.message}" }
            // Last resort: create an empty view to avoid crashing
            throw e2
        }
    }

@Composable
fun AutoPipEffect(playerState: VideoPlayerState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && playerState.isPipEnabled) {
                    scope.coroutineContext[MonotonicFrameClock]?.let { monoticClock ->
                        val activity = context as? ComponentActivity
                        activity?.lifecycleScope?.launch(context = Dispatchers.Main + monoticClock) {
                            playerState.enterPip()
                        }
                    }
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
