package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Pointer
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
        private val mediaFoundationInitialized = AtomicBoolean(false)

        // Initialize Media Foundation only once for all instances
        private fun initializeMediaFoundation() {
            if (!mediaFoundationInitialized.getAndSet(true)) {
                val hr = MediaFoundationLib.INSTANCE.InitMediaFoundation()
                if (hr < 0) {
                    println("Media Foundation initialization failed (hr=0x${hr.toString(16)})")
                }
            }
        }

        init {
            // Initialize Media Foundation when class is loaded
            initializeMediaFoundation()
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

    // Video player instance handle
    private var videoPlayerInstance: Pointer? = null

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
                        val instance = videoPlayerInstance
                        if (instance != null) {
                            val hr = player.SetAudioVolume(instance, newVolume)
                            if (hr < 0) {
                                setError("Error updating volume (hr=0x${hr.toString(16)})")
                            }
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

    // Updating audio levels via GetAudioLevels
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

    // Variable to store the last opened URI
    private var lastUri: String? = null

    init {
        scope.launch {
            try {
                // Media Foundation is already initialized in companion object

                // Create a new VideoPlayerInstance using the helper method
                val instance = MediaFoundationLib.createInstance()
                if (instance == null) {
                    setError("Failed to create video player instance")
                    return@launch
                }
                videoPlayerInstance = instance
                isInitialized = true
            } catch (e: Exception) {
                setError("Exception during initialization: ${e.message}")
            }
        }
    }

    fun getLockedComposeImageBitmap(): ImageBitmap? =
        bitmapLock.read { _currentFrame?.asComposeImageBitmap() }

    override fun dispose() {
        scope.launch {
            try {
                mediaOperationMutex.withLock {
                    _isPlaying = false
                    val instance = videoPlayerInstance
                    if (instance != null) {
                        player.SetPlaybackState(instance, false)
                        videoJob?.cancelAndJoin()
                        audioLevelsJob?.cancel()
                        player.CloseMedia(instance)
                        MediaFoundationLib.destroyInstance(instance)
                        videoPlayerInstance = null
                    }
                    releaseAllResources()
                    // Don't shut down Media Foundation as other instances might be using it
                    // player.ShutdownMediaFoundation()
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
        lastUri = uri

        if (!isInitialized || videoPlayerInstance == null) {
            setError("Player is not initialized.")
            return
        }

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    // Stop playback and release existing resources
                    val wasPlaying = _isPlaying
                    val instance = videoPlayerInstance
                    if (instance == null) {
                        setError("Video player instance is null")
                        return@withLock
                    }

                    if (wasPlaying) {
                        player.SetPlaybackState(instance, false)
                        _isPlaying = false
                        delay(50)
                    }

                    videoJob?.cancelAndJoin()
                    releaseAllResources()
                    player.CloseMedia(instance)

                    _currentTime = 0.0
                    _progress = 0f
                    _duration = 0.0
                    _hasMedia = false

                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    val hrOpen = player.OpenMedia(instance, WString(uri))
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        println("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    _hasMedia = true

                    // Retrieve video dimensions
                    val wRef = IntByReference()
                    val hRef = IntByReference()
                    player.GetVideoSize(instance, wRef, hRef)
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
                    val hrDuration = player.GetMediaDuration(instance, durationRef)
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

    private suspend fun updateAudioLevels() {
        mediaOperationMutex.withLock {
            val instance = videoPlayerInstance
            if (instance != null) {
                val leftRef = FloatByReference()
                val rightRef = FloatByReference()
                val hr = player.GetAudioLevels(instance, leftRef, rightRef)
                if (hr >= 0) {
                    _leftLevel = leftRef.value
                    _rightLevel = rightRef.value
                }
            }
        }
    }

    private suspend fun produceFrames() {
        while (scope.isActive && _hasMedia) {
            val instance = videoPlayerInstance ?: break

            if (player.IsEOF(instance)) {
                if (loop) {
                    try {
                        player.SeekMedia(instance, 0)
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
                val readResult = player.ReadVideoFrame(instance, ptrRef, sizeRef)

                if (readResult < 0 || ptrRef.value == null || sizeRef.value <= 0) {
                    yield()
                    continue
                }

                if (sharedFrameBuffer == null || sharedFrameBuffer!!.size < frameBufferSize) {
                    sharedFrameBuffer = ByteArray(frameBufferSize)
                }
                val sharedBuffer = sharedFrameBuffer!!

                try {
                    val buffer = ptrRef.value.getByteBuffer(0, sizeRef.value.toLong())
                    val copySize = min(sizeRef.value, frameBufferSize)
                    if (buffer != null && copySize > 0) {
                        buffer.get(sharedBuffer, 0, copySize)
                    }
                } catch (e: Exception) {
                    setError("Error copying frame data: ${e.message}")
                    delay(100)
                    continue
                }

                player.UnlockVideoFrame(instance)

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
                    val frameTime = if (player.GetMediaPosition(instance, posRef) >= 0) {
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
        if (!isInitialized || videoPlayerInstance == null) {
            // If not initialized or no instance, try to initialize with lastUri if available
            if (lastUri != null && lastUri!!.isNotEmpty()) {
                scope.launch {
                    openUri(lastUri!!)
                    // After opening, try to play again after a short delay
                    delay(100)
                    if (isInitialized && videoPlayerInstance != null) {
                        executeMediaOperation(
                            operation = "play after init",
                            precondition = true
                        ) {
                            setPlaybackState(true, "Error while starting playback after initialization")
                        }
                    }
                }
            }
            return
        }

        executeMediaOperation(
            operation = "play",
            precondition = true
        ) {
            // Always try to set playback state to true, regardless of _isPlaying
            // This ensures we attempt to start playback even if state is inconsistent
            setPlaybackState(true, "Error while starting playback")

            // If we have media but no video job running, restart it
            if (_hasMedia && (videoJob == null || videoJob?.isActive == false)) {
                videoJob = scope.launch {
                    launch { produceFrames() }
                    launch { consumeFrames() }
                }
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
            val instance = videoPlayerInstance
            if (instance != null) {
                player.CloseMedia(instance)
            }
        }
    }

    override fun seekTo(value: Float) {
        executeMediaOperation(
            operation = "seek",
            precondition = _hasMedia && videoPlayerInstance != null
        ) {
            val instance = videoPlayerInstance
            if (instance != null) {
                try {
                    isLoading = true

                    videoJob?.cancelAndJoin()
                    clearFrameChannel()
                    sharedFrameBuffer = ByteArray(frameBufferSize)

                    val targetPos = (_duration * (value / 1000f) * 10000000).toLong()
                    var hr = player.SeekMedia(instance, targetPos)
                    if (hr < 0) {
                        delay(50)
                        hr = player.SeekMedia(instance, targetPos)
                        if (hr < 0) {
                            setError("Seek failed (hr=0x${hr.toString(16)})")
                            return@executeMediaOperation
                        }
                    }

                    val posRef = LongByReference()
                    if (player.GetMediaPosition(instance, posRef) >= 0) {
                        _currentTime = posRef.value / 10000000.0
                        _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                    }

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
        while (frameChannel.tryReceive().isSuccess) { }
    }

    private fun createVideoImageInfo() =
        ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    private fun setPlaybackState(playing: Boolean, errorMessage: String): Boolean {
        val instance = videoPlayerInstance
        if (instance != null) {
            // Try up to 3 times without delay
            for (attempt in 1..3) {
                val res = player.SetPlaybackState(instance, playing)
                if (res >= 0) {
                    _isPlaying = playing
                    // Clear any previous error if successful
                    if (_error != null) {
                        clearError()
                    }
                    return true
                }

                // Only set error on the last failed attempt
                if (attempt == 3) {
                    setError("$errorMessage (hr=0x${res.toString(16)}) after $attempt attempts")
                }
            }
            return false
        }
        setError("$errorMessage: No player instance")
        return false
    }

    private suspend fun waitForPlaybackState() {
        if (!_isPlaying) {
            try {
                // Add a timeout of 5 seconds to prevent hanging indefinitely
                withTimeoutOrNull(5000) {
                    snapshotFlow { _isPlaying }.filter { it }.first()
                } ?: run {
                    // If timeout occurs, try to restart playback
                    if (_hasMedia && videoPlayerInstance != null) {
                        setPlaybackState(true, "Error while restarting playback after timeout")
                        // Give a short time for playback to start
                        delay(100)
                        // If still not playing, yield to allow other coroutines to run
                        if (!_isPlaying) {
                            yield()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log the error but continue execution
                println("Error in waitForPlaybackState: ${e.message}")
                yield()
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
