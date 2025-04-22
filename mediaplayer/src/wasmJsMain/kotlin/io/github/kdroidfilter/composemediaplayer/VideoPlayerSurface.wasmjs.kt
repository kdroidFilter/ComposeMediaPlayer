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


/**
 * Logger for WebAssembly video player surface
 */
internal val wasmVideoLogger = Logger.withTag("WasmVideoPlayerSurface").apply { Logger.setMinSeverity(Severity.Warn) }

/**
 * Extension function to create a Modifier that draws a transparent rectangle respecting the video ratio
 * @param videoRatio The aspect ratio of the video (width / height)
 */
fun Modifier.videoRatioClip(videoRatio: Float?): Modifier {
    return this.drawBehind {
        videoRatio?.let { ratio ->
            drawVideoRatioRect(ratio)
        }
    }
}

/**
 * Helper function to draw a transparent rectangle respecting the video ratio
 * @param ratio The aspect ratio of the video (width / height)
 */
private fun DrawScope.drawVideoRatioRect(ratio: Float) {
    // We calculate a centered rectangle respecting the ratio of the video
    val containerWidth = size.width
    val containerHeight = size.height
    val rectWidth: Float
    val rectHeight: Float

    if (containerWidth / containerHeight > ratio) {
        // The Box is too wide, we base ourselves on the height
        rectHeight = containerHeight
        rectWidth = rectHeight * ratio
    } else {
        // The Box is too high, we base ourselves on the width
        rectWidth = containerWidth
        rectHeight = rectWidth / ratio
    }

    val offsetX = (containerWidth - rectWidth) / 2f
    val offsetY = (containerHeight - rectHeight) / 2f

    drawRect(
        color = Color.Transparent,
        blendMode = BlendMode.Clear,
        topLeft = Offset(offsetX, offsetY),
        size = Size(rectWidth, rectHeight)
    )
}

/**
 * Reusable composable for displaying subtitles
 * @param playerState The video player state
 */
@Composable
private fun SubtitleOverlay(playerState: VideoPlayerState) {
    if (playerState.subtitlesEnabled && playerState.currentSubtitleTrack != null) {
        // Calculate current time in milliseconds
        val currentTimeMs = (playerState.sliderPos / 1000f * playerState.durationText.toTimeMs()).toLong()

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

        // Handle fullscreen update
        LaunchedEffect(playerState.isFullscreen) {
            try {
                if (playerState.isFullscreen) {
                    wasmVideoLogger.d { "Requesting fullscreen" }
                    // No need to call requestFullScreen here as it's handled by toggleFullscreen in VideoPlayerState
                } else {
                    wasmVideoLogger.d { "Exiting fullscreen" }
                    // Ensure we exit fullscreen if needed
                    FullscreenManager.exitFullscreen()
                }
            } catch (e: Exception) {
                wasmVideoLogger.e { "Error handling fullscreen: ${e.message}" }
            }
        }

        // Listen for fullscreen change events on the document
        DisposableEffect(Unit) {
            // Create a fullscreen change listener function
            val fullscreenChangeListener: (Event) -> Unit = {
                // Check if we're no longer in fullscreen mode but our state says we are
                videoElement?.let { video ->
                    val isDocumentFullscreen = video.ownerDocument?.fullscreenElement != null
                    if (!isDocumentFullscreen && playerState.isFullscreen) {
                        wasmVideoLogger.d { "Fullscreen exited externally (e.g., via Escape key)" }
                        playerState.isFullscreen = false
                    }
                }
            }

            // Add event listeners for fullscreen change events
            document.addEventListener("fullscreenchange", fullscreenChangeListener)
            document.addEventListener("webkitfullscreenchange", fullscreenChangeListener)
            document.addEventListener("mozfullscreenchange", fullscreenChangeListener)
            document.addEventListener("MSFullscreenChange", fullscreenChangeListener)

            // Remove the event listeners when the composable is disposed
            onDispose {
                document.removeEventListener("fullscreenchange", fullscreenChangeListener)
                document.removeEventListener("webkitfullscreenchange", fullscreenChangeListener)
                document.removeEventListener("mozfullscreenchange", fullscreenChangeListener)
                document.removeEventListener("MSFullscreenChange", fullscreenChangeListener)
            }
        }
        // Handle source change effect
        LaunchedEffect(playerState.sourceUri) {
            videoElement?.let {
                val sourceUri = playerState.sourceUri ?: ""
                if (sourceUri.isNotEmpty()) {
                    // Log the source URI for debugging
                    wasmVideoLogger.d { "Setting video source to: $sourceUri with useCors=$useCors" }

                    // Clear any previous error
                    playerState.clearError()

                    // Set the source
                    it.src = sourceUri

                    // Try to load the video
                    wasmVideoLogger.d { "Calling load() on video element" }
                    it.load()

                    if (playerState.isPlaying) {
                        try {
                            wasmVideoLogger.d { "Attempting to play video" }
                            it.play()
                        } catch (e: Exception) {
                            wasmVideoLogger.e { "Error playing video: ${e.message}" }
                        }
                    } else {
                        it.pause()
                    }
                }
            }
        }

        // Handle play/pause
        LaunchedEffect(playerState.isPlaying) {
            videoElement?.let {
                if (playerState.isPlaying) {
                    it.play()
                } else {
                    it.pause()
                }
            }
        }

        // Handle volume update
        LaunchedEffect(playerState.volume) {
            videoElement?.volume = playerState.volume.toDouble()
        }

        // Handle loop update
        LaunchedEffect(playerState.loop) {
            videoElement?.loop = playerState.loop
        }

        // When CORS mode changes, we log it and store the current position and playing state
        var lastPosition by remember { mutableStateOf(0.0) }
        var wasPlaying by remember { mutableStateOf(false) }

        LaunchedEffect(useCors) {
            wasmVideoLogger.d { "CORS mode changed to $useCors" }
            // Store current position and playing state before the video element is recreated
            videoElement?.let {
                lastPosition = it.currentTime
                wasPlaying = playerState.isPlaying
            }
        }

        // After the video element is recreated, restore the position and playing state
        LaunchedEffect(videoElement, useCors) {
            videoElement?.let {
                if (lastPosition > 0) {
                    it.currentTime = lastPosition
                    // Reset lastPosition to avoid applying it again
                    lastPosition = 0.0
                }
                if (wasPlaying) {
                    try {
                        it.play()
                    } catch (e: Exception) {
                        wasmVideoLogger.e { "Error playing media after CORS mode change: ${e.message}" }
                    }
                    // Reset wasPlaying to avoid playing again
                    wasPlaying = false
                }
            }
        }

        // Handle seek via sliderPos (with debounce)
        LaunchedEffect(playerState.sliderPos) {
            if (!playerState.userDragging && playerState.hasMedia) {
                val job = scope.launch {
                    val duration = videoElement?.duration?.toFloat() ?: 0f
                    if (duration > 0f) {
                        val newTime = (playerState.sliderPos / VideoPlayerState.PERCENTAGE_MULTIPLIER) * duration
                        // Avoid seeking if the difference is small
                        if (abs((videoElement?.currentTime ?: 0.0) - newTime) > 0.5) {
                            videoElement?.currentTime = newTime.toDouble()
                        }
                    }
                }
                // Cancel previous job if a new sliderPos arrives before completion
                playerState.seekJob?.cancel()
                playerState.seekJob = job
            }
        }

        // Listen for play/pause events on the video element
        DisposableEffect(videoElement) {
            val video = videoElement ?: return@DisposableEffect onDispose {}

            // Create play event listener
            val playListener: (Event) -> Unit = {
                wasmVideoLogger.d { "Video played externally" }
                if (!playerState.isPlaying) {
                    scope.launch {
                        playerState.play()
                    }
                }
            }

            // Create pause event listener
            val pauseListener: (Event) -> Unit = {
                wasmVideoLogger.d { "Video paused externally" }
                if (playerState.isPlaying) {
                    scope.launch {
                        playerState.pause()
                    }
                }
            }

            // Add event listeners for play/pause events
            video.addEventListener("play", playListener)
            video.addEventListener("pause", pauseListener)

            // Remove the event listeners when the composable is disposed
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
    // Note: ContentScale parameter is not used here as the actual implementation will be handled by someone else as per the issue description
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .videoRatioClip(videoRatio)
    ) {
        // Add Compose-based subtitle layer
        SubtitleOverlay(playerState)

        // Render the overlay content on top of the video
        overlay()

        if (playerState.isFullscreen) {
            FullScreenLayout(onDismissRequest = { playerState.isFullscreen = false }) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black), 
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .videoRatioClip(videoRatio)
                    ) {
                        // Add Compose-based subtitle layer in fullscreen mode
                        SubtitleOverlay(playerState)

                        // Render the overlay content on top of the video in fullscreen mode
                        overlay()
                    }
                }
            }
        }

        // Create HTML video element
        // Use key to force recreation when CORS mode changes
        key(useCors) {
            HtmlView(
                factory = {
                    createVideoElement(useCors)
                }, 
                modifier = modifier, 
                update = { video ->
                    onVideoElementChange(video)

                    video.addEventListener("loadedmetadata") {
                        val width = video.videoWidth
                        val height = video.videoHeight
                        if (height != 0) {
                            onVideoRatioChange(width.toFloat() / height.toFloat())
                            wasmVideoLogger.d { "The video ratio is updated: ${width.toFloat() / height.toFloat()}" }

                            // Update metadata properties
                            playerState.metadata.width = width
                            playerState.metadata.height = height
                            playerState.metadata.duration = (video.duration * 1000).toLong() // Convert seconds to milliseconds

                            // Try to get mimeType from the video source
                            val src = video.src
                            if (src.isNotEmpty()) {
                                val extension = src.substringAfterLast('.', "").lowercase()
                                playerState.metadata.mimeType = when (extension) {
                                    "mp4" -> "video/mp4"
                                    "webm" -> "video/webm"
                                    "ogg" -> "video/ogg"
                                    "mov" -> "video/quicktime"
                                    "avi" -> "video/x-msvideo"
                                    "mkv" -> "video/x-matroska"
                                    else -> null
                                }

                                // Try to extract title from the filename
                                try {
                                    val filename = src.substringAfterLast('/', "")
                                                     .substringAfterLast('\\', "")
                                                     .substringBeforeLast('.', "")
                                    if (filename.isNotEmpty()) {
                                        playerState.metadata.title = filename
                                    }
                                } catch (e: Exception) {
                                    wasmVideoLogger.w { "Failed to extract title from filename: ${e.message}" }
                                }
                            }

                            // Set frameRate - use a common default value since it's not directly available
                            // Most videos are either 24, 25, 30, or 60 fps - 30 is a reasonable default
                            playerState.metadata.frameRate = 30.0f

                            // Estimate bitrate based on video dimensions and a quality factor
                            // This is a very rough estimate
                            if (width > 0 && height > 0) {
                                val qualityFactor = when {
                                    width >= 3840 -> 20000000L // 4K video (~20 Mbps)
                                    width >= 1920 -> 8000000L  // 1080p video (~8 Mbps)
                                    width >= 1280 -> 5000000L  // 720p video (~5 Mbps)
                                    else -> 2000000L           // SD video (~2 Mbps)
                                }
                                playerState.metadata.bitrate = qualityFactor
                            }

                            // Artist is typically not available in video files without additional metadata
                            // We could set it to a default value or leave it as null

                            wasmVideoLogger.d { "Updated metadata: width=${width}, height=${height}, duration=${playerState.metadata.duration}, " +
                                               "mimeType=${playerState.metadata.mimeType}, title=${playerState.metadata.title}, " +
                                               "frameRate=${playerState.metadata.frameRate}, bitrate=${playerState.metadata.bitrate}" }
                        }
                    }

                    setupVideoElement(
                        video,
                        playerState,
                        scope,
                        enableAudioDetection = true,
                        useCors = useCors,
                        onCorsError = { onCorsChange(false) })
                },
                isFullscreen = playerState.isFullscreen
            )
        }
    }
}

private fun createVideoElement(useCors: Boolean = true): HTMLVideoElement {
    wasmVideoLogger.d { "Creating video element with useCors=$useCors" }

    return (document.createElement("video") as HTMLVideoElement).apply {
        controls = false
        // Absolute position to fit the video in its container
        style.position = "absolute"

        // negative z-index => in background
        style.zIndex = "-1"

        style.width = "100%"
        style.height = "100%"

        // Handle CORS mode
        if (useCors) {
            // Set crossOrigin to anonymous to enable CORS requests
            crossOrigin = "anonymous"
            wasmVideoLogger.d { "CORS mode enabled: Set crossOrigin=anonymous" }
        } else {
            // Explicitly remove the crossOrigin attribute to disable CORS
            // This is important as some browsers might keep the attribute if not explicitly removed
            removeAttribute("crossorigin")
            wasmVideoLogger.d { "CORS mode disabled: Removed crossOrigin attribute" }
        }

        // Add additional attributes to help with format detection
        setAttribute("playsinline", "")
        setAttribute("webkit-playsinline", "")
        setAttribute("preload", "auto")

        // Enable more formats
        setAttribute("x-webkit-airplay", "allow")

        // Log supported types for debugging
        val supportedTypes = listOf(
            "video/mp4", "video/webm", "video/ogg"
        )

        supportedTypes.forEach { type ->
            val canPlay = canPlayType(type)
            wasmVideoLogger.d { "Browser support for $type: $canPlay" }
        }
    }
}


/** Configure video element: listeners, WebAudioAnalyzer, etc. */
fun setupVideoElement(
    video: HTMLVideoElement,
    playerState: VideoPlayerState,
    scope: CoroutineScope,
    enableAudioDetection: Boolean = true,
    useCors: Boolean = true,
    onCorsError: () -> Unit = {},
) {
    wasmVideoLogger.d { "Setup video => enableAudioDetection = $enableAudioDetection, useCors = $useCors" }

    // Create analyzer only if enableAudioDetection is true
    val audioAnalyzer = if (enableAudioDetection) {
        AudioLevelProcessor(video)
    } else null

    var initializationJob: Job? = null
    // Track if we've detected CORS errors - start with false for each new setup
    var corsErrorDetected = false

    // Reset error state when setting up a new video element
    playerState.clearError()

    // Reset audio metadata
    playerState.metadata.audioChannels = null
    playerState.metadata.audioSampleRate = null

    // Note: Fullscreen styling is now handled directly by the HtmlView component

    // Helper => initialize analysis if enableAudioDetection
    fun initAudioAnalyzer() {
        if (!enableAudioDetection || corsErrorDetected) return
        initializationJob?.cancel()
        initializationJob = scope.launch {
            val success = audioAnalyzer?.initialize() ?: false
            if (!success) {
                // CORS error detected, disable audio analysis for this video
                corsErrorDetected = true
                wasmVideoLogger.w { "CORS error detected during audio analyzer initialization. Audio level processing disabled for this video." }
            } else {
                // Update metadata with audio properties
                audioAnalyzer?.let { analyzer ->
                    playerState.metadata.audioChannels = analyzer.audioChannels
                    playerState.metadata.audioSampleRate = analyzer.audioSampleRate
                    wasmVideoLogger.d { "Updated metadata with audio properties: channels=${analyzer.audioChannels}, sampleRate=${analyzer.audioSampleRate}" }
                }
            }
        }
    }

    // loadedmetadata => attempt initialization
    video.addEventListener("loadedmetadata") {
        wasmVideoLogger.d { "Video => loadedmetadata => init analyzer if enabled" }
        initAudioAnalyzer()
    }

    // play => re-init
    video.addEventListener("play") {
        wasmVideoLogger.d { "Video => play => init analyzer if needed" }

        if (!enableAudioDetection) {
            wasmVideoLogger.d { "Audio detection disabled => no analyzer." }
        } else if (!corsErrorDetected && initializationJob?.isActive != true) {
            initAudioAnalyzer()
        }

        // Loop => read levels only if analyzer is not null and no CORS errors
        if (enableAudioDetection) {
            scope.launch {
                wasmVideoLogger.d { "Starting audio level update loop" }
                while (true) {
                    // Only try to get audio levels if no CORS errors were detected
                    val (left, right) = if (!corsErrorDetected) {
                        audioAnalyzer?.getAudioLevels() ?: (0f to 0f)
                    } else {
                        // If CORS errors were detected, just return zeros
                        0f to 0f
                    }
                    playerState.updateAudioLevels(left, right)
                    delay(100)
                }
            }
        }
    }

    // timeupdate
    video.removeEventListener("timeupdate", playerState::onTimeUpdateEvent)
    video.addEventListener("timeupdate", playerState::onTimeUpdateEvent)

    // seeking, waiting, canplay, etc.
    video.addEventListener("seeking") {
        scope.launch { playerState._isLoading = true }
    }
    video.addEventListener("seeked") {
        scope.launch { playerState._isLoading = false }
    }
    video.addEventListener("waiting") {
        scope.launch { playerState._isLoading = true }
    }
    video.addEventListener("playing") {
        scope.launch { playerState._isLoading = false }
    }
    video.addEventListener("canplaythrough") {
        scope.launch { playerState._isLoading = false }
    }
    video.addEventListener("canplay") {
        scope.launch { playerState._isLoading = false }
    }
    video.addEventListener("suspend") {
        scope.launch {
            if (video.readyState >= 3) {
                playerState._isLoading = false
            }
        }
    }
    // error
    video.addEventListener("error") {
        scope.launch {
            playerState._isLoading = false
            // Mark as CORS error to prevent further audio analyzer attempts
            corsErrorDetected = true

            // Log detailed error information
            val error = video.error
            if (error != null) {
                val errorMessage = when (error.code) {
                    MediaError.MEDIA_ERR_ABORTED -> "MEDIA_ERR_ABORTED: The fetching process was aborted by the user"
                    MediaError.MEDIA_ERR_NETWORK -> "MEDIA_ERR_NETWORK: A network error occurred while fetching the media"
                    MediaError.MEDIA_ERR_DECODE -> "MEDIA_ERR_DECODE: An error occurred while decoding the media"
                    MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED -> "MEDIA_ERR_SRC_NOT_SUPPORTED: The media format is not supported"
                    else -> "Unknown error code: ${error.code}"
                }
                wasmVideoLogger.e { "Video error details: $errorMessage" }

                // Check if this is likely a CORS error (network errors are often CORS-related)
                val isCorsRelatedError =
                    error.code == MediaError.MEDIA_ERR_NETWORK || (error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED && useCors)

                // Check for CORS-specific error in the console (this won't be captured directly, but helps with debugging)
                wasmVideoLogger.d { "Is this likely a CORS-related error? $isCorsRelatedError (current useCors=$useCors)" }
                wasmVideoLogger.d { "If you see 'Access to video has been blocked by CORS policy' in the browser console, this is definitely a CORS error" }
            }

            if (useCors) {
                // If we're using CORS and got an error, try without CORS
                wasmVideoLogger.w { "Video error with CORS enabled. Attempting to reload without CORS restrictions." }
                // Clear the error since we're going to try again
                playerState.clearError()
                // Call the callback to update useCors in the composable
                onCorsError()

                // Force a reload by clearing and resetting the source
                val currentSrc = video.src
                if (currentSrc.isNotEmpty()) {
                    wasmVideoLogger.d { "Forcing reload of video source: $currentSrc" }
                    // We don't actually change the source here because the video element will be recreated
                    // when useCors changes due to the key(useCors) in the composable
                }
            } else {
                // If we already tried without CORS and still got an error, set the error state
                val errorMsg = if (error?.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED) {
                    "Failed to load because the video format is not supported"
                } else {
                    "Failed to load because no supported source was found"
                }
                playerState.setError(VideoPlayerError.SourceError(errorMsg))
                wasmVideoLogger.e { "Video error: $errorMsg" }
                wasmVideoLogger.w { "Audio levels will be set to 0 due to video error." }
            }
        }
    }

    // loadedmetadata => set isLoading false + play if needed
    video.addEventListener("loadedmetadata") {
        scope.launch {
            playerState._isLoading = false
            if (playerState.isPlaying) {
                try {
                    video.play()
                } catch (e: Exception) {
                    wasmVideoLogger.e { "Error opening media: ${e.message}" }
                }
            }
        }
    }

    // ended => pause the video
    video.addEventListener("ended") {
        scope.launch {
            wasmVideoLogger.d { "Video playback ended" }
            playerState.pause()
        }
    }

    // volume, loop
    video.volume = playerState.volume.toDouble()
    video.loop = playerState.loop

    // If source already exists + want to play
    if (video.src.isNotEmpty() && playerState.isPlaying) {
        try {
            video.play()
        } catch (e: Exception) {
            wasmVideoLogger.e { "Error opening media: ${e.message}" }
        }
    }
}

// Handle "timeupdate" event to manage progress cursor
private fun VideoPlayerState.onTimeUpdateEvent(event: Event) {
    val video = event.target as? HTMLVideoElement
    video?.let {
        onTimeUpdate(it.currentTime.toFloat(), it.duration.toFloat())
    }
}

/**
 * Manages fullscreen functionality for the video player
 */
object FullscreenManager {
    /**
     * Exit fullscreen if document is in fullscreen mode
     */
    fun exitFullscreen() {
        if (document.fullscreenElement != null) {
            document.exitFullscreen()
        }
    }

    /**
     * Apply fullscreen styles to the video element
     * This function is kept for backward compatibility but should not be called directly.
     * Instead, use the fullscreenStyleCallback in VideoPlayerState.
     */
    suspend fun applyVideoStyles() {
        val video = document.querySelector("video") as? HTMLVideoElement
        delay(501)
        video?.let {
            it.style.width = "100%"
            it.style.height = "100%"
            it.style.margin = "0px"
            it.style.left = "0"
            it.style.top = "0"
        }
    }

    /**
     * Request fullscreen mode
     */
    fun requestFullScreen() {
        val document = document.documentElement
        document?.requestFullscreen()
    }

    /**
     * Toggle fullscreen mode
     * @param isCurrentlyFullscreen Whether the player is currently in fullscreen mode
     * @param onFullscreenChange Callback to update the fullscreen state
     */
    fun toggleFullscreen(isCurrentlyFullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
        if (!isCurrentlyFullscreen) {
            requestFullScreen()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                delay(500)
                applyVideoStyles()
            }
        } else {
            exitFullscreen()
        }
        onFullscreenChange(!isCurrentlyFullscreen)
    }
}

// Backward compatibility functions
fun exitFullscreen() = FullscreenManager.exitFullscreen()
suspend fun applyVideoStyles() = FullscreenManager.applyVideoStyles()
fun requestFullScreen() = FullscreenManager.requestFullScreen()
