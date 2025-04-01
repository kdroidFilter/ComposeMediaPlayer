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
 * Windows implementation for offscreen video playback.
 * Uses a ReentrantReadWriteLock to protect the reusable Bitmap instance and optimizes memory allocations.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var isInitialized by mutableStateOf(false)

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean get() = _hasMedia

    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean get() = _isPlaying

    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
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

    override val leftLevel: Float get() = 0f
    override val rightLevel: Float get() = 0f

    // Backing field for the current frame (Skia Bitmap), protected by a lock.
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    var currentFrame: Bitmap?
        get() = bitmapLock.read { _currentFrame }
        private set(value) = bitmapLock.write { _currentFrame = value }

    private val bitmapLock = ReentrantReadWriteLock()

    // Safely convert the current Bitmap to a Compose ImageBitmap.
    fun getLockedComposeImageBitmap(): ImageBitmap? = bitmapLock.read {
        _currentFrame?.asComposeImageBitmap()
    }

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

    private var videoJob: Job? = null

    var videoWidth: Int = 0
    var videoHeight: Int = 0

    private var reusableBitmap: Bitmap? = null
    private var reusableByteArray: ByteArray? = null

    private var _frameCounter by mutableStateOf(0)
    val frameCounter: Int get() = _frameCounter

    // Flag to indicate if resizing is happening
    var isResizing by mutableStateOf(false)
        private set

    // Job to debounce resizing events
    private var resizeJob: Job? = null

    init {
        // Initialize Media Foundation
        val hr = player.InitMediaFoundation()
        isInitialized = (hr >= 0)
        if (!isInitialized) {
            handleError("Failed to initialize Media Foundation", hr)
        }
    }

    /**
     * Disposes of the player, cancelling all jobs and releasing resources.
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
     * Opens a media file or stream from the given URI.
     * @param uri The URI of the media to open.
     */
    override fun openUri(uri: String) {
        if (!isInitialized) {
            handleError("Player not initialized")
            return
        }
        videoJob?.cancel()
        player.CloseMedia()
        currentFrame = null
        _currentTime = 0.0
        _progress = 0f

        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            handleError("File not found: $uri")
            return
        }
        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            handleError("Failed to open media: $uri", hrOpen)
            return
        }
        _hasMedia = true
        _isPlaying = false
        isLoading = true

        // Retrieve video dimensions
        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = if (wRef.value > 0) wRef.value else 1280
        videoHeight = if (hRef.value > 0) hRef.value else 720

        // Pre-allocate reusable ByteArray and Bitmap once based on video size
        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)
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

        // Retrieve media duration
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) {
            handleError("Failed to get media duration", hrDuration)
            return
        }
        _duration = durationRef.value / 10000000.0

        play()

        // Launch coroutine to handle frame reading and playback
        videoJob = scope.launch {
            while (isActive && !player.IsEOF()) {
                if (!_isPlaying) {
                    delay(16)
                    continue
                }

                // Read video frame from native player
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0) break

                val pFrame = ptrRef.value
                val dataSize = sizeRef.value
                if (pFrame == null || dataSize <= 0) break

                // Copy frame data into pre-allocated ByteArray
                val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                byteBuffer.get(reusableByteArray, 0, dataSize)
                player.UnlockVideoFrame()

                if (isResizing) {
                    continue
                }

                // Update Bitmap with frame data, reusing the same object
                bitmapLock.write {
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

                // Update UI-related state on the main thread
                withContext(Dispatchers.Main) {
                    _frameCounter++
                }

                // Update playback position and progress
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat()
                }
                isLoading = false

                delay(8) // Frame delay for smooth playback

                // Handle looping
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
     * Starts or resumes playback.
     */
    override fun play() {
        if (!isInitialized || !_hasMedia) return
        _isPlaying = true
        player.StartAudioPlayback()
    }

    /**
     * Pauses playback.
     */
    override fun pause() {
        if (!_isPlaying) return
        _isPlaying = false
        player.StopAudioPlayback()
    }

    /**
     * Stops playback and releases resources.
     */
    override fun stop() {
        _isPlaying = false
        player.StopAudioPlayback()
        currentFrame = null
        videoJob?.cancel()
    }

    /**
     * Seeks to a specific position in the media.
     * @param value The position to seek to, in milliseconds.
     */
    override fun seekTo(value: Float) {
        if (!_hasMedia) return
        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) {
            handleError("Failed to get media duration", hrDuration)
            return
        }
        val duration100ns = durationRef.value
        val fraction = value / 1000f
        val newPosition = (duration100ns * fraction).toLong()
        val hrSeek = player.SeekMedia(newPosition)
        if (hrSeek < 0) {
            handleError("Failed to seek media", hrSeek)
        } else {
            _currentTime = newPosition / 10000000.0
            _progress = fraction
        }
    }

    /**
     * Handles resizing events to debounce and manage frame updates.
     */
    fun onResized() {
        isResizing = true
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(200)
            isResizing = false
        }
    }

    /**
     * Centralized error handling function.
     * @param message The error message.
     * @param hr The HRESULT code, if applicable.
     */
    private fun handleError(message: String, hr: Int? = null) {
        val errorMsg = if (hr != null) "$message (hr=0x${hr.toString(16)})" else message
        _error = VideoPlayerError.UnknownError(errorMsg)
        errorMessage = errorMsg
        isLoading = false
    }
}