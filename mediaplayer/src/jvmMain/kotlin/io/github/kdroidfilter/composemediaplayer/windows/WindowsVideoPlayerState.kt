package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Logger.Companion.setMinSeverity
import co.touchlab.kermit.Severity
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.math.min

/**
 * Logger for Windows video player implementation
 */
internal val windowsLogger = Logger.withTag("WindowsVideoPlayerState")
    .apply { setMinSeverity(Severity.Warn) }

/**
 * Windows implementation of the video player state.
 * Handles media playback using Media Foundation on Windows platform.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    companion object {
        private val mediaFoundationInitialized = AtomicBoolean(false)

        /** Map to store volume settings for each player instance */
        private val instanceVolumes = ConcurrentHashMap<Pointer, Float>()

        /**
         * Initialize Media Foundation only once for all instances.
         * This is called automatically when the class is loaded.
         */
        private fun initializeMediaFoundation() {
            if (!mediaFoundationInitialized.getAndSet(true)) {
                val hr = MediaFoundationLib.INSTANCE.InitMediaFoundation()
                if (hr < 0) {
                    windowsLogger.e { "Media Foundation initialization failed (hr=0x${hr.toString(16)})" }
                }
            }
        }

        init {
            // Initialize Media Foundation when class is loaded
            initializeMediaFoundation()
        }
    }

    /** Instance of the native Media Foundation player */
    private val player = MediaFoundationLib.INSTANCE

    /** Coroutine scope for all async operations */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Whether the player has been initialized */
    private var isInitialized by mutableStateOf(false)

    /** Whether media has been loaded */
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia

    /** Whether media is currently playing */
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    /** Video player instance handle */
    private var videoPlayerInstance: Pointer? = null

    /** Current volume level (0.0 to 1.0) */
    private var _volume by mutableStateOf(1f)

    /**
     * Volume control for the player (0.0 to 1.0)
     * Any modification triggers the native call SetAudioVolume
     */
    override var volume: Float
        get() = _volume
        set(value) {
            val newVolume = value.coerceIn(0f, 1f)
            if (_volume != newVolume) {
                _volume = newVolume
                scope.launch {
                    mediaOperationMutex.withLock {
                        videoPlayerInstance?.let { instance ->
                            // Store the volume setting for this instance
                            instanceVolumes[instance] = newVolume

                            // Apply the volume setting to the native player
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

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newSpeed = value.coerceIn(0.5f, 2.0f)
            if (_playbackSpeed != newSpeed) {
                _playbackSpeed = newSpeed
                scope.launch {
                    mediaOperationMutex.withLock {
                        videoPlayerInstance?.let { instance ->
                            val hr = player.SetPlaybackSpeed(instance, newSpeed)
                            if (hr < 0) {
                                setError("Error updating playback speed (hr=0x${hr.toString(16)})")
                            }
                        }
                    }
                }
            }
        }

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
    private val bitmapLock = ReentrantReadWriteLock()
    internal val currentFrameState = mutableStateOf<ImageBitmap?>(null)

    // Aspect ratio property
    override val aspectRatio: Float
        get() = if (videoWidth > 0 && videoHeight > 0)
            videoWidth.toFloat() / videoHeight.toFloat()
        else 16f / 9f

    // Metadata and UI state
    override val metadata = VideoMetadata()
    override var subtitlesEnabled = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var subtitleTextStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    )
    override var subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    private var errorMessage: String? by mutableStateOf(null)

    // Fullscreen state
    override var isFullscreen by mutableStateOf(false)

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
                val instance = MediaFoundationLib.createInstance()
                if (instance == null) {
                    setError("Failed to create video player instance")
                    return@launch
                }
                videoPlayerInstance = instance

                // Set initial volume for this instance
                instanceVolumes[instance] = _volume

                isInitialized = true
            } catch (e: Exception) {
                setError("Exception during initialization: ${e.message}")
            }
        }
    }

    override fun dispose() {
        scope.launch {
            try {
                mediaOperationMutex.withLock {
                    // Stop playing if active
                    _isPlaying = false
                    val instance = videoPlayerInstance
                    if (instance != null) {
                        // Stop playback before releasing resources
                        val hr = player.SetPlaybackState(instance, false, true)
                        if (hr < 0) {
                            windowsLogger.e { "Error stopping playback (hr=0x${hr.toString(16)})" }
                        }

                        // Cancel all media jobs
                        videoJob?.cancelAndJoin()
                        audioLevelsJob?.cancel()

                        // Close the media
                        player.CloseMedia(instance)

                        // Remove volume setting for this instance
                        instanceVolumes.remove(instance)

                        // Destroy the player instance
                        MediaFoundationLib.destroyInstance(instance)
                        videoPlayerInstance = null
                    }
                    releaseAllResources()  // Ensure all other resources are cleared
                }
            } catch (e: Exception) {
                windowsLogger.e { "Error during dispose: ${e.message}" }
            } finally {
                // Mark player as uninitialized
                isInitialized = false
                _hasMedia = false
                scope.cancel()  // Cancel the scope to clean up any remaining jobs
            }
        }
    }

    private fun releaseAllResources() {
        // Cancel any remaining jobs related to video processing
        videoJob?.cancel()
        audioLevelsJob?.cancel()
        resizeJob?.cancel()

        // Ensure the frame channel is emptied
        runBlocking { clearFrameChannel() }

        // Free bitmaps and frame buffers
        bitmapLock.write {
            _currentFrame?.close()  // Close the current frame bitmap if any
            _currentFrame = null
            // Reset the currentFrameState
            currentFrameState.value = null
            frameBitmapRecycler?.close()  // Recycle the bitmap if any
            frameBitmapRecycler = null
        }

        // Clear any shared buffer allocated for frames
        sharedFrameBuffer = null
        frameBufferSize = 0  // Reset frame buffer size
    }

    private fun clearFrameChannel() {
        // Drain the frame channel to ensure all items are removed
        while (frameChannel.tryReceive().isSuccess) {
            // Intentionally empty - just draining the channel
        }
    }


    /**
     * Opens a media file or URL for playback
     *
     * @param uri The path to the media file or URL to open
     */
    override fun openUri(uri: String) {
        lastUri = uri

        if (!isInitialized || videoPlayerInstance == null) {
            // Instead of immediately returning an error, wait for initialization to complete
            scope.launch {
                try {
                    // Wait for initialization to complete with a timeout
                    var timeoutCounter = 0
                    while ((!isInitialized || videoPlayerInstance == null) && timeoutCounter < 10) {
                        delay(100)
                        timeoutCounter++
                    }

                    // Check if initialization completed successfully
                    if (!isInitialized || videoPlayerInstance == null) {
                        setError("Player initialization timed out.")
                        return@launch
                    }

                    // Now that the player is initialized, open the URI
                    openUriInternal(uri)
                } catch (e: Exception) {
                    setError("Error waiting for player initialization: ${e.message}")
                }
            }
            return
        }

        openUriInternal(uri)
    }

    /**
     * Internal implementation of openUri that assumes the player is initialized
     *
     * @param uri The path to the media file or URL to open
     */
    private fun openUriInternal(uri: String) {
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
                        player.SetPlaybackState(instance, false, false)
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

                    // Check if the file or URL is valid
                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    // Open the media
                    val hrOpen = player.OpenMedia(instance, WString(uri))
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    // Get the video dimensions
                    val wRef = IntByReference()
                    val hRef = IntByReference()
                    player.GetVideoSize(instance, wRef, hRef)
                    if (wRef.value <= 0 || hRef.value <= 0) {
                        setError("Failed to retrieve video size")
                        player.CloseMedia(instance)
                        return@withLock
                    }
                    videoWidth = wRef.value
                    videoHeight = hRef.value

                    // Calculate the buffer size for frames
                    frameBufferSize = videoWidth * videoHeight * 4

                    // Allocate the shared buffer
                    sharedFrameBuffer = ByteArray(frameBufferSize)

                    // Get the media duration
                    val durationRef = LongByReference()
                    val hrDuration = player.GetMediaDuration(instance, durationRef)
                    if (hrDuration < 0) {
                        setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                        player.CloseMedia(instance)
                        return@withLock
                    }
                    _duration = durationRef.value / 10000000.0

                    // Set _hasMedia to true only if everything succeeded
                    _hasMedia = true

                    // Explicitly seek to the beginning of the video
                    val hrSeek = player.SeekMedia(instance, 0)
                    if (hrSeek < 0) {
                        windowsLogger.e { "Failed to seek to beginning (hr=0x${hrSeek.toString(16)})" }
                    }

                    // Start video processing
                    videoJob = scope.launch {
                        launch { produceFrames() }
                        launch { consumeFrames() }
                    }

                    // Start a task to update audio levels
                    audioLevelsJob = scope.launch {
                        while (isActive && _hasMedia) {
                            updateAudioLevels()
                            delay(50)
                        }
                    }

                    // Restore the volume setting for this instance
                    val storedVolume = instanceVolumes[instance]
                    if (storedVolume != null) {
                        val volumeRef = FloatByReference()
                        val hr = player.GetAudioVolume(instance, volumeRef)
                        if (hr >= 0 && storedVolume != volumeRef.value) {
                            val setHr = player.SetAudioVolume(instance, storedVolume)
                            if (setHr < 0) {
                                windowsLogger.e { "Error restoring volume (hr=0x${setHr.toString(16)})" }
                            }
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
     * Updates the audio level meters
     */
    private suspend fun updateAudioLevels() {
        mediaOperationMutex.withLock {
            videoPlayerInstance?.let { instance ->
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
                        seekTo(0f)
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

                // For 4K videos, we need to be careful with memory allocation
                // Only allocate a new buffer if absolutely necessary
                if (sharedFrameBuffer == null) {
                    // First allocation
                    sharedFrameBuffer = ByteArray(frameBufferSize)
                } else if (sharedFrameBuffer!!.size < frameBufferSize) {
                    // Buffer is too small, release old one before allocating new one
                    sharedFrameBuffer = null
                    System.gc() // Hint to garbage collector
                    delay(10) // Give GC a chance to run
                    sharedFrameBuffer = ByteArray(frameBufferSize)
                }

                // Use the shared buffer with null safety check
                val sharedBuffer = sharedFrameBuffer ?: run {
                    // Fallback if buffer is null (shouldn't happen)
                    ByteArray(frameBufferSize).also { sharedFrameBuffer = it }
                }

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
                    windowsLogger.e { "Error processing frame bitmap: ${e.message}" }
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
        // Timeout mechanism to prevent getting stuck in loading state
        var frameReceived = false
        var loadingTimeout = 0

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
                    // If we're still loading and haven't received a frame yet, increment timeout counter
                    if (isLoading && !frameReceived) {
                        loadingTimeout++
                        // After ~3 seconds (16ms * 200) of no frames while loading, force isLoading to false
                        if (loadingTimeout > 200) {
                            windowsLogger.w { "No frames received for 3 seconds, forcing isLoading to false" }
                            isLoading = false
                            loadingTimeout = 0
                        }
                    }
                    delay(16)
                    return@run null
                } ?: continue

                // Reset timeout counter and mark that we've received a frame
                loadingTimeout = 0
                frameReceived = true

                bitmapLock.write {
                    _currentFrame?.let { oldBitmap ->
                        if (frameBitmapRecycler == null) {
                            frameBitmapRecycler = oldBitmap
                        } else {
                            oldBitmap.close()
                        }
                    }
                    _currentFrame = frameData.bitmap
                    // Update the currentFrameState with the new frame
                    currentFrameState.value = frameData.bitmap.asComposeImageBitmap()
                }

                _currentTime = frameData.timestamp
                _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                isLoading = false  // Once frames start arriving, set isLoading to false

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

    /**
     * Starts or resumes playback
     * If no media is loaded but a previous URI exists, it will try to open and play it
     */
    override fun play() {
        if (!isInitialized || videoPlayerInstance == null || !_hasMedia) {
            lastUri?.takeIf { it.isNotEmpty() }?.let { uri ->
                scope.launch {
                    openUri(uri)
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
            setPlaybackState(true, "Error while starting playback")
            if (_hasMedia && (videoJob == null || videoJob?.isActive == false)) {
                videoJob = scope.launch {
                    launch { produceFrames() }
                    launch { consumeFrames() }
                }
            }

            // Restore the volume setting for this instance
            val instance = videoPlayerInstance
            if (instance != null) {
                val storedVolume = instanceVolumes[instance]
                if (storedVolume != null) {
                    val volumeRef = FloatByReference()
                    val hr = player.GetAudioVolume(instance, volumeRef)
                    if (hr >= 0 && storedVolume != volumeRef.value) {
                        val setHr = player.SetAudioVolume(instance, storedVolume)
                        if (setHr < 0) {
                            windowsLogger.e { "Error restoring volume during play (hr=0x${setHr.toString(16)})" }
                        }
                    }
                }
            }
        }
    }

    /**
     * Pauses playback if currently playing
     */
    override fun pause() {
        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying
        ) {
            setPlaybackState(false, "Error while pausing playback")
        }
    }

    /**
     * Stops playback and releases media resources
     * This will close the media file but keep the player instance
     */
    override fun stop() {
        executeMediaOperation(
            operation = "stop"
        ) {
            setPlaybackState(false, "Error while stopping playback", true)
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
            videoPlayerInstance?.let { instance ->
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

                    // For 4K videos, we need to be careful with memory allocation
                    // Only allocate a new buffer if absolutely necessary
                    if (sharedFrameBuffer == null) {
                        // First allocation
                        sharedFrameBuffer = ByteArray(frameBufferSize)
                    } else if (sharedFrameBuffer!!.size < frameBufferSize) {
                        // Buffer is too small, release old one before allocating new one
                        sharedFrameBuffer = null
                        System.gc() // Hint to garbage collector
                        delay(10) // Give GC a chance to run
                        sharedFrameBuffer = ByteArray(frameBufferSize)
                    }
                    // If buffer exists and is large enough, reuse it

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

    /**
     * Called when the player surface is resized
     * Temporarily pauses frame processing to avoid artifacts during resize
     * For 4K videos, we need a longer delay to prevent memory pressure
     */
    fun onResized() {
        isResizing.set(true)
        scope.launch {
            try {
                // Clear frame channel to stop processing frames during resize
                clearFrameChannel()

                // Release shared frame buffer to reduce memory pressure during resize
                // This is especially important for 4K videos
                sharedFrameBuffer = null

                // Force garbage collection to free up memory
                System.gc()
            } finally {
                resizeJob?.cancel()
                resizeJob = scope.launch {
                    // Increased delay for 4K videos (was 200ms)
                    delay(500)
                    isResizing.set(false)
                }
            }
        }
    }

    /**
     * Sets an error state with the given message
     *
     * @param msg The error message
     */
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
        windowsLogger.e { msg }
    }

    /**
     * Creates an ImageInfo object for the current video dimensions
     * 
     * @return ImageInfo configured for the current video frame
     */
    private fun createVideoImageInfo() =
        ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    /**
     * Sets the playback state (playing or paused)
     * 
     * @param playing True to start playback, false to pause
     * @param errorMessage Error message to display if the operation fails
     * @param bStop True to stop playback completely, false to pause
     * @return True if the operation succeeded, false otherwise
     */
    private fun setPlaybackState(playing: Boolean, errorMessage: String, bStop: Boolean = false): Boolean {
        return videoPlayerInstance?.let { instance ->
            for (attempt in 1..3) {
                val res = player.SetPlaybackState(instance, playing, bStop)
                if (res >= 0) {
                    _isPlaying = playing
                    _error?.let { clearError() }
                    return true
                }
                if (attempt == 3) {
                    setError("$errorMessage (hr=0x${res.toString(16)}) after $attempt attempts")
                }
            }
            false
        } ?: run {
            setError("$errorMessage: No player instance")
            false
        }
    }

    /**
     * Waits for the playback state to become active
     * If playback doesn't start within 5 seconds, attempts to restart it
     */
    private suspend fun waitForPlaybackState() {
        if (!_isPlaying) {
            try {
                withTimeoutOrNull(5000) {
                    snapshotFlow { _isPlaying }.filter { it }.first()
                } ?: run {
                    if (_hasMedia && videoPlayerInstance != null) {
                        setPlaybackState(true, "Error while restarting playback after timeout")
                        delay(100)
                        if (!_isPlaying) {
                            yield()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                windowsLogger.e { "Error in waitForPlaybackState: ${e.message}" }
                yield()
            }
        }
    }

    /**
     * Waits if the player is currently resizing
     * Uses a longer delay for 4K videos to reduce memory pressure
     * 
     * @return True if resizing is in progress and we waited, false otherwise
     */
    private suspend fun waitIfResizing(): Boolean {
        if (isResizing.get()) {
            try {
                // Increased delay for 4K videos (was 100ms)
                // This helps reduce memory pressure during resizing
                delay(200)
            } catch (e: CancellationException) {
                throw e
            }
            return true
        }
        return false
    }

    /**
     * Executes a media operation with proper error handling and mutex locking
     *
     * @param operation Name of the operation for error reporting
     * @param precondition Condition that must be true for the operation to execute
     * @param block The operation to execute
     */
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

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        scope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun disableSubtitles() {
        scope.launch {
            withContext(Dispatchers.Main) {
                subtitlesEnabled = false
                currentSubtitleTrack = null
            }
        }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }
}
