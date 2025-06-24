package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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
import kotlin.math.log10

// Initialize logger using Kermit
internal val macLogger = Logger.withTag("MacVideoPlayerState")
    .apply { setMinSeverity(Severity.Warn) }

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

    // Audio level state variables (added for left and right levels)
    private val _leftLevel = mutableStateOf(0.0f)
    private val _rightLevel = mutableStateOf(0.0f)
    override val leftLevel: Float get() = _leftLevel.value
    override val rightLevel: Float get() = _rightLevel.value

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
    override var isLoading: Boolean by mutableStateOf(false)
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override val metadata: VideoMetadata = VideoMetadata()
    override var isFullscreen: Boolean by mutableStateOf(false)
    private var lastUri: String? = null

    // Non-blocking text properties
    private val _positionText = mutableStateOf("")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("")
    override val durationText: String get() = _durationText.value

    // Non-blocking aspect ratio property
    private val _aspectRatio = mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio.value

    // Player settings
    // Volume variable is stored independently so it can always be modified.
    private val _volumeState = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volumeState.value
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volumeState.value != newValue) {
                _volumeState.value = newValue
                // Launch a coroutine to apply the volume if the native player is available.
                ioScope.launch {
                    applyVolume()
                }
            }
        }

    // Playback speed control
    private val _playbackSpeedState = mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeedState.value
        set(value) {
            val newValue = value.coerceIn(0.5f, 2.0f)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                // Launch a coroutine to apply the playback speed if the native player is available.
                ioScope.launch {
                    applyPlaybackSpeed()
                }
            }
        }

    private val updateInterval: Long
        get() = if (captureFrameRate > 0) {
            (1000.0f / captureFrameRate).toLong()
        } else {
            33L  // Default value (in ms) if no valid capture rate is provided
        }

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
     * Starts a job to update UI state based on frame updates. This is the only
     * job that touches the main thread.
     */
    @OptIn(FlowPreview::class)
    private fun startUIUpdateJob() {
        uiUpdateJob?.cancel()
        uiUpdateJob = ioScope.launch {
            _currentFrameState.debounce(1).collect { newFrame ->
                ensureActive() // Checks that the coroutine is still active
                withContext(Dispatchers.Main) {
                    (currentFrameState as MutableState).value = newFrame
                }
            }
        }
    }


    /** Initializes the native video player on the IO thread. */
    private suspend fun initPlayer() = ioScope.launch {
        macLogger.d { "initPlayer() - Creating native player" }
        try {
            val ptr = SharedVideoPlayer.INSTANCE.createVideoPlayer()
            if (ptr != null) {
                mainMutex.withLock { playerPtr = ptr }
                macLogger.d { "Native player created successfully" }
                applyVolume()
                applyPlaybackSpeed()
            } else {
                macLogger.e { "Error: Failed to create native player" }
                withContext(Dispatchers.Main) {
                    error = VideoPlayerError.UnknownError("Failed to create native player")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            macLogger.e { "Error updating frame rate info: ${e.message}" }
        }
    }

    override fun openUri(uri: String) {
        macLogger.d { "openUri() - Opening URI: $uri" }

        lastUri = uri

        // Update UI state first
        ioScope.launch {
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null  // Clear any previous errors
                playbackSpeed = 1.0f
            }

            // Ensure heavy operations are performed in the background
            try {
                // Stop and clean up any existing playback
                if (hasMedia) {
                    cleanupCurrentPlayback()
                }

                // Ensure player is initialized in the background
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

                    // First frame update in the background
                    updateFrameAsync()

                    // Start buffering check in the background
                    startBufferingCheck()

                    // Start playback if needed - in the background
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
                if (e is CancellationException) throw e
                macLogger.e { "openUri() - Exception: ${e.message}" }
                handleError(e)
            }
        }
    }

    /** Cleans up current playback state. */
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

        // Release resources outside of the mutex lock
        ptrToDispose?.let {
            try {
                SharedVideoPlayer.INSTANCE.disposeVideoPlayer(it)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error disposing player: ${e.message}" }
            }
        }
    }

    /** Ensures the player is initialized. */
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
                applyPlaybackSpeed()
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    /** Opens media URI and returns a success flag. */
    private suspend fun openMediaUri(uri: String): Boolean {
        macLogger.d { "openMediaUri() - Opening URI: $uri" }
        val ptr = mainMutex.withLock { playerPtr } ?: return false

        // Check if file exists (for local files)
        if (uri.startsWith("file://") || !uri.contains("://")) {
            val filePath = uri.replace("file://", "")
            val file = java.io.File(filePath)
            if (!file.exists()) {
                macLogger.e { "File does not exist: $filePath" }
                withContext(Dispatchers.Main) {
                    error = VideoPlayerError.SourceError("File not found: $filePath")
                }
                return false
            }
        }

        return try {
            // Open video asynchronously
            SharedVideoPlayer.INSTANCE.openUri(ptr, uri)

            // Instead of directly calling `updateMetadata()`,
            // we poll until valid dimensions are available
            pollDimensionsUntilReady(ptr)

            // Once dimensions are retrieved, call updateMetadata()
            updateMetadata()

            true
        } catch (e: Exception) {
            macLogger.e { "Failed to open URI: ${e.message}" }
            withContext(Dispatchers.Main) {
                error = VideoPlayerError.SourceError("Error opening media: ${e.message}")
            }
            false
        }
    }

    /**
     * Loops several times (every 250 ms) until width/height
     * are no longer zero. If dimensions are still zero after
     * a specified number of attempts, stop waiting.
     */
    private suspend fun pollDimensionsUntilReady(ptr: Pointer, maxAttempts: Int = 20) {
        for (attempt in 1..maxAttempts) {
            val width = SharedVideoPlayer.INSTANCE.getFrameWidth(ptr)
            val height = SharedVideoPlayer.INSTANCE.getFrameHeight(ptr)

            if (width > 0 && height > 0) {
                macLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            macLogger.d { "Dimensions not ready yet (attempt $attempt/$maxAttempts), waiting..." }
            delay(250)
        }
        macLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
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
            val newAspectRatio = if (width > 0 && height > 0) {
                width.toFloat() / height.toFloat()
            } else {
                // Au lieu de forcer 16f/9f, ne changez pas l’aspect si la vidéo n’est pas encore prête.
                // Par exemple, on peut conserver l’ancien aspect ratio :
                _aspectRatio.value
            }

            // Get additional metadata
            val title = SharedVideoPlayer.INSTANCE.getVideoTitle(ptr)
            val bitrate = SharedVideoPlayer.INSTANCE.getVideoBitrate(ptr)
            val mimeType = SharedVideoPlayer.INSTANCE.getVideoMimeType(ptr)
            val audioChannels = SharedVideoPlayer.INSTANCE.getAudioChannels(ptr)
            val audioSampleRate = SharedVideoPlayer.INSTANCE.getAudioSampleRate(ptr)

            withContext(Dispatchers.Main) {
                // Update metadata
                metadata.duration = duration
                metadata.width = width
                metadata.height = height
                metadata.frameRate = frameRate
                metadata.title = title
                metadata.bitrate = bitrate
                metadata.mimeType = mimeType
                metadata.audioChannels = if (audioChannels == 0) null else audioChannels
                metadata.audioSampleRate = if (audioSampleRate == 0) null else audioSampleRate

                // Met à jour l’aspect ratio seulement si width/height valides
                _aspectRatio.value = newAspectRatio
            }

            macLogger.d { "Metadata updated: $metadata" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    /** Starts periodic frame updates on a background thread. */
    private fun startFrameUpdates() {
        macLogger.d { "startFrameUpdates() - Starting frame updates" }
        stopFrameUpdates()
        frameUpdateJob = ioScope.launch {
            while (isActive) {
                ensureActive() // Check if coroutine is still active
                updateFrameAsync()
                if (!userDragging) {
                    updatePositionAsync()
                    // Call the audio level update separately
                    updateAudioLevelsAsync()
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

    /** Starts periodic buffering detection on a background thread. */
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

    /** Checks if the media is currently buffering. */
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

    /** Stops the buffering detection job. */
    private fun stopBufferingCheck() {
        macLogger.d { "stopBufferingCheck() - Stopping buffering detection" }
        bufferingCheckJob?.cancel()
        bufferingCheckJob = null
    }

    /**
     * Calculates a simple hash of the image data to detect if the frame has
     * changed. This runs on the compute dispatcher for CPU-intensive work and
     * samples fewer pixels for better performance.
     */
    private suspend fun calculateFrameHash(data: IntArray): Int = withContext(Dispatchers.Default) {
        var hash = 0
        // Sample a smaller subset of pixels for performance
        val step = data.size / 200
        if (step > 0) {
            for (i in data.indices step step) {
                hash = 31 * hash + data[i]
            }
        }
        hash
    }

    /** Updates the current video frame on a background thread. */
    private suspend fun updateFrameAsync() {
        try {
            // Safely get the player pointer
            val ptr = mainMutex.withLock { playerPtr } ?: return

            // Get frame dimensions
            val width = SharedVideoPlayer.INSTANCE.getFrameWidth(ptr)
            val height = SharedVideoPlayer.INSTANCE.getFrameHeight(ptr)

            if (width <= 0 || height <= 0) {
                return
            }

            // Get the latest frame to minimize mutex lock time
            val framePtr = SharedVideoPlayer.INSTANCE.getLatestFrame(ptr) ?: return

            // Create or reuse a buffered image on a compute thread
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

            // Copy frame data on a compute thread for performance
            withContext(Dispatchers.Default) {
                val pixels = (bufferedImage.raster.dataBuffer as DataBufferInt).data
                framePtr.getByteBuffer(0, (width * height * 4).toLong()).asIntBuffer().get(pixels)

                // Calculate frame hash to detect changes
                val newHash = calculateFrameHash(pixels)
                val frameChanged = newHash != lastFrameHash
                lastFrameHash = newHash

                if (frameChanged) {
                    // Update timestamp
                    lastFrameUpdateTime = System.currentTimeMillis()

                    // Convert to ImageBitmap on a compute thread
                    val imageBitmap = bufferedImage.toComposeImageBitmap()

                    // Publish to flow
                    _currentFrameState.value = imageBitmap

                    // Update loading state if needed on the main thread
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
        }
    }

    private suspend fun updateAudioLevelsAsync() {
        if (!hasMedia) return

        try {
            val ptr = mainMutex.withLock { playerPtr }
            if (ptr != null) {
                val newLeft = SharedVideoPlayer.INSTANCE.getLeftAudioLevel(ptr)
                val newRight = SharedVideoPlayer.INSTANCE.getRightAudioLevel(ptr)
//                macLogger.d { "Audio levels fetched: L=$newLeft, R=$newRight" }

                // Converts the linear level to a percentage on a logarithmic scale.
                fun convertToPercentage(level: Float): Float {
                    if (level <= 0f) return 0f
                    // Conversion to decibels: 20 * log10(level)
                    val db = 20 * log10(level)
                    // Assume that -60 dB corresponds to silence and 0 dB to maximum level.
                    val normalized = ((db + 60) / 60).coerceIn(0f, 1f)
                    return normalized * 100f
                }

                withContext(Dispatchers.Main) {
                    _leftLevel.value = convertToPercentage(newLeft)
                    _rightLevel.value = convertToPercentage(newRight)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating audio levels: ${e.message}" }
        }
    }

    /**
     * Updates the playback position, slider, and audio levels on a background
     * thread.
     */
    private suspend fun updatePositionAsync() {
        if (!hasMedia || userDragging) return

        try {
            val duration = getDurationSafely()
            if (duration <= 0) return

            val current = getPositionSafely()

            // Update time text display on the main thread
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
                // Update slider position, batched with other UI updates to reduce main thread calls
                val newSliderPos = (current / duration * 1000).toFloat().coerceIn(0f, 1000f)
                withContext(Dispatchers.Main) {
                    sliderPos = newSliderPos
                }
            }

            // Check for looping
            checkLoopingAsync(current, duration)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            if (!hasMedia && lastUri != null) {
                // Reload the media using the saved URI
                openUri(lastUri!!)
                // The openUri method will start reading if the opening is successful
            } else if (hasMedia) {
                // If the media is already loaded, start playing in the background
                playInBackground()
            } else {
                withContext(Dispatchers.Main) {
                    isPlaying = false
                    isLoading = false
                }
            }
        }
    }

    /** Plays video on a background thread. */
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
            if (e is CancellationException) throw e
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

    /** Pauses video on a background thread. */
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
            if (e is CancellationException) throw e
            macLogger.e { "Error in pauseInBackground: ${e.message}" }
        }
    }

    override fun stop() {
        macLogger.d { "stop() - Stopping playback" }
        ioScope.launch {
            pauseInBackground()
            if (hasMedia) {
                seekToAsync(0f)
            }
            withContext(Dispatchers.Main) {
                hasMedia = false
                isLoading = false
                resetState()
            }
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

    /** Seeks to a position on a background thread. */
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
                // Reduce delay to update frame faster for local videos
                delay(10)
                updateFrameAsync()
                // Reduced timeout delay from 2000ms to 300ms
                ioScope.launch {
                    delay(300)
                    if (seekInProgress) {
                        macLogger.d { "seekToAsync() - Forcing end of seek after timeout" }
                        seekInProgress = false
                        targetSeekTime = null
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

            // Dispose native resources outside the mutex lock
            ptrToDispose?.let {
                macLogger.d { "dispose() - Disposing native player" }
                try {
                    SharedVideoPlayer.INSTANCE.disposeVideoPlayer(it)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Error disposing player: ${e.message}" }
                }
            }

            resetState()

            // Clear buffered image
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
            _positionText.value = "00:00"
            _durationText.value = "00:00"
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            macLogger.e { "Error getting duration: ${e.message}" }
            0.0
        }
    }

    /**
     * Applies the current volume setting to the native player. If no player
     * is available, the volume is simply stored in _volumeState and will be
     * applied when the player is initialized.
     */
    private suspend fun applyVolume() {
        mainMutex.withLock {
            playerPtr?.let { ptr ->
                try {
                    SharedVideoPlayer.INSTANCE.setVolume(ptr, _volumeState.value)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Error applying volume: ${e.message}" }
                }
            }
        }
    }

    /**
     * Applies the current playback speed setting to the native player. If no player
     * is available, the speed is simply stored in _playbackSpeedState and will be
     * applied when the player is initialized.
     */
    private suspend fun applyPlaybackSpeed() {
        mainMutex.withLock {
            playerPtr?.let { ptr ->
                try {
                    SharedVideoPlayer.INSTANCE.setPlaybackSpeed(ptr, _playbackSpeedState.value)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Error applying playback speed: ${e.message}" }
                }
            }
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

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        // Update the state immediately for test synchronization
        isFullscreen = !isFullscreen

        // Launch any additional background work if needed
        ioScope.launch {
            // Any additional work related to fullscreen toggle can go here
        }
    }
}
