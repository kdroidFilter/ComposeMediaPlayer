package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.WString
import com.sun.jna.ptr.*
import io.github.kdroidfilter.composemediaplayer.*
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max

class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isInitialized by mutableStateOf(false)
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) { _volume = value.coerceIn(0f, 1f) }
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    private var _progress by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
            if (!userDragging) seekTo(value)
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) { _userDragging = value }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) { _loop = value }
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f
    private var _error: VideoPlayerError? = null
    override val error get() = _error
    override fun clearError() { _error = null; errorMessage = null }

    // Current frame management
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    // Metadata and UI state
    override val metadata = VideoMetadata()
    override var subtitlesEnabled = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    private var errorMessage: String? by mutableStateOf(null)

    // Video properties
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)
    private var frameBufferSize = 0
    private var isHighDefinition = false

    // Synchronization
    private val mediaOperationMutex = kotlinx.coroutines.sync.Mutex()
    private val isResizing = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var resizeJob: Job? = null

    // Memory-optimized frame processing
    private val frameQueueSize = 2 // Reduced queue size
    private val frameChannel = Channel<FrameData>(
        capacity = frameQueueSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Reusable frame data structure
    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double
    )

    // Singleton buffer for frame data to minimize allocations
    private var sharedFrameBuffer: ByteArray? = null
    private var frameBitmapRecycler: Bitmap? = null

    init {
        try {
            val hr = player.InitMediaFoundation()
            isInitialized = hr >= 0
            if (!isInitialized) setError("Failed to initialize Media Foundation (hr=0x${hr.toString(16)})")
        } catch (e: Exception) {
            setError("Exception during initialization: ${e.message}")
        }
    }

    fun getLockedComposeImageBitmap(): ImageBitmap? =
        bitmapLock.read { _currentFrame?.asComposeImageBitmap() }

    override fun dispose() {
        scope.launch {
            try {
                mediaOperationMutex.withLock {
                    _isPlaying = false
                    player.SetPlaybackState(false)
                    videoJob?.cancelAndJoin()
                    player.CloseMedia()
                    releaseAllResources()
                    player.ShutdownMediaFoundation()
                }
            } catch (e: Exception) {
                println("Error during dispose: ${e.message}")
            } finally {
                isInitialized = false
                _hasMedia = false
                scope.cancel()
            }
        }
    }

    private fun releaseAllResources() {
        // Cancel ongoing jobs
        videoJob?.cancel()
        resizeJob?.cancel()

        // Clear frame channel
        runBlocking { clearFrameChannel() }

        // Release bitmap resources
        bitmapLock.write {
            _currentFrame?.close()
            _currentFrame = null

            frameBitmapRecycler?.close()
            frameBitmapRecycler = null
        }

        // Release shared buffer
        sharedFrameBuffer = null

        // Reset state
        frameBufferSize = 0
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player is not initialized.")
            return
        }

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    // Stop playback and clear existing resources
                    val wasPlaying = _isPlaying
                    if (wasPlaying) {
                        player.SetPlaybackState(false)
                        _isPlaying = false
                        delay(50)
                    }

                    videoJob?.cancelAndJoin()
                    releaseAllResources()
                    player.CloseMedia()

                    // Reset state
                    _currentTime = 0.0
                    _progress = 0f
                    _duration = 0.0
                    _hasMedia = false

                    // Validate file exists
                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    // Open the media
                    val hrOpen = player.OpenMedia(WString(uri))
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    _hasMedia = true

                    // Get video dimensions
                    val wRef = IntByReference()
                    val hRef = IntByReference()
                    player.GetVideoSize(wRef, hRef)

                    if (wRef.value > 0 && hRef.value > 0) {
                        videoWidth = wRef.value
                        videoHeight = hRef.value
                    } else {
                        videoWidth = 1280
                        videoHeight = 720
                    }

                    // Determine if this is HD and adjust strategy
                    isHighDefinition = videoWidth * videoHeight > 1280 * 720

                    // Calculate buffer size needed for frames
                    frameBufferSize = videoWidth * videoHeight * 4

                    // Allocate shared buffer for frame processing
                    sharedFrameBuffer = ByteArray(frameBufferSize)

                    // Get media duration
                    val durationRef = LongByReference()
                    val hrDuration = player.GetMediaDuration(durationRef)
                    if (hrDuration < 0) {
                        setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                        return@withLock
                    }
                    _duration = durationRef.value / 10000000.0

                    // Start video processing jobs
                    videoJob = scope.launch {
                        launch { produceFrames() }
                        launch { consumeFrames() }
                    }

                    delay(100)

                    play()

                } catch (e: Exception) {
                    setError("Error opening media: ${e.message}")
                    _hasMedia = false
                } finally {
                    if (!_hasMedia) isLoading = false
                }
            }
        }
    }

    private suspend fun produceFrames() {
        while (scope.isActive && _hasMedia) {
            if (player.IsEOF()) {
                if (loop) {
                    try {
                        player.SeekMedia(0)
                        _currentTime = 0.0
                        _progress = 0f
                        play()
                    } catch (e: Exception) {
                        setError("Error during SeekMedia for looping: ${e.message}")
                    }
                } else {
                    pause()
                    break
                }
            }

            try {
                waitForPlaybackState()
            } catch (e: CancellationException) {
                break
            }

            if (waitIfResizing()) {
                continue
            }

            try {
                // Memory-efficient frame reading using shared buffer
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val readResult = player.ReadVideoFrame(ptrRef, sizeRef)

                if (readResult < 0 || ptrRef.value == null || sizeRef.value <= 0) {
                    yield()
                    continue
                }

                val sharedBuffer = sharedFrameBuffer ?: ByteArray(frameBufferSize).also {
                    sharedFrameBuffer = it
                }

                // Copy frame data to shared buffer
                ptrRef.value.getByteBuffer(0, sizeRef.value.toLong()).get(sharedBuffer, 0,
                    max(sizeRef.value, frameBufferSize))
                player.UnlockVideoFrame()

                // Create or reuse bitmap
                var bitmap = frameBitmapRecycler
                if (bitmap == null) {
                    bitmap = Bitmap().apply {
                        allocPixels(createVideoImageInfo())
                    }
                    frameBitmapRecycler = bitmap
                }

                // Safely install pixels into the bitmap
                try {
                    bitmap.installPixels(
                        createVideoImageInfo(),
                        sharedBuffer,
                        videoWidth * 4
                    )

                    // Clone the bitmap for safe passing to consumer
                    val frameBitmap = if (isHighDefinition) {
                        // For HD, reuse bitmap and just pass reference
                        bitmap
                    } else {
                        // For non-HD, we can afford to make a copy
                        bitmap.makeClone()
                    }

                    // Get timestamp
                    val posRef = LongByReference()
                    val frameTime = if (player.GetMediaPosition(posRef) >= 0) {
                        posRef.value / 10000000.0
                    } else {
                        0.0
                    }

                    // Send frame to channel
                    frameChannel.trySend(FrameData(frameBitmap, frameTime))

                    // For HD videos, we need to create a new bitmap for the next frame
                    if (isHighDefinition) {
                        frameBitmapRecycler = null
                    }

                } catch (e: Exception) {
                    // Recover from bitmap errors
                    if (isHighDefinition) {
                        frameBitmapRecycler = bitmap
                    }
                }

                // Adaptive delay based on resolution
                if (isHighDefinition) {
                    delay(16)
                } else {
                    yield()
                }

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia) {
                    setError("Error reading frame: ${e.message}")
                }
                delay(100) // Recovery delay
            }
        }
    }

    private suspend fun consumeFrames() {
        while (scope.isActive && _hasMedia) {
            try {
                waitForPlaybackState()
            } catch (e: CancellationException) {
                break
            }

            if (waitIfResizing()) {
                continue
            }

            try {
                val frameData = frameChannel.tryReceive().getOrNull() ?: run {
                    delay(16)
                    return@run null
                } ?: continue

                // Update the current frame with proper locking
                bitmapLock.write {
                    // Release previous frame
                    _currentFrame?.let { oldBitmap ->
                        if (!isHighDefinition && frameBitmapRecycler == null) {
                            frameBitmapRecycler = oldBitmap
                        } else {
                            oldBitmap.close()
                        }
                    }

                    // Set new frame
                    _currentFrame = frameData.bitmap
                }

                // Update playback state
                _currentTime = frameData.timestamp
                _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                isLoading = false

                // Adaptive rendering delay based on resolution
                delay(16)

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia) {
                    setError("Error processing frame: ${e.message}")
                }
                delay(100)
            }
        }
    }

    override fun play() {
        executeMediaOperation(
            operation = "play",
            precondition = isInitialized && _hasMedia
        ) {
            if (!_isPlaying) {
                setPlaybackState(true, "Error starting playback")
            }
        }
    }

    override fun pause() {
        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying
        ) {
            setPlaybackState(false, "Error pausing")
        }
    }

    override fun stop() {
        executeMediaOperation(
            operation = "stop"
        ) {
            // Stop playback
            setPlaybackState(false, "Error stopping playback")
            delay(50)

            // Cancel video processing
            videoJob?.cancelAndJoin()

            // Release resources
            releaseAllResources()

            // Reset state
            _hasMedia = false
            _progress = 0f
            _currentTime = 0.0
            isLoading = false
            errorMessage = null
            _error = null

            // Close media
            player.CloseMedia()
        }
    }

    override fun seekTo(value: Float) {
        executeMediaOperation(
            operation = "seek",
            precondition = _hasMedia
        ) {
            val wasPlaying = _isPlaying
            try {
                // Pause during seek
                if (_isPlaying) {
                    setPlaybackState(false, "Error pausing for seek")
                    delay(50)
                }

                isLoading = true

                // Clear frame channel
                clearFrameChannel()

                // Ensure frame is unlocked
                try {
                    player.UnlockVideoFrame()
                } catch (e: Exception) {
                    // Ignore errors if frame wasn't locked
                }

                // Perform seek
                val targetPos = (_duration * (value / 1000f) * 10000000).toLong()
                var hr = player.SeekMedia(targetPos)

                if (hr < 0) {
                    delay(50)
                    hr = player.SeekMedia(targetPos)

                    if (hr < 0) {
                        setError("Seek failed (hr=0x${hr.toString(16)})")
                        return@executeMediaOperation
                    }
                }

                // Update position
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                }

                delay(50)

                // Resume playback if needed
                if (wasPlaying) {
                    setPlaybackState(true, "Failed to resume playback after seek")
                }
            } catch (e: Exception) {
                throw e  // Let executeMediaOperation handle the error
            } finally {
                // Try to resume playback on error if needed
                if (wasPlaying && !_isPlaying) {
                    try {
                        setPlaybackState(true, "Failed to resume playback after seek error")
                    } catch (e: Exception) {
                        // Ignore additional errors
                    }
                }

                delay(50)
                isLoading = false
            }
        }
    }

    fun onResized() {
        isResizing.set(true)
        scope.launch {
            try {
                // Clear frame channel during resize
                clearFrameChannel()
            } finally {
                // Cancel previous resize job
                resizeJob?.cancel()

                // Schedule end of resize state
                resizeJob = scope.launch {
                    delay(200)
                    isResizing.set(false)
                }
            }
        }
    }

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }

    // Helper method to clear frame channel
    private fun clearFrameChannel() {
        while (frameChannel.tryReceive().isSuccess) { /* empty the channel */ }
    }

    // Helper method to create ImageInfo with standard parameters
    private fun createVideoImageInfo() = 
        ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    // Helper method to set playback state with error handling
    private fun setPlaybackState(playing: Boolean, errorMessage: String): Boolean {
        val res = player.SetPlaybackState(playing)
        if (res < 0) {
            setError("$errorMessage (hr=0x${res.toString(16)})")
            return false
        }
        _isPlaying = playing
        return true
    }

    // Helper method to wait for playback state change
    private suspend fun waitForPlaybackState() {
        if (!_isPlaying) {
            try {
                snapshotFlow { _isPlaying }.filter { it }.first()
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    // Helper method to wait if resizing
    private suspend fun waitIfResizing(): Boolean {
        if (isResizing.get()) {
            try {
                delay(100)
            } catch (e: CancellationException) {
                throw e
            }
            return true
        }
        return false
    }

    // Helper method to execute media operations with proper locking and error handling
    private fun executeMediaOperation(
        operation: String,
        precondition: Boolean = true,
        block: suspend () -> Unit
    ) {
        if (!precondition) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    block()
                } catch (e: Exception) {
                    setError("Error during $operation: ${e.message}")
                }
            }
        }
    }

    override fun hideMedia() {}
    override fun showMedia() {}
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}
}
