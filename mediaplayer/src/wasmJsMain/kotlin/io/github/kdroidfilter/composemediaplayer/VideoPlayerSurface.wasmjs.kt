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

// Cache mime type mappings for better performance
private val EXTENSION_TO_MIME_TYPE = mapOf(
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "ogg" to "video/ogg",
    "mov" to "video/quicktime",
    "avi" to "video/x-msvideo",
    "mkv" to "video/x-matroska"
)

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

fun Modifier.videoRatioClip(videoRatio: Float?, contentScale: ContentScale = ContentScale.Fit): Modifier = 
    drawBehind { videoRatio?.let { drawVideoRatioRect(it, contentScale) } }

// Optimized drawing function to reduce calculations during rendering
private fun DrawScope.drawVideoRatioRect(ratio: Float, contentScale: ContentScale) {
    val containerWidth = size.width
    val containerHeight = size.height
    val containerRatio = containerWidth / containerHeight

    when (contentScale) {
        ContentScale.Fit, ContentScale.Inside -> {
            // Fit behavior - maintain aspect ratio and fit within container
            val (rectWidth, rectHeight) = if (containerRatio > ratio) {
                // Container is wider than video
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                // Container is taller than or equal to video
                val width = containerWidth
                val height = width / ratio
                width to height
            }

            // Calculate offset only once
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f

            // Use pre-calculated values
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight)
            )
        }
        ContentScale.Crop -> {
            // Crop behavior - maintain aspect ratio and fill container
            val (rectWidth, rectHeight) = if (containerRatio < ratio) {
                // Container is taller than video
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                // Container is wider than or equal to video
                val width = containerWidth
                val height = width / ratio
                width to height
            }

            // Calculate offset only once
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f

            // Use pre-calculated values
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(xOffset, yOffset),
                size = Size(rectWidth, rectHeight)
            )
        }
        ContentScale.FillWidth -> {
            // Fill width behavior - maintain aspect ratio and fill width
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
            // Fill height behavior - maintain aspect ratio and fill height
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
            // Fill bounds behavior - fill entire container without maintaining aspect ratio
            drawRect(
                color = Color.Transparent,
                blendMode = BlendMode.Clear,
                topLeft = Offset(0f, 0f),
                size = Size(containerWidth, containerHeight)
            )
        }
        else -> {
            // Default to Fit behavior
            val (rectWidth, rectHeight) = if (containerRatio > ratio) {
                // Container is wider than video
                val height = containerHeight
                val width = height * ratio
                width to height
            } else {
                // Container is taller than or equal to video
                val width = containerWidth
                val height = width / ratio
                width to height
            }

            // Calculate offset only once
            val xOffset = (containerWidth - rectWidth) / 2f
            val yOffset = (containerHeight - rectHeight) / 2f

            // Use pre-calculated values
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
private fun SubtitleOverlay(playerState: VideoPlayerState) {
    // Early return if subtitles are disabled or no track is selected
    if (!playerState.subtitlesEnabled || playerState.currentSubtitleTrack == null) {
        return
    }

    // Cache duration calculation to avoid repeated conversions
    val durationMs = remember(playerState.durationText) { 
        playerState.durationText.toTimeMs() 
    }

    // Calculate current time only once per composition
    val currentTimeMs = remember(playerState.sliderPos, durationMs) {
        ((playerState.sliderPos / 1000f) * durationMs).toLong()
    }

    ComposeSubtitleLayer(
        currentTimeMs = currentTimeMs,
        durationMs = durationMs,
        isPlaying = playerState.isPlaying,
        subtitleTrack = playerState.currentSubtitleTrack,
        subtitlesEnabled = true, // We already checked this above
        textStyle = playerState.subtitleTextStyle,
        backgroundColor = playerState.subtitleBackgroundColor
    )
}


@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
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

                    // Don't set playback speed directly, it will be handled by the applyPlaybackSpeedCallback

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

        // Handle property updates - combined for better performance
        // Store pending changes to apply after seeking is complete
        var pendingVolumeChange by remember { mutableStateOf<Double?>(null) }
        var pendingPlaybackSpeedChange by remember { mutableStateOf<Float?>(null) }

        LaunchedEffect(playerState.loop) {
            videoElement?.let { video ->
                // Always update loop property immediately
                video.loop = playerState.loop
            }
        }

        // Apply pending volume change when seeking is complete
        DisposableEffect(videoElement) {
            val video = videoElement ?: return@DisposableEffect onDispose {}

            // Set the volume callback to respect seeking state
            playerState.applyVolumeCallback = { value ->
                if (playerState._isLoading) {
                    // Store the volume change to apply after seeking is complete
                    pendingVolumeChange = value.toDouble()
                } else {
                    // Apply volume change immediately if not seeking
                    video.volume = value.toDouble()
                    pendingVolumeChange = null
                }
            }

            // Apply current volume immediately if needed
            if (!playerState._isLoading) {
                video.volume = playerState.volume.toDouble()
            } else {
                pendingVolumeChange = playerState.volume.toDouble()
            }

            // Set the playback speed callback to respect seeking state
            playerState.applyPlaybackSpeedCallback = { value ->
                if (playerState._isLoading) {
                    // Store the playback speed change to apply after seeking is complete
                    pendingPlaybackSpeedChange = value
                } else {
                    // Apply playback speed change immediately if not seeking
                    video.safeSetPlaybackRate(value)
                    pendingPlaybackSpeedChange = null
                }
            }

            // Apply current playback speed immediately if needed
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

        // Handle seeking - optimized to reduce job creation
        LaunchedEffect(playerState.sliderPos) {
            if (!playerState.userDragging && playerState.hasMedia) {
                // Cancel previous seek job if it exists
                playerState.seekJob?.cancel()

                // Create a new seek job only if needed
                videoElement?.let { video ->
                    val duration = video.duration.toFloat()
                    if (duration > 0f) {
                        val newTime = (playerState.sliderPos / VideoPlayerState.PERCENTAGE_MULTIPLIER) * duration
                        val currentTime = video.currentTime

                        // Only seek if the difference is significant (> 0.5 seconds)
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
    fun VideoBox(isFullscreenMode: Boolean = false) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        VideoBox(isFullscreenMode = false)

        if (playerState.isFullscreen) {
            FullScreenLayout(onDismissRequest = { playerState.isFullscreen = false }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VideoBox(isFullscreenMode = true)
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

                                // Try to get mimeType and title from the video source - optimized
                                val src = video.src
                                if (src.isNotEmpty()) {
                                    // Optimize extension extraction
                                    val lastDotIndex = src.lastIndexOf('.')
                                    if (lastDotIndex > 0 && lastDotIndex < src.length - 1) {
                                        val extension = src.substring(lastDotIndex + 1).lowercase()
                                        // Use map for faster lookup
                                        mimeType = EXTENSION_TO_MIME_TYPE[extension]
                                    }

                                    // Extract title from filename - optimized
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
                                        wasmVideoLogger.w { "Failed to extract title from filename: ${e.message}" }
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
                isFullscreen = playerState.isFullscreen,
                contentScale = contentScale,
                videoRatio = videoRatio
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
        style.backgroundColor = "black" // Always set background color to black
        // Don't set objectFit here, it will be set by setElementPosition based on contentScale

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
                audioAnalyzer.let { analyzer ->
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

    // Optimize event handling by combining related events
    // Map of events that can set loading state to false based on conditions
    val conditionalLoadingEvents = mapOf(
        "suspend" to { video.readyState >= 3 },
        "loadedmetadata" to { true }
    )

    // Add conditional loading event listeners
    conditionalLoadingEvents.forEach { (event, condition) ->
        video.addEventListener(event) {
            // For loadedmetadata, also initialize audio analyzer
            if (event == "loadedmetadata") {
                initAudioAnalyzer()
            }

            // Single coroutine launch for all events
            scope.launch {
                if (condition()) {
                    playerState._isLoading = false
                }

                // Additional actions for loadedmetadata
                if (event == "loadedmetadata") {
                    // Don't set playback speed directly, it will be handled by the applyPlaybackSpeedCallback
                    if (playerState.isPlaying) {
                        video.safePlay()
                    }
                }
            }
        }
    }

    // Handle play event and audio level updates
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

    // Cancel audio level job when paused
    video.addEventListener("pause") {
        audioLevelJob?.cancel()
        audioLevelJob = null
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
    // Don't set volume or playback speed here, they will be handled by the callbacks in the DisposableEffect
    // This avoids potential race conditions with the seeking state
    video.loop = playerState.loop

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
