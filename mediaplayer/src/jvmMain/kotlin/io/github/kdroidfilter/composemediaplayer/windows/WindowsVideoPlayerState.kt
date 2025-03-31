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

/**
 * Windows implementation for offscreen video playback.
 * This version secures access to the reusable Bitmap instance using a lock,
 * and provides a helper function to convert the Bitmap to a Compose ImageBitmap safely.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    // Native MediaFoundation library instance via JNA
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
            // Volume control can be implemented if supported by the native library.
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

    override val leftLevel: Float get() = 0f // Placeholder for audio level
    override val rightLevel: Float get() = 0f

    // Backing field for the current frame (Skia Bitmap)
    // Access to this Bitmap is synchronized via bitmapLock.
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    var currentFrame: Bitmap?
        get() = synchronized(bitmapLock) { _currentFrame }
        private set(value) {
            synchronized(bitmapLock) { _currentFrame = value }
        }

    // Lock object to secure access to the Bitmap
    private val bitmapLock = Any()

    // Helper function to safely convert the current Bitmap to a Compose ImageBitmap
    // The conversion is performed inside a synchronized block to prevent concurrent modifications.
    fun getLockedComposeImageBitmap(): ImageBitmap? {
        return synchronized(bitmapLock) {
            _currentFrame?.asComposeImageBitmap()
        }
    }

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

    // Coroutine job for the video playback loop
    private var videoJob: Job? = null

    // Video dimensions and frame rate
    var videoWidth: Int = 0
    var videoHeight: Int = 0
    private var frameRate: Float = 30f

    // Reusable Bitmap and ByteArray for frame data
    // We update the same Bitmap instance inside a synchronized block.
    private var reusableBitmap: Bitmap? = null
    private var reusableByteArray: ByteArray? = null

    // Frame counter to trigger UI recomposition
    private var _frameCounter by mutableStateOf(0)
    val frameCounter: Int get() = _frameCounter

    init {
        // Initialize Media Foundation when object is created.
        val hr = player.InitMediaFoundation()
        isInitialized = (hr >= 0)
        if (!isInitialized) {
            setError("InitMediaFoundation failed (hr=0x${hr.toString(16)})")
        }
    }

    override fun dispose() {
        videoJob?.cancel()
        player.CloseMedia()
        isInitialized = false
        _isPlaying = false
        currentFrame = null
        scope.cancel()
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player not initialized.")
            return
        }

        // Reset previous state and cancel ongoing playback
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

        // Open media using the native library.
        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            setError("OpenMedia($uri) failed (hr=0x${hrOpen.toString(16)})")
            return
        }

        _hasMedia = true
        _isPlaying = false
        isLoading = true

        // Retrieve video dimensions.
        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = if (wRef.value > 0) wRef.value else 1280
        videoHeight = if (hRef.value > 0) hRef.value else 720

        // Allocate the reusable ByteArray (RGB32: 4 bytes per pixel).
        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)

        // Retrieve media duration (in seconds).
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) return
        _duration = durationRef.value / 10000000.0

        // Retrieve video frame rate.
        val numRef = IntByReference()
        val denomRef = IntByReference()
        val hrFrameRate = player.GetVideoFrameRate(numRef, denomRef)
        frameRate = if (hrFrameRate >= 0 && denomRef.value != 0) {
            numRef.value.toFloat() / denomRef.value.toFloat()
        } else {
            30f
        }
        println("Frame rate: $frameRate fps")

        // Start playback.
        play()

        // Launch the video playback loop using a coroutine.
        videoJob = scope.launch {
            while (isActive && !player.IsEOF()) {
                if (!_isPlaying) return@launch

                // Read a video frame from the native library.
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0) return@launch

                val pFrame = ptrRef.value
                val dataSize = sizeRef.value
                if (pFrame == null || dataSize <= 0) return@launch

                // Ensure the reusable ByteArray is large enough.
                if (reusableByteArray == null || reusableByteArray!!.size < dataSize) {
                    reusableByteArray = ByteArray(videoWidth * videoHeight * 4)
                }
                val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                byteBuffer.get(reusableByteArray, 0, dataSize)
                player.UnlockVideoFrame()

                // Update the reusable Bitmap in a synchronized block.
                synchronized(bitmapLock) {
                    if (reusableBitmap == null) {
                        // Allocate the Bitmap if not yet created.
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
                    // Update the Bitmap with the new frame data.
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
                    // Also update the currentFrame field.
                    _currentFrame = reusableBitmap
                }

                // Update UI state on the main thread.
                withContext(Dispatchers.Main) {
                    _frameCounter++ // Trigger UI recomposition.
                }

                // Update playback position.
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat()
                }
                isLoading = false

                // Small delay to match frame rate.
                delay(16)

                // Looping: if at end-of-stream and looping is enabled, restart playback.
                if (player.IsEOF() && _loop) {
                    player.SeekMedia(0)
                    _currentTime = 0.0
                    _progress = 0f
                    play()
                }
            }
        }
    }

    override fun play() {
        if (!isInitialized || !_hasMedia) return
        _isPlaying = true
        player.StartAudioPlayback() // Start audio to maintain synchronization.
    }

    override fun pause() {
        if (!_isPlaying) return
        _isPlaying = false
        player.StopAudioPlayback()
    }

    override fun stop() {
        _isPlaying = false
        player.StopAudioPlayback()
        currentFrame = null
        videoJob?.cancel()
    }

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

    // Set an error state with the provided message.
    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }
}
