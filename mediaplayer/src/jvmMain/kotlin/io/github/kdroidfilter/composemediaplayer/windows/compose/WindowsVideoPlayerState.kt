package io.github.kdroidfilter.composemediaplayer.windows.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.FloatByReference
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.DEFAULT_ASPECT_RATIO
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.windows.MediaPlayerLib
import io.github.kdroidfilter.composemediaplayer.windows.ui.VideoCanvas
import io.github.kdroidfilter.composemediaplayer.windows.util.Logger
import io.github.kdroidfilter.composemediaplayer.windows.wrapper.AudioControl
import io.github.kdroidfilter.composemediaplayer.windows.wrapper.MediaPlayerSlider
import io.github.kdroidfilter.composemediaplayer.windows.wrapper.VideoMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.awt.Canvas
import java.io.File
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.seconds

class WindowsVideoPlayerState : PlatformVideoPlayerState {
    private lateinit var mediaPlayerCallback: MediaPlayerLib.MediaPlayerCallback

    companion object {
        private const val UPDATE_INTERVAL = 60L  // ~60 FPS
        private const val AUDIO_UPDATE_INTERVAL = 50L
        private val LOADING_TIMEOUT = 10.seconds
        private val logger = Logger("WindowsVideoPlayerState")
    }

    private val mediaPlayer = MediaPlayerLib.INSTANCE
    private val audioControl = AudioControl(mediaPlayer)
    private val mediaSlider = MediaPlayerSlider(mediaPlayer)
    private val videoMetrics = VideoMetrics(mediaPlayer)

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Rendering canvas
    private var videoCanvas: Canvas? = null

    // Background jobs
    private var loadingTimeoutJob: Job? = null
    private var videoUpdateJob: Job? = null

    // Compose state
    var isInitialized by mutableStateOf(false)
        private set


    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean
        get() = _hasMedia

    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean
        get() = _isPlaying


    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f, 1f)
            audioControl.setVolume(_volume)
        }

    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    private var _progress by mutableStateOf(0f)  // Fraction between 0f..1f

    // Virtual slider 0..1000
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            if (!isInitialized) return
            _progress = (value / 1000f).coerceIn(0f, 1f)
        }

    // Indicates if the user is dragging the slider
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            if (_userDragging && !value) {
                applySeek()
            }
            _userDragging = value
        }

    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) { _loop = value }

    private var audioLevelsJob: Job? = null

    private var _leftLevel by mutableStateOf(0f)
    private var _rightLevel by mutableStateOf(0f)

    override val leftLevel: Float get() = _leftLevel
    override val rightLevel: Float get() = _rightLevel

    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)

    override var isLoading by mutableStateOf(false)
        private set

    private var _error: VideoPlayerError? by mutableStateOf(null)
    override val error: VideoPlayerError?
        get() = _error

    override val metadata: VideoMetadata = VideoMetadata()

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // In WindowsVideoPlayerState class
    private var _aspectRatio by mutableStateOf(DEFAULT_ASPECT_RATIO)
    val aspectRatio: Float
        get() = _aspectRatio

    // Initialize the player with a Canvas
    fun initializeWithCanvas(canvas: VideoCanvas) = coroutineScope.launch {
        if (isInitialized) return@launch

        try {
            val hwnd = Native.getComponentPointer(canvas) ?: run {
                logger.error("Null HWND, Canvas not displayable?")
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (!canvas.isDisplayable) {
                    canvas.createBufferStrategy(1)
                }

                mediaPlayerCallback = MediaPlayerLib.MediaPlayerCallback { eventType, hr ->
                    onMediaEvent(eventType, hr)
                }

                logger.log("Registering callback...")
                val hr = mediaPlayer.InitializeMediaPlayer(WinDef.HWND(hwnd), mediaPlayerCallback)
                logger.log("InitializeMediaPlayer returned HR=0x${hr.toString(16)}")

                if (hr < 0) {
                    handleError("MediaPlayer initialization failed (HR=0x${hr.toString(16)})")
                    return@withContext
                }

                videoCanvas = canvas

                (videoCanvas as VideoCanvas).onResize = {
                    mediaPlayer.UpdateVideo()
                    logger.log("UpdateVideo() called on resize")
                }

                isInitialized = true
                startVideoUpdates()
                startAudioLevelsUpdate()
                logger.log("Player initialized.")
            }
        } catch (e: Exception) {
            handleError("Initialization error: ${e.message}")
        }
    }

    override fun openUri(uri: String) {
        if (!isInitialized) {
            handleError("Player not initialized")
            _hasMedia = false
            return
        }
        resetState()

        try {
            logger.log("Playing URL: $uri")
            val hr = mediaPlayer.PlayURL(WString(uri))
            logger.log("PlayFile/URL returned HR=0x${hr.toString(16)}") // Ajout de log
            if (hr < 0) {
                handleError("Unable to open (HR=0x${hr.toString(16)})")
                return
            }
            _hasMedia = true
            startLoadingTimeout()

        } catch (e: Exception) {
            _hasMedia = false
            handleError("openUri error: ${e.message}")
        }
    }

    override fun play() {
        if (_currentTime >= _duration && !_loop) {
            // Si on est à la fin, recommencer depuis le début
            coroutineScope.launch {
                mediaSlider.setProgress(0f)
                _currentTime = 0.0
                _progress = 0f
                mediaOperation("resume playback") { mediaPlayer.ResumePlayback() }
            }
        } else {
            mediaOperation("resume playback") { mediaPlayer.ResumePlayback() }
        }
        _hasMedia = true
    }
    override fun pause() = mediaOperation("pause") { mediaPlayer.PausePlayback() }
    override fun stop() {
        mediaOperation("stop playback") { mediaPlayer.StopPlayback() }
        _hasMedia = false
    }

    override fun seekTo(value: Float) {
        sliderPos = value
        applySeek()
    }

    override fun dispose() {
        if (!isInitialized) return
        loadingTimeoutJob?.cancel()
        audioLevelsJob?.cancel()
        try {
            mediaPlayer.StopPlayback()
            mediaPlayer.CleanupMediaPlayer()
        } catch (e: Exception) {
            logger.error("Cleanup error: ${e.message}")
        } finally {
            cleanupState()
        }
    }

    override fun clearError() {
        errorMessage = null
        _error = null
    }

    // ------------------------------
    // Video update logic
    // ------------------------------
    private fun startVideoUpdates() {
        videoUpdateJob?.cancel()
        videoUpdateJob = coroutineScope.launch {
            flow {
                while (currentCoroutineContext().isActive) {
                    emit(Unit)
                    delay(UPDATE_INTERVAL)
                }
            }
                .onEach {
                    if (isInitialized) {
                        if (!_userDragging) {
                            updateProgressFromPlayer()
                        }
                    }
                }
                .collect()
        }
    }

    private fun updateProgressFromPlayer() {
        if (!mediaPlayer.IsInitialized()) return
        try {
            _isPlaying = if (!isInitialized || isLoading) false
            else if (_duration > 0 && _currentTime >= _duration && !_loop) false
            else mediaPlayer.IsPlaying()
            mediaSlider.getDurationInSeconds()?.let { dur ->
                if (dur > 0.0) {
                    _duration = dur
                    mediaSlider.getCurrentPositionInSeconds()?.let { pos ->
                        _currentTime = pos
                        _progress = (pos / dur).toFloat().coerceIn(0f, 1f)

                        // Debug logging
                        if (pos >= dur - 0.1) { // Slightly before the end
                            logger.log("Near video end - pos: $pos, dur: $dur, loop: $_loop, isPlaying: $_isPlaying")
                            // If looping is off, stop playback and set isPlaying to false
                            if (!_loop) {
                                _isPlaying = false
                                mediaPlayer.StopPlayback()
                            } else {
                                // If looping is on, ensure isPlaying remains true and restart playback
                                _isPlaying = true
                                logger.log("Attempting loop in updateProgressFromPlayer")
                                coroutineScope.launch(Dispatchers.Main) {
                                    try {
                                        // Seek to start
                                        mediaSlider.setProgress(0f)
                                        _currentTime = 0.0
                                        _progress = 0f
                                        mediaPlayer.StopPlayback()
                                        delay(50) // Small delay to ensure stop is processed
                                        mediaPlayer.ResumePlayback()
                                        logger.log("Loop attempt completed")
                                    } catch (e: Exception) {
                                        logger.error("Loop attempt failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Update progress error: ${e.message}")
        }
    }

    private fun applySeek() {
        if (!isInitialized) return
        coroutineScope.launch {
            try {
                mediaSlider.setProgress(_progress)
                _currentTime = _duration * _progress
            } catch (e: Exception) {
                logger.error("Apply seek error: ${e.message}")
            }
        }
    }

    private fun resetState() {
        loadingTimeoutJob?.cancel()
        _isPlaying = false
        clearError()
        isLoading = false
        _currentTime = 0.0
        _duration = 0.0
        _progress = 0f
        _hasMedia = false
    }

    private fun startLoadingTimeout() {
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = coroutineScope.launch {
            delay(LOADING_TIMEOUT)
            if (isLoading) {
                handleError("Video loading timeout.")
            }
        }
    }

    private fun cleanupState() {
        isInitialized = false
        _isPlaying = false
        _currentTime = 0.0
        _duration = 0.0
        _progress = 0f
        _hasMedia = false
        videoCanvas = null
        logger.log("Player cleaned up.")
    }

    private fun mediaOperation(name: String, block: () -> Int) {
        if (!isInitialized) return
        val hr = block()
        if (hr < 0) {
            handleError("Unable to $name (HR=0x${hr.toString(16)})")
        }
    }

    // ------------------------------
    // Audio levels update logic
    // ------------------------------

    private fun startAudioLevelsUpdate() {
        audioLevelsJob?.cancel()
        audioLevelsJob = coroutineScope.launch {
            flow {
                while (currentCoroutineContext().isActive) {
                    emit(Unit)
                    delay(AUDIO_UPDATE_INTERVAL)
                }
            }
                .onEach {
                    if (isInitialized && !isLoading && isPlaying) {
                        updateAudioLevels()
                    } else {
                        _leftLevel = 0f
                        _rightLevel = 0f
                    }
                }
                .collect()
        }
    }

    private fun updateAudioLevels() {
        try {
            val leftRef = FloatByReference()
            val rightRef = FloatByReference()

            val hr = mediaPlayer.GetChannelLevels(leftRef, rightRef)
            if (hr == 0) {
                // Convertir les valeurs en pourcentage (0-100)
                _leftLevel = (leftRef.value * 100f).coerceIn(0f, 100f)
                _rightLevel = (rightRef.value * 100f).coerceIn(0f, 100f)
//                logger.log("Audio levels - Left: $_leftLevel%, Right: $_rightLevel%")
            } else {
                logger.error("GetChannelLevels failed with HR=0x${hr.toString(16)}")
                _leftLevel = 0f
                _rightLevel = 0f
            }
        } catch (e: Exception) {
            logger.error("Error updating audio levels: ${e.message}")
            _leftLevel = 0f
            _rightLevel = 0f
        }
    }

    // ------------------------------
    // MediaPlayer events
    // ------------------------------
    private fun onMediaEvent(eventType: Int, hr: Int) {
        logger.log("onMediaEvent: $eventType (HR=0x${hr.toString(16)})")
        when (eventType) {
            MediaPlayerLib.MP_EVENT_MEDIAITEM_SET -> {
                logger.log("Media item set event received")
                updateProgressFromPlayer()
                videoMetrics.getAspectRatio()?.let { ratio ->
                    _aspectRatio = ratio  // Update the aspect ratio
                    logger.log("Video aspect ratio: $ratio")
                } ?: run {
                    _aspectRatio =  DEFAULT_ASPECT_RATIO // Fall back to 16:9 if we can't get the ratio
                    logger.error("Could not get video aspect ratio")
                }
            }

            MediaPlayerLib.MP_EVENT_PLAYBACK_STARTED -> {
                logger.log("Playback started event received")
                handlePlaybackStarted()
            }
            MediaPlayerLib.MP_EVENT_PLAYBACK_PAUSED -> {
                logger.log("Playback paused event received")
                _isPlaying = false
            }
            MediaPlayerLib.MP_EVENT_PLAYBACK_STOPPED -> {
                logger.log("Playback stopped event received")
                handlePlaybackStopped()
            }
            MediaPlayerLib.MP_EVENT_PLAYBACK_ERROR -> {
                logger.log("Playback error event received with error code: $hr")
                handlePlaybackError(hr)
            }
            MediaPlayerLib.MP_EVENT_LOADING_STARTED -> {
                logger.log("Loading started event received")
                handleLoadingStarted()
            }
            MediaPlayerLib.MP_EVENT_LOADING_COMPLETE -> {
                logger.log("Loading complete event received")
                handleLoadingComplete()
            }
            MediaPlayerLib.MP_EVENT_PLAYBACK_ENDED -> {
                logger.log("Playback ended event received")
                handlePlaybackEnded()
            }
        }
    }

    private fun handlePlaybackStarted() {
        if (_currentTime >= _duration && !_loop) {
            // Si on essaie de lire après la fin sans boucle
            stop()
            return
        }
        _isPlaying = true
        isLoading = false
        loadingTimeoutJob?.cancel()
        _leftLevel = 0f  // Réinitialiser les niveaux audio
        _rightLevel = 0f
    }

    private fun handlePlaybackStopped() {
        _isPlaying = false
        isLoading = false
        _leftLevel = 0f  // Réinitialiser les niveaux audio
        _rightLevel = 0f

        // Ne réinitialise la position que si ce n'est pas une fin naturelle
        if (_currentTime < _duration || _loop) {
            _currentTime = 0.0
            _progress = 0f
            if (_loop) {
                mediaSlider.setProgress(0f)
                play()
            }
        }
    }

    private fun handlePlaybackError(hr: Int) {
        _isPlaying = false
        isLoading = false
        loadingTimeoutJob?.cancel()
        handleError("Playback error (HR=0x${hr.toString(16)})")
    }

    private fun handleLoadingStarted() {
        isLoading = true
        logger.log("Loading started…")
    }

    private fun handleLoadingComplete() {
        loadingTimeoutJob?.cancel()
        isLoading = false
        logger.log("Loading complete.")
    }

    private fun handlePlaybackEnded() {
        logger.log("handlePlaybackEnded called - loop: $_loop, isPlaying: $_isPlaying")
        if (!_loop) {
            _isPlaying = false
        } else {
            coroutineScope.launch(Dispatchers.Main) {
                try {
                    logger.log("Starting loop in handlePlaybackEnded")
                    _isPlaying = true
                    mediaSlider.setProgress(0f)
                    _currentTime = 0.0
                    _progress = 0f
                    mediaPlayer.StopPlayback()
                    delay(50)
                    mediaPlayer.ResumePlayback()
                    logger.log("Loop completed in handlePlaybackEnded")
                } catch (e: Exception) {
                    logger.error("Loop failed in handlePlaybackEnded: ${e.message}")
                }
            }
        }
    }

    private fun handleError(message: String) {
        errorMessage = message
        _error = VideoPlayerError.UnknownError(message)
        logger.error(message)
    }

}