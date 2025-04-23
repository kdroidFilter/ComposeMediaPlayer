package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.composemediaplayer.htmlinterop.HtmlView
import io.github.kdroidfilter.composemediaplayer.jsinterop.MediaError
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.abs

internal val wasmVideoLogger = Logger.withTag("WasmVideoPlayerSurface").apply { Logger.setMinSeverity(Severity.Warn) }

// Helper functions for common operations
private fun HTMLVideoElement.safePlay() {
    try {
        play()
    } catch (e: Exception) {
        wasmVideoLogger.e { "Error playing video: ${e.message}" }
    }
}

private fun HTMLVideoElement.safePause() {
    try {
        pause()
    } catch (e: Exception) {
        wasmVideoLogger.e { "Error pausing video: ${e.message}" }
    }
}

private fun HTMLVideoElement.safeSetPlaybackRate(rate: Float) {
    try {
        playbackRate = rate.toDouble()
    } catch (e: Exception) {
        wasmVideoLogger.e { "Error setting playback rate: ${e.message}" }
    }
}

private fun HTMLVideoElement.safeSetCurrentTime(time: Double) {
    try {
        currentTime = time
    } catch (e: Exception) {
        wasmVideoLogger.e { "Error seeking to ${time}s: ${e.message}" }
    }
}

private fun HTMLVideoElement.addEventListeners(
    scope: CoroutineScope,
    playerState: VideoPlayerState,
    events: Map<String, (Event) -> Unit>,
    loadingEvents: Map<String, Boolean> = emptyMap()
) {
    events.forEach { (event, handler) ->
        addEventListener(event, handler)
    }

    loadingEvents.forEach { (event, isLoading) ->
        addEventListener(event) {
            scope.launch { playerState._isLoading = isLoading }
        }
    }
}

fun Modifier.videoRatioClip(videoRatio: Float?): Modifier = 
    drawBehind { videoRatio?.let { drawVideoRatioRect(it) } }

private fun DrawScope.drawVideoRatioRect(ratio: Float) {
    val containerWidth = size.width
    val containerHeight = size.height

    val (rectWidth, rectHeight) = if (containerWidth / containerHeight > ratio) {
        containerHeight * ratio to containerHeight
    } else {
        containerWidth to containerWidth / ratio
    }

    drawRect(
        color = Color.Transparent,
        blendMode = BlendMode.Clear,
        topLeft = Offset((containerWidth - rectWidth) / 2f, (containerHeight - rectHeight) / 2f),
        size = Size(rectWidth, rectHeight)
    )
}

@Composable
private fun SubtitleOverlay(playerState: VideoPlayerState) {
    if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
        ComposeSubtitleLayer(
            currentTimeMs = (playerState.sliderPos / 1000f * playerState.durationText.toTimeMs()).toLong(),
            durationMs = playerState.durationText.toTimeMs(),
            isPlaying = playerState.isPlaying,
            subtitleTrack = playerState.currentSubtitleTrack,
            subtitlesEnabled = playerState.subtitlesEnabled,
            textStyle = playerState.subtitleTextStyle,
            backgroundColor = playerState.subtitleBackgroundColor
        )
    }
}


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
        // Track if we're using CORS mode (initially true, will be set to false if CORS errors occur)
        var useCors by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()


        // Use VideoContent composable for video display logic
        VideoContent(
            playerState = playerState,
            modifier = modifier,
            videoRatio = videoRatio,
            useCors = useCors,
            onVideoElementChange = { videoElement = it },
            onVideoRatioChange = { videoRatio = it },
            onCorsChange = { useCors = it },
            scope = scope,
            contentScale = contentScale,
            overlay = overlay
        )

        // Handle fullscreen
        LaunchedEffect(playerState.isFullscreen) {
            try {
                if (!playerState.isFullscreen) {
                    FullscreenManager.exitFullscreen()
                }
            } catch (e: Exception) {
                wasmVideoLogger.e { "Error handling fullscreen: ${e.message}" }
            }
        }

        // Listen for fullscreen change events
        DisposableEffect(Unit) {
            val fullscreenChangeListener: (Event) -> Unit = {
                videoElement?.let { video ->
                    val isDocumentFullscreen = video.ownerDocument?.fullscreenElement != null
                    if (!isDocumentFullscreen && playerState.isFullscreen) {
                        playerState.isFullscreen = false
                    }
                }
            }

            val fullscreenEvents = listOf(
                "fullscreenchange", "webkitfullscreenchange", 
                "mozfullscreenchange", "MSFullscreenChange"
            )

            fullscreenEvents.forEach { event ->
                document.addEventListener(event, fullscreenChangeListener)
            }

            onDispose {
                fullscreenEvents.forEach { event ->
                    document.removeEventListener(event, fullscreenChangeListener)
                }
            }
        }
        // Handle source change effect
        LaunchedEffect(playerState.sourceUri) {
            videoElement?.let { video ->
                val sourceUri = playerState.sourceUri ?: ""
                if (sourceUri.isNotEmpty()) {
                    playerState.clearError()
                    video.src = sourceUri
                    video.load()

                    if (playerState.playbackSpeed != 1.0f) {
                        video.safeSetPlaybackRate(playerState.playbackSpeed)
                    }

                    if (playerState.isPlaying) video.safePlay() else video.safePause()
                }
            }
        }

        // Handle play/pause
        LaunchedEffect(playerState.isPlaying) {
            videoElement?.let { video ->
                if (playerState.isPlaying) video.safePlay() else video.safePause()
            }
        }

        // Handle property updates
        LaunchedEffect(playerState.volume) {
            videoElement?.volume = playerState.volume.toDouble()
        }

        LaunchedEffect(playerState.loop) {
            videoElement?.loop = playerState.loop
        }

        LaunchedEffect(playerState.playbackSpeed) {
            videoElement?.safeSetPlaybackRate(playerState.playbackSpeed)
        }

        // State for CORS mode changes
        var lastPosition by remember { mutableStateOf(0.0) }
        var wasPlaying by remember { mutableStateOf(false) }
        var lastPlaybackSpeed by remember { mutableStateOf(1.0f) }

        // Store state before video element recreation
        LaunchedEffect(useCors) {
            videoElement?.let {
                lastPosition = it.currentTime
                wasPlaying = playerState.isPlaying
                lastPlaybackSpeed = playerState.playbackSpeed
            }
        }

        // Restore state after video element recreation
        LaunchedEffect(videoElement, useCors) {
            videoElement?.let { video ->
                if (lastPosition > 0) {
                    video.safeSetCurrentTime(lastPosition)
                    lastPosition = 0.0
                }

                if (lastPlaybackSpeed != 1.0f) {
                    video.safeSetPlaybackRate(lastPlaybackSpeed)
                    lastPlaybackSpeed = 1.0f
                }

                if (wasPlaying) {
                    video.safePlay()
                    wasPlaying = false
                }
            }
        }

        // Handle seeking
        LaunchedEffect(playerState.sliderPos) {
            if (!playerState.userDragging && playerState.hasMedia) {
                val job = scope.launch {
                    val duration = videoElement?.duration?.toFloat() ?: 0f
                    if (duration > 0f) {
                        val newTime = (playerState.sliderPos / VideoPlayerState.PERCENTAGE_MULTIPLIER) * duration
                        if (abs((videoElement?.currentTime ?: 0.0) - newTime) > 0.5) {
                            videoElement?.safeSetCurrentTime(newTime.toDouble())
                        }
                    }
                }
                playerState.seekJob?.cancel()
                playerState.seekJob = job
            }
        }

        // Listen for external play/pause events
        DisposableEffect(videoElement) {
            val video = videoElement ?: return@DisposableEffect onDispose {}

            val playListener: (Event) -> Unit = {
                if (!playerState.isPlaying) scope.launch { playerState.play() }
            }

            val pauseListener: (Event) -> Unit = {
                if (playerState.isPlaying) scope.launch { playerState.pause() }
            }

            video.addEventListener("play", playListener)
            video.addEventListener("pause", pauseListener)

            onDispose {
                video.removeEventListener("play", playListener)
                video.removeEventListener("pause", pauseListener)
            }
        }
    }
}

@Composable
private fun VideoContent(
    playerState: VideoPlayerState,
    modifier: Modifier,
    videoRatio: Float?,
    useCors: Boolean,
    onVideoElementChange: (HTMLVideoElement?) -> Unit,
    onVideoRatioChange: (Float?) -> Unit,
    onCorsChange: (Boolean) -> Unit,
    scope: CoroutineScope,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit = {}
) {
    @Composable
    fun VideoBox() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .videoRatioClip(videoRatio)
        ) {
            SubtitleOverlay(playerState)
            overlay()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VideoBox()

        if (playerState.isFullscreen) {
            FullScreenLayout(onDismissRequest = { playerState.isFullscreen = false }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VideoBox()
                }
            }
        }

        // Create HTML video element
        key(useCors) {
            HtmlView(
                factory = { createVideoElement(useCors) },
                modifier = modifier,
                update = { video ->
                    onVideoElementChange(video)

                    video.addEventListener("loadedmetadata") {
                        val width = video.videoWidth
                        val height = video.videoHeight
                        if (height != 0) {
                            onVideoRatioChange(width.toFloat() / height.toFloat())

                            // Update metadata properties
                            with(playerState.metadata) {
                                this.width = width
                                this.height = height
                                duration = (video.duration * 1000).toLong()
                                frameRate = 30.0f // Default value

                                // Try to get mimeType and title from the video source
                                val src = video.src
                                if (src.isNotEmpty()) {
                                    val extension = src.substringAfterLast('.', "").lowercase()
                                    mimeType = when (extension) {
                                        "mp4" -> "video/mp4"
                                        "webm" -> "video/webm"
                                        "ogg" -> "video/ogg"
                                        "mov" -> "video/quicktime"
                                        "avi" -> "video/x-msvideo"
                                        "mkv" -> "video/x-matroska"
                                        else -> null
                                    }

                                    // Extract title from filename
                                    try {
                                        val filename = src.substringAfterLast('/', "")
                                            .substringAfterLast('\\', "")
                                            .substringBeforeLast('.', "")
                                        if (filename.isNotEmpty()) {
                                            title = filename
                                        }
                                    } catch (e: Exception) {
                                        wasmVideoLogger.w { "Failed to extract title from filename: ${e.message}" }
                                    }
                                }

                                // Estimate bitrate based on dimensions
                                if (width > 0 && height > 0) {
                                    bitrate = when {
                                        width >= 3840 -> 20000000L // 4K
                                        width >= 1920 -> 8000000L  // 1080p
                                        width >= 1280 -> 5000000L  // 720p
                                        else -> 2000000L           // SD
                                    }
                                }
                            }
                        }
                    }

                    setupVideoElement(
                        video,
                        playerState,
                        scope,
                        enableAudioDetection = true,
                        useCors = useCors,
                        onCorsError = { onCorsChange(false) }
                    )
                },
                isFullscreen = playerState.isFullscreen
            )
        }
    }
}

private fun createVideoElement(useCors: Boolean = true): HTMLVideoElement {
    return (document.createElement("video") as HTMLVideoElement).apply {
        controls = false
        style.position = "absolute"
        style.zIndex = "-1"
        style.width = "100%"
        style.height = "100%"

        // Handle CORS mode
        if (useCors) {
            crossOrigin = "anonymous"
        } else {
            removeAttribute("crossorigin")
        }

        // Set attributes for better compatibility
        setAttribute("playsinline", "")
        setAttribute("webkit-playsinline", "")
        setAttribute("preload", "auto")
        setAttribute("x-webkit-airplay", "allow")
    }
}


fun setupVideoElement(
    video: HTMLVideoElement,
    playerState: VideoPlayerState,
    scope: CoroutineScope,
    enableAudioDetection: Boolean = true,
    useCors: Boolean = true,
    onCorsError: () -> Unit = {},
) {
    val audioAnalyzer = if (enableAudioDetection) AudioLevelProcessor(video) else null
    var initializationJob: Job? = null
    var corsErrorDetected = false

    // Reset state
    playerState.clearError()
    playerState.metadata.audioChannels = null
    playerState.metadata.audioSampleRate = null

    // Initialize audio analyzer
    fun initAudioAnalyzer() {
        if (!enableAudioDetection || corsErrorDetected) return
        initializationJob?.cancel()
        initializationJob = scope.launch {
            val success = audioAnalyzer?.initialize() ?: false
            if (!success) {
                corsErrorDetected = true
            } else {
                audioAnalyzer?.let { analyzer ->
                    playerState.metadata.audioChannels = analyzer.audioChannels
                    playerState.metadata.audioSampleRate = analyzer.audioSampleRate
                }
            }
        }
    }

    // Setup loading state events
    video.addEventListeners(
        scope = scope,
        playerState = playerState,
        events = mapOf(
            "timeupdate" to playerState::onTimeUpdateEvent,
            "ended" to { scope.launch { playerState.pause() } }
        ),
        loadingEvents = mapOf(
            "seeking" to true,
            "waiting" to true,
            "playing" to false,
            "seeked" to false,
            "canplaythrough" to false,
            "canplay" to false
        )
    )

    // Handle suspend event separately (has a condition)
    video.addEventListener("suspend") {
        scope.launch {
            if (video.readyState >= 3) {
                playerState._isLoading = false
            }
        }
    }

    // Handle metadata and analyzer initialization
    video.addEventListener("loadedmetadata") {
        initAudioAnalyzer()
        scope.launch {
            playerState._isLoading = false
            if (playerState.playbackSpeed != 1.0f) {
                video.safeSetPlaybackRate(playerState.playbackSpeed)
            }
            if (playerState.isPlaying) {
                video.safePlay()
            }
        }
    }

    // Handle play event and audio level updates
    video.addEventListener("play") {
        if (enableAudioDetection && !corsErrorDetected && initializationJob?.isActive != true) {
            initAudioAnalyzer()
        }

        if (enableAudioDetection) {
            scope.launch {
                while (true) {
                    val (left, right) = if (!corsErrorDetected) {
                        audioAnalyzer?.getAudioLevels() ?: (0f to 0f)
                    } else {
                        0f to 0f
                    }
                    playerState.updateAudioLevels(left, right)
                    delay(100)
                }
            }
        }
    }

    // Handle errors
    video.addEventListener("error") {
        scope.launch {
            playerState._isLoading = false
            corsErrorDetected = true

            val error = video.error
            if (error != null) {
                val isCorsRelatedError = error.code == MediaError.MEDIA_ERR_NETWORK || 
                    (error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED && useCors)

                if (useCors) {
                    playerState.clearError()
                    onCorsError()
                } else {
                    val errorMsg = if (error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                        "Failed to load because the video format is not supported"
                    } else {
                        "Failed to load because no supported source was found"
                    }
                    playerState.setError(VideoPlayerError.SourceError(errorMsg))
                }
            }
        }
    }

    // Set initial properties
    video.volume = playerState.volume.toDouble()
    video.loop = playerState.loop
    video.safeSetPlaybackRate(playerState.playbackSpeed)

    // Play if needed
    if (video.src.isNotEmpty() && playerState.isPlaying) {
        video.safePlay()
    }
}

private fun VideoPlayerState.onTimeUpdateEvent(event: Event) {
    (event.target as? HTMLVideoElement)?.let {
        onTimeUpdate(it.currentTime.toFloat(), it.duration.toFloat())
    }
}
