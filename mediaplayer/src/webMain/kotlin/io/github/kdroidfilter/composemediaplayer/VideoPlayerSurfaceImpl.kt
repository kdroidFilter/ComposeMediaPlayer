package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.composemediaplayer.jsinterop.MediaError
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toTimeMs
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import kotlin.math.abs

internal val webVideoLogger = Logger.withTag("WebVideoPlayerSurface").apply { Logger.setMinSeverity(Severity.Warn) }

// Cache mime type mappings for better performance
internal val EXTENSION_TO_MIME_TYPE = mapOf(
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "ogg" to "video/ogg",
    "mov" to "video/quicktime",
    "avi" to "video/x-msvideo",
    "mkv" to "video/x-matroska"
)

// Helper functions for common operations
internal fun HTMLVideoElement.safePlay() {
    try {
        play()
    } catch (e: Exception) {
        webVideoLogger.e { "Error playing video: ${e.message}" }
    }
}

internal fun HTMLVideoElement.safePause() {
    try {
        pause()
    } catch (e: Exception) {
        webVideoLogger.e { "Error pausing video: ${e.message}" }
    }
}

internal fun HTMLVideoElement.safeSetPlaybackRate(rate: Float) {
    try {
        playbackRate = rate.toDouble()
    } catch (e: Exception) {
        webVideoLogger.e { "Error setting playback rate: ${e.message}" }
    }
}

internal fun HTMLVideoElement.safeSetCurrentTime(time: Double) {
    try {
        currentTime = time
    } catch (e: Exception) {
        webVideoLogger.e { "Error seeking to ${time}s: ${e.message}" }
    }
}

internal fun HTMLVideoElement.addEventListeners(
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

fun Modifier.videoRatioClip(videoRatio: Float?, contentScale: ContentScale = ContentScale.Fit): Modifier =
    drawBehind { videoRatio?.let { drawVideoRatioRect(it, contentScale) } }

// Optimized drawing function to reduce calculations during rendering
private fun DrawScope.drawVideoRatioRect(ratio: Float, contentScale: ContentScale) {
    val containerWidth = size.width
    val containerHeight = size.height
    val containerRatio = containerWidth / containerHeight

    when (contentScale) {
        ContentScale.Fit, ContentScale.Inside -> {
            val (rectWidth, rectHeight) = if (containerRatio > ratio) {
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                val width = containerWidth
                val height = width / ratio
                width to height
            }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight)
            )
        }
        ContentScale.Crop -> {
            val (rectWidth, rectHeight) = if (containerRatio < ratio) {
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                val width = containerWidth
                val height = width / ratio
                width to height
            }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight)
            )
        }
        ContentScale.FillWidth -> {
            val width = containerWidth
            val height = width / ratio
            val yOffset = (containerHeight - height) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(0f, yOffset),
                size = Size(width, height)
            )
        }
        ContentScale.FillHeight -> {
            val height = containerHeight
            val width = height * ratio
            val xOffset = (containerWidth - width) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, 0f),
                size = Size(width, height)
            )
        }
        ContentScale.FillBounds -> {
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(0f, 0f),
                size = Size(containerWidth, containerHeight)
            )
        }
        else -> {
            val (rectWidth, rectHeight) = if (containerRatio > ratio) {
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                val width = containerWidth
                val height = width / ratio
                width to height
            }
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight)
            )
        }
    }
}

@Composable
internal fun SubtitleOverlay(playerState: VideoPlayerState) {
    if (!playerState.subtitlesEnabled || playerState.currentSubtitleTrack == null) {
        return
    }

    val durationMs = remember(playerState.durationText) {
        playerState.durationText.toTimeMs()
    }

    val currentTimeMs = remember(playerState.sliderPos, durationMs) {
        ((playerState.sliderPos / 1000f) * durationMs).toLong()
    }

    ComposeSubtitleLayer(
        currentTimeMs = currentTimeMs,
        durationMs = durationMs,
        isPlaying = playerState.isPlaying,
        subtitleTrack = playerState.currentSubtitleTrack,
        subtitlesEnabled = true,
        textStyle = playerState.subtitleTextStyle,
        backgroundColor = playerState.subtitleBackgroundColor
    )
}

@Composable
internal fun VideoBox(
    playerState: VideoPlayerState,
    videoRatio: Float?,
    contentScale: ContentScale,
    isFullscreenMode: Boolean,
    overlay: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isFullscreenMode) Color.Black else Color.Transparent)
            .videoRatioClip(videoRatio, contentScale)
    ) {
        SubtitleOverlay(playerState)
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}

@Composable
internal fun VideoContentLayout(
    playerState: VideoPlayerState,
    modifier: Modifier,
    videoRatio: Float?,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    videoElementContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (playerState.isFullscreen) {
            FullScreenLayout(onDismissRequest = { playerState.isFullscreen = false }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VideoBox(playerState, videoRatio, contentScale, true, overlay)
                }
            }
        } else {
            Box(modifier = modifier) {
                VideoBox(playerState, videoRatio, contentScale, false, overlay)
            }
        }
        videoElementContent()
    }
}

internal fun HTMLVideoElement.applyInteropBehindCanvas() {
    val wrapper = parentElement as? HTMLElement ?: return
    wrapper.style.apply {
        zIndex = "-1"
        setProperty("pointer-events", "none")
        backgroundColor = "transparent"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
    }
}

internal fun HTMLVideoElement.applyContentScale(contentScale: ContentScale, videoRatio: Float?) {
    style.apply {
        backgroundColor = "black"
        setProperty("pointer-events", "none")
        display = "block"

        when (contentScale) {
            ContentScale.Crop -> {
                width = "100%"
                height = "100%"
                objectFit = "cover"
            }
            ContentScale.FillBounds -> {
                width = "100%"
                height = "100%"
                objectFit = "fill"
            }
            ContentScale.FillWidth -> {
                objectFit = "contain"
                if (videoRatio != null) {
                    width = "100%"
                    height = "auto"
                } else {
                    width = "100%"
                    height = "100%"
                }
            }
            ContentScale.FillHeight -> {
                objectFit = "contain"
                if (videoRatio != null) {
                    width = "auto"
                    height = "100%"
                } else {
                    width = "100%"
                    height = "100%"
                }
            }
            else -> {
                width = "100%"
                height = "100%"
                objectFit = "contain"
            }
        }
    }
}

internal fun createVideoElement(useCors: Boolean = true): HTMLVideoElement {
    return (document.createElement("video") as HTMLVideoElement).apply {
        controls = false
        style.width = "100%"
        style.height = "100%"
        style.backgroundColor = "black"
        style.setProperty("pointer-events", "none")
        style.display = "block"

        if (useCors) {
            crossOrigin = "anonymous"
        } else {
            removeAttribute("crossorigin")
        }

        setAttribute("playsinline", "")
        setAttribute("webkit-playsinline", "")
        setAttribute("preload", "auto")
        setAttribute("x-webkit-airplay", "allow")
    }
}

internal fun setupVideoElement(
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

    playerState.clearError()
    playerState.metadata.audioChannels = null
    playerState.metadata.audioSampleRate = null

    fun initAudioAnalyzer() {
        if (!enableAudioDetection || corsErrorDetected) return
        initializationJob?.cancel()
        initializationJob = scope.launch {
            val success = audioAnalyzer?.initialize() ?: false
            if (!success) {
                corsErrorDetected = true
            } else {
                audioAnalyzer.let { analyzer ->
                    playerState.metadata.audioChannels = analyzer.audioChannels
                    playerState.metadata.audioSampleRate = analyzer.audioSampleRate
                }
            }
        }
    }

    video.addEventListeners(
        scope = scope,
        playerState = playerState,
        events = mapOf(
            "timeupdate" to { event -> playerState.onTimeUpdateEvent(event) },
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

    val conditionalLoadingEvents = mapOf(
        "suspend" to { video.readyState >= 3 },
        "loadedmetadata" to { true }
    )

    conditionalLoadingEvents.forEach { (event, condition) ->
        video.addEventListener(event) {
            if (event == "loadedmetadata") {
                initAudioAnalyzer()
            }

            scope.launch {
                if (condition()) {
                    playerState._isLoading = false
                }

                if (event == "loadedmetadata") {
                    if (playerState.isPlaying) {
                        video.safePlay()
                    }
                }
            }
        }
    }

    var audioLevelJob: Job? = null

    video.addEventListener("play") {
        if (enableAudioDetection && !corsErrorDetected && initializationJob?.isActive != true) {
            initAudioAnalyzer()
        }

        if (enableAudioDetection && audioLevelJob?.isActive != true) {
            audioLevelJob = scope.launch {
                while (video.paused.not()) {
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

    video.addEventListener("pause") {
        audioLevelJob?.cancel()
        audioLevelJob = null
    }

    video.addEventListener("error") {
        scope.launch {
            playerState._isLoading = false
            corsErrorDetected = true

            val error = video.error
            if (error != null) {
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

    video.loop = playerState.loop

    if (video.src.isNotEmpty() && playerState.isPlaying) {
        video.safePlay()
    }
}

internal fun VideoPlayerState.onTimeUpdateEvent(event: Event) {
    (event.target as? HTMLVideoElement)?.let {
        onTimeUpdate(it.currentTime.toFloat(), it.duration.toFloat())
    }
}

internal fun HTMLVideoElement.setupMetadataListener(
    playerState: VideoPlayerState,
    onVideoRatioChange: (Float) -> Unit
) {
    addEventListener("loadedmetadata") {
        val width = videoWidth
        val height = videoHeight
        if (height != 0) {
            onVideoRatioChange(width.toFloat() / height.toFloat())

            with(playerState.metadata) {
                this.width = width
                this.height = height
                duration = (this@setupMetadataListener.duration * 1000).toLong()

                val src = this@setupMetadataListener.src
                if (src.isNotEmpty()) {
                    val lastDotIndex = src.lastIndexOf('.')
                    if (lastDotIndex > 0 && lastDotIndex < src.length - 1) {
                        val extension = src.substring(lastDotIndex + 1).lowercase()
                        mimeType = EXTENSION_TO_MIME_TYPE[extension]
                    }

                    try {
                        val lastSlashIndex = src.lastIndexOf('/')
                        val lastBackslashIndex = src.lastIndexOf('\\')
                        val startIndex = maxOf(lastSlashIndex, lastBackslashIndex) + 1

                        if (startIndex > 0 && startIndex < src.length) {
                            val endIndex = if (lastDotIndex > startIndex) lastDotIndex else src.length
                            val filename = src.substring(startIndex, endIndex)
                            if (filename.isNotEmpty()) {
                                title = filename
                            }
                        }
                    } catch (e: Exception) {
                        webVideoLogger.w { "Failed to extract title from filename: ${e.message}" }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoPlayerEffects(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    scope: CoroutineScope,
    useCors: Boolean,
    onLastPositionChange: (Double) -> Unit,
    onWasPlayingChange: (Boolean) -> Unit,
    onLastPlaybackSpeedChange: (Float) -> Unit,
    lastPosition: Double,
    wasPlaying: Boolean,
    lastPlaybackSpeed: Float
) {
    // Handle fullscreen
    LaunchedEffect(playerState.isFullscreen) {
        try {
            if (!playerState.isFullscreen) {
                FullscreenManager.exitFullscreen()
            }
        } catch (e: Exception) {
            webVideoLogger.e { "Error handling fullscreen: ${e.message}" }
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
    LaunchedEffect(videoElement, playerState.sourceUri) {
        videoElement?.let { video ->
            val sourceUri = playerState.sourceUri ?: ""
            if (sourceUri.isNotEmpty()) {
                playerState.clearError()
                video.src = sourceUri
                video.load()
                if (playerState.isPlaying) video.safePlay() else video.safePause()
            }
        }
    }

    // Handle play/pause
    LaunchedEffect(videoElement, playerState.isPlaying) {
        videoElement?.let { video ->
            if (playerState.isPlaying) video.safePlay() else video.safePause()
        }
    }

    // Handle loop property
    LaunchedEffect(playerState.loop) {
        videoElement?.let { video ->
            video.loop = playerState.loop
        }
    }

    // Store state before video element recreation
    LaunchedEffect(useCors) {
        videoElement?.let {
            onLastPositionChange(it.currentTime)
            onWasPlayingChange(playerState.isPlaying)
            onLastPlaybackSpeedChange(playerState.playbackSpeed)
        }
    }

    // Restore state after video element recreation
    LaunchedEffect(videoElement, useCors) {
        videoElement?.let { video ->
            if (lastPosition > 0) {
                video.safeSetCurrentTime(lastPosition)
                onLastPositionChange(0.0)
            }

            if (lastPlaybackSpeed != 1.0f) {
                video.safeSetPlaybackRate(lastPlaybackSpeed)
                onLastPlaybackSpeedChange(1.0f)
            }

            if (wasPlaying) {
                video.safePlay()
                onWasPlayingChange(false)
            }
        }
    }

    // Handle seeking
    LaunchedEffect(playerState.sliderPos) {
        if (!playerState.userDragging && playerState.hasMedia) {
            playerState.seekJob?.cancel()

            videoElement?.let { video ->
                val duration = video.duration.toFloat()
                if (duration > 0f) {
                    val newTime = (playerState.sliderPos / VideoPlayerState.PERCENTAGE_MULTIPLIER) * duration
                    val currentTime = video.currentTime

                    if (abs(currentTime - newTime) > 0.5) {
                        playerState.seekJob = scope.launch {
                            video.safeSetCurrentTime(newTime.toDouble())
                        }
                    }
                }
            }
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

@Composable
internal fun VideoVolumeAndSpeedEffects(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?
) {
    var pendingVolumeChange by remember { mutableStateOf<Double?>(null) }
    var pendingPlaybackSpeedChange by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(videoElement) {
        val video = videoElement ?: return@DisposableEffect onDispose {}

        playerState.applyVolumeCallback = { value ->
            if (playerState._isLoading) {
                pendingVolumeChange = value.toDouble()
            } else {
                video.volume = value.toDouble()
                pendingVolumeChange = null
            }
        }

        if (!playerState._isLoading) {
            video.volume = playerState.volume.toDouble()
        } else {
            pendingVolumeChange = playerState.volume.toDouble()
        }

        playerState.applyPlaybackSpeedCallback = { value ->
            if (playerState._isLoading) {
                pendingPlaybackSpeedChange = value
            } else {
                video.safeSetPlaybackRate(value)
                pendingPlaybackSpeedChange = null
            }
        }

        if (!playerState._isLoading) {
            video.safeSetPlaybackRate(playerState.playbackSpeed)
        } else {
            pendingPlaybackSpeedChange = playerState.playbackSpeed
        }

        val seekedListener: (Event) -> Unit = {
            pendingVolumeChange?.let { volume ->
                video.volume = volume
                pendingVolumeChange = null
            }
            pendingPlaybackSpeedChange?.let { speed ->
                video.safeSetPlaybackRate(speed)
                pendingPlaybackSpeedChange = null
            }
        }

        video.addEventListener("seeked", seekedListener)

        onDispose {
            video.removeEventListener("seeked", seekedListener)
            playerState.applyVolumeCallback = null
            playerState.applyPlaybackSpeedCallback = null
        }
    }
}
