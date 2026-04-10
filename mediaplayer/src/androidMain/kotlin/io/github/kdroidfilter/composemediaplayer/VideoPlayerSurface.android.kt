package io.github.kdroidfilter.composemediaplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                // Détacher la vue du player
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
                isFullscreen = false
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
                        // Créer PlayerView avec le type de surface approprié

                        createPlayerViewWithSurfaceType(context, surfaceType).apply {
                            if (playerState is DefaultVideoPlayerState) {
                                // Attacher cette vue à l'état du lecteur
                                playerState.attachPlayerView(this)

                                if (playerState.exoPlayer != null) {
                                    // Attacher le lecteur depuis l'état
                                    player = playerState.exoPlayer
                                }
                            }

                            useController = false
                            defaultArtwork = null
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                            // Mapper ContentScale vers les modes de redimensionnement ExoPlayer
                            resizeMode = mapContentScaleToResizeMode(contentScale)

                            // Désactiver la vue de sous-titres native car nous utilisons des sous-titres basés sur Compose
                            subtitleView?.visibility = android.view.View.GONE
                        }
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error creating PlayerView: ${e.message}" }
                        // Retourner une vue vide en cas d'erreur
                        PlayerView(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    }
                },
                update = { playerView ->
                    try {
                        // Vérifier que le player est toujours valide avant la mise à jour
                        if (playerState is DefaultVideoPlayerState &&
                            playerState.exoPlayer != null &&
                            playerView.player != null
                        ) {
                            // Mettre à jour le mode de redimensionnement lorsque contentScale change
                            playerView.resizeMode = mapContentScaleToResizeMode(contentScale)
                        }
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error updating PlayerView: ${e.message}" }
                    }
                },
                onReset = { playerView ->
                    try {
                        // Nettoyer les ressources lorsque la vue est recyclée dans une LazyList
                        playerView.player = null
                        playerView.onPause()
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error resetting PlayerView: ${e.message}" }
                    }
                },
                onRelease = { playerView ->
                    try {
                        // Nettoyer complètement la vue lors de sa libération
                        playerView.player = null
                    } catch (e: Exception) {
                        androidVideoLogger.e { "Error releasing PlayerView: ${e.message}" }
                    }
                },
            )

            // Ajouter une couche de sous-titres basée sur Compose
            if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
                // Calculer le temps actuel en millisecondes
                val currentTimeMs =
                    remember(playerState.sliderPos, playerState.durationText) {
                        (playerState.sliderPos / 1000f * playerState.durationText.toTimeMs()).toLong()
                    }

                // Calculer la durée en millisecondes
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

        // Rendre le contenu de l'overlay au-dessus de la vidéo avec le modificateur fillMaxSize
        // pour s'assurer qu'il prend toute la hauteur du Box parent
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
        // Essayer d'abord d'inflater les layouts personnalisés
        val layoutId =
            when (surfaceType) {
                SurfaceType.SurfaceView -> R.layout.player_view_surface
                SurfaceType.TextureView -> R.layout.player_view_texture
            }

        LayoutInflater.from(context).inflate(layoutId, null) as PlayerView
    } catch (e: Exception) {
        androidVideoLogger.e { "Error inflating PlayerView layout: ${e.message}, creating programmatically" }

        // Créer PlayerView programmatiquement pour éviter les problèmes de ressources manquantes
        try {
            PlayerView(context).apply {
                // Désactiver complètement les contrôles pour éviter l'inflation du layout des contrôles
                useController = false

                // Configurer le type de surface programmatiquement
                when (surfaceType) {
                    SurfaceType.TextureView -> {
                        // Utiliser TextureView si disponible
                        videoSurfaceView?.let { view ->
                            if (view is TextureView) {
                                androidVideoLogger.d { "Using TextureView" }
                            }
                        }
                    }

                    SurfaceType.SurfaceView -> {
                        // SurfaceView est le défaut
                        androidVideoLogger.d { "Using SurfaceView" }
                    }
                }

                // Désactiver les fonctionnalités qui pourraient causer des problèmes
                controllerAutoShow = false
                controllerHideOnTouch = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        } catch (e2: Exception) {
            androidVideoLogger.e { "Error creating PlayerView programmatically: ${e2.message}" }
            // Dernier recours : créer une vue vide pour éviter le crash
            throw e2
        }
    }
}

@Composable
fun AutoPipEffect(
    playerState: VideoPlayerState,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
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
