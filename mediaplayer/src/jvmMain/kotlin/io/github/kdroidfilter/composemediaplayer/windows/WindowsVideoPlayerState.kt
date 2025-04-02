package io.github.kdroidfilter.composemediaplayer.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Windows implementation for offscreen video playback using Media Foundation.
 * The following code tries to be more robust in handling crashes and unexpected conditions:
 * - Added try/catch for native calls
 * - Enhanced protection with read/write locks
 * - Improved coroutine management for playback
 * - Better player state handling
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {

    // Reference to the native Media Foundation library (via JNA)
    private val player = MediaFoundationLib.INSTANCE

    // Scope for managing video playback coroutine
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Initialization state of Media Foundation
    private var isInitialized by mutableStateOf(false)

    // Indicates if a media file is loaded
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // Indicates if playback is ongoing
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    // Audio volume (0f..1f)
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
        }

    // Current time (in seconds) and total media duration
    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)

    // Progress of the media (0f..1f)
    private var _progress by mutableStateOf(0f)

    // Handling the slider for the seekBar
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
            if (!userDragging) {
                seekTo(value)
            }
        }

    // Indicator for whether the user is manually dragging the slider
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }

    // Indicates if playback should loop when it reaches the end
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    // Left/right audio levels (not implemented here)
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f

    // Error handling
    private var _error: VideoPlayerError? = null
    override val error: VideoPlayerError?
        get() = _error

    override fun clearError() {
        _error = null
        errorMessage = null
    }

    // Variables related to video (current bitmap, lock, etc.)
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = ReentrantReadWriteLock()

    // Converts Skia Bitmap to Compose ImageBitmap (read-protected)
    fun getLockedComposeImageBitmap(): ImageBitmap? = bitmapLock.read {
        _currentFrame?.asComposeImageBitmap()
    }

    // Metadata and subtitles (not handled for now)
    override val metadata: VideoMetadata = VideoMetadata()
    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}

    // Loading indicator
    override var isLoading by mutableStateOf(false)
        private set

    // Display current time and duration as text
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    // Error message (text) for the UI
    var errorMessage: String? by mutableStateOf(null)
        private set

    // Job managing video playback in a coroutine
    private var videoJob: Job? = null

    // Video size
    var videoWidth: Int = 0
    var videoHeight: Int = 0

    // Reusable memory area for each new frame
    private var reusableBitmap: Bitmap? = null
    private var reusableByteArray: ByteArray? = null

    // Frame read counter (for debug or stats)
    private var _frameCounter by mutableStateOf(0)
    val frameCounter: Int get() = _frameCounter

    // Indicates if resizing is in progress
    var isResizing by mutableStateOf(false)
        private set

    // For debouncing the resize event
    private var resizeJob: Job? = null

    init {
        // Attempt to initialize Media Foundation
        try {
            val hr = player.InitMediaFoundation()
            isInitialized = (hr >= 0)
            if (!isInitialized) {
                setError("Media Foundation initialization failed (hr=0x${hr.toString(16)})")
            }
        } catch (e: Exception) {
            setError("Exception during initialization: ${e.message}")
        }
    }

    /**
     * Cleanup and release resources.
     * Call when the player is no longer needed.
     */
    override fun dispose() {
        try {
            videoJob?.cancel()
            player.CloseMedia()
        } catch (e: Exception) {
            // Ignore or log exception
        } finally {
            isInitialized = false
            _isPlaying = false
            _currentFrame = null
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

        // Cancel previous video playback (if any)
        videoJob?.cancel()
        videoJob = null

        // Attempt to close the previous media
        try {
            player.CloseMedia()
        } catch (e: Exception) {
            // Ignore or log exception
        }

        _currentFrame = null
        _currentTime = 0.0
        _progress = 0f

        // Check file existence if local path
        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("File not found: $uri")
            return
        }

        // Attempt to open the media
        try {
            val hrOpen = player.OpenMedia(WString(uri))
            if (hrOpen < 0) {
                setError("Failed to open media (hr=0x${hrOpen.toString(16)}) : $uri")
                return
            }
            _hasMedia = true
            _isPlaying = false
            isLoading = true
        } catch (e: Exception) {
            setError("Exception during media opening: ${e.message}")
            return
        }

        // Retrieve media size
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

        // Allocate reusable buffer
        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)

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
            setError("Exception during duration retrieval: ${e.message}")
            return
        }

        // Start playback
        play()

        // Create video playback job
        videoJob = scope.launch {
            while (isActive) {
                // Check if media has ended
                try {
                    if (player.IsEOF()) {
                        // If end of file and loop is active, restart from the beginning
                        if (loop) {
                            try {
                                player.SeekMedia(0)
                                _currentTime = 0.0
                                _progress = 0f
                                play()
                            } catch (e: Exception) {
                                setError("Error during SeekMedia for loop: ${e.message}")
                            }
                        }
                        // If no loop, exit the loop
                        if (!loop) break
                    }
                } catch (e: Exception) {
                    setError("Exception during EOF check: ${e.message}")
                    break
                }

                // If not playing, wait briefly
                if (!_isPlaying) {
                    delay(16)
                    continue
                }

                // Read a video frame
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

                    // Ensure buffer is large enough
                    if (reusableByteArray == null || reusableByteArray!!.size < dataSize) {
                        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)
                    }

                    // Copy data to reusable buffer
                    val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                    byteBuffer.get(reusableByteArray, 0, dataSize)

                    // Signal that the native frame is no longer needed
                    player.UnlockVideoFrame()

                    // Skip update if resizing
                    if (isResizing) {
                        delay(8)
                        continue
                    }

                    // Update shared bitmap
                    bitmapLock.write {
                        if (reusableBitmap == null) {
                            reusableBitmap = Bitmap().apply {
                                allocPixels(
                                    ImageInfo(
                                        width = videoWidth,
                                        height = videoHeight,
                                        colorType = ColorType.BGRA_8888,
                                        alphaType = ColorAlphaType.OPAQUE
                                    )
                                )
                            }
                        }
                        reusableBitmap!!.installPixels(
                            ImageInfo(
                                width = videoWidth,
                                height = videoHeight,
                                colorType = ColorType.BGRA_8888,
                                alphaType = ColorAlphaType.OPAQUE
                            ),
                            reusableByteArray,
                            videoWidth * 4
                        )
                        _currentFrame = reusableBitmap
                    }

                    // Update frame counter
                    withContext(Dispatchers.Main) {
                        _frameCounter++
                    }
                } catch (e: Exception) {
                    setError("Error during frame read: ${e.message}")
                    delay(16)
                    continue
                }

                // Update current time and progress
                try {
                    val posRef = LongByReference()
                    if (player.GetMediaPosition(posRef) >= 0) {
                        _currentTime = posRef.value / 10000000.0
                        _progress = (_currentTime / _duration).toFloat()
                    }
                } catch (e: Exception) {
                    // Ignore or log error
                }

                // No longer loading if we reach here
                isLoading = false

                delay(16)
            }
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
            setError("Error during audio playback start: ${e.message}")
        }
    }

    override fun pause() {
        if (!_isPlaying) return
        try {
            val result = player.SetPlaybackState(false)
            if (result < 0) {
                setError("Error pausing playback (hr=0x${result.toString(16)})")
                return
            }
            _isPlaying = false
        } catch (e: Exception) {
            setError("Error during pause: ${e.message}")
        }
    }

    /**
     * Stops playback and resets certain states.
     */
    override fun stop() {
        try {
            _isPlaying = false
            _currentFrame = null
            videoJob?.cancel()
        } catch (e: Exception) {
            setError("Error during stop: ${e.message}")
        }
    }

    /**
     * Seeks to a certain point (value is based on sliderPos).
     */
    override fun seekTo(value: Float) {
        if (!_hasMedia) return
        try {
            // First pause any current playback
            val wasPlaying = _isPlaying
            if (wasPlaying) {
                pause()
            }

            val durationRef = LongByReference()
            val hrDuration = player.GetMediaDuration(durationRef)
            if (hrDuration < 0) {
                setError("Failed to retrieve duration (hr=0x${hrDuration.toString(16)})")
                return
            }
            val duration100ns = durationRef.value
            val fraction = value / 1000f
            val newPosition = (duration100ns * fraction).toLong()

            val hrSeek = player.SeekMedia(newPosition)
            if (hrSeek < 0) {
                setError("SeekMedia failed (hr=0x${hrSeek.toString(16)})")
            } else {
                _currentTime = newPosition / 10000000.0
                _progress = fraction

                // Resume playback if it was playing
                if (wasPlaying) {
                    play()
                }
            }
        } catch (e: Exception) {
            setError("Exception during SeekMedia: ${e.message}")
        }
    }

    /**
     * To be called when the window is resized. Introduces a small delay before resuming frame updates.
     */
    fun onResized() {
        isResizing = true
        resizeJob?.cancel()
        resizeJob = scope.launch {
            // Wait 200 ms before resuming frame updates
            delay(200)
            isResizing = false
        }
    }

    /**
     * Utility method for setting error message in a centralized way.
     */
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }

    // Empty methods (or not needed in this context)
    override fun hideMedia() {}
    override fun showMedia() {}
}
