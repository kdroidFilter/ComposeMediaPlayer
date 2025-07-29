package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.*
import kotlinx.io.IOException
import org.w3c.dom.url.URL
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Implementation of VideoPlayerState for WebAssembly/JavaScript platform.
 * Manages the state of a video player including playback controls, media information,
 * and error handling.
 */
@Stable
actual open class VideoPlayerState {

    // Variable to store the last opened URI for potential replay
    private var lastUri: String? = null

    // Coroutine scope for managing async operations
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastUpdateTime = TimeSource.Monotonic.markNow()

    // Throttling for control changes
    private var lastVolumeChangeTime = TimeSource.Monotonic.markNow()
    private var lastSpeedChangeTime = TimeSource.Monotonic.markNow()
    private var pendingVolumeChange: Job? = null
    private var pendingSpeedChange: Job? = null

    // Source URI of the current media
    private var _sourceUri by mutableStateOf<String?>(null)
    val sourceUri: String? get() = _sourceUri

    // Playback state properties
    private var _isPlaying by mutableStateOf(false)
    actual val isPlaying: Boolean get() = _isPlaying

    private var _hasMedia by mutableStateOf(false)
    actual val hasMedia: Boolean get() = _hasMedia

    internal var _isLoading by mutableStateOf(false)
    actual val isLoading: Boolean get() = _isLoading

    // Error handling
    private var _error by mutableStateOf<VideoPlayerError?>(null)
    actual val error: VideoPlayerError? get() = _error

    // Media metadata
    actual val metadata = VideoMetadata()
    actual val aspectRatio : Float = 16f / 9f //TO DO: Get from video source

    // Subtitle management
    actual var subtitlesEnabled by mutableStateOf(false)
    actual var currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    actual val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    actual var subtitleTextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    )
    actual var subtitleBackgroundColor by mutableStateOf(Color.Black.copy(alpha = 0.5f))

    // Playback control properties
    private var _volume by mutableStateOf(1.0f)
    actual var volume: Float
        get() = _volume
        set(value) {
            val newValue = value.coerceIn(0f, 1f)
            if (_volume != newValue) {
                _volume = newValue
                applyVolumeChangeWithThrottle(newValue)
            }
        }

    actual var sliderPos by mutableStateOf(0.0f)
    actual var userDragging by mutableStateOf(false)
    actual var loop by mutableStateOf(false)

    private var _playbackSpeed by mutableStateOf(1.0f)
    actual var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newValue = value.coerceIn(0.5f, 2.0f)
            if (_playbackSpeed != newValue) {
                _playbackSpeed = newValue
                applyPlaybackSpeedWithThrottle(newValue)
            }
        }

    actual var isFullscreen by mutableStateOf(false)

    // Audio level indicators
    private var _leftLevel by mutableStateOf(0f)
    private var _rightLevel by mutableStateOf(0f)
    actual val leftLevel: Float get() = _leftLevel
    actual val rightLevel: Float get() = _rightLevel

    // Time display properties
    private var _positionText by mutableStateOf("00:00")
    private var _durationText by mutableStateOf("00:00")
    actual val positionText: String get() = _positionText
    actual val durationText: String get() = _durationText

    // Current duration of the media
    private var _currentDuration: Float = 0f
    
    // Current time of the media in seconds
    private var _currentTime: Double = 0.0
    actual val currentTime: Double get() = _currentTime

    // Job for handling seek operations
    internal var seekJob: Job? = null

    /**
     * Callback function to force recalculation of the HTML view position.
     * This is set by the VideoPlayerSurface when the HTML view is created.
     */
    var positionRecalculationCallback: (() -> Unit)? = null

    /**
     * Callback to apply volume changes to the underlying media player
     */
    var applyVolumeCallback: ((Float) -> Unit)? = null

    /**
     * Callback to apply playback speed changes to the underlying media player
     */
    var applyPlaybackSpeedCallback: ((Float) -> Unit)? = null

    /**
     * Forces recalculation of the HTML view position.
     * This is useful when the layout changes and the HTML view needs to be repositioned.
     */
    fun forcePositionRecalculation() {
        positionRecalculationCallback?.invoke()
    }

    /**
     * Applies volume changes with throttling to prevent performance issues
     */
    private fun applyVolumeChangeWithThrottle(value: Float) {
        val now = TimeSource.Monotonic.markNow()
        val timeSinceLastChange = now - lastVolumeChangeTime

        // Cancel any pending volume change
        pendingVolumeChange?.cancel()

        if (timeSinceLastChange < 100.milliseconds) {
            // If changes are coming too rapidly, schedule them with a delay
            pendingVolumeChange = playerScope.launch {
                delay(100.milliseconds.minus(timeSinceLastChange).inWholeMilliseconds)
                applyVolumeCallback?.invoke(value)
                lastVolumeChangeTime = TimeSource.Monotonic.markNow()
            }
        } else {
            // Apply immediately if we're not throttling
            applyVolumeCallback?.invoke(value)
            lastVolumeChangeTime = now
        }
    }

    /**
     * Applies playback speed changes with throttling to prevent performance issues
     */
    private fun applyPlaybackSpeedWithThrottle(value: Float) {
        val now = TimeSource.Monotonic.markNow()
        val timeSinceLastChange = now - lastSpeedChangeTime

        // Cancel any pending speed change
        pendingSpeedChange?.cancel()

        if (timeSinceLastChange < 100.milliseconds) {
            // If changes are coming too rapidly, schedule them with a delay
            pendingSpeedChange = playerScope.launch {
                delay(100.milliseconds.minus(timeSinceLastChange).inWholeMilliseconds)
                applyPlaybackSpeedCallback?.invoke(value)
                lastSpeedChangeTime = TimeSource.Monotonic.markNow()
            }
        } else {
            // Apply immediately if we're not throttling
            applyPlaybackSpeedCallback?.invoke(value)
            lastSpeedChangeTime = now
        }
    }

    /**
     * Selects a subtitle track and enables subtitles.
     *
     * @param track The subtitle track to select, or null to disable subtitles
     */
    actual fun selectSubtitleTrack(track: SubtitleTrack?) {
        currentSubtitleTrack = track
        subtitlesEnabled = (track != null)
    }

    /**
     * Disables subtitles by clearing the current track and setting subtitlesEnabled to false.
     */
    actual fun disableSubtitles() {
        currentSubtitleTrack = null
        subtitlesEnabled = false
    }

    /**
     * Opens a media source from the given URI.
     *
     * @param uri The URI of the media to open
     */
    actual fun openUri(uri: String) {
        playerScope.coroutineContext.cancelChildren()

        // Store the URI for potential replay after stop
        lastUri = uri

        _sourceUri = uri
        _hasMedia = true
        _isLoading = true  // Set initial loading state
        _error = null
        _isPlaying = false
        _playbackSpeed = 1.0f

        // Don't set isLoading to false here - let the video events handle it
        playerScope.launch {
            try {
                _isPlaying = true
            } catch (e: Exception) {
                _isLoading = false
                _error = when (e) {
                    is IOException -> VideoPlayerError.NetworkError(e.message ?: "Network error")
                    else -> VideoPlayerError.UnknownError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Opens a media file.
     *
     * @param file The file to open
     */
    actual fun openFile(file: PlatformFile) {
        val fileUri = file.toUriString()
        openUri(fileUri)
    }

    /**
     * Starts or resumes playback of the current media.
     * If no media is loaded but a previous URI exists, reopens that media.
     */
    actual fun play() {
        if (_hasMedia && !_isPlaying) {
            _isPlaying = true
        } else if (!_hasMedia && lastUri != null) {
            // If we have a stored URI but no media, reopen the media
            openUri(lastUri!!)
        }
    }

    /**
     * Pauses playback of the current media.
     */
    actual fun pause() {
        if (_isPlaying) {
            _isPlaying = false
        }
    }

    /**
     * Stops playback and resets the player state.
     * Note: lastUri is preserved for potential replay.
     */
    actual fun stop() {
        _isPlaying = false
        _sourceUri = null
        _hasMedia = false
        _isLoading = false
        sliderPos = 0.0f
        _positionText = "00:00"
        _durationText = "00:00"
        _currentTime = 0.0
        // Note: We don't clear lastUri, so it can be used to replay the video
    }

    /**
     * Seeks to a specific position in the media.
     *
     * @param value The position to seek to, as a percentage (0-1000)
     */
    actual fun seekTo(value: Float) {
        sliderPos = value
        seekJob?.cancel()
    }

    /**
     * Clears any error state.
     */
    actual fun clearError() {
        _error = null
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    actual fun toggleFullscreen() {
        FullscreenManager.toggleFullscreen(isFullscreen) { newFullscreenState ->
            isFullscreen = newFullscreenState
        }
    }

    /**
     * Sets the error state.
     *
     * @param error The error to set
     */
    fun setError(error: VideoPlayerError) {
        _error = error
    }

    /**
     * Updates the audio level indicators.
     *
     * @param left The left channel audio level
     * @param right The right channel audio level
     */
    fun updateAudioLevels(left: Float, right: Float) {
        _leftLevel = left
        _rightLevel = right
    }

    /**
     * Updates the position and duration display.
     *
     * @param currentTime The current playback position in seconds
     * @param duration The total duration of the media in seconds
     * @param forceUpdate If true, bypasses the rate limiting check (useful for tests)
     */
    fun updatePosition(currentTime: Float, duration: Float, forceUpdate: Boolean = false) {
        val now = TimeSource.Monotonic.markNow()
        if (forceUpdate || now - lastUpdateTime >= 1.seconds) {
            // Calculate a dynamic threshold based on video duration (10% of duration or at least 0.5 seconds)
            val threshold = if (duration > 0f && !duration.isNaN()) {
                maxOf(duration * 0.1f, 0.5f)
            } else {
                0.5f
            }

            // Check if we're very close to the end of the video
            val isNearEnd = duration > 0f && !duration.isNaN() && !currentTime.isNaN() &&
                    (duration - currentTime < threshold)

            // If we're near the end, use the duration as the current time
            val displayTime = if (isNearEnd) duration else currentTime

            _positionText = if (displayTime.isNaN()) "00:00" else formatTime(displayTime)
            _durationText = if (duration.isNaN()) "00:00" else formatTime(duration)
            
            // Update the current time property
            _currentTime = displayTime.toDouble()

            if (!userDragging && duration > 0f && !duration.isNaN() && !_isLoading) {
                sliderPos = if (isNearEnd) {
                    PERCENTAGE_MULTIPLIER // Set to 100% if near end
                } else {
                    (currentTime / duration) * PERCENTAGE_MULTIPLIER
                }
            }
            _currentDuration = duration
            lastUpdateTime = now
        }
    }

    /**
     * Callback for time update events from the media player.
     *
     * @param currentTime The current playback position in seconds
     * @param duration The total duration of the media in seconds
     * @param forceUpdate If true, bypasses the rate limiting check (useful for tests)
     */
    fun onTimeUpdate(currentTime: Float, duration: Float, forceUpdate: Boolean = false) {
        updatePosition(currentTime, duration, forceUpdate)
    }

    /**
     * Disposes of resources used by the player.
     */
    actual fun dispose() {
        pendingVolumeChange?.cancel()
        pendingSpeedChange?.cancel()
        playerScope.cancel()
    }

    companion object {
        internal const val PERCENTAGE_MULTIPLIER = 1000f
    }
}

/**
 * Converts a PlatformFile to a URI string that can be used by the media player.
 *
 * @return A URI string representing the file
 */
fun PlatformFile.toUriString(): String {
    return URL.createObjectURL(this.file)
}