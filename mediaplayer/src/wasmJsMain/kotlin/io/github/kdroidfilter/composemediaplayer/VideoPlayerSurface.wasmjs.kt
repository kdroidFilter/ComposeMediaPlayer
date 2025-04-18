package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.composemediaplayer.htmlinterop.HtmlView
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import io.github.kdroidfilter.composemediaplayer.jsinterop.MediaError
import kotlin.js.js
import kotlin.math.abs


/**
 * Logger for WebAssembly video player surface
 */
internal val wasmVideoLogger = Logger.withTag("WasmVideoPlayerSurface")
    .apply { Logger.setMinSeverity(Severity.Debug) }


@Composable
actual fun VideoPlayerSurface(playerState: VideoPlayerState, modifier: Modifier) {
    if (playerState.hasMedia) {

        var videoElement by remember { mutableStateOf<HTMLVideoElement?>(null) }
        var videoRatio by remember { mutableStateOf<Float?>(null) }
        // Track if we're using CORS mode (initially true, will be set to false if CORS errors occur)
        var useCors by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Transparent)
                .drawBehind {
                    videoRatio?.let { ratio ->
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
                }
        ) {

            // Create HTML video element
            // Use key to force recreation when CORS mode changes
            key(useCors) {
                HtmlView(
                    factory = {
                        createVideoElement(useCors)
                    },
                    modifier = modifier,
                    update = { video ->
                        videoElement = video

                        video.addEventListener("loadedmetadata") {
                            val width = video.videoWidth
                            val height = video.videoHeight
                            if (height != 0) {
                                videoRatio = width.toFloat() / height.toFloat()
                                wasmVideoLogger.d { "The video ratio is updated: $videoRatio" }
                            }
                        }

                        setupVideoElement(
                            video,
                            playerState,
                            scope,
                            enableAudioDetection = true,
                            useCors = useCors,
                            onCorsError = { useCors = false }
                        )
                    },
                    onPositionCallback = { recalculateFunc ->
                        // Store the recalculation function in the player state
                        playerState.positionRecalculationCallback = recalculateFunc
                        wasmVideoLogger.d { "Position recalculation callback set" }
                    }
                )
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

                    // Reset corsErrorDetected flag when loading a new source
                    // This will be handled in setupVideoElement

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

        // Handle fullscreen using FullScreenLayout
        if (playerState.isFullscreen) {
            // Store current position and playing state before entering fullscreen
            var currentPosition by remember(playerState.isFullscreen) { mutableStateOf(videoElement?.currentTime ?: 0.0) }
            var isPlaying by remember(playerState.isFullscreen) { mutableStateOf(playerState.isPlaying) }

            // Log fullscreen state change
            LaunchedEffect(Unit) {
                wasmVideoLogger.d { "Entering fullscreen mode using FullScreenLayout" }
            }

            FullScreenLayout(
                modifier = Modifier.fillMaxSize(),
                onDismissRequest = {
                    wasmVideoLogger.d { "Exiting fullscreen mode" }
                    playerState.isFullscreen = false
                }
            ) {
                // Create a fullscreen video player surface
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.Black)
                        .drawBehind {
                            videoRatio?.let { ratio ->
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
                        }
                ) {
                    // Create HTML video element in fullscreen mode
                    key(useCors) {
                        HtmlView(
                            factory = {
                                createVideoElement(useCors)
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { video ->
                                // Set up the fullscreen video element
                                videoElement = video

                                video.addEventListener("loadedmetadata") {
                                    val width = video.videoWidth
                                    val height = video.videoHeight
                                    if (height != 0) {
                                        videoRatio = width.toFloat() / height.toFloat()
                                        wasmVideoLogger.d { "The video ratio is updated: $videoRatio" }
                                    }

                                    // Restore position when metadata is loaded
                                    if (currentPosition > 0) {
                                        video.currentTime = currentPosition
                                        wasmVideoLogger.d { "Restored position in fullscreen: $currentPosition" }
                                    }

                                    // Restore playing state
                                    if (isPlaying) {
                                        try {
                                            video.play()
                                            wasmVideoLogger.d { "Resumed playback in fullscreen" }
                                        } catch (e: Exception) {
                                            wasmVideoLogger.e { "Error resuming playback in fullscreen: ${e.message}" }
                                        }
                                    }
                                }

                                setupVideoElement(
                                    video,
                                    playerState,
                                    scope,
                                    enableAudioDetection = true,
                                    useCors = useCors,
                                    onCorsError = { useCors = false }
                                )
                            },
                            onPositionCallback = { recalculateFunc ->
                                // Store the recalculation function in the player state
                                playerState.positionRecalculationCallback = recalculateFunc
                                wasmVideoLogger.d { "Position recalculation callback set" }
                            }
                        )
                    }
                }
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

        LaunchedEffect(playerState.currentSubtitleTrack) {
            videoElement?.let { video ->
                val trackElements = video.querySelectorAll("track")
                for (i in 0 until trackElements.length) {
                    val track = trackElements.item(i)
                    track?.let { video.removeChild(it) }
                }

                playerState.currentSubtitleTrack?.let { track ->
                    val trackElement = document.createElement("track") as HTMLTrackElement
                    trackElement.kind = "subtitles"
                    trackElement.label = track.label
                    trackElement.srclang = track.language
                    trackElement.src = track.src
                    trackElement.default = true
                    video.appendChild(trackElement)
                }
            }
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
            "video/mp4",
            "video/webm",
            "video/ogg"
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
                val isCorsRelatedError = error.code == MediaError.MEDIA_ERR_NETWORK ||
                        (error.code == MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED && useCors)

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
