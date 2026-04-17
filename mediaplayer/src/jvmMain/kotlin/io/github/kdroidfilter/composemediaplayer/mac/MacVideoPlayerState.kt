package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.runtime.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal val macLogger = TaggedLogger("MacVideoPlayerState")

/**
 * MacVideoPlayerState handles the native Mac video player state.
 *
 * This implementation uses a native video player via MacNativeBridge.
 */
class MacVideoPlayerState : VideoPlayerState {
    // Main state variables
    // AtomicLong allows lock-free reads of the native pointer from the frame hot path
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    // Serial dispatcher for frame processing — ensures only one frame is processed at a time
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val _currentFrameState = MutableStateFlow<ImageBitmap?>(null)
    internal val currentFrameState: State<ImageBitmap?> = mutableStateOf(null)
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var nextSkiaBitmapA: Boolean = true

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private val isResizing = AtomicBoolean(false)
    private var resizeJob: Job? = null

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
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override var error: VideoPlayerError? by mutableStateOf(null)
    override var subtitlesEnabled: Boolean by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override val metadata: VideoMetadata = VideoMetadata()
    override var isFullscreen: Boolean by mutableStateOf(false)
    private var lastUri: String? = null

    // Non-blocking text properties
    private val _positionText = mutableStateOf("00:00")
    override val positionText: String get() = _positionText.value

    private val _durationText = mutableStateOf("00:00")
    override val durationText: String get() = _durationText.value

    override val currentTime: Double
        get() =
            runBlocking {
                if (hasMedia) getPositionSafely() else 0.0
            }

    override val duration: Double
        get() =
            runBlocking {
                if (hasMedia) getDurationSafely() else 0.0
            }

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
            val newValue = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeedState.value != newValue) {
                _playbackSpeedState.value = newValue
                // Launch a coroutine to apply the playback speed if the native player is available.
                ioScope.launch {
                    applyPlaybackSpeed()
                }
            }
        }

    private val updateInterval: Long
        get() =
            if (captureFrameRate > 0) {
                (1000.0f / captureFrameRate).toLong()
            } else {
                33L // Default value (in ms) if no valid capture rate is provided
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
        uiUpdateJob =
            ioScope.launch {
                _currentFrameState.debounce(1).collect { newFrame ->
                    ensureActive() // Checks that the coroutine is still active
                    withContext(Dispatchers.Main) {
                        (currentFrameState as MutableState).value = newFrame
                    }
                }
            }
    }

    /** Initializes the native video player on the IO thread. */
    private suspend fun initPlayer() =
        ioScope
            .launch {
                macLogger.d { "initPlayer() - Creating native player" }
                try {
                    val ptr = MacNativeBridge.nCreatePlayer()
                    if (ptr != 0L) {
                        playerPtrAtomic.set(ptr)
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
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            videoFrameRate = MacNativeBridge.nGetVideoFrameRate(ptr)
            screenRefreshRate = MacNativeBridge.nGetScreenRefreshRate(ptr)
            captureFrameRate = MacNativeBridge.nGetCaptureFrameRate(ptr)
            macLogger.d {
                "Frame Rates - Video: $videoFrameRate, Screen: $screenRefreshRate, Capture: $captureFrameRate"
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating frame rate info: ${e.message}" }
        }
    }

    // Check if this is a local file that doesn't exist
    // This handles both URIs with a "file:" scheme and simple filenames without a scheme, with or without authority.
    // Uses File directly to support paths with spaces or non-ASCII characters that URI.create() rejects.
    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme = if (schemeDelimiter >= 0) uri.substring(0, schemeDelimiter) else ""
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") uri.removePrefix("file://") else uri
                File(path).exists()
            }
            else -> true // Network URI — assume reachable
        }
    }

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        macLogger.d { "openUri() - Opening URI: $uri, initializeplayerState: $initializeplayerState" }

        lastUri = uri

        // Check if this is a local file that doesn't exist
        if (!checkExistsIfLocalFile(uri)) {
            macLogger.e { "File does not exist: $uri" }
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return
        }

        // Update UI state first
        ioScope.launch {
            withContext(Dispatchers.Main) {
                isLoading = true
                error = null // Clear any previous errors only if we got this far
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

                    // Scale output to match display surface if size is already known
                    if (surfaceWidth > 0 && surfaceHeight > 0) {
                        applyOutputScaling()
                    }

                    // Update UI state on main thread
                    withContext(Dispatchers.Main) {
                        hasMedia = true
                        isLoading = false
                        // Set isPlaying based on the initializeplayerState parameter
                        isPlaying = initializeplayerState == InitialPlayerState.PLAY
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
                    // Use withContext directly since we're already in a suspend function
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

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    /** Cleans up current playback state. */
    private suspend fun cleanupCurrentPlayback() {
        macLogger.d { "cleanupCurrentPlayback() - Cleaning up current playback" }
        pauseInBackground()
        stopFrameUpdates()
        stopBufferingCheck()

        val ptrToDispose =
            withContext(frameDispatcher) {
                playerPtrAtomic.getAndSet(0L)
            }

        // Release resources outside of the mutex lock
        if (ptrToDispose != 0L) {
            try {
                MacNativeBridge.nDisposePlayer(ptrToDispose)
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

        if (playerPtr == 0L) {
            val ptr = MacNativeBridge.nCreatePlayer()
            if (ptr != 0L) {
                if (!playerPtrAtomic.compareAndSet(0L, ptr)) {
                    // Another coroutine already initialized the player; discard ours
                    MacNativeBridge.nDisposePlayer(ptr)
                } else {
                    applyVolume()
                    applyPlaybackSpeed()
                }
            } else {
                throw IllegalStateException("Failed to create native player")
            }
        }
    }

    /** Opens media URI and returns a success flag. */
    private suspend fun openMediaUri(uri: String): Boolean {
        macLogger.d { "openMediaUri() - Opening URI: $uri" }
        val ptr = playerPtr
        if (ptr == 0L) return false

        // Check if file exists (for local files)
        // This handles both URIs with file:// scheme and simple filenames without a scheme
        if (!checkExistsIfLocalFile(uri)) {
            macLogger.e { "File does not exist: $uri" }
            // Use setPlayerError to ensure the error is set synchronously
            setPlayerError(VideoPlayerError.SourceError("File not found: $uri"))
            return false
        }

        return try {
            // Open video asynchronously
            MacNativeBridge.nOpenUri(ptr, uri)

            // Instead of directly calling `updateMetadata()`,
            // we poll until valid dimensions are available
            pollDimensionsUntilReady(ptr)

            // Once dimensions are retrieved, call updateMetadata()
            updateMetadata()

            true
        } catch (e: Exception) {
            macLogger.e { "Failed to open URI: ${e.message}" }
            // Use setPlayerError to ensure the error is set synchronously
            setPlayerError(VideoPlayerError.SourceError("Error opening media: ${e.message}"))
            false
        }
    }

    /**
     * Loops several times (every 250 ms) until width/height
     * are no longer zero. If dimensions are still zero after
     * a specified number of attempts, stop waiting.
     */
    private suspend fun pollDimensionsUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            val width = MacNativeBridge.nGetFrameWidth(ptr)
            val height = MacNativeBridge.nGetFrameHeight(ptr)

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
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            val width = MacNativeBridge.nGetFrameWidth(ptr)
            val height = MacNativeBridge.nGetFrameHeight(ptr)
            val duration = (MacNativeBridge.nGetVideoDuration(ptr) * 1000).toLong()
            val frameRate = MacNativeBridge.nGetVideoFrameRate(ptr)

            // Calculate aspect ratio
            val newAspectRatio =
                if (width > 0 && height > 0) {
                    width.toFloat() / height.toFloat()
                } else {
                    // Instead of forcing 16f/9f, don’t change the aspect if the video is not ready yet.
                    // For example, we can keep the previous aspect ratio:
                    _aspectRatio.value
                }

            // Get additional metadata
            val title = MacNativeBridge.nGetVideoTitle(ptr)
            val bitrate = MacNativeBridge.nGetVideoBitrate(ptr)
            val mimeType = MacNativeBridge.nGetVideoMimeType(ptr)
            val audioChannels = MacNativeBridge.nGetAudioChannels(ptr)
            val audioSampleRate = MacNativeBridge.nGetAudioSampleRate(ptr)

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

                // Update the aspect ratio only if width/height are valid
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
        frameUpdateJob =
            ioScope.launch {
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

    /** Starts periodic buffering detection on a background thread. */
    private fun startBufferingCheck() {
        macLogger.d { "startBufferingCheck() - Starting buffering detection" }
        stopBufferingCheck()
        bufferingCheckJob =
            ioScope.launch {
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

    /** Updates the current video frame on a background thread. */
    private suspend fun updateFrameAsync() {
        withContext(frameDispatcher) {
            try {
                val ptr = playerPtr
                if (ptr == 0L) return@withContext

                // Lock the CVPixelBuffer directly — eliminates the Swift-side memcpy.
                // outInfo = [width, height, bytesPerRow]
                val outInfo = IntArray(3)
                val frameAddress = MacNativeBridge.nLockFrame(ptr, outInfo)
                if (frameAddress == 0L) return@withContext

                val width = outInfo[0]
                val height = outInfo[1]
                val srcBytesPerRow = outInfo[2]

                if (width <= 0 || height <= 0) {
                    MacNativeBridge.nUnlockFrame(ptr)
                    return@withContext
                }

                val frameSizeBytes = srcBytesPerRow.toLong() * height.toLong()
                var framePublished = false

                try {
                    withContext(Dispatchers.Default) {
                        val srcBuf =
                            MacNativeBridge.nWrapPointer(frameAddress, frameSizeBytes)
                                ?: return@withContext

                        // Allocate/reuse two bitmaps (double-buffering) to avoid writing while the UI draws.
                        if (skiaBitmapA == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
                            skiaBitmapA?.close()
                            skiaBitmapB?.close()

                            val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                            skiaBitmapA = Bitmap().apply { allocPixels(imageInfo) }
                            skiaBitmapB = Bitmap().apply { allocPixels(imageInfo) }
                            skiaBitmapWidth = width
                            skiaBitmapHeight = height
                            nextSkiaBitmapA = true
                        }

                        val targetBitmap = if (nextSkiaBitmapA) skiaBitmapA!! else skiaBitmapB!!
                        nextSkiaBitmapA = !nextSkiaBitmapA

                        val pixmap = targetBitmap.peekPixels() ?: return@withContext
                        val pixelsAddr = pixmap.addr
                        if (pixelsAddr == 0L) return@withContext

                        // Single copy: CVPixelBuffer → Skia bitmap pixels (no intermediate buffer)
                        srcBuf.rewind()
                        val dstRowBytes = pixmap.rowBytes
                        val dstSizeBytes = dstRowBytes.toLong() * height.toLong()
                        val destBuf =
                            MacNativeBridge.nWrapPointer(pixelsAddr, dstSizeBytes)
                                ?: return@withContext
                        copyBgraFrame(srcBuf, destBuf, width, height, srcBytesPerRow, dstRowBytes)

                        _currentFrameState.value = targetBitmap.asComposeImageBitmap()
                        framePublished = true
                    }
                } finally {
                    MacNativeBridge.nUnlockFrame(ptr)
                }

                if (framePublished) {
                    lastFrameUpdateTime = System.currentTimeMillis()

                    // Update loading state if needed on the main thread
                    if (isLoading && !seekInProgress) {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "updateFrameAsync() - Exception: ${e.message}" }
            }
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
                val newSliderPos = if (duration > 0) (current / duration * 1000).toFloat().coerceIn(0f, 1000f) else 0f
                withContext(Dispatchers.Main) {
                    sliderPos = newSliderPos
                }
            }

            // Check for looping
            checkLoopingAsync()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error in updatePositionAsync: ${e.message}" }
        }
    }

    /** Checks if playback has ended and triggers loop or stop accordingly. */
    private suspend fun checkLoopingAsync() {
        val ptr = playerPtr
        if (ptr == 0L) return

        // Trust AVPlayerItemDidPlayToEndTime: it fires reliably on macOS for both
        // file and HLS playback. A position-based fallback (current >= duration - x)
        // is dangerous because it stops playback x seconds early — the slider
        // freezes at (duration - x) / duration instead of reaching 100%.
        if (!MacNativeBridge.nConsumeDidPlayToEnd(ptr)) return

        if (loop) {
            macLogger.d { "checkLoopingAsync() - Loop enabled, restarting video" }
            seekToAsync(0f)
            onRestart?.invoke()
        } else {
            macLogger.d { "checkLoopingAsync() - Video completed, updating state" }
            withContext(Dispatchers.Main) {
                isPlaying = false
            }
            pauseInBackground()
            onPlaybackEnded?.invoke()
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
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            MacNativeBridge.nPlay(ptr)

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
        val ptr = playerPtr
        if (ptr == 0L) return

        try {
            MacNativeBridge.nPause(ptr)

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

            val ptr = playerPtr
            if (ptr == 0L) return
            MacNativeBridge.nSeekTo(ptr, seekTime.toDouble())

            if (isPlaying) {
                MacNativeBridge.nPlay(ptr)
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

        // Clear the pointer atomically so no background task can use it
        val ptrToDispose = playerPtrAtomic.getAndSet(0L)

        // Release bitmaps on the frame dispatcher (rendering accesses them there)
        // then dispose the native player — all on a background thread to avoid
        // blocking the main/UI thread.
        Thread {
            try {
                // Close bitmaps (not thread-safe with rendering, but frame updates
                // are already cancelled above and playerPtr is zeroed)
                skiaBitmapA?.close()
                skiaBitmapB?.close()
                skiaBitmapA = null
                skiaBitmapB = null
                skiaBitmapWidth = 0
                skiaBitmapHeight = 0
                nextSkiaBitmapA = true
            } catch (e: Exception) {
                macLogger.e { "Error releasing bitmaps: ${e.message}" }
            }

            if (ptrToDispose != 0L) {
                macLogger.d { "dispose() - Disposing native player" }
                try {
                    MacNativeBridge.nDisposePlayer(ptrToDispose)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    macLogger.e { "Error disposing player: ${e.message}" }
                }
            }
        }.start()

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

    /**
     * Sets an error in a consistent way, ensuring it's always set on the main thread.
     * For synchronous calls, this will block until the error is set.
     */
    private fun setPlayerError(error: VideoPlayerError) {
        macLogger.e { "setPlayerError() - Setting error: $error" }

        // For properties that need to be updated on the main thread,
        // use runBlocking to ensure the update happens immediately
        runBlocking {
            withContext(Dispatchers.Main) {
                isLoading = false
                this@MacVideoPlayerState.error = error
            }
        }
    }

    /** Handles errors by updating the state and logging the error. */
    private suspend fun handleError(e: Exception) {
        macLogger.e { "handleError() - Player error: ${e.message}" }

        // Since this is called from a suspend function, we can use withContext directly
        withContext(Dispatchers.Main) {
            isLoading = false
            error = VideoPlayerError.SourceError("Error: ${e.message}")
        }
    }

    /** Retrieves the current playback time from the native player. */
    private suspend fun getPositionSafely(): Double {
        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            MacNativeBridge.nGetCurrentTime(ptr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error getting position: ${e.message}" }
            0.0
        }
    }

    /** Retrieves the total duration of the video from the native player. */
    private suspend fun getDurationSafely(): Double {
        val ptr = playerPtr
        if (ptr == 0L) return 0.0
        return try {
            MacNativeBridge.nGetVideoDuration(ptr)
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
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                MacNativeBridge.nSetVolume(ptr, _volumeState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying volume: ${e.message}" }
            }
        }
    }

    /**
     * Applies the current playback speed setting to the native player. If no player
     * is available, the speed is simply stored in _playbackSpeedState and will be
     * applied when the player is initialized.
     */
    private suspend fun applyPlaybackSpeed() {
        val ptr = playerPtr
        if (ptr != 0L) {
            try {
                MacNativeBridge.nSetPlaybackSpeed(ptr, _playbackSpeedState.value)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying playback speed: ${e.message}" }
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
        macLogger.d { "clearError() - Clearing error" }

        // Use runBlocking to ensure the error is cleared immediately
        // This is important for tests that expect the error to be cleared synchronously
        runBlocking {
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

    /**
     * Called when the player surface is resized. Debounces rapid events and
     * asks the native layer to decode at the surface size instead of native
     * resolution, saving significant memory for high-resolution video.
     */
    fun onResized(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            ioScope.launch {
                delay(120)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
                }
            }
    }

    /**
     * Asks the native layer to produce frames at the display surface size
     * instead of full native resolution. Saves significant memory for 4K+ video.
     */
    private suspend fun applyOutputScaling() {
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return
        val ptr = playerPtr
        if (ptr == 0L) return

        MacNativeBridge.nSetOutputSize(ptr, sw, sh)
    }
}
