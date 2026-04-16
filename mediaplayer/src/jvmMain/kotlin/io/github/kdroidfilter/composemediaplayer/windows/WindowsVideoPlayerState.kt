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
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

internal val windowsLogger = TaggedLogger("WindowsVideoPlayerState")

/**
 * Windows implementation of the video player state.
 * Handles media playback using Media Foundation on Windows platform.
 */
class WindowsVideoPlayerState : VideoPlayerState {
    companion object {
        private val isMfBootstrapped = AtomicBoolean(false)

        /** Map to store volume settings for each player instance */
        private val instanceVolumes = ConcurrentHashMap<Long, Float>()

        /**
         * Initialize Media Foundation only once for all instances.
         * This is called automatically when the class is loaded.
         */
        private fun ensureMfInitialized() {
            if (!isMfBootstrapped.getAndSet(true)) {
                val hr = WindowsNativeBridge.InitMediaFoundation()
                if (hr < 0) {
                    windowsLogger.e { "Media Foundation initialization failed (hr=0x${hr.toString(16)})" }
                    return
                }
                // Tear MF down on JVM exit — otherwise MF worker threads stay
                // alive while the DLL is unloaded, corrupting KERNELBASE
                // internals on shutdown (crash 0x87A).
                try {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            try { WindowsNativeBridge.ShutdownMediaFoundation() } catch (_: Throwable) {}
                        }
                    )
                } catch (_: Throwable) { /* best effort */ }
            }
        }

        init {
            // Initialize Media Foundation when class is loaded
            ensureMfInitialized()
        }
    }

    /** Instance of the native Media Foundation player */
    private val player = WindowsNativeBridge

    /** Coroutine scope for all async operations */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Whether media has been loaded */
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia

    /** Whether media is currently playing */
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    /** Whether the user has intentionally paused the video */
    private var userPaused = false

    /** Video player instance handle */
    private var videoPlayerInstance: Long = 0L

    /** Deferred completed when initialization is ready */
    private val initReady = CompletableDeferred<Unit>()

    /** Flag to track if the player is being disposed */
    private val isDisposing = AtomicBoolean(false)

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
                        videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
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
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newSpeed) {
                _playbackSpeed = newSpeed
                scope.launch {
                    mediaOperationMutex.withLock {
                        videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                            val hr = player.SetPlaybackSpeed(instance, newSpeed)
                            if (hr < 0) {
                                setError("Error updating playback speed (hr=0x${hr.toString(16)})")
                            }
                        }
                    }
                }
            }
        }

    private var _error: VideoPlayerError? = null
    override val error get() = _error

    override fun clearError() {
        _error = null
        errorMessage = null
    }

    // Current frame management
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = ReentrantReadWriteLock()
    internal val currentFrameState = mutableStateOf<ImageBitmap?>(null)

    // Aspect ratio property
    override val aspectRatio: Float
        get() =
            if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight.toFloat()
            } else {
                16f / 9f
            }

    // Metadata and UI state
    private var _metadata by mutableStateOf(VideoMetadata())
    override val metadata: VideoMetadata get() = _metadata
    override var subtitlesEnabled = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var subtitleTextStyle: TextStyle =
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    override var subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Double get() = _currentTime
    override val duration: Double get() = _duration
    private var errorMessage: String? by mutableStateOf(null)

    // Fullscreen state
    override var isFullscreen by mutableStateOf(false)

    // Video properties
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Synchronization
    private val mediaOperationMutex = Mutex()
    private val isResizing = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var resizeJob: Job? = null

    // Seek coalescing: rapid slider drags overwrite the target; only the
    // latest value is actually seeked. seekInFlight acts as the "a loop is
    // draining the target" claim.
    private val pendingSeekTarget = AtomicLong(Long.MIN_VALUE)
    private val seekInFlight = AtomicBoolean(false)

// Serializes the native video reader: ReadVideoFrame / UnlockVideoFrame
    // (held by the producer coroutine) and SeekMedia (held by the seek flow).
    // This lets us seek *without* cancelling & restarting the producer — a
    // pattern that turned out to behave inconsistently under GraalVM native
    // image, leaving the video frozen after the first seek.
    private val videoReaderMutex = Mutex()
    private val isSeeking = AtomicBoolean(false)

    // Memory optimization for frame processing
    private val frameQueueSize = 1
    private val frameChannel =
        Channel<FrameData>(
            capacity = frameQueueSize,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Data structure for a frame
    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double,
    )

    // Double-buffering for zero-copy frame rendering
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var nextSkiaBitmapA: Boolean = true
    @Volatile
    private var lastFrameHash: Int = Int.MIN_VALUE
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0

    // Adaptive frame interval (ms) based on the video's native frame rate.
    // Mirrors macOS approach: poll at the video frame rate, not faster.
    // This prevents starving the audio thread on the shared SourceReader.
    private var frameIntervalMs: Long = 16L // Default ~60fps, updated after open

    // Variable to store the last opened URI
    private var lastUri: String? = null

    init {
        // Kick off native initialization immediately
        scope.launch {
            try {
                val handle = WindowsNativeBridge.createInstance()
                if (handle == 0L) {
                    setError("Failed to create video player instance")
                    return@launch
                }
                videoPlayerInstance = handle

                // Store default volume so that later instances inherit it
                instanceVolumes[handle] = _volume
                initReady.complete(Unit)
            } catch (e: Exception) {
                initReady.completeExceptionally(e)
                setError("Exception during initialization: ${e.message}")
            }
        }
    }

    override fun dispose() {
        if (isDisposing.getAndSet(true)) {
            return // Already disposing
        }

        // Stop coroutines first. The producer reads native state under
        // videoReaderMutex; we must wait for it to exit its critical section
        // before tearing down the native reader, otherwise CloseMedia can
        // free memory the producer is still dereferencing (exit 2170).
        val jobToJoin = videoJob
        videoJob = null
        jobToJoin?.cancel()
        resizeJob?.cancel()
        _isPlaying = false
        _hasMedia = false

        releaseAllResources()

        val instance = videoPlayerInstance
        videoPlayerInstance = 0L
        lastUri = null

        // Native cleanup must run SYNCHRONOUSLY. Compose Desktop's window close
        // ultimately calls System.exit, which will not wait for an arbitrary
        // background thread: the DLL gets unloaded while the native audio
        // thread is still running against freed globals, crashing the process
        // (exit 2170). Doing it here blocks the caller briefly (<500 ms for
        // StopAudioThread + MF teardown) but guarantees a clean shutdown.
        if (instance != 0L) {
            if (jobToJoin != null) {
                try {
                    kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(500) {
                            jobToJoin.join()
                        }
                    }
                } catch (_: Exception) { /* ignore */ }
            }

            try {
                player.SetPlaybackState(instance, false, true)
            } catch (e: Exception) {
                windowsLogger.e { "Exception stopping playback: ${e.message}" }
            }
            try {
                player.CloseMedia(instance)
            } catch (e: Exception) {
                windowsLogger.e { "Exception closing media: ${e.message}" }
            }
            instanceVolumes.remove(instance)
            try {
                WindowsNativeBridge.destroyInstance(instance)
            } catch (e: Exception) {
                windowsLogger.e { "Exception destroying instance: ${e.message}" }
            }
        }

        scope.cancel()
    }

    private fun clearAllResourcesSync() {
        // Clear the frame channel synchronously
        while (frameChannel.tryReceive().isSuccess) {
            // Drain the channel
        }

        // Free bitmaps and frame buffers
        bitmapLock.write {
            _currentFrame = null
            currentFrameState.value = null

            // Don't close bitmaps — see comment in releaseAllResources().
            skiaBitmapA = null
            skiaBitmapB = null
            skiaBitmapWidth = 0
            skiaBitmapHeight = 0
            nextSkiaBitmapA = true
            lastFrameHash = Int.MIN_VALUE
        }

        // Reset all state
        _currentTime = 0.0
        _duration = 0.0
        _progress = 0f
        _metadata = VideoMetadata()
        userPaused = false
        isLoading = false
        errorMessage = null
        _error = null

        // Reset initialFrameRead flag to ensure we read an initial frame when reinitialized
        initialFrameRead.set(false)
    }

    private fun releaseAllResources() {
        // Cancel any remaining jobs related to video processing
        videoJob?.cancel()
        resizeJob?.cancel()

        // Drain the frame channel (tryReceive is non-suspending)
        clearFrameChannel()

        // Free bitmaps and frame buffers
        bitmapLock.write {
            _currentFrame = null
            currentFrameState.value = null

            // Do NOT close the double-buffer bitmaps here: the ImageBitmap
            // exposed via currentFrameState shares the same native pixel memory
            // (asComposeImageBitmap is zero-copy). Compose may still be rendering
            // the last frame on the AWT-EventQueue thread. Closing now would free
            // the native memory while Skia reads it, causing an access violation.
            // Nullifying the references lets the Skia Managed cleaner release them
            // once Compose (and any other holder) drops its reference.
            skiaBitmapA = null
            skiaBitmapB = null
            skiaBitmapWidth = 0
            skiaBitmapHeight = 0
            nextSkiaBitmapA = true
            lastFrameHash = Int.MIN_VALUE
        }

        // Reset initialFrameRead flag to ensure we read an initial frame when reinitialized
        initialFrameRead.set(false)
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
     * @param initializeplayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        if (isDisposing.get()) {
            windowsLogger.w { "Ignoring openUri call - player is being disposed" }
            return
        }

        lastUri = uri
        playbackSpeed = 1.0f

        scope.launch {
            try {
                // Wait for initialization to complete with a timeout
                withTimeout(10_000) { initReady.await() }

                // Here the native instance is guaranteed to be non-null
                openUriInternal(uri, initializeplayerState)
            } catch (_: TimeoutCancellationException) {
                setError("Player initialization timed out after 10 s.")
            } catch (e: Exception) {
                setError("Error while waiting for initialization: ${e.message}")
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    /**
     * Internal implementation of openUri that assumes the player is initialized
     *
     * @param uri The path to the media file or URL to open
     * @param initializeplayerState Controls whether playback should start automatically after opening
     */
    private fun openUriInternal(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        scope.launch {
            if (isDisposing.get()) {
                return@launch
            }

            mediaOperationMutex.withLock {
                try {
                    isLoading = true

                    // Stop playback and release existing resources
                    val wasPlaying = _isPlaying
                    val instance = videoPlayerInstance
                    if (instance == 0L) {
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
                    _metadata = VideoMetadata()
                    _hasMedia = false
                    userPaused = false

                    // Reset initialFrameRead flag to ensure we read an initial frame for the new video
                    initialFrameRead.set(false)

                    // Check if the file or URL is valid
                    if (!uri.startsWith("http", ignoreCase = true) && !File(uri).exists()) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    // Always open media in paused state to avoid starting the native
                    // playback clock before we've finished setup (SetOutputSize, metadata, etc.).
                    // We explicitly call SetPlaybackState(true) later, right before starting
                    // the frame-reading coroutine, so the wall-clock is in sync with frame production.
                    val startPlayback = initializeplayerState == InitialPlayerState.PLAY
                    val hrOpen = player.OpenMedia(instance, uri, false)
                    if (hrOpen < 0) {
                        setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                        return@withLock
                    }

                    // Get the video dimensions
                    val sizeArr = IntArray(2)
                    player.GetVideoSize(instance, sizeArr)
                    if (sizeArr[0] <= 0 || sizeArr[1] <= 0) {
                        setError("Failed to retrieve video size")
                        player.CloseMedia(instance)
                        return@withLock
                    }
                    videoWidth = sizeArr[0]
                    videoHeight = sizeArr[1]

                    // Scale output to match display surface (saves memory for 4K+ video)
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        val hrScale = player.SetOutputSize(instance, surfaceWidth, surfaceHeight)
                        if (hrScale >= 0) {
                            player.GetVideoSize(instance, sizeArr)
                            if (sizeArr[0] > 0 && sizeArr[1] > 0) {
                                videoWidth = sizeArr[0]
                                videoHeight = sizeArr[1]
                            }
                        }
                    }

                    // Get the media duration (may be 0 for live HLS streams)
                    val durArr = LongArray(1)
                    val hrDuration = player.GetMediaDuration(instance, durArr)
                    if (hrDuration < 0) {
                        // Only fail for non-network sources; network/HLS may lack duration
                        if (!uri.startsWith("http", ignoreCase = true)) {
                            setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                            player.CloseMedia(instance)
                            return@withLock
                        }
                    }
                    _duration = durArr[0] / 10000000.0

                    // Retrieve metadata using the native function
                    val retrievedMetadata = WindowsNativeBridge.getVideoMetadata(instance)
                    if (retrievedMetadata != null) {
                        _metadata = retrievedMetadata
                    } else {
                        // If metadata retrieval failed, create a basic metadata object with what we know
                        _metadata =
                            VideoMetadata(
                                width = videoWidth,
                                height = videoHeight,
                                duration = (_duration * 1000).toLong(), // Convert to milliseconds
                            )
                    }

                    // Query the native frame rate to compute an adaptive polling interval
                    // like macOS does with captureFrameRate.
                    val rateArr = IntArray(2)
                    if (player.nGetVideoFrameRate(instance, rateArr) >= 0 && rateArr[0] > 0) {
                        val fps = rateArr[0].toDouble() / rateArr[1].coerceAtLeast(1).toDouble()
                        frameIntervalMs = (1000.0 / fps).toLong().coerceIn(8L, 50L)
                    } else {
                        frameIntervalMs = 16L // fallback ~60fps
                    }

                    // Set _hasMedia to true only if everything succeeded
                    _hasMedia = true

                    if (!isDisposing.get()) {
                        // Restore the volume setting BEFORE starting playback
                        val storedVolume = instanceVolumes[instance]
                        if (storedVolume != null) {
                            val volArr = FloatArray(1)
                            val hr = player.GetAudioVolume(instance, volArr)
                            if (hr >= 0 && storedVolume != volArr[0]) {
                                val setHr = player.SetAudioVolume(instance, storedVolume)
                                if (setHr < 0) {
                                    windowsLogger.e { "Error restoring volume (hr=0x${setHr.toString(16)})" }
                                }
                            }
                        }

                        if (!startPlayback) {
                            userPaused = true
                            initialFrameRead.set(false)
                            isLoading = false
                        }

                        // Start native playback as late as possible — this sets
                        // the wall-clock origin (llPlaybackStartTime) to NOW,
                        // minimising the gap before produceFrames reads its first frame.
                        if (startPlayback) {
                            val hrPlay = player.SetPlaybackState(instance, true, false)
                            if (hrPlay < 0) {
                                windowsLogger.e { "Failed to start playback (hr=0x${hrPlay.toString(16)})" }
                            }
                        }
                        _isPlaying = startPlayback

                        // Start video processing
                        videoJob =
                            scope.launch {
                                launch { produceFrames() }
                                launch { consumeFrames() }
                            }
                    }
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
     * Zero-copy optimized frame producer using double-buffering and direct memory access.
     *
     * Optimizations applied:
     * 1. Double-buffering: Reuses two Bitmap objects, alternating between them.
     * 2. Frame hashing: Skips processing if the frame content hasn't changed.
     * 3. peekPixels(): Direct access to Skia bitmap memory, no ByteArray allocation.
     * 4. Single memory copy: Native buffer → Skia bitmap pixels.
     */
    private suspend fun produceFrames() {
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            val instance = videoPlayerInstance
            if (instance == 0L) break

            if (player.IsEOF(instance)) {
                if (_duration <= 0.0) {
                    // Live HLS stream — EOF means the live window ended,
                    // wait and continue (new segments may become available)
                    delay(1000)
                    continue
                } else if (loop) {
                    try {
                        userPaused = false // Reset userPaused when looping
                        initialFrameRead.set(false) // Reset initialFrameRead flag
                        lastFrameHash = Int.MIN_VALUE // Reset hash for new loop
                        seekTo(0f)
                        play()
                        onRestart?.invoke()
                    } catch (e: Exception) {
                        setError("Error during SeekMedia for loop: ${e.message}")
                    }
                } else {
                    // The last decoded frame's timestamp is always slightly before the
                    // total duration (duration = last_frame_pts + frame_duration), so
                    // snap currentTime/progress to the end when playback completes.
                    if (_duration > 0.0) {
                        _currentTime = _duration
                        _progress = 1f
                    }
                    pause()
                    onPlaybackEnded?.invoke()
                    break
                }
            }

            try {
                // Wait for playback state, allowing initial frame when paused
                // If the return value is false, we should wait and not process frames
                if (!waitForPlaybackState(allowInitialFrame = true)) {
                    delay(100) // Add a small delay to prevent busy waiting
                    continue
                }
            } catch (e: CancellationException) {
                break
            }

            if (waitIfResizing()) {
                continue
            }

            // Short-circuit while a seek is in progress — avoids contending
            // on videoReaderMutex which the seek flow is holding.
            if (isSeeking.get()) {
                delay(5)
                continue
            }

            val produced = try {
                videoReaderMutex.withLock {
                    processOneFrame(instance)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia && !isDisposing.get()) {
                    setError("Error while reading a frame: ${e.message}")
                }
                delay(100)
                null
            }

            when (produced) {
                ProduceOutcome.NotReady -> delay(2)
                ProduceOutcome.SkipIteration -> yield()
                is ProduceOutcome.Frame -> {
                    frameChannel.trySend(FrameData(produced.bitmap, produced.timestamp))
                    delay(1)
                }
                null -> { /* exception already handled */ }
            }
        }
    }

    /**
     * Outcome of a single frame-read pass, consumed by the produceFrames loop.
     */
    private sealed interface ProduceOutcome {
        data object NotReady : ProduceOutcome               // native says "retry later"
        data object SkipIteration : ProduceOutcome          // frame dropped / duplicate
        data class  Frame(val bitmap: Bitmap, val timestamp: Double) : ProduceOutcome
    }

    /**
     * Reads one frame from the native reader, copies it to the next Skia
     * bitmap, and returns the outcome. Must be called under
     * [videoReaderMutex] — this method calls ReadVideoFrame / UnlockVideoFrame.
     */
    private fun processOneFrame(instance: Long): ProduceOutcome {
        val hrArr = IntArray(1)
        val srcBuffer = player.ReadVideoFrame(instance, hrArr) ?: return ProduceOutcome.NotReady
        if (hrArr[0] < 0) return ProduceOutcome.NotReady

        // HLS adaptive bitrate may change the decoded size mid-stream.
        val sizeArr = IntArray(2)
        player.GetVideoSize(instance, sizeArr)
        if (sizeArr[0] > 0 && sizeArr[1] > 0 &&
            (sizeArr[0] != videoWidth || sizeArr[1] != videoHeight)
        ) {
            videoWidth = sizeArr[0]
            videoHeight = sizeArr[1]
        }

        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0) {
            player.UnlockVideoFrame(instance)
            return ProduceOutcome.SkipIteration
        }

        srcBuffer.rewind()
        val pixelCount = width * height
        val newHash = calculateFrameHash(srcBuffer, pixelCount)
        if (newHash == lastFrameHash) {
            player.UnlockVideoFrame(instance)
            return ProduceOutcome.SkipIteration
        }
        lastFrameHash = newHash

        if (skiaBitmapA == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
            bitmapLock.write {
                // Do NOT close the previous bitmaps: the most recent one is
                // shared (zero-copy) with the ImageBitmap currently held by
                // Compose via currentFrameState and may still be drawn on the
                // AWT-EventQueue. Closing destroys the underlying Skia peer
                // and causes a null-pointer crash in Image.makeFromBitmap.
                // Same pattern as releaseAllResources(): drop the reference
                // and let the Skia managed cleaner reclaim it once Compose
                // releases its hold. Sacrifices a frame's worth of RAM on
                // resolution changes — the correct trade-off.
                val imageInfo = createVideoImageInfo()
                skiaBitmapA = Bitmap().apply { allocPixels(imageInfo) }
                skiaBitmapB = Bitmap().apply { allocPixels(imageInfo) }
                skiaBitmapWidth = width
                skiaBitmapHeight = height
                nextSkiaBitmapA = true
            }
        }

        val targetBitmap = if (nextSkiaBitmapA) skiaBitmapA!! else skiaBitmapB!!
        nextSkiaBitmapA = !nextSkiaBitmapA

        val pixmap = targetBitmap.peekPixels()
        if (pixmap == null) {
            player.UnlockVideoFrame(instance)
            windowsLogger.e { "Failed to get pixmap from bitmap" }
            return ProduceOutcome.SkipIteration
        }
        val pixelsAddr = pixmap.addr
        if (pixelsAddr == 0L) {
            player.UnlockVideoFrame(instance)
            windowsLogger.e { "Invalid pixel address" }
            return ProduceOutcome.SkipIteration
        }

        val dstRowBytes = pixmap.rowBytes
        val dstSizeBytes = dstRowBytes.toLong() * height.toLong()
        val dstBuffer = WindowsNativeBridge.nWrapPointer(pixelsAddr, dstSizeBytes)
        if (dstBuffer == null) {
            player.UnlockVideoFrame(instance)
            return ProduceOutcome.SkipIteration
        }

        srcBuffer.rewind()
        copyBgraFrame(srcBuffer, dstBuffer, width, height, dstRowBytes)
        player.UnlockVideoFrame(instance)

        val posArr = LongArray(1)
        val frameTime =
            if (player.GetMediaPosition(instance, posArr) >= 0) posArr[0] / 10000000.0
            else 0.0

        return ProduceOutcome.Frame(targetBitmap, frameTime)
    }

    /**
     * Consumes frames from the channel and updates the UI.
     * With zero-copy optimization, bitmaps are reused from the double-buffer pool
     * and should not be closed here.
     */
    private suspend fun consumeFrames() {
        var frameReceived = false
        var loadingTimeout = 0

        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            if (waitIfResizing()) continue

            try {
                val frameData =
                    frameChannel.tryReceive().getOrNull() ?: run {
                        if (isLoading && !frameReceived) {
                            loadingTimeout++
                            if (loadingTimeout > 200) {
                                windowsLogger.w { "No frames received for 3 seconds, forcing isLoading to false" }
                                isLoading = false
                                loadingTimeout = 0
                            }
                        }
                        delay(16)
                        return@run null
                    } ?: continue

                loadingTimeout = 0
                frameReceived = true

                bitmapLock.write {
                    _currentFrame = frameData.bitmap
                    currentFrameState.value = frameData.bitmap.asComposeImageBitmap()
                }

                _currentTime = frameData.timestamp
                // Don't clobber _progress while the user is dragging the
                // slider: sliderPos is backed by _progress, and seekFinished()
                // reads sliderPos to decide where to seek. Overwriting it with
                // the current playback position would make the drag seek land
                // wherever the video happened to be, not where the user
                // released.
                if (!_userDragging) {
                    _progress =
                        if (_duration > 0.0) {
                            (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f // Live stream — no meaningful progress
                        }
                }
                isLoading = false

                delay(1)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia && !isDisposing.get()) {
                    setError("Error while processing a frame: ${e.message}")
                }
                delay(100)
            }
        }
    }

    /**
     * Starts or resumes playback.
     * If media is not loaded yet (openUri in progress), waits for it to finish
     * instead of triggering a second open which would race with the first.
     */
    override fun play() {
        if (isDisposing.get()) return

        if (readyForPlayback()) {
            // Fast path: media is loaded, just resume
            executeMediaOperation(operation = "play") {
                resumePlayback()
            }
            return
        }

        // Slow path: wait for any in-progress openUri to complete, then resume
        scope.launch {
            try {
                withTimeout(10_000) { initReady.await() }
                // Wait for _hasMedia to become true (set by openUriInternal)
                withTimeout(10_000) {
                    snapshotFlow { _hasMedia }.filter { it }.first()
                }
            } catch (_: Exception) {
                // Timeout or cancellation — if we still have a URI, try a fresh open
                if (!_hasMedia) {
                    lastUri?.takeIf { it.isNotEmpty() }?.let { uri ->
                        openUriInternal(uri, InitialPlayerState.PLAY)
                    }
                }
                return@launch
            }

            // Media is loaded — resume playback
            mediaOperationMutex.withLock {
                if (!isDisposing.get()) resumePlayback()
            }
        }
    }

    /**
     * Resumes playback — must be called under mediaOperationMutex.
     */
    private fun resumePlayback() {
        userPaused = false
        initialFrameRead.set(false)

        if (!_isPlaying) {
            setPlaybackState(true, "Error while starting playback")
        }

        if (_hasMedia && (videoJob == null || videoJob?.isActive == false)) {
            videoJob =
                scope.launch {
                    launch { produceFrames() }
                    launch { consumeFrames() }
                }
        }
    }

    /**
     * Pauses playback if currently playing
     */
    override fun pause() {
        if (isDisposing.get()) return

        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying,
        ) {
            userPaused = true
            // Reset initialFrameRead flag when switching to pause state
            // This ensures that we'll read a new initial frame to display
            initialFrameRead.set(false)

            setPlaybackState(false, "Error while pausing playback")
        }
    }

    /**
     * Stops playback and releases media resources
     * This will close the media file but keep the player instance
     */
    override fun stop() {
        if (isDisposing.get()) return

        executeMediaOperation(
            operation = "stop",
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
            userPaused = false

            // Reset initialFrameRead flag to ensure we read a new frame when playing again
            initialFrameRead.set(false)

            videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
                player.CloseMedia(instance)
            }
        }
    }

    override fun seekTo(value: Float) {
        if (isDisposing.get()) return
        if (_duration <= 0.0) return // Live stream — seeking not supported

        val clamped = value.coerceIn(0f, 1000f)
        val targetPos = (_duration * (clamped / 1000f) * 10000000).toLong()

        // Latch the newest target; whoever is running the seek loop will see it.
        pendingSeekTarget.set(targetPos)

        // Optimistic UI so the slider tracks the drag smoothly even while the
        // native seek is still settling.
        _progress = (clamped / 1000f).coerceIn(0f, 1f)
        _currentTime = _duration * _progress

        scheduleSeek()
    }

    /**
     * Launches the seek loop if no other loop is currently draining the target.
     * If multiple `seekTo` calls arrive in quick succession, only the latest
     * target is actually processed — intermediate values are coalesced.
     */
    private fun scheduleSeek() {
        if (!seekInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                while (true) {
                    val target = pendingSeekTarget.getAndSet(Long.MIN_VALUE)
                    if (target == Long.MIN_VALUE) break
                    performSeek(target)
                }
            } finally {
                seekInFlight.set(false)
                // Tiny race: a caller may have enqueued a target between our
                // last getAndSet and releasing the claim. Re-check & re-launch.
                if (pendingSeekTarget.get() != Long.MIN_VALUE) scheduleSeek()
            }
        }
    }

    /**
     * Executes a single native seek.
     *
     * Strategy: keep the producer/consumer coroutines alive and instead
     * serialize native reader access with [videoReaderMutex] + [isSeeking].
     * Cancelling & relaunching `videoJob` on every seek proved fragile
     * under GraalVM native-image (the relaunched job sometimes never ran,
     * leaving audio but no video).
     */
    private suspend fun performSeek(targetPos: Long) {
        val loadingTrigger = scope.launch {
            delay(200)
            if (!isDisposing.get()) isLoading = true
        }

        try {
            mediaOperationMutex.withLock {
                if (isDisposing.get()) return@withLock
                val instance = videoPlayerInstance
                if (instance == 0L || !_hasMedia) return@withLock

                isSeeking.set(true)
                try {
                    videoReaderMutex.withLock {
                        // Inside the reader mutex: no concurrent ReadVideoFrame.
                        initialFrameRead.set(false)
                        lastFrameHash = Int.MIN_VALUE
                        clearFrameChannel()

                        var hr = player.SeekMedia(instance, targetPos)
                        if (hr < 0) {
                            delay(30)
                            hr = player.SeekMedia(instance, targetPos)
                        }
                        if (hr < 0) {
                            setError("Seek failed (hr=0x${hr.toString(16)})")
                            return@withLock
                        }

                        val posArr = LongArray(1)
                        if (player.GetMediaPosition(instance, posArr) >= 0) {
                            _currentTime = posArr[0] / 10000000.0
                            _progress =
                                if (_duration > 0.0)
                                    (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                                else 0f
                        }
                    }
                } finally {
                    isSeeking.set(false)
                }

                // If the producer was never started (e.g. stop() was called
                // before the first play), start it now so the new frame shows.
                if (!isDisposing.get() && (videoJob == null || videoJob?.isActive == false)) {
                    videoJob = scope.launch {
                        launch { produceFrames() }
                        launch { consumeFrames() }
                    }
                }
            }
        } finally {
            loadingTrigger.cancel()
            isLoading = false
        }
    }

    /**
     * Called when the player surface is resized
     * Temporarily pauses frame processing to avoid artifacts during resize
     * For 4K videos, we need a longer delay to prevent memory pressure
     */
    fun onResized(
        width: Int = 0,
        height: Int = 0,
    ) {
        if (isDisposing.get()) return

        if (width <= 0 || height <= 0) return

        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        // Mark resizing in progress and debounce rapid events
        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            scope.launch {
                delay(120)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
                }
            }
    }

    /**
     * Asks Media Foundation to produce frames at the display surface size
     * instead of full native resolution. Saves significant memory for 4K+ video.
     */
    private suspend fun applyOutputScaling() {
        if (isDisposing.get() || !_hasMedia) return
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return

        val instance = videoPlayerInstance
        if (instance == 0L) return

        mediaOperationMutex.withLock {
            val hr = player.SetOutputSize(instance, sw, sh)
            if (hr >= 0) {
                // Update dimensions from native side
                val sizeArr = IntArray(2)
                player.GetVideoSize(instance, sizeArr)
                if (sizeArr[0] > 0 && sizeArr[1] > 0) {
                    videoWidth = sizeArr[0]
                    videoHeight = sizeArr[1]
                    // Reset bitmaps so they are reallocated at the new size
                    lastFrameHash = Int.MIN_VALUE
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
    private fun createVideoImageInfo() = ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)

    /**
     * Sets the playback state (playing or paused)
     *
     * @param playing True to start playback, false to pause
     * @param errorMessage Error message to display if the operation fails
     * @param bStop True to stop playback completely, false to pause
     * @return True if the operation succeeded, false otherwise
     */
    private fun setPlaybackState(
        playing: Boolean,
        errorMessage: String,
        bStop: Boolean = false,
    ): Boolean {
        return videoPlayerInstance.takeIf { it != 0L }?.let { instance ->
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
     * Flag to track if we've read at least one frame when paused.
     * Initialize to false to ensure we read an initial frame when the player is first loaded.
     */
    private val initialFrameRead = AtomicBoolean(false)

    /**
     * Waits for the playback state to become active
     * If playback doesn't start within 5 seconds, attempts to restart it
     * unless the user has intentionally paused the video
     *
     * @param allowInitialFrame If true, allows reading one frame even when paused (for thumbnail)
     * @return True if the method should continue processing frames, false if it should wait
     */
    private suspend fun waitForPlaybackState(allowInitialFrame: Boolean = false): Boolean {
        if (_isPlaying) return true

        // When paused, allow the producer to read exactly one frame for display.
        if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) {
            return true
        }

        if (isLoading) isLoading = false

        // Polling wait — wakes up on either _isPlaying turning true OR
        // initialFrameRead being reset (e.g. after a paused seek, where the
        // producer must fetch & display the new frame without needing
        // cancellation/restart of its coroutine).
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            if (_isPlaying) return true
            if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) return true
            try {
                delay(40)
            } catch (e: CancellationException) {
                throw e
            }
        }
        return false
    }

    /** Tracks how many consecutive iterations we've been waiting for resize */
    private var resizeWaitCount = 0

    /**
     * Waits if the player is currently resizing.
     * Has a safety timeout to prevent infinite blocking.
     *
     * @return True if resizing is in progress and we waited, false otherwise
     */
    private suspend fun waitIfResizing(): Boolean {
        if (isResizing.get()) {
            resizeWaitCount++
            if (resizeWaitCount > 200) { // ~1.6s max wait
                windowsLogger.w {
                    "waitIfResizing: timeout after $resizeWaitCount iterations, forcing isResizing=false"
                }
                isResizing.set(false)
                resizeWaitCount = 0
                return false
            }
            try {
                yield()
                delay(8)
            } catch (e: CancellationException) {
                throw e
            }
            return true
        }
        resizeWaitCount = 0
        return false
    }

    /**
     * Checks if the player is ready for playback
     *
     * @return True if the player is initialized and has media loaded, false otherwise
     */
    private fun readyForPlayback(): Boolean =
        initReady.isCompleted && videoPlayerInstance != 0L && _hasMedia && !isDisposing.get()

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
        block: suspend () -> Unit,
    ) {
        if (!precondition || isDisposing.get()) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    if (!isDisposing.get()) {
                        block()
                    }
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
