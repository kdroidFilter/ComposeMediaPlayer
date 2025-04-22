@file:OptIn(ExperimentalForeignApi::class)
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
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.*
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue


@OptIn(ExperimentalForeignApi::class)
@Stable
actual open class VideoPlayerState {

    // Base states
    private var _volume = mutableStateOf(1.0f)
    actual var volume: Float
        get() = _volume.value
        set(value) {
            _volume.value = value
            if (_isPlaying) {
                player?.volume = value
            }
        }

    actual var sliderPos: Float by mutableStateOf(0f) // value between 0 and 1000
    actual var userDragging: Boolean = false
    actual var loop: Boolean = false

    // Playback speed control
    private var _playbackSpeed by mutableStateOf(1.0f)
    actual var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(0.5f, 2.0f)
            player?.rate = _playbackSpeed
        }

    // Playback states
    actual val hasMedia: Boolean get() = _hasMedia
    actual val isPlaying: Boolean get() = _isPlaying
    private var _hasMedia by mutableStateOf(false)
    private var _isPlaying by mutableStateOf(false)

    // Displayed texts for position and duration
    private var _positionText: String by mutableStateOf("00:00")
    actual val positionText: String get() = _positionText
    private var _durationText: String by mutableStateOf("00:00")
    actual val durationText: String get() = _durationText

    // Loading state
    private var _isLoading by mutableStateOf(false)
    actual val isLoading: Boolean
        get() = _isLoading

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    actual var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    actual val error: VideoPlayerError? = null

    // Observable instance of AVPlayer
    var player: AVPlayer? by mutableStateOf(null)
        private set

    // Periodic observer for position updates (≈60 fps)
    private var timeObserverToken: Any? = null

    // End-of-playback notification observer
    private var endObserver: Any? = null

    // Stalled playback notification observer
    private var stalledObserver: Any? = null

    // Internal time values (in seconds)
    private var _currentTime: Double = 0.0
    private var _duration: Double = 0.0

    // Flag to indicate user-initiated pause
    private var userInitiatedPause: Boolean = false

    // Audio levels (not yet implemented)
    actual val leftLevel: Float = 0f
    actual val rightLevel: Float = 0f

    // Observable video aspect ratio (default to 16:9)
    private var _videoAspectRatio by mutableStateOf(16.0 / 9.0)
    val videoAspectRatio: CGFloat
        get() = _videoAspectRatio

    // Video metadata
    private var _metadata = VideoMetadata(audioChannels = 2)

    private fun startPositionUpdates() {
        stopPositionUpdates()
        val interval = CMTimeMakeWithSeconds(1.0 / 60.0, 600) // approx. 60 fps
        timeObserverToken = player?.addPeriodicTimeObserverForInterval(
            interval = interval,
            queue = dispatch_get_main_queue(),
            usingBlock = { time ->
                val currentSeconds = CMTimeGetSeconds(time)
                val durationSeconds = player?.currentItem?.duration?.let { CMTimeGetSeconds(it) } ?: 0.0
                _currentTime = currentSeconds
                _duration = durationSeconds

                // Update duration in metadata
                if (durationSeconds > 0 && !durationSeconds.isNaN()) {
                    _metadata.duration = (durationSeconds * 1000).toLong()
                }

                if (!userDragging && durationSeconds > 0 && !currentSeconds.isNaN() && !durationSeconds.isNaN()) {
                    sliderPos = ((currentSeconds / durationSeconds) * 1000).toFloat()
                }
                _positionText = if (currentSeconds.isNaN()) "00:00" else formatTime(currentSeconds.toFloat())
                _durationText = if (durationSeconds.isNaN()) "00:00" else formatTime(durationSeconds.toFloat())

                player?.currentItem?.presentationSize?.useContents {
                    // Only update if dimensions are valid (greater than 0)
                    if (width > 0 && height > 0) {
                        // Try to use real aspect ratio if available, fallback to 16:9
                        val realAspect = width / height
                        _videoAspectRatio = realAspect

                        // Update width and height in metadata if they're not already set or if they're zero
                        if (_metadata.width == null || _metadata.width == 0 || _metadata.height == null || _metadata.height == 0) {
                            _metadata.width = width.toInt()
                            _metadata.height = height.toInt()
                            Logger.d { "Video resolution updated during playback: ${width.toInt()}x${height.toInt()}" }
                        }
                    }
                }

                player?.currentItem?.let { item ->
                    val isBufferEmpty = item.playbackBufferEmpty
                    val isLikelyToKeepUp = item.playbackLikelyToKeepUp
                    _isLoading = isBufferEmpty || !isLikelyToKeepUp
                } ?: run {
                    _isLoading = false
                }
            }
        )
    }

    private fun stopPositionUpdates() {
        timeObserverToken?.let { token ->
            player?.removeTimeObserver(token)
            timeObserverToken = null
        }
    }

    private fun removeObservers() {
        endObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            endObserver = null
        }

        stalledObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            stalledObserver = null
        }
    }

    actual fun openUri(uri: String) {
        Logger.d { "openUri called with uri: $uri" }
        val nsUrl = NSURL.URLWithString(uri) ?: run {
            Logger.d { "Failed to create NSURL from uri: $uri" }
            return
        }

        stopPositionUpdates()
        removeObservers()
        player?.pause()

        // Set loading state to true at the beginning of loading a new video
        _isLoading = true

        // Reset metadata to default values
        _metadata = VideoMetadata(audioChannels = 2)

        // Create a temporary player with minimal setup to show something immediately
        val tempPlayerItem = AVPlayerItem(nsUrl)
        player = AVPlayer(playerItem = tempPlayerItem).apply {
            volume = this@VideoPlayerState.volume
            rate = 0.0f // Don't start playing yet
        }
        _hasMedia = true

        // Process the asset on a background thread to avoid blocking the UI
        dispatch_async(platform.darwin.dispatch_get_global_queue(platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            // Create an AVAsset to extract metadata
            val asset = AVURLAsset.URLAssetWithURL(nsUrl, null)

            // Extract metadata from tracks
            var videoAspectRatioTemp = 16.0 / 9.0
            var widthTemp: Int? = null
            var heightTemp: Int? = null

            // Process video tracks
            val videoTracks = asset.tracksWithMediaType(AVMediaTypeVideo)
            if (videoTracks.isNotEmpty()) {
                val videoTrack = videoTracks.firstOrNull() as? AVAssetTrack
                videoTrack?.let { track ->
                    // Get frame rate
                    val nominalFrameRate = track.nominalFrameRate
                    if (nominalFrameRate > 0) {
                        _metadata.frameRate = nominalFrameRate
                    }

                    // Get bitrate
                    val trackBitrate = track.estimatedDataRate
                    if (trackBitrate > 0) {
                        _metadata.bitrate = (trackBitrate * 1000).toLong()
                    }

                    // Get resolution from naturalSize
                    track.naturalSize.useContents {
                        if (width > 0 && height > 0) {
                            widthTemp = width.toInt()
                            heightTemp = height.toInt()
                            // Try to use real aspect ratio if available, fallback to 16:9
                            videoAspectRatioTemp = width / height
                            Logger.d { "Video resolution from track: ${width.toInt()}x${height.toInt()}" }
                        }
                    }
                }
            }

            // Process audio tracks
            val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
            if (audioTracks.isNotEmpty()) {
                // Update audio channels count based on number of audio tracks
                _metadata.audioChannels = audioTracks.size

                // Try to get sample rate (simplified approach)
                _metadata.audioSampleRate = 44100 // Default to common value
            }

            // Create player item from asset to get more accurate metadata
            val playerItem = AVPlayerItem(asset)
            var durationSeconds = 0.0

            // Try to get duration
            durationSeconds = CMTimeGetSeconds(playerItem.duration)
            if (durationSeconds > 0 && !durationSeconds.isNaN()) {
                _metadata.duration = (durationSeconds * 1000).toLong()
            }

            // Try to extract title from the file name
            val fileName = nsUrl.lastPathComponent
            if (fileName != null) {
                _metadata.title = fileName
            }

            // Update UI on the main thread
            dispatch_async(dispatch_get_main_queue()) {
                // Update metadata
                if (widthTemp != null && heightTemp != null) {
                    _metadata.width = widthTemp
                    _metadata.height = heightTemp
                    _videoAspectRatio = videoAspectRatioTemp
                }

                // Create the final player with the fully loaded asset
                player = AVPlayer(playerItem = playerItem).apply {
                    volume = this@VideoPlayerState.volume
                    rate = _playbackSpeed
                    actionAtItemEnd = AVPlayerActionAtItemEndNone
                }

                endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemDidPlayToEndTimeNotification,
                    `object` = player?.currentItem,
                    queue = null
                ) { _ ->
                    if (userInitiatedPause) return@addObserverForName
                    if (_duration > 0 && (_duration - _currentTime) > 0.1) {
                        return@addObserverForName
                    }
                    if (loop) {
                        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
                        player?.rate = _playbackSpeed
                        player?.play()
                    } else {
                        player?.pause()
                        _isPlaying = false
                    }
                }

                startPositionUpdates()
                play()
            }
        }
    }

    actual fun play() {
        Logger.d { "play called" }
        userInitiatedPause = false
        if (player == null) {
            Logger.d { "play: player is null" }
            return
        }
        // Set loading to true when play is called
        _isLoading = true
        player?.volume = volume
        player?.rate = _playbackSpeed
        player?.play()
        _isPlaying = true
        _hasMedia = true

        // Add a listener to detect when the player is ready to play
        player?.currentItem?.let { item ->
            if (item.playbackLikelyToKeepUp) {
                _isLoading = false
            } else {
                // If not ready yet, add a notification observer for when it becomes ready
                stalledObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemPlaybackStalledNotification,
                    `object` = item,
                    queue = null
                ) { _ ->
                    // Check if playback is likely to keep up now
                    if (item.playbackLikelyToKeepUp) {
                        _isLoading = false
                    }
                }
            }
        }
    }

    actual fun pause() {
        Logger.d { "pause called" }
        userInitiatedPause = true
        // Ensure the pause call is on the main thread:
        dispatch_async(dispatch_get_main_queue()) {
            player?.pause()
        }
        _isPlaying = false
    }

    actual fun stop() {
        Logger.d { "stop called" }
        player?.pause()
        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
        _isPlaying = false
        _hasMedia = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    actual fun seekTo(value: Float) {
        if (_duration > 0) {
            // Set loading state to true to indicate seeking is happening
            _isLoading = true

            val targetTime = _duration * (value / 1000.0)

            // First, perform a seek with a lower timescale (like in macOS)
            player?.seekToTime(CMTimeMakeWithSeconds(targetTime, 1))

            // Then immediately perform another seek with a higher timescale
            // This ensures at least one of the seeks will work properly
            player?.seekToTime(CMTimeMakeWithSeconds(targetTime, 600))

            // Ensure playback speed is maintained after seeking
            player?.rate = _playbackSpeed

            // Reset loading state after a short delay
            dispatch_async(dispatch_get_main_queue()) {
                _isLoading = false
            }
        }
    }


    actual fun clearError() {
        Logger.d { "clearError called" }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    actual fun toggleFullscreen() {
        Logger.d { "toggleFullscreen called" }
        _isFullscreen = !_isFullscreen
    }

    actual fun dispose() {
        Logger.d { "dispose called" }
        stopPositionUpdates()
        removeObservers()
        player?.pause()
        player = null
        _hasMedia = false
        _isPlaying = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    actual fun openFile(file: PlatformFile) {
        Logger.d { "openFile called with file: $file" }
        openUri(file.toString())
    }

    actual val metadata: VideoMetadata
        get() = _metadata
    // Subtitle state
    private var _subtitlesEnabled by mutableStateOf(false)
    actual var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            _subtitlesEnabled = value
        }

    private var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    actual var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            _currentSubtitleTrack = value
        }

    private val _availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    actual val availableSubtitleTracks: MutableList<SubtitleTrack>
        get() = _availableSubtitleTracks

    actual var subtitleTextStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    )

    actual var subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)

    /**
     * Selects a subtitle track for display.
     * If track is null, disables subtitles.
     * 
     * @param track The subtitle track to select, or null to disable subtitles
     */
    actual fun selectSubtitleTrack(track: SubtitleTrack?) {
        Logger.d { "selectSubtitleTrack called with track: $track" }
        if (track == null) {
            disableSubtitles()
            return
        }

        // Update current track and enable flag
        currentSubtitleTrack = track
        subtitlesEnabled = true

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
    }

    /**
     * Disables subtitle display.
     */
    actual fun disableSubtitles() {
        Logger.d { "disableSubtitles called" }
        // Update state
        currentSubtitleTrack = null
        subtitlesEnabled = false

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
    }
}
