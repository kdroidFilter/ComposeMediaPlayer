package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import io.github.kdroidfilter.composemediaplayer.*
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.min

class WindowsVideoPlayerState : PlatformVideoPlayerState {
    companion object {
        private var instanceCount = 0
        private var isMediaFoundationInitialized = false

        @Synchronized
        private fun initializeMediaFoundation(): Boolean {
            if (!isMediaFoundationInitialized) {
                val hr = MediaFoundationLib.INSTANCE.InitMediaFoundation()
                isMediaFoundationInitialized = hr >= 0
                if (!isMediaFoundationInitialized) {
                    println("Media Foundation initialization failed (hr=0x${hr.toString(16)})")
                }
            }
            return isMediaFoundationInitialized
        }

        @Synchronized
        private fun shutdownMediaFoundation() {
            if (isMediaFoundationInitialized && instanceCount == 0) {
                MediaFoundationLib.INSTANCE.ShutdownMediaFoundation()
                isMediaFoundationInitialized = false
            }
        }
    }

    // Instance of the native Media Foundation player
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isInitialized by mutableStateOf(false)
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    // Volume management: any modification triggers the native call SetAudioVolume
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            val newVolume = value.coerceIn(0f, 1f)
            if (_volume != newVolume) {
                _volume = newVolume
                scope.launch {
                    mediaOperationMutex.withLock {
                        val hr = player.SetAudioVolume(newVolume)
                        if (hr < 0) {
                            setError("Error updating volume (hr=0x${hr.toString(16)})")
                        }
                    }
                }
            }
        }

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

    // Updating audio levels via GetAudioLevels (mutable)
    private var _leftLevel by mutableStateOf(0f)
    override val leftLevel: Float get() = _leftLevel
    private var _rightLevel by mutableStateOf(0f)
    override val rightLevel: Float get() = _rightLevel

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
    private var frameBufferSize = 1

    // Synchronization
    private val mediaOperationMutex = Mutex()
    private val isResizing = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var resizeJob: Job? = null
    // Job to periodically update the audio levels
    private var audioLevelsJob: Job? = null

    // Memory optimization for frame processing
    private val frameQueueSize = 1
    private val frameChannel = Channel<FrameData>(
        capacity = frameQueueSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Data structure for a frame
    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double
    )

    // Singleton buffer to limit allocations
    private var sharedFrameBuffer: ByteArray? = null
    private var frameBitmapRecycler: Bitmap? = null

    // Variable to store the last opened URI so that the same video can be restarted
    private var lastUri: String? = null

    init {
        try {
            synchronized(WindowsVideoPlayerState::class.java) {
                instanceCount++
            }
            isInitialized = initializeMediaFoundation()
            if (!isInitialized) setError("Media Foundation initialization failed")
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
                    audioLevelsJob?.cancel() // Cancel the audio update job
                    try {
                        player.CloseMedia()
                    } catch (e: Exception) {
                        println("Error during CloseMedia in dispose: ${e.message}")
                        // Continue with disposal even if closing fails
                    }
                    releaseAllResources()

                    synchronized(WindowsVideoPlayerState::class.java) {
                        instanceCount--
                        shutdownMediaFoundation()
                    }
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
        videoJob?.cancel()
        resizeJob?.cancel()
        audioLevelsJob?.cancel()
        runBlocking { clearFrameChannel() }
        bitmapLock.write {
            _currentFrame?.close()
            _currentFrame = null
            frameBitmapRecycler?.close()
            frameBitmapRecycler = null
        }
        sharedFrameBuffer = null
        frameBufferSize = 0
    }

    override fun openUri(uri: String) {
        // Save the last opened URI
        lastUri = uri

        if (!isInitialized) {
            setError("Player is not initialized.")
            return
        }

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    // Stop playback and release existing resources
                    val wasPlaying = _isPlaying
                    if (wasPlaying) {
                        player.SetPlaybackState(false)
                        _isPlaying = false
                        delay(50)
                    }

                    videoJob?.cancelAndJoin()
                    releaseAllResources()
                    try {
                        player.CloseMedia()
                    } catch (e: Exception) {
                        println("Error during CloseMedia: ${e.message}")
                        // Continue with opening new media even if closing fails
                    }

                    _currentTime = 0.0
                    _progress = 0f
                    _duration = 0.0
                    _hasMedia = false

                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    val hrOpen = player.OpenMedia(WString(uri))
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        println("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    _hasMedia = true

                    // Retrieve video dimensions
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

                    // Calculate the buffer size for frames
                    frameBufferSize = videoWidth * videoHeight * 4

                    // Allocate shared buffer
                    sharedFrameBuffer = ByteArray(frameBufferSize)

                    // Retrieve media duration
                    val durationRef = LongByReference()
                    val hrDuration = player.GetMediaDuration(durationRef)
                    if (hrDuration < 0) {
                        setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                        return@withLock
                    }
                    _duration = durationRef.value / 10000000.0

                    // Start video processing
                    videoJob = scope.launch {
                        launch { produceFrames() }
                        launch { consumeFrames() }
                    }

                    // Launch a job to periodically update audio levels
                    audioLevelsJob = scope.launch {
                        while (isActive && _hasMedia) {
                            updateAudioLevels()
                            delay(50)
                        }
                    }

                    delay(100)
                    play()

                } catch (e: Exception) {
                    setError("Error while opening media: ${e.message}")
                    _hasMedia = false
                } finally {
                    if (!_hasMedia) isLoading = false
                }
            }
        }
    }

    /**
     * Updates audio levels safely, with error handling to prevent crashes.
     * This method is called periodically by the audioLevelsJob.
     */
    private suspend fun updateAudioLevels() {
        // Skip if no media is loaded
        if (!_hasMedia) return

        try {
            mediaOperationMutex.withLock {
                try {
                    // Create references for the audio levels
                    val leftRef = FloatByReference()
                    val rightRef = FloatByReference()
                    val hr = player.GetAudioLevels(leftRef, rightRef)

                    // Only update if the call was successful
                    if (hr >= 0) {
                        _leftLevel = leftRef.value
                        _rightLevel = rightRef.value
                    }
                } catch (e: Exception) {
                    // Log the error but don't crash
                    println("Error during GetAudioLevels: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Handle any other exceptions that might occur
            println("Unexpected error in updateAudioLevels: ${e.message}")
            // Cancel the audio levels job to prevent further errors
            scope.launch {
                audioLevelsJob?.cancel()
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
                        setError("Error during SeekMedia for loop: ${e.message}")
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
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val readResult = player.ReadVideoFrame(ptrRef, sizeRef)

                if (readResult < 0 || ptrRef.value == null || sizeRef.value <= 0) {
                    yield()
                    continue
                }

                if (sharedFrameBuffer == null || sharedFrameBuffer!!.size < frameBufferSize) {
                    sharedFrameBuffer = ByteArray(frameBufferSize)
                }
                val sharedBuffer = sharedFrameBuffer!!

                ptrRef.value.getByteBuffer(0, sizeRef.value.toLong())
                    .get(sharedBuffer, 0, min(sizeRef.value, frameBufferSize))

                player.UnlockVideoFrame()

                var bitmap = frameBitmapRecycler
                if (bitmap == null) {
                    bitmap = Bitmap().apply {
                        allocPixels(createVideoImageInfo())
                    }
                    frameBitmapRecycler = bitmap
                }

                try {
                    bitmap.installPixels(
                        createVideoImageInfo(),
                        sharedBuffer,
                        videoWidth * 4
                    )
                    val frameBitmap = bitmap
                    val posRef = LongByReference()
                    val frameTime = if (player.GetMediaPosition(posRef) >= 0) {
                        posRef.value / 10000000.0
                    } else {
                        0.0
                    }
                    frameChannel.trySend(FrameData(frameBitmap, frameTime))
                    frameBitmapRecycler = null
                } catch (e: Exception) {
                    frameBitmapRecycler = bitmap
                }

                delay(1)

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia) {
                    setError("Error while reading a frame: ${e.message}")
                }
                delay(100)
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

                bitmapLock.write {
                    _currentFrame?.let { oldBitmap ->
                        if (frameBitmapRecycler == null) {
                            frameBitmapRecycler = oldBitmap
                        } else {
                            oldBitmap.close()
                        }
                    }
                    _currentFrame = frameData.bitmap
                }

                _currentTime = frameData.timestamp
                _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                isLoading = false

                delay(1)

            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia) {
                    setError("Error while processing a frame: ${e.message}")
                }
                delay(100)
            }
        }
    }

    override fun play() {
        if (!isInitialized) return
        // If no media is loaded but a previous URI exists, reopen it
        if (!_hasMedia) {
            lastUri?.let { uri ->
                openUri(uri)
            }
            return
        }
        executeMediaOperation(
            operation = "play",
            precondition = true
        ) {
            if (!_isPlaying) {
                setPlaybackState(true, "Error while starting playback")
            }
        }
    }

    override fun pause() {
        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying
        ) {
            setPlaybackState(false, "Error while pausing playback")
        }
    }

    override fun stop() {
        executeMediaOperation(
            operation = "stop"
        ) {
            setPlaybackState(false, "Error while stopping playback")
            delay(50)
            videoJob?.cancelAndJoin()
            releaseAllResources()
            _hasMedia = false
            _progress = 0f
            _currentTime = 0.0
            _duration = 0.0
            isLoading = false
            errorMessage = null
            _error = null
            try {
                player.CloseMedia()
            } catch (e: Exception) {
                println("Error during CloseMedia in stop: ${e.message}")
                // Continue with stop operation even if closing fails
            }
        }
    }

    override fun seekTo(value: Float) {
        executeMediaOperation(
            operation = "seek",
            precondition = _hasMedia
        ) {
            try {
                isLoading = true

                // Cancel video processing to avoid conflicts during seek
                videoJob?.cancelAndJoin()

                // Empty the frame channel and reallocate the shared buffer
                clearFrameChannel()
                sharedFrameBuffer = ByteArray(frameBufferSize)

                // Calculate the target position
                val targetPos = (_duration * (value / 1000f) * 10000000).toLong()

                // Perform seek with a second attempt in case of failure
                var hr = player.SeekMedia(targetPos)
                if (hr < 0) {
                    delay(50)
                    hr = player.SeekMedia(targetPos)
                    if (hr < 0) {
                        setError("Seek failed (hr=0x${hr.toString(16)})")
                        return@executeMediaOperation
                    }
                }

                // Update current position
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                }

                // Restart video processing job after seek
                videoJob = scope.launch {
                    launch { produceFrames() }
                    launch { consumeFrames() }
                }

                delay(8)
            } catch (e: Exception) {
                setError("Error during seek: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    fun onResized() {
        isResizing.set(true)
        scope.launch {
            try {
                clearFrameChannel()
            } finally {
                resizeJob?.cancel()
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

    private fun clearFrameChannel() {
        // Empty the frame channel
        while (frameChannel.tryReceive().isSuccess) { }
    }

    private fun createVideoImageInfo() =
        ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    private fun setPlaybackState(playing: Boolean, errorMessage: String): Boolean {
        val res = player.SetPlaybackState(playing)
        if (res < 0) {
            setError("$errorMessage (hr=0x${res.toString(16)})")
            return false
        }
        _isPlaying = playing
        return true
    }

    private suspend fun waitForPlaybackState() {
        if (!_isPlaying) {
            try {
                snapshotFlow { _isPlaying }.filter { it }.first()
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

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
