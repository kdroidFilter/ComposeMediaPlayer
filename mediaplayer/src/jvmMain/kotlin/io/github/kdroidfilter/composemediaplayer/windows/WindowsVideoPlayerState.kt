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
        set(value) { _volume = value.coerceIn(0f, 1f) }
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
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f
    private var _error: VideoPlayerError? = null
    override val error: VideoPlayerError? get() = _error
    override fun clearError() {
        _error = null
        errorMessage = null
    }
    var _currentFrame: Bitmap? by mutableStateOf(null)
    val bitmapLock = ReentrantReadWriteLock()
    inline fun getLockedComposeImageBitmap(): ImageBitmap? = bitmapLock.read {
        _currentFrame?.asComposeImageBitmap()
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
    private var errorMessage: String? by mutableStateOf(null)
    private var videoJob: Job? = null
    var videoWidth by mutableStateOf(0)
    var videoHeight by mutableStateOf(0)
    private val frameQueueCapacity = 5 // Augmenté pour gérer les pics
    private val frameQueue = ArrayBlockingQueue<FrameData>(frameQueueCapacity)
    private val queueMutex = Mutex()
    private val poolCapacity = 5 // Augmenté pour réduire les allocations
    private val bitmapPool = ArrayBlockingQueue<Bitmap>(poolCapacity)
    private val byteArrayPool = ArrayBlockingQueue<ByteArray>(poolCapacity)
    data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double,
        val buffer: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameData) return false
            return bitmap == other.bitmap && timestamp == other.timestamp
        }
        override fun hashCode(): Int {
            var result = bitmap.hashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    private var isResizing by mutableStateOf(false)
        private set
    private var resizeJob: Job? = null

    init {
        try {
            val hr = player.InitMediaFoundation()
            isInitialized = (hr >= 0)
            if (!isInitialized) setError("Failed to initialize Media Foundation (hr=0x${hr.toString(16)})")
        } catch (e: Exception) {
            setError("Exception during initialization: ${e.message}")
        }
    }

    override fun dispose() {
        try {
            _isPlaying = false
            player.SetPlaybackState(false)
            videoJob?.cancel()
            player.CloseMedia()
            bitmapLock.write { _currentFrame = null }
            scope.launch {
                queueMutex.withLock {
                    frameQueue.clear()
                    bitmapPool.clear()
                    byteArrayPool.clear()
                }
            }
            player.ShutdownMediaFoundation()
        } catch (e: Exception) {
            println("Error during dispose: ${e.message}")
        } finally {
            isInitialized = false
            _hasMedia = false
            scope.cancel()
        }
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            setError("Player is not initialized.")
            return
        }
        runBlocking { videoJob?.cancelAndJoin(); videoJob = null }
        runBlocking {
            queueMutex.withLock {
                while (frameQueue.isNotEmpty()) {
                    val oldFrame = frameQueue.poll()
                    oldFrame?.let {
                        bitmapPool.offer(it.bitmap)
                        byteArrayPool.offer(it.buffer)
                    }
                }
            }
        }
        try {
            player.CloseMedia()
            runBlocking { delay(100) }
        } catch (e: Exception) {
            println("Error closing previous media: ${e.message}")
        }
        _currentFrame = null
        _currentTime = 0.0
        _progress = 0f
        val file = File(uri)
        if (!uri.startsWith("http", ignoreCase = true) && !file.exists()) {
            setError("File not found: $uri")
            return
        }
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
        play()
        videoJob = scope.launch {
            launch { produceFrames() }
            launch { consumeFrames() }
        }
    }

    private suspend inline fun produceFrames() {
        val requiredBufferSize = videoWidth * videoHeight * 4
        while (scope.isActive) {
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
                } else break
            }
            if (!_isPlaying || frameQueue.size >= frameQueueCapacity) {
                delay(16)
                continue
            }
            try {
                val ptrRef = PointerByReference()
                val sizeRef = IntByReference()
                val hrFrame = player.ReadVideoFrame(ptrRef, sizeRef)
                if (hrFrame < 0 || ptrRef.value == null || sizeRef.value <= 0) {
                    delay(16)
                    continue
                }
                val frameBytes = byteArrayPool.poll()?.takeIf { it.size >= requiredBufferSize }
                    ?: ByteArray(requiredBufferSize)
                ptrRef.value.getByteBuffer(0, sizeRef.value.toLong()).get(frameBytes, 0, sizeRef.value)
                player.UnlockVideoFrame()
                val frameBitmap = bitmapPool.poll() ?: Bitmap().apply {
                    allocPixels(ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))
                }
                frameBitmap.installPixels(
                    ImageInfo(videoWidth, videoHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                    frameBytes, videoWidth * 4
                )
                val posRef = LongByReference()
                val frameTime = if (player.GetMediaPosition(posRef) >= 0) posRef.value / 10000000.0 else 0.0
                val frameData = FrameData(frameBitmap, frameTime, frameBytes)
                queueMutex.withLock {
                    if (frameQueue.remainingCapacity() == 0) {
                        frameQueue.poll()?.let {
                            bitmapPool.offer(it.bitmap)
                            byteArrayPool.offer(it.buffer)
                        }
                    }
                    frameQueue.offer(frameData)
                }
            } catch (e: Exception) {
                setError("Error reading frame: ${e.message}")
                delay(16)
            }
        }
    }

    private suspend inline fun consumeFrames() {
        while (true) {
            scope.ensureActive()
            if (!_isPlaying || isResizing) {
                delay(16)
                continue
            }
            val frameData = queueMutex.withLock { if (frameQueue.isNotEmpty()) frameQueue.poll() else null }
            frameData?.let {
                bitmapLock.write {
                    val oldBitmap = _currentFrame
                    _currentFrame = it.bitmap
                    oldBitmap?.let { bitmapPool.offer(it) }
                }
                _currentTime = it.timestamp
                _progress = (_currentTime / _duration).toFloat()
                isLoading = false
                queueMutex.withLock { byteArrayPool.offer(it.buffer) }
            }
            delay(16)
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
            setError("Error starting playback: ${e.message}")
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

    override fun stop() {
        try {
            _isPlaying = false
            _currentFrame = null
            videoJob?.cancel()
            player.SetPlaybackState(false)
            _hasMedia = false
            _progress = 0f
            _currentTime = 0.0
            isLoading = false
            errorMessage = null
            _error = null
        } catch (e: Exception) {
            setError("Error stopping playback: ${e.message}")
        }
    }

    override fun seekTo(value: Float) {
        if (!_hasMedia) return
        val wasPlaying = _isPlaying
        scope.launch {
            try {
                if (_isPlaying) {
                    pause()
                    delay(50)
                }
                isLoading = true
                queueMutex.withLock {
                    frameQueue.forEach { bitmapPool.offer(it.bitmap); byteArrayPool.offer(it.buffer) }
                    frameQueue.clear()
                }
                player.UnlockVideoFrame()
                val targetPos = (_duration * (value / 1000f) * 10000000).toLong()
                var seekSuccess = false
                var retryCount = 0
                val maxRetries = 3
                while (!seekSuccess && retryCount < maxRetries) {
                    if (player.SeekMedia(targetPos) >= 0) seekSuccess = true
                    else { retryCount++; delay(50) }
                }
                if (!seekSuccess) {
                    setError("Seek failed after $maxRetries attempts")
                    return@launch
                }
                delay(100)
                val posRef = LongByReference()
                if (player.GetMediaPosition(posRef) >= 0) {
                    _currentTime = posRef.value / 10000000.0
                    _progress = (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                }
                if (wasPlaying) {
                    delay(100)
                    var resumeSuccess = false
                    retryCount = 0
                    while (!resumeSuccess && retryCount < maxRetries) {
                        try {
                            val result = player.SetPlaybackState(true)
                            if (result >= 0) {
                                _isPlaying = true
                                resumeSuccess = true
                            } else {
                                retryCount++
                                delay(50)
                            }
                        } catch (e: Exception) {
                            retryCount++
                            delay(50)
                        }
                    }
                    if (!resumeSuccess) setError("Failed to resume playback after seek")
                }
            } catch (e: Exception) {
                setError("Exception during seeking: ${e.message}")
                if (wasPlaying) player.SetPlaybackState(true).also { _isPlaying = true }
            } finally {
                delay(200)
                isLoading = false
            }
        }
    }

    fun onResized() {
        isResizing = true
        scope.launch {
            queueMutex.withLock {
                while (frameQueue.isNotEmpty()) {
                    frameQueue.poll()?.let { bitmapPool.offer(it.bitmap); byteArrayPool.offer(it.buffer) }
                }
            }
        }
        resizeJob?.cancel()
        resizeJob = scope.launch { delay(200); isResizing = false }
    }

    private inline fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        errorMessage = msg
        isLoading = false
    }

    override fun hideMedia() {}
    override fun showMedia() {}
}