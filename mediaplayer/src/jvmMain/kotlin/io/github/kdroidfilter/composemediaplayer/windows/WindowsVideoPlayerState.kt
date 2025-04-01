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
 * Uses a ReentrantReadWriteLock to protect the reusable Bitmap instance.
 */
class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private val player = MediaFoundationLib.INSTANCE
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var isInitialized by mutableStateOf(false)
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
        // Initialize media foundation
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
        val hrOpen = player.OpenMedia(WString(uri))
        if (hrOpen < 0) {
            setError("OpenMedia($uri) failed (hr=0x${hrOpen.toString(16)})")
            return
        }
        _hasMedia = true
        _isPlaying = false
        isLoading = true

        val wRef = IntByReference()
        val hRef = IntByReference()
        player.GetVideoSize(wRef, hRef)
        videoWidth = if (wRef.value > 0) wRef.value else 1280
        videoHeight = if (hRef.value > 0) hRef.value else 720

        reusableByteArray = ByteArray(videoWidth * videoHeight * 4)

        val durationRef = LongByReference()
        val hrDuration = player.GetMediaDuration(durationRef)
        if (hrDuration < 0) {
            setError("GetMediaDuration failed (hr=0x${hrDuration.toString(16)})")
            return
        }
        _duration = durationRef.value / 10000000.0


        // Start playback
        play()

        videoJob = scope.launch {
            while (isActive && !player.IsEOF()) {
                // If not playing, wait briefly and continue
                if (!_isPlaying) {
                    delay(16)
                    continue
                }

                // Read the video frame from native player
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0) break

                val pFrame = ptrRef.value
                val dataSize = sizeRef.value
                if (pFrame == null || dataSize <= 0) break

                // Ensure the reusable byte array is large enough
                if (reusableByteArray == null || reusableByteArray!!.size < dataSize) {
                    reusableByteArray = ByteArray(videoWidth * videoHeight * 4)
                }
                val byteBuffer = pFrame.getByteBuffer(0, dataSize.toLong())
                byteBuffer.get(reusableByteArray, 0, dataSize)
                // Unlock the frame in the native player
                player.UnlockVideoFrame()

                // If resizing is in progress, skip updating UI to drop accumulated frames
                if (isResizing) {
                    continue
                }

                // Update the reusable bitmap with the new frame data
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

                // Update frame counter on the main thread
                withContext(Dispatchers.Main) {
                    _frameCounter++
                }

                // Update current playback position and progress
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat()
                }
                isLoading = false

                // Calculate frame delay based on frame rate
                delay(8)

                // If end of media and looping is enabled, restart playback
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
        player.StartAudioPlayback()
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

    // Method to be called when a resize event is detected
    fun onResized() {
        // Set resizing flag to true
        isResizing = true
        // Cancel previous debounce job if any
        resizeJob?.cancel()
        // Start a new debounce job to clear resizing flag after 200ms without new resize events
        resizeJob = scope.launch {
            delay(200)
            isResizing = false
        }
    }

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }
}