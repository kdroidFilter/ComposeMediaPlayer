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
 * This version minimizes extra delays and CPU usage for 4K video playback.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Initialization state
    var isInitialized by mutableStateOf(false)
        private set

    // Media open state
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    // Playback state
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
        }

    // Playback timing and progress
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
        set(value) { _userDragging = value }

    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) { _loop = value }

    override val leftLevel: Float get() = 0f
    override val rightLevel: Float get() = 0f

    // Current video frame to be displayed
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

    // Loading indicator and time texts
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    // Show/hide media
    override fun showMedia() { _hasMedia = true }
    override fun hideMedia() { _hasMedia = false }

    // Error message string for logging
    var errorMessage: String? by mutableStateOf(null)
        private set

    // Video playback coroutine job
    private var videoJob: Job? = null

    // Detected video dimensions
    var videoWidth: Int = 0
    var videoHeight: Int = 0

    // Frame rate information (frames per second)
    private var frameRate: Float = 30f
    // Calculate frame interval in milliseconds (for synchronization)
    private val defaultFrameIntervalMs: Long
        get() = (1000 / frameRate).toLong()

    init {
        // Initialize Media Foundation
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
    }

    override fun openUri(uri: String) {
        // Ensure Media Foundation is initialized
        if (!isInitialized) {
            setError("Player not initialized.")
            return
        }

        videoJob?.cancel()
        player.CloseMedia()

        // Check if local file exists
        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("File not found: $uri")
            return
        }

        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            setError("OpenMedia($uri) failed (hr=0x${hrOpen.toString(16)})")
            return
        }

        _hasMedia = true
        _isPlaying = false

        // Retrieve video dimensions
        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = if (wRef.value > 0) wRef.value else 1280
        videoHeight = if (hRef.value > 0) hRef.value else 720

        // Retrieve media duration (in seconds)
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration >= 0) {
            _duration = durationRef.value / 10000000.0
        }

        // Retrieve video frame rate
        val numRef = IntByReference()
        val denomRef = IntByReference()
        val hrFrameRate = player.GetVideoFrameRate(numRef, denomRef)
        if (hrFrameRate >= 0 && denomRef.value != 0) {
            frameRate = numRef.value.toFloat() / denomRef.value.toFloat()
            println("Frame rate: $frameRate fps")
        } else {
            println("Unable to retrieve frame rate, defaulting to 30 fps")
            frameRate = 30f
        }

        // Start video playback
        play()

        // Launch video playback loop coroutine with minimal delays
        videoJob = scope.launch {
            while (isActive && !player.IsEOF()) {
                if (_isPlaying) {
                    val ptrRef = PointerByReference()
                    val sizeRef = IntByReference()
                    val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                    if (hrFrame >= 0) {
                        val pFrame = ptrRef.value
                        val dataSize = sizeRef.value
                        if (pFrame != null && dataSize > 0) {
                            val byteArray = ByteArray(dataSize)
                            pFrame.read(0, byteArray, 0, dataSize)
                            // Unlock the frame buffer after reading
                            player.UnlockVideoFrame()
                            // Convert raw data to a Skia Bitmap (BGRA format)
                            val bmp = Bitmap().apply {
                                allocPixels(
                                    ImageInfo(
                                        width = videoWidth,
                                        height = videoHeight,
                                        colorType = ColorType.BGRA_8888,
                                        alphaType = ColorAlphaType.OPAQUE
                                    )
                                )
                                installPixels(imageInfo, byteArray, rowBytes = videoWidth * 4)
                            }
                            // Update current frame and playback position on the main thread
                            withContext(Dispatchers.Main) {
                                currentFrame = bmp
                                val posRef = LongByReference()
                                if (player.GetMediaPosition(posRef) >= 0) {
                                    _currentTime = posRef.value / 10000000.0
                                }
                            }
                        } else {
                            yield()
                        }
                    } else {
                        yield()
                    }
                } else {
                    delay(20)
                }
            }
        }
    }

    override fun play() {
        if (!isInitialized || !_hasMedia) return
        _isPlaying = true
        player.StartAudioPlayback() // Start audio playback
    }

    override fun pause() {
        _isPlaying = false
        player.StopAudioPlayback()
    }

    override fun stop() {
        _isPlaying = false
        player.StopAudioPlayback()
        currentFrame = null
    }

    /**
     * Seek to a new position based on a slider value (0 to 1000).
     */
    override fun seekTo(value: Float) {
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
        }
    }

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
    }
}
