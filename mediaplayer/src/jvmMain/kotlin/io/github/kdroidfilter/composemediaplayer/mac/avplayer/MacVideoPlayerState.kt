package io.github.kdroidfilter.composemediaplayer.mac.avplayer

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Logger.Companion.setMinSeverity
import co.touchlab.kermit.Severity
import com.sun.jna.Pointer
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.abs

// Initialize logger using Kermit
internal val macLogger = Logger.withTag("MacVideoPlayerState").apply { setMinSeverity(Severity.Warn) }

/**
 * MacVideoPlayerState handles the native Mac video player state.
 *
 * This implementation uses a native video player via SharedVideoPlayer.
 * All debug logs are handled with Kermit.
 */
class MacVideoPlayerState : PlatformVideoPlayerState {

    // Main state variables
    private val mainMutex = Mutex()
    private var playerPtr: Pointer? = null
    private val _currentFrameState = MutableStateFlow<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)
    private var _bufferImage: BufferedImage? = null

    // Background worker threads and jobs
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameUpdateJob: Job? = null
    private var bufferingCheckJob: Job? = null
    private var uiUpdateJob: Job? = null

    // State tracking
    private var lastFrameUpdateTime: Long = 0
    private var seekInProgress = false
    private var targetSeekTime: Double? = null
    private var lastFrameHash: Int = 0
    private var videoFrameRate: Float = 0.0f
    private var screenRefreshRate: Float = 0.0f
    private var captureFrameRate: Float = 0.0f

    // UI State (Main thread)
    override var hasMedia: Boolean by mutableStateOf(false)
    override var isPlaying: Boolean by mutableStateOf(false)
    override var sliderPos: Float by mutableStateOf(0.0f)
    override var userDragging: Boolean by mutableStateOf(false)
    override var loop: Boolean by mutableStateOf(false)
    override val leftLevel: Float by mutableStateOf(0.0f)
    override val rightLevel: Float by mutableStateOf(0.0f)
    override var isLoading: Boolean by mutableStateOf(false)
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override val metadata: VideoMetadata = VideoMetadata()

    // Non-blocking text properties
    private val _positionText = mutableStateOf("")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("")
    override val durationText: String get() = _durationText.value

    // Non-blocking aspect ratio property
    private val _aspectRatio = mutableStateOf(16f / 9f)
    internal val aspectRatio: Float get() = _aspectRatio.value

    // Player settings
    private var _volume: Float = 1.0f
    override var volume: Float
        get() = _volume
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volume != newValue) {
                _volume = newValue
                ioScope.launch {
                    applyVolume()
                }
            }
        }

    private val updateInterval: Long
        get() = if (captureFrameRate > 0) (1000.0f / captureFrameRate).toLong().coerceAtLeast(33L) else 33L

    // Buffering detection constants
    private val bufferingCheckInterval = 200L // Increased from 100ms to reduce CPU usage
    private val bufferingTimeoutThreshold = 500L

    init {
        macLogger.d { "Initializing video player" }
        ioScope.launch {
            initPlayer()
            startUIUpdateJob()
        }
    }

    /**
     * Starts a job to update UI state based on frame updates.
     * This is the only job that touches the main thread.
     */
    @OptIn(FlowPreview::class)
    private fun startUIUpdateJob() {
        uiUpdateJob?.cancel()
        uiUpdateJob = ioScope.launch {
            _currentFrameState
                .debounce(33) // Decreased from 16ms to 33ms (30fps max) to reduce UI pressure
                .collect { newFrame ->
                    ensureActive() // Check if coroutine is still active
                    withContext(Dispatchers.Main) {
                        (currentFrameState as androidx.compose.runtime.MutableState).value = newFrame
                    }
                }
        }
    }

    /** Initializes the native video player on IO thread. */
    private suspend fun initPlayer() = ioScope.launch {
        macLogger.d { "initPlayer() - Creating native player" }
        try {
            val ptr = SharedVideoPlayer.INSTANCE.createVideoPlayer()
            if (ptr != null) {
                mainMutex.withLock { playerPtr = ptr }
                macLogger.d { "Native player created successfully" }
                applyVolume()
            } else {
                macLogger.e { "Error: Failed to create native player" }
                withContext(Dispatchers.Main) {
                    error = VideoPlayerError.UnknownError("Failed to create native player")
                }
            }
        } catch (e: Exception) {
            macLogger.e { "Exception in initPlayer: ${e.message}" }
            withContext(Dispatchers.Main) {
                error = VideoPlayerError.UnknownError("Failed to initialize player: ${e.message}")
            }
        }
    }.join()

    /** Updates the frame rate information from the native player. */
    private suspend fun updateFrameRateInfo() {
        macLogger.d { "updateFrameRateInfo()" }
        val ptr = mainMutex.withLock { playerPtr } ?: return

        try {
            videoFrameRate = SharedVideoPlayer.INSTANCE.getVideoFrameRate(ptr)
            screenRefreshRate = SharedVideoPlayer.INSTANCE.getScreenRefreshRate(ptr)
            captureFrameRate = SharedVideoPlayer.INSTANCE.getCaptureFrameRate(ptr)
            macLogger.d { "Frame Rates - Video: $videoFrameRate, Screen: $screenRefreshRate, Capture: $captureFrameRate" }
        } catch (e: Exception) {
            macLogger.e { "Error updating frame rate info: ${e.message}" }
        }
    }

    override fun openUri(uri: String) {
        macLogger.d { "openUri() - Opening URI: $uri" }

        // Update UI state first
        ioScope.launch {
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null  // Clear any previous errors
            }

            // Ensure all heavy operations are in background
            try {
                // Stop and clean up any existing playback
                if (hasMedia) {
                    cleanupCurrentPlayback()
                }

                // Ensure player is initialized in background
                ensurePlayerInitialized()

                // Open URI on IO thread and capture result
                val result = openMediaUri(uri)

                if (result) {
                    // Launch parallel background tasks
                    coroutineScope {
                        launch { updateFrameRateInfo() }
                        launch { updateMetadata() }
                    }

                    // Update UI state on main thread
                    withContext(Dispatchers.Main) {
                        hasMedia = true
                        isLoading = false
                        isPlaying = true
                    }

                    // Start background processes for frame updates
                    startFrameUpdates()

                    // First frame update in background
                    updateFrameAsync()

                    // Start buffering check in background
                    startBufferingCheck()

                    // Start playback if needed - in background
                    if (isPlaying) {
                        playInBackground()
                    }
                } else {
                    macLogger.e { "Failed to open URI" }
                    withContext(Dispatchers.Main) {
                        isLoading = false
                        error = VideoPlayerError.SourceError("Failed to open media source")
                    }
                }
            } catch (e: Exception) {
                macLogger.e { "openUri() - Exception: ${e.message}" }
                handleError(e)
            }
        }
    }

    /**
     * Cleans up current playback state.
     */
    private suspend fun cleanupCurrentPlayback() {
        macLogger.d { "cleanupCurrentPlayback() - Cleaning up current playback" }
        pauseInBackground()
        stopFrameUpdates()
        stopBufferingCheck()

        val ptrToDispose = mainMutex.withLock {
            val ptr = playerPtr
            playerPtr = null
            ptr
        }

        // Release resources outside of mutex lock
        ptrToDispose?.let {
            try {
                SharedVideoPlayer.INSTANCE.disposeVideoPlayer(it)
            } catch (e: Exception) {
                macLogger.e { "Error disposing player: ${e.message}" }
            }
        }
    }

    /**
     * Ensures player is initialized.
     */
    private suspend fun ensurePlayerInitialized() {
        macLogger.d { "ensurePlayerInitialized() - Ensuring player is initialized" }
        if (!playerScope.isActive) {
            playerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        val isPlayerNull = mainMutex.withLock { playerPtr == null }

        if (isPlayerNull) {
            val ptr = SharedVideoPlayer.INSTANCE.createVideoPlayer()
            if (ptr != null) {
                mainMutex.withLock { playerPtr = ptr }
                applyVolume()
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    /**
     * Opens media URI and returns success state.
     */
    private suspend fun openMediaUri(uri: String): Boolean {
        macLogger.d { "openMediaUri() - Opening URI: $uri" }
        val ptr = mainMutex.withLock { playerPtr } ?: return false

        return try {
            SharedVideoPlayer.INSTANCE.openUri(ptr, uri)
            // Wait a small amount of time for initialization
            delay(100)
            true
        } catch (e: Exception) {
            macLogger.e { "Failed to open URI: ${e.message}" }
            false
        }
    }

    /** Updates the metadata from the native player. */
    private suspend fun updateMetadata() {
        macLogger.d { "updateMetadata()" }
        val ptr = mainMutex.withLock { playerPtr } ?: return

        try {
            val width = SharedVideoPlayer.INSTANCE.getFrameWidth(ptr)
            val height = SharedVideoPlayer.INSTANCE.getFrameHeight(ptr)
            val duration = SharedVideoPlayer.INSTANCE.getVideoDuration(ptr).toLong()
            val frameRate = SharedVideoPlayer.INSTANCE.getVideoFrameRate(ptr)

            // Calculate aspect ratio
            val newAspectRatio = if (width > 0 && height > 0)
                width.toFloat() / height.toFloat()
            else
                16f / 9f

            withContext(Dispatchers.Main) {
                // Update metadata
                metadata.duration = duration
                metadata.width = width
                metadata.height = height
                metadata.frameRate = frameRate

                // Update aspect ratio
                _aspectRatio.value = newAspectRatio
            }

            macLogger.d { "Metadata updated: $metadata" }
        } catch (e: Exception) {
            macLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    /** Starts periodic frame updates on background thread. */
    private fun startFrameUpdates() {
        macLogger.d { "startFrameUpdates() - Starting frame updates" }
        stopFrameUpdates()
        frameUpdateJob = ioScope.launch {
            while (isActive) {
                ensureActive() // Check if coroutine is still active
                updateFrameAsync()
                if (!userDragging) {
                    updatePositionAsync()
                }
                delay(updateInterval)
            }
        }
    }

    /** Stops periodic frame updates. */
    private fun stopFrameUpdates() {
        macLogger.d { "stopFrameUpdates() - Stopping frame updates" }
        frameUpdateJob?.cancel()
        frameUpdateJob = null
    }

    /** Starts periodic checks for buffering state on background thread. */
    private fun startBufferingCheck() {
        macLogger.d { "startBufferingCheck() - Starting buffering detection" }
        stopBufferingCheck()
        bufferingCheckJob = ioScope.launch {
            while (isActive) {
                ensureActive() // Check if coroutine is still active
                checkBufferingState()
                delay(bufferingCheckInterval)
            }
        }
    }

    /** Checks if media is currently buffering. */
    private suspend fun checkBufferingState() {
        if (isPlaying && !isLoading) {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastFrame = currentTime - lastFrameUpdateTime

            if (timeSinceLastFrame > bufferingTimeoutThreshold) {
                macLogger.d { "Buffering detected: $timeSinceLastFrame ms since last frame update" }
                withContext(Dispatchers.Main) {
                    isLoading = true
                }
            }
        }
    }

    /** Stops buffering check job. */
    private fun stopBufferingCheck() {
        macLogger.d { "stopBufferingCheck() - Stopping buffering detection" }
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
    }

    /**
     * Calculates a simple hash of the image data to detect if the frame has
     * changed. Running in compute dispatcher for CPU-intensive work.
     * Optimized to sample fewer pixels to improve performance.
     */
    private suspend fun calculateFrameHash(data: IntArray): Int = withContext(Dispatchers.Default) {
        var hash = 0
        // Sample a smaller subset of pixels for better performance
        val step = data.size / 200 // Increased from 100 to 200 to sample fewer pixels
        if (step > 0) {
            for (i in 0 until data.size step step) {
                hash = 31 * hash + data[i]
            }
        }
        hash
    }

    /** Updates the current video frame on background thread. */
    private suspend fun updateFrameAsync() {
        try {
            // Get player pointer safely
            val ptr = mainMutex.withLock { playerPtr } ?: return

            // Get frame dimensions
            val width = SharedVideoPlayer.INSTANCE.getFrameWidth(ptr)
            val height = SharedVideoPlayer.INSTANCE.getFrameHeight(ptr)

            if (width <= 0 || height <= 0) {
                return
            }

            // Get latest frame - do this before creating buffer to minimize mutex lock time
            val framePtr = SharedVideoPlayer.INSTANCE.getLatestFrame(ptr) ?: return

            // Create or reuse buffered image on compute thread
            val bufferedImage = withContext(Dispatchers.Default) {
                val existingBuffer = mainMutex.withLock { _bufferImage }
                if (existingBuffer == null || existingBuffer.width != width || existingBuffer.height != height) {
                    val newBuffer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    mainMutex.withLock { _bufferImage = newBuffer }
                    newBuffer
                } else {
                    existingBuffer
                }
            }

            // Copy frame data on compute thread for better performance
            withContext(Dispatchers.Default) {
                val pixels = (bufferedImage.raster.dataBuffer as DataBufferInt).data
                framePtr.getByteBuffer(0, (width * height * 4).toLong())
                    .asIntBuffer().get(pixels)

                // Calculate frame hash to detect changes
                val newHash = calculateFrameHash(pixels)
                val frameChanged = newHash != lastFrameHash
                lastFrameHash = newHash

                if (frameChanged) {
                    // Update timestamp
                    lastFrameUpdateTime = System.currentTimeMillis()

                    // Convert to ImageBitmap on compute thread
                    val imageBitmap = bufferedImage.toComposeImageBitmap()

                    // Publish to flow
                    _currentFrameState.value = imageBitmap

                    // Update loading state if needed - on main thread
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            macLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
        }
    }

    /** Updates the playback position and slider on background thread. */
    private suspend fun updatePositionAsync() {
        if (!hasMedia || userDragging) return

        try {
            val duration = getDurationSafely()
            if (duration <= 0) return

            val current = getPositionSafely()

            // Update time text display
            withContext(Dispatchers.Main) {
                _positionText.value = formatTime(current)
                _durationText.value = formatTime(duration)
            }

            // Handle seek in progress
            if (seekInProgress && targetSeekTime != null) {
                if (abs(current - targetSeekTime!!) < 0.3) {
                    seekInProgress = false
                    targetSeekTime = null
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                    macLogger.d { "Seek completed, resetting loading state" }
                }
            } else {
                // Update slider position - batch with other UI updates to reduce UI thread calls
                val newSliderPos = (current / duration * 1000).toFloat().coerceIn(0f, 1000f)
                withContext(Dispatchers.Main) {
                    sliderPos = newSliderPos
                }
            }

            // Check for looping
            checkLoopingAsync(current, duration)
        } catch (e: Exception) {
            macLogger.e { "Error in updatePositionAsync: ${e.message}" }
        }
    }

    /** Checks if looping is enabled and restarts the video if needed. */
    private suspend fun checkLoopingAsync(current: Double, duration: Double) {
        if (current >= duration - 0.5) {
            if (loop) {
                macLogger.d { "checkLoopingAsync() - Loop enabled, restarting video" }
                seekToAsync(0f)
            } else {
                macLogger.d { "checkLoopingAsync() - Video completed, updating state" }
                withContext(Dispatchers.Main) {
                    isPlaying = false
                }

                // Ensure native player state is consistent
                pauseInBackground()
            }
        }
    }

    override fun play() {
        macLogger.d { "play() - Starting playback" }
        ioScope.launch {
            playInBackground()
        }
    }

    /** Plays video in background. */
    private suspend fun playInBackground() {
        val ptr = mainMutex.withLock { playerPtr } ?: return

        try {
            SharedVideoPlayer.INSTANCE.playVideo(ptr)

            withContext(Dispatchers.Main) {
                isPlaying = true
            }

            startFrameUpdates()
            startBufferingCheck()
        } catch (e: Exception) {
            macLogger.e { "Error in playInBackground: ${e.message}" }
            handleError(e)
        }
    }

    override fun pause() {
        macLogger.d { "pause() - Pausing playback" }
        ioScope.launch {
            pauseInBackground()
        }
    }

    /** Pauses video in background. */
    private suspend fun pauseInBackground() {
        val ptr = mainMutex.withLock { playerPtr } ?: return

        try {
            SharedVideoPlayer.INSTANCE.pauseVideo(ptr)

            withContext(Dispatchers.Main) {
                isPlaying = false
                isLoading = false
            }

            updateFrameAsync()
            stopFrameUpdates()
            stopBufferingCheck()
        } catch (e: Exception) {
            macLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        macLogger.d { "stop() - Stopping playback" }
        ioScope.launch {
            pauseInBackground()
            seekToAsync(0f)
        }
    }

    override fun seekTo(value: Float) {
        macLogger.d { "seekTo() - Seeking with slider value: $value" }
        ioScope.launch {
            // Throttle rapid seek operations
            delay(10) // Small delay to coalesce rapid seek events
            seekToAsync(value)
        }
    }

    /** Seeks to position in background. */
    private suspend fun seekToAsync(value: Float) {
        withContext(Dispatchers.Main) {
            isLoading = true
        }

        try {
            val duration = getDurationSafely()
            if (duration <= 0) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
                return
            }

            val seekTime = ((value / 1000f) * duration.toFloat()).coerceIn(0f, duration.toFloat())

            withContext(Dispatchers.Main) {
                seekInProgress = true
                targetSeekTime = seekTime.toDouble()
                sliderPos = value
            }

            lastFrameUpdateTime = System.currentTimeMillis()

            val ptr = mainMutex.withLock { playerPtr } ?: return
            SharedVideoPlayer.INSTANCE.seekTo(ptr, seekTime.toDouble())

            if (isPlaying) {
                SharedVideoPlayer.INSTANCE.playVideo(ptr)

                // Force frame update after seek
                delay(50)
                updateFrameAsync()

                // Timeout for seek operation
                ioScope.launch {
                    delay(2000) // Reduced from 3000ms
                    if (seekInProgress) {
                        macLogger.d { "seekToAsync() - Forcing end of seek after timeout" }
                        seekInProgress = false
                        targetSeekTime = null

                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastFrame = currentTime - lastFrameUpdateTime
                        withContext(Dispatchers.Main) {
                            isLoading = timeSinceLastFrame >= bufferingTimeoutThreshold
                        }
                    }
                }
            }
        } catch (e: Exception) {
            macLogger.e { "Error in seekToAsync: ${e.message}" }
            withContext(Dispatchers.Main) {
                isLoading = false
                seekInProgress = false
                targetSeekTime = null
            }
        }
    }

    override fun dispose() {
        macLogger.d { "dispose() - Releasing resources" }
        // Cancel all background tasks first
        stopFrameUpdates()
        stopBufferingCheck()
        uiUpdateJob?.cancel()
        playerScope.cancel()

        ioScope.launch {
            // Get player pointer to dispose
            val ptrToDispose = mainMutex.withLock {
                val ptr = playerPtr
                playerPtr = null
                ptr
            }

            // Dispose native resources outside mutex lock
            ptrToDispose?.let {
                macLogger.d { "dispose() - Disposing native player" }
                try {
                    SharedVideoPlayer.INSTANCE.disposeVideoPlayer(it)
                } catch (e: Exception) {
                    macLogger.e { "Error disposing player: ${e.message}" }
                }
            }

            resetState()

            // Clear buffer image
            mainMutex.withLock {
                _bufferImage = null
            }
        }

        // Cancel ioScope last to ensure cleanup completes
        ioScope.cancel()
    }

    /** Resets the player's state. */
    private suspend fun resetState() {
        withContext(Dispatchers.Main) {
            hasMedia = false
            isPlaying = false
            isLoading = false
            _positionText.value = ""
            _durationText.value = ""
            _aspectRatio.value = 16f / 9f
            error = null
        }
        _currentFrameState.value = null
    }

    /** Handles errors by updating the state and logging the error. */
    private suspend fun handleError(e: Exception) {
        withContext(Dispatchers.Main) {
            isLoading = false
            error = VideoPlayerError.SourceError("Error: ${e.message}")
        }
        macLogger.e { "handleError() - Player error: ${e.message}" }
    }

    /** Retrieves the current playback time from the native player. */
    private suspend fun getPositionSafely(): Double {
        val ptr = mainMutex.withLock { playerPtr } ?: return 0.0
        return try {
            SharedVideoPlayer.INSTANCE.getCurrentTime(ptr)
        } catch (e: Exception) {
            macLogger.e { "Error getting position: ${e.message}" }
            0.0
        }
    }

    /** Retrieves the total duration of the video from the native player. */
    private suspend fun getDurationSafely(): Double {
        val ptr = mainMutex.withLock { playerPtr } ?: return 0.0
        return try {
            SharedVideoPlayer.INSTANCE.getVideoDuration(ptr)
        } catch (e: Exception) {
            macLogger.e { "Error getting duration: ${e.message}" }
            0.0
        }
    }

    /** Applies volume setting to native player. */
    private suspend fun applyVolume() {
        val ptr = mainMutex.withLock { playerPtr } ?: return
        try {
            SharedVideoPlayer.INSTANCE.setVolume(ptr, _volume)
        } catch (e: Exception) {
            macLogger.e { "Error applying volume: ${e.message}" }
        }
    }

    // Subtitle methods (stub implementations)
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        ioScope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun disableSubtitles() {
        ioScope.launch {
            withContext(Dispatchers.Main) {
                subtitlesEnabled = false
                currentSubtitleTrack = null
            }
        }
    }

    override fun clearError() {
        ioScope.launch {
            withContext(Dispatchers.Main) {
                error = null
            }
        }
    }

    override fun hideMedia() {
        macLogger.d { "hideMedia() - Hiding media" }
        stopFrameUpdates()
        stopBufferingCheck()
        ioScope.launch {
            _currentFrameState.value = null
        }
    }

    override fun showMedia() {
        macLogger.d { "showMedia() - Showing media" }
        if (hasMedia) {
            ioScope.launch {
                updateFrameAsync()
                if (isPlaying) {
                    startFrameUpdates()
                    startBufferingCheck()
                }
            }
        }
    }
}