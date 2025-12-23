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
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.*
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun createVideoPlayerState(): VideoPlayerState = DefaultVideoPlayerState()

@Stable
open class DefaultVideoPlayerState: VideoPlayerState {

    // Base states
    private var _volume = mutableStateOf(1.0f)
    override var volume: Float
        get() = _volume.value
        set(value) {
            val clampedValue = value.coerceIn(0f, 1f)
            _volume.value = clampedValue
            if (_isPlaying) {
                player?.volume = clampedValue
            }
        }

    override var sliderPos: Float by mutableStateOf(0f) // value between 0 and 1000
    override var userDragging: Boolean = false
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            Logger.d { "Loop setting changed to: $value" }

            // If we have an active player, update its loop behavior
            player?.let { player ->
                // In iOS, we need to recreate the end observer with the new loop setting
                // First, remove the existing observer
                endObserver?.let {
                    NSNotificationCenter.defaultCenter.removeObserver(it)
                    endObserver = null
                }

                // Then create a new observer with the updated loop setting
                endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemDidPlayToEndTimeNotification,
                    `object` = player.currentItem,
                    queue = null
                ) { _ ->
                    if (userInitiatedPause) return@addObserverForName
                    if (_duration > 0 && (_duration - _currentTime) > 0.1) {
                        return@addObserverForName
                    }
                    if (_loop) {
                        player.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
                        player.rate = _playbackSpeed
                        player.play()
                    } else {
                        player.pause()
                        _isPlaying = false
                    }
                }
            }
        }

    // Playback speed control
    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(0.5f, 2.0f)
            player?.rate = _playbackSpeed
        }

    // Playback states
    override val hasMedia: Boolean get() = _hasMedia
    override val isPlaying: Boolean get() = _isPlaying
    private var _hasMedia by mutableStateOf(false)
    private var _isPlaying by mutableStateOf(false)

    // Displayed texts for position and duration
    private var _positionText: String by mutableStateOf("00:00")
    override val positionText: String get() = _positionText
    private var _durationText: String by mutableStateOf("00:00")
    override val durationText: String get() = _durationText

    // Loading state
    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean
        get() = _isLoading

    // Fullscreen state
    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    override val error: VideoPlayerError? = null

    // Observable instance of AVPlayer
    var player: AVPlayer? by mutableStateOf(null)
        private set

    // Periodic observer for position updates (≈60 fps)
    private var timeObserverToken: Any? = null

    // Keep track of which player instance added the time observer
    private var timeObserverPlayer: AVPlayer? = null

    // End-of-playback notification observer
    private var endObserver: Any? = null

    // Stalled playback notification observer
    private var stalledObserver: Any? = null
    
    // App lifecycle notification observers
    private var backgroundObserver: Any? = null
    private var foregroundObserver: Any? = null
    
    // Flag to track if player was playing before going to background
    private var wasPlayingBeforeBackground: Boolean = false

    // Internal time values (in seconds)
    private var _currentTime: Double = 0.0
    private var _duration: Double = 0.0
    override val currentTime: Double get() = _currentTime

    // Flag to indicate user-initiated pause
    private var userInitiatedPause: Boolean = false

    // Audio levels (not yet implemented)
    override val leftLevel: Float = 0f
    override val rightLevel: Float = 0f

    // Observable video aspect ratio (default to 16:9)
    private var _videoAspectRatio by mutableStateOf(16.0 / 9.0)
    val videoAspectRatio: CGFloat
        get() = _videoAspectRatio

    override val aspectRatio: Float = _videoAspectRatio.toFloat()

    // Video metadata
    private var _metadata = VideoMetadata(audioChannels = 2)

    private fun startPositionUpdates() {
        stopPositionUpdates()

        // Only add observer if we have a valid player
        player?.let { currentPlayer ->
            val interval = CMTimeMakeWithSeconds(1.0 / 60.0, 600) // approx. 60 fps
            timeObserverToken = currentPlayer.addPeriodicTimeObserverForInterval(
                interval = interval,
                queue = dispatch_get_main_queue(),
                usingBlock = { time ->
                    val currentSeconds = CMTimeGetSeconds(time)
                    val durationSeconds = currentPlayer.currentItem?.duration?.let { CMTimeGetSeconds(it) } ?: 0.0
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

                    currentPlayer.currentItem?.presentationSize?.useContents {
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

                    currentPlayer.currentItem?.let { item ->
                        val isBufferEmpty = item.playbackBufferEmpty
                        val isLikelyToKeepUp = item.playbackLikelyToKeepUp
                        _isLoading = isBufferEmpty || !isLikelyToKeepUp
                    } ?: run {
                        _isLoading = false
                    }
                }
            )

            // Store which player instance added this observer
            timeObserverPlayer = currentPlayer
        }
    }

    private fun stopPositionUpdates() {
        // Only remove observer if we have both token and the same player instance
        timeObserverToken?.let { token ->
            timeObserverPlayer?.let { observerPlayer ->
                // Check if the current player is the same instance that added the observer
                if (player === observerPlayer) {
                    observerPlayer.removeTimeObserver(token)
                }
            }
            timeObserverToken = null
            timeObserverPlayer = null
        }
    }

    private fun setupAppLifecycleObservers() {
        // Remove any existing observers first
        removeAppLifecycleObservers()
        
        // Add observer for when app goes to background (screen lock)
        backgroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = UIApplication.sharedApplication,
            queue = null
        ) { _ ->
            Logger.d { "App entered background (screen locked)" }
            // Store current playing state before background
            wasPlayingBeforeBackground = _isPlaying
            
            // If player is paused by the system, update our state to match
            player?.let { player ->
                if (player.rate == 0.0f) {
                    Logger.d { "Player was paused by system, updating isPlaying state" }
                    _isPlaying = false
                }
            }
        }
        
        // Add observer for when app comes to foreground (screen unlock)
        foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = UIApplication.sharedApplication,
            queue = null
        ) { _ ->
            Logger.d { "App will enter foreground (screen unlocked)" }
            // If player was playing before going to background, resume playback
            if (wasPlayingBeforeBackground) {
                Logger.d { "Player was playing before background, resuming" }
                player?.let { player ->
                    // Only resume if the player is overridely paused
                    if (player.rate == 0.0f) {
                        player.rate = _playbackSpeed
                        player.play()
                        _isPlaying = true
                    }
                }
            }
        }
        
        Logger.d { "App lifecycle observers set up" }
    }
    
    private fun removeAppLifecycleObservers() {
        backgroundObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            backgroundObserver = null
        }
        
        foregroundObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            foregroundObserver = null
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
        
        removeAppLifecycleObservers()
    }

    /**
     * Clean up all resources associated with the current player
     */
    private fun cleanupCurrentPlayer() {
        stopPositionUpdates()
        removeObservers()
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        timeObserverPlayer = null
    }

    /**
     * Opens a media source from the given URI.
     *
     * IMPORTANT: iOS AVPlayer has a tendency to auto-play when certain properties are set.
     * To ensure proper behavior with InitialPlayerState.PAUSE, we need to:
     * 1. Explicitly call pause() on the player
     * 2. Set rate to 0
     * 3. Not set rate during initialization
     * 4. Update all relevant state variables
     *
     * @param uri The URI of the media to open
     * @param initializeplayerState Controls whether playback should start automatically after opening
     */
    override fun openUri(uri: String, initializeplayerState: InitialPlayerState) {
        Logger.d { "openUri called with uri: $uri, initializeplayerState: $initializeplayerState" }
        val nsUrl = NSURL.URLWithString(uri) ?: run {
            Logger.d { "Failed to create NSURL from uri: $uri" }
            return
        }

        // Clean up the current player completely before creating a new one
        cleanupCurrentPlayer()

        // Reset playback speed to 1.0f when opening a new video
        _playbackSpeed = 1.0f

        // Set loading state to true at the beginning of loading a new video
        _isLoading = true

        // Reset metadata to default values
        _metadata = VideoMetadata(audioChannels = 2)

        // Create a temporary player with minimal setup to show something immediately
        val tempPlayerItem = AVPlayerItem(nsUrl)
        player = AVPlayer(playerItem = tempPlayerItem).apply {
            volume = this@DefaultVideoPlayerState.volume
            rate = 0.0f // Explicitly set rate to 0 to prevent auto-play
            pause() // Explicitly pause to ensure it doesn't auto-play
            allowsExternalPlayback = false // Disable AirPlay
        }
        _hasMedia = true
        // Don't set _isPlaying to true yet, as we haven't decided whether to play or pause

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
                        _metadata.bitrate = trackBitrate.toLong()
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
                // Set audio channels to 2 (stereo) as a more accurate default
                // Most audio content is stereo, and we can't easily get the override channel count
                // from AVAssetTrack in Kotlin/Native
                _metadata.audioChannels = 2 // Default to stereo instead of using track count

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
                // Clean up any existing player before creating the new one
                cleanupCurrentPlayer()

                // Update metadata
                if (widthTemp != null && heightTemp != null) {
                    _metadata.width = widthTemp
                    _metadata.height = heightTemp
                    _videoAspectRatio = videoAspectRatioTemp
                }

                // Create the final player with the fully loaded asset
                player = AVPlayer(playerItem = playerItem).apply {
                    volume = this@DefaultVideoPlayerState.volume
                    // Don't set rate here, as it can cause auto-play
                    actionAtItemEnd = AVPlayerActionAtItemEndNone

                    // Configure AVPlayer to prevent automatic pausing during configuration changes
                    // like rotation or entering fullscreen mode
                    automaticallyWaitsToMinimizeStalling = false
                    
                    // Disable AirPlay
                    allowsExternalPlayback = false
                }

                // Set up the end observer with the current loop setting
                // This will be updated whenever the loop property changes
                endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = AVPlayerItemDidPlayToEndTimeNotification,
                    `object` = player?.currentItem,
                    queue = null
                ) { _ ->
                    if (userInitiatedPause) return@addObserverForName
                    if (_duration > 0 && (_duration - _currentTime) > 0.1) {
                        return@addObserverForName
                    }
                    if (_loop) {
                        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
                        player?.rate = _playbackSpeed
                        player?.play()
                    } else {
                        player?.pause()
                        _isPlaying = false
                    }
                }

                startPositionUpdates()
                
                // Set up app lifecycle observers
                setupAppLifecycleObservers()

                // Control initial playback state based on the parameter
                if (initializeplayerState == InitialPlayerState.PLAY) {
                    // For PLAY state, explicitly call play() which will set the rate
                    play()
                } else {
                    // For PAUSE state, ensure the player is paused
                    player?.pause()
                    // Explicitly set rate to 0 to prevent auto-play
                    player?.rate = 0.0f
                    // Update state variables
                    _isPlaying = false
                    _hasMedia = true
                    // Set loading to false since we're not playing
                    _isLoading = false
                }
            }
        }
    }

    override fun play() {
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

        // Ensure the player won't pause during configuration changes like rotation
        player?.automaticallyWaitsToMinimizeStalling = false
        
        // Set up app lifecycle observers
        setupAppLifecycleObservers()

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

    override fun pause() {
        Logger.d { "pause called" }
        userInitiatedPause = true
        // Ensure the pause call is on the main thread:
        dispatch_async(dispatch_get_main_queue()) {
            player?.pause()
        }
        _isPlaying = false
    }

    override fun stop() {
        Logger.d { "stop called" }
        player?.pause()
        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
        _isPlaying = false
        _hasMedia = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    override fun seekTo(value: Float) {
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


    override fun clearError() {
        Logger.d { "clearError called" }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        Logger.d { "toggleFullscreen called" }
        _isFullscreen = !_isFullscreen
    }

    override fun dispose() {
        Logger.d { "dispose called" }
        cleanupCurrentPlayer()
        _hasMedia = false
        _isPlaying = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    override fun openFile(file: PlatformFile, initializeplayerState: InitialPlayerState) {
        Logger.d { "openFile called with file: $file, initializeplayerState: $initializeplayerState" }
        // Use the getUri extension function to get a proper file URL
        val fileUrl = file.getUri()
        Logger.d { "Opening file with URL: $fileUrl" }
        openUri(fileUrl, initializeplayerState)
    }

    override val metadata: VideoMetadata
        get() = _metadata
    // Subtitle state
    private var _subtitlesEnabled by mutableStateOf(false)
    override var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            _subtitlesEnabled = value
        }

    private var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    override var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            _currentSubtitleTrack = value
        }

    private val _availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override val availableSubtitleTracks: MutableList<SubtitleTrack>
        get() = _availableSubtitleTracks

    override var subtitleTextStyle: TextStyle = TextStyle(
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Center
    )

    override var subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)

    /**
     * Selects a subtitle track for display.
     * If track is null, disables subtitles.
     *
     * @param track The subtitle track to select, or null to disable subtitles
     */
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
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
    override fun disableSubtitles() {
        Logger.d { "disableSubtitles called" }
        // Update state
        currentSubtitleTrack = null
        subtitlesEnabled = false

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
    }
}
