package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Windows implementation for offscreen video playback using Media Foundation,
 * with an object pooling system for reusing Bitmap and ByteArray objects.
 *
 * Improvements include:
 * - Robust handling of native calls and exceptions
 * - Read/Write locks for bitmap synchronization
 * - Coroutine-based video reading and rendering
 * - Circular queue for frame processing with pooling to limit allocations
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {

    // Reference to the native Media Foundation library (via JNA)
    private val player = MediaFoundationLib.INSTANCE

    // Coroutine scope for managing video playback
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Indicates whether Media Foundation has been successfully initialized
    private var isInitialized by mutableStateOf(false)

    // Indicates if a media file is loaded
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // Indicates if playback is currently active
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    // Audio volume (range 0f..1f)
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
        }

    // Current playback time (in seconds) and total media duration
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)

    // Media progress (range 0f..1f)
    private var _progress by mutableStateOf(0f)

    // Slider position for the seek bar
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
            if (!userDragging) {
                seekTo(value)
            }
        }

    // Flag to indicate if the user is interacting with the slider
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }

    // Indicates whether the media should loop
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    // Audio levels for left/right channels (not implemented here)
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f

    // Error management
    private var _error: VideoPlayerError? = null
    override val error: VideoPlayerError?
        get() = _error

    override fun clearError() {
        _error = null
        errorMessage = null
    }

    // Variables related to the video (current bitmap, synchronization lock, etc.)
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = ReentrantReadWriteLock()

    // Converts Skia Bitmap to Compose ImageBitmap (protected by a read lock)
    fun getLockedComposeImageBitmap(): ImageBitmap? = bitmapLock.read {
        _currentFrame?.asComposeImageBitmap()
    }

    // Metadata and subtitles (currently not handled)
    override val metadata: VideoMetadata = VideoMetadata()
    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}

    // Loading indicator
    override var isLoading by mutableStateOf(false)
        private set

    // Display texts for current position and total duration
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    // Error message for the user interface
    private var errorMessage: String? by mutableStateOf(null)
        private set

    // Job managing video playback in a coroutine
    private var videoJob: Job? = null

    // Video dimensions
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)


    // Circular queue for frame processing
    private val frameQueueCapacity = 5 // Adjust based on memory constraints and playback needs
    private val frameQueue = ArrayBlockingQueue<FrameData>(frameQueueCapacity)
    private val queueMutex = Mutex()

    // Pools for reusing Bitmap and ByteArray objects
    private val poolCapacity = 5
    private val bitmapPool = ArrayBlockingQueue<Bitmap>(poolCapacity)
    private val byteArrayPool = ArrayBlockingQueue<ByteArray>(poolCapacity)

    // Data class to store information for each frame
    data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double,
        val buffer: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameData) return false
            return bitmap == other.bitmap &&
                    timestamp == other.timestamp
        }
        override fun hashCode(): Int {
            var result = bitmap.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }


    // Indicates if a resizing operation is in progress
    private var isResizing by mutableStateOf(false)
        private set

    // To prevent multiple rapid resize events
    private var resizeJob: Job? = null

    init {
        // Attempt to initialize Media Foundation
        try {
            val hr = player.InitMediaFoundation()
            isInitialized = (hr >= 0)
            if (!isInitialized) {
                setError("Failed to initialize Media Foundation (hr=0x${hr.toString(16)})")
            }
        } catch (e: Exception) {
            setError("Exception during initialization: ${e.message}")
        }
    }

    /**
     * Cleanup and release resources.
     * Call this method when the player is no longer needed.
     */
    override fun dispose() {
        try {
            // D'abord, arrêter la lecture
            _isPlaying = false
            try {
                player.SetPlaybackState(false)
            } catch (e: Exception) {
                println("Error stopping playback during dispose: ${e.message}")
            }

            // Ensuite, annuler les coroutines sans bloquer
            videoJob?.cancel()

            // Nettoyer les ressources Media Foundation
            try {
                player.CloseMedia()
            } catch (e: Exception) {
                println("Error closing media: ${e.message}")
            }

            // Nettoyer les structures de données
            bitmapLock.write {
                _currentFrame = null
            }

            // Vider les pools sans bloquer
            scope.launch {
                queueMutex.withLock {
                    frameQueue.clear()
                    bitmapPool.clear()
                    byteArrayPool.clear()
                }
            }

            // Finaliser Media Foundation en dernier
            try {
                player.ShutdownMediaFoundation()
            } catch (e: Exception) {
                println("Error shutting down Media Foundation: ${e.message}")
            }
        } catch (e: Exception) {
            println("Error during dispose: ${e.message}")
        } finally {
            // Mettre à jour l'état
            isInitialized = false
            _isPlaying = false
            _hasMedia = false

            // Annuler le scope principal en dernier
            scope.cancel()
        }
    }

    /**
     * Opens a media file from a URI (local path or URL).
     */
    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player is not initialized.")
            return
        }

        // Cancel any previous playback job
        runBlocking {
            videoJob?.cancelAndJoin()
            videoJob = null
        }

        // Clear the frame queue
        runBlocking {
            queueMutex.withLock {
                // Recycler correctement toutes les frames
                while (frameQueue.isNotEmpty()) {
                    val oldFrame = frameQueue.poll()
                    oldFrame?.let {
                        bitmapPool.offer(it.bitmap)
                        byteArrayPool.offer(it.buffer)
                    }
                }
            }
        }

        // Close the previous media, if any
        try {
            player.CloseMedia()
            // Ajouter un petit délai pour laisser le temps au backend natif de se nettoyer
            runBlocking { delay(100) }
        } catch (e: Exception) {
            // Log l'exception
            println("Error closing previous media: ${e.message}")
        }


        _currentFrame = null
        _currentTime = 0.0
        _progress = 0f

        // Check file existence for local paths
        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("File not found: $uri")
            return
        }

        // Attempt to open the media
        try {
            val hrOpen = player.OpenMedia(WString(uri))
            if (hrOpen < 0) {
                setError("Failed to open media (hr=0x${hrOpen.toString(16)}): $uri")
                return
            }
            _hasMedia = true
            _isPlaying = false
            isLoading = true
        } catch (e: Exception) {
            setError("Exception while opening media: ${e.message}")
            return
        }

        // Retrieve video dimensions
        try {
            val wRef = IntByReference()
            val hRef = IntByReference()
            player.GetVideoSize(wRef, hRef)
            videoWidth = if (wRef.value > 0) wRef.value else 1280
            videoHeight = if (hRef.value > 0) hRef.value else 720
        } catch (e: Exception) {
            videoWidth = 1280
            videoHeight = 720
        }

        // Retrieve media duration
        try {
            val durationRef = LongByReference()
            val hrDuration = player.GetMediaDuration(durationRef)
            if (hrDuration < 0) {
                setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                return
            }
            _duration = durationRef.value / 10000000.0
        } catch (e: Exception) {
            setError("Exception while retrieving duration: ${e.message}")
            return
        }

        // Start playback
        play()

        // Create the video playback job with a circular queue and pooling
        videoJob = scope.launch {
            // Launch a producer coroutine to fill the frame queue
            launch { produceFrames() }
            // Launch a consumer coroutine to display frames
            launch { consumeFrames() }
        }
    }

    /**
     * Producer coroutine that reads video frames and adds them to the frame queue.
     * Uses object pools to retrieve reusable ByteArray and Bitmap objects.
     */
    private suspend fun produceFrames() {
        // Calculate the required buffer size
        val requiredBufferSize = videoWidth * videoHeight * 4
        while (scope.isActive) {
            // Check for end-of-media and handle looping
            try {
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
                    }
                    if (!loop) break
                }
            } catch (e: Exception) {
                setError("Exception while checking end of media: ${e.message}")
                break
            }

            // If playback is not active, wait briefly
            if (!_isPlaying) {
                delay(16)
                continue
            }

            // If the frame queue is full, wait a bit
            if (frameQueue.size >= frameQueueCapacity) {
                delay(16)
                continue
            }

            try {
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0) {
                    delay(16)
                    continue
                }

                val pFrame = ptrRef.value
                val dataSize = sizeRef.value
                if (pFrame == null || dataSize <= 0) {
                    delay(16)
                    continue
                }

                // Retrieve or create a reusable buffer
                val frameBytes = byteArrayPool.poll()?.let {
                    if (it.size >= requiredBufferSize) it else ByteArray(requiredBufferSize)
                } ?: ByteArray(requiredBufferSize)

                // Copy data from native memory into the buffer
                val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                byteBuffer.get(frameBytes, 0, dataSize)

                // Signal that the native frame is no longer needed
                player.UnlockVideoFrame()

                // Retrieve or create a reusable Bitmap
                val frameBitmap = bitmapPool.poll() ?: Bitmap().apply {
                    allocPixels(
                        ImageInfo(
                            width = videoWidth,
                            height = videoHeight,
                            colorType = ColorType.BGRA_8888,
                            alphaType = ColorAlphaType.OPAQUE
                        )
                    )
                }
                // Install the buffer content into the Bitmap
                frameBitmap.installPixels(
                    ImageInfo(
                        width = videoWidth,
                        height = videoHeight,
                        colorType = ColorType.BGRA_8888,
                        alphaType = ColorAlphaType.OPAQUE
                    ),
                    frameBytes,
                    videoWidth * 4
                )

                // Get the current time for this frame
                var frameTime = 0.0
                try {
                    val posRef = LongByReference()
                    if (player.GetMediaPosition(posRef) >= 0) {
                        frameTime = posRef.value / 10000000.0
                    }
                } catch (e: Exception) {
                    // Ignore timing errors
                }

                // Create a FrameData object with the reusable Bitmap and buffer
                val frameData = FrameData(frameBitmap, frameTime, frameBytes)
                queueMutex.withLock {
                    // If the queue is full, remove the oldest frame and recycle its resources
                    if (frameQueue.remainingCapacity() == 0) {
                        val oldFrame = frameQueue.poll()
                        oldFrame?.let {
                            bitmapPool.offer(it.bitmap)
                            byteArrayPool.offer(it.buffer)
                        }
                    }
                    frameQueue.offer(frameData)
                }
            } catch (e: Exception) {
                setError("Error reading frame: ${e.message}")
                delay(16)
                continue
            }
        }
    }

    /**
     * Consumer coroutine that displays frames from the frame queue.
     * After displaying, the resources of the previous frame are returned to the pools.
     */
    private suspend fun consumeFrames() {
        while (true) {
            // Ensure the coroutine has not been cancelled
            scope.ensureActive()

            if (!_isPlaying || isResizing) {
                delay(16)
                continue
            }

            var frameData: FrameData? = null
            queueMutex.withLock {
                if (frameQueue.isNotEmpty()) {
                    frameData = frameQueue.poll()
                }
            }

            if (frameData != null) {
                // Update the current frame by recycling the old bitmap
                bitmapLock.write {
                    val oldBitmap = _currentFrame
                    _currentFrame = frameData!!.bitmap
                    if (oldBitmap != null) {
                        bitmapPool.offer(oldBitmap)
                    }
                }
                // Update current time and progress
                _currentTime = frameData!!.timestamp
                _progress = (_currentTime / _duration).toFloat()

                // End the loading state
                isLoading = false

                // Return the buffer of the consumed frame to the pool
                queueMutex.withLock {
                    byteArrayPool.offer(frameData!!.buffer)
                }
            }

            delay(16) // Approximately 60 FPS
        }
    }

    override fun play() {
        if (!isInitialized || !_hasMedia) return
        try {
            val result = player.SetPlaybackState(true)
            if (result < 0) {
                setError("Error starting playback (hr=0x${result.toString(16)})")
                return
            }
            _isPlaying = true
        } catch (e: Exception) {
            setError("Error starting audio playback: ${e.message}")
        }
    }

    override fun pause() {
        if (!_isPlaying) return
        try {
            val result = player.SetPlaybackState(false)
            if (result < 0) {
                setError("Error pausing (hr=0x${result.toString(16)})")
                return
            }
            _isPlaying = false
        } catch (e: Exception) {
            setError("Error pausing: ${e.message}")
        }
    }

    /**
     * Stops playback and resets relevant states.
     */
    override fun stop() {
        try {
            _isPlaying = false
            _currentFrame = null
            videoJob?.cancel()
            player.SetPlaybackState(false)
            _hasMedia = false
            _isPlaying = false
            _progress = 0f
            _currentTime = 0.0
            isLoading = false
            errorMessage = null
            _error = null
        } catch (e: Exception) {
            setError("Error stopping playback: ${e.message}")
        }
    }

    /**
     * Seeks to a specific position (value based on sliderPos).
     */
    override fun seekTo(value: Float) {
        if (!_hasMedia) return
        try {
            val wasPlaying = _isPlaying
            if (wasPlaying) {
                pause()
            }

            // Clear frame queue and reset current frame
            scope.launch {
                queueMutex.withLock {
                    frameQueue.forEach { frameData ->
                        bitmapPool.offer(frameData.bitmap)
                        byteArrayPool.offer(frameData.buffer)
                    }
                    frameQueue.clear()
                }
                bitmapLock.write {
                    _currentFrame = null
                }
            }

            val durationRef = LongByReference()
            val hrDuration = player.GetMediaDuration(durationRef)
            if (hrDuration < 0) return

            val newPosition = (durationRef.value * (value / 1000f)).toLong()
            val hrSeek = player.SeekMedia(newPosition)

            if (hrSeek >= 0) {
                _currentTime = newPosition / 10000000.0
                _progress = value / 1000f
            }

            if (wasPlaying) {
                play()
            }
        } catch (e: Exception) {
            setError("Exception during seeking: ${e.message}")
        }
    }

    /**
     * Called when the window is resized.
     * Introduces a short delay before resuming frame updates.
     */
    fun onResized() {
        isResizing = true
        // Clear the frame queue to discard frames generated during resizing
        scope.launch {
            queueMutex.withLock {
                while (frameQueue.isNotEmpty()) {
                    val oldFrame = frameQueue.poll()
                    oldFrame?.let {
                        // Recycle the bitmap and buffer back into their pools
                        bitmapPool.offer(it.bitmap)
                        byteArrayPool.offer(it.buffer)
                    }
                }
            }
        }
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(200)
            isResizing = false
        }
    }

    /**
     * Utility method to centralize error handling.
     */
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }

    // Empty methods (not needed in this context)
    override fun hideMedia() {}
    override fun showMedia() {}
}
