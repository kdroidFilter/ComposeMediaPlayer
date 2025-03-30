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
import java.io.File

/**
 * Optimized Windows implementation for offscreen video playback using the OffscreenPlayer DLL via JNA.
 * This version enhances performance for 4K video playback by reducing CPU usage, optimizing frame handling,
 * and improving audio-video synchronization.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State variables with Compose mutable state for UI reactivity
    var isInitialized by mutableStateOf(false)
        private set

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            // player.SetVolume(_volume) // Assuming MediaFoundationLib supports volume control
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
        set(value) {
            _userDragging = value
        }

    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    override val leftLevel: Float get() = 0f // Placeholder for audio levels
    override val rightLevel: Float get() = 0f

    // Current video frame displayed via Skia Bitmap
    var currentFrame: Bitmap? by mutableStateOf(null)
        private set

    // Error handling
    private var _error: VideoPlayerError? = null
    override val error: VideoPlayerError? get() = _error
    override fun clearError() {
        _error = null
        errorMessage = null
    }

    override val metadata: VideoMetadata = VideoMetadata()
    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks: MutableList<SubtitleTrack> = mutableListOf()
    override fun selectSubtitleTrack(track: SubtitleTrack?) {}
    override fun disableSubtitles() {}

    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    override fun showMedia() {
        _hasMedia = true
    }

    override fun hideMedia() {
        _hasMedia = false
    }

    var errorMessage: String? by mutableStateOf(null)
        private set

    // Coroutine job for video playback
    private var videoJob: Job? = null

    // Video dimensions and frame rate
    var videoWidth: Int = 0
    var videoHeight: Int = 0
    private var frameRate: Float = 30f
    private val frameIntervalMs: Long
        get() = (1000 / frameRate).toLong()

    // Reusable Bitmap to avoid allocating a new one for every frame
    private var reusableBitmap: Bitmap? = null

    // Reusable ByteArray for frame data to reduce memory allocations
    private var reusableByteArray: ByteArray? = null

    // Dummy counter to force recomposition when updating the bitmap
    private var _frameCounter by mutableStateOf(0)
    // Expose frameCounter so that the composable can observe it
    val frameCounter: Int get() = _frameCounter

    init {
        // Initialize Media Foundation on object creation
        val hr = player.InitMediaFoundation()
        isInitialized = (hr >= 0)
        if (!isInitialized) {
            setError("InitMediaFoundation failed (hr=0x${hr.toString(16)})")
        }
    }

    /**
     * Clean up resources when disposing of the player.
     */
    override fun dispose() {
        videoJob?.cancel()
        player.CloseMedia()
        isInitialized = false
        _isPlaying = false
        currentFrame = null
        scope.cancel()
    }

    /**
     * Opens a media URI, sets up video parameters, allocates resources, and starts playback.
     *
     * @param uri the media URI to open.
     */
    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player not initialized.")
            return
        }

        // Reset previous state and cancel any ongoing playback job
        videoJob?.cancel()
        player.CloseMedia()
        currentFrame = null
        _currentTime = 0.0
        _progress = 0f

        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("File not found: $uri")
            return
        }

        // Open the media file using the native library
        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            setError("OpenMedia($uri) failed (hr=0x${hrOpen.toString(16)})")
            return
        }

        _hasMedia = true
        _isPlaying = false
        isLoading = true

        // Retrieve video dimensions from the native library
        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = wRef.value.takeIf { it > 0 } ?: 1280
        videoHeight = hRef.value.takeIf { it > 0 } ?: 720

        // Allocate the reusable ByteArray once using the video dimensions (RGB32: 4 bytes per pixel)
        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)

        // Retrieve the media duration in seconds
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) return
        _duration = durationRef.value / 10000000.0

        // Retrieve the video frame rate for synchronization
        val numRef = IntByReference()
        val denomRef = IntByReference()
        val hrFrameRate = player.GetVideoFrameRate(numRef, denomRef)
        frameRate = if (hrFrameRate >= 0 && denomRef.value != 0) {
            numRef.value.toFloat() / denomRef.value.toFloat()
        } else {
            30f // Default frame rate if not available
        }
        println("Frame rate: $frameRate fps")

        // Start video playback
        play()

        // Launch the video playback loop using a coroutine
        videoJob = scope.launch {
            while (isActive && !player.IsEOF()) {
                if (!_isPlaying) return@launch

                // Read a video frame from the native buffer
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0) return@launch

                val pFrame = ptrRef.value
                val dataSize = sizeRef.value
                if (pFrame == null || dataSize <= 0) return@launch

                // Reuse the allocated ByteArray to store the frame data.
                // If the current ByteArray is too small (unlikely if resolution is constant), reallocate it.
                if (reusableByteArray == null || reusableByteArray!!.size < dataSize) {
                    reusableByteArray = ByteArray(videoWidth * videoHeight * 4)
                }
                // Use a ByteBuffer to obtain a direct mapping to the native memory without an initial copy.
                val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                // Copy the data from the ByteBuffer into the reusable ByteArray.
                byteBuffer.get(reusableByteArray, 0, dataSize)
                // Immediately unlock the native video frame buffer.
                player.UnlockVideoFrame()

                // Initialize the reusable Bitmap if it hasn't been created yet.
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
                // Update the Bitmap with the new frame pixel data from the reusable ByteArray.
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

                // Update UI state on the main thread and force recomposition by incrementing the frame counter.
                withContext(Dispatchers.Main) {
                    _frameCounter++  // Triggers UI update.
                    currentFrame = reusableBitmap
                }

                // Update the current playback position and progress percentage.
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat()
                }
                isLoading = false

                // Small delay before reading the next frame.
                delay(1)

                // If looping is enabled and the end-of-file is reached, restart playback.
                if (player.IsEOF() && _loop) {
                    player.SeekMedia(0)
                    _currentTime = 0.0
                    _progress = 0f
                    play()
                }
            }
        }
    }

    /**
     * Start or resume video and audio playback.
     */
    override fun play() {
        if (!isInitialized || !_hasMedia) return
        _isPlaying = true
        player.StartAudioPlayback() // Start audio playback for better synchronization.
    }

    /**
     * Pause video and audio playback.
     */
    override fun pause() {
        if (!_isPlaying) return
        _isPlaying = false
        player.StopAudioPlayback()
    }

    /**
     * Stop playback and reset the displayed frame.
     */
    override fun stop() {
        _isPlaying = false
        player.StopAudioPlayback()
        currentFrame = null
        videoJob?.cancel()
    }

    /**
     * Seek to a new position in the media based on the slider value (0 to 1000).
     *
     * @param value the slider value representing the new position.
     */
    override fun seekTo(value: Float) {
        if (!_hasMedia) return
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) {
            setError("GetMediaDuration failed (hr=0x${hrDuration.toString(16)})")
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
        }
    }

    /**
     * Set an error state with the specified message.
     *
     * @param msg the error message.
     */
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }
}
