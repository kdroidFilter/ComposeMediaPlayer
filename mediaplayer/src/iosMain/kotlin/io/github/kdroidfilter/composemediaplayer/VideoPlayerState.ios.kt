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
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategorySoloAmbient
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMoviePlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.*
import platform.AVKit.AVPictureInPictureController
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingOptions
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

actual fun createVideoPlayerState(audioMode: AudioMode): VideoPlayerState = DefaultVideoPlayerState(audioMode)

private val iosLogger = TaggedLogger("iOSVideoPlayerState")

@Stable
open class DefaultVideoPlayerState(
    private val audioMode: AudioMode = AudioMode(),
) : VideoPlayerState {
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

    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override var sliderPos: Float by mutableStateOf(0f) // value between 0 and 1000
    override var userDragging: Boolean = false
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
            iosLogger.d { "Loop setting changed to: $value" }
        }

    // Playback speed control
    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            _playbackSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            // Only update player rate if we are playing to avoid auto-play
            if (_isPlaying) {
                player?.rate = _playbackSpeed
            }
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

    override val isPipSupported: Boolean
        get() = AVPictureInPictureController.isPictureInPictureSupported()

    private var _isPipEnabled by mutableStateOf(false)

    override var isPipEnabled: Boolean
        get() = _isPipEnabled
        set(value) {
            pipController?.setCanStartPictureInPictureAutomaticallyFromInline(value)
            _isPipEnabled = value
        }

    override var isPipActive by mutableStateOf(false)

    override val error: VideoPlayerError? = null

    // Observable instance of AVPlayer
    var player: AVPlayer? by mutableStateOf(null)
        private set

    var playerLayer: AVPlayerLayer? by mutableStateOf(null)
        internal set

    internal var pipController: AVPictureInPictureController? = null

    // Periodic observer for position updates (≈60 fps)
    private var timeObserverToken: Any? = null

    // KVO Observers
    private var timeControlStatusObserver: NSObject? = null
    private var statusObserver: NSObject? = null

    // End-of-playback notification observer
    private var endObserver: Any? = null

    // App lifecycle notification observers
    private var backgroundObserver: Any? = null
    private var foregroundObserver: Any? = null

    // Flag to track if player was playing before going to background
    private var wasPlayingBeforeBackground: Boolean = false

    // Flag to track if the state has been disposed
    private var isDisposed = false

    // Internal time values (in seconds)
    private var _currentTime: Double = 0.0
    private var _duration: Double = 0.0
    override val currentTime: Double get() = _currentTime
    override val duration: Double get() = _duration

    // Observable video aspect ratio (default to 16:9)
    private var _videoAspectRatio by mutableStateOf(16.0 / 9.0)
    val videoAspectRatio: CGFloat
        get() = _videoAspectRatio

    override val aspectRatio: Float = _videoAspectRatio.toFloat()

    // Video metadata
    private var _metadata = VideoMetadata(audioChannels = 2)

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        try {
            val category =
                if (audioMode.playsInSilentMode) {
                    AVAudioSessionCategoryPlayback
                } else {
                    when (audioMode.interruptionMode) {
                        InterruptionMode.DoNotMix -> AVAudioSessionCategorySoloAmbient
                        InterruptionMode.MixWithOthers,
                        InterruptionMode.DuckOthers,
                        -> AVAudioSessionCategoryAmbient
                    }
                }

            val mode =
                if (audioMode.playsInSilentMode) {
                    AVAudioSessionModeMoviePlayback
                } else {
                    AVAudioSessionModeDefault
                }

            val options: ULong =
                when (audioMode.interruptionMode) {
                    InterruptionMode.DoNotMix -> 0u
                    InterruptionMode.MixWithOthers -> AVAudioSessionCategoryOptionMixWithOthers
                    InterruptionMode.DuckOthers ->
                        AVAudioSessionCategoryOptionMixWithOthers or
                            AVAudioSessionCategoryOptionDuckOthers
                }

            session.setCategory(category, mode = mode, options = options, error = null)
            session.setActive(true, error = null)
        } catch (e: Exception) {
            iosLogger.e { "Failed to configure audio session: ${e.message}" }
        }
    }

    private fun startPositionUpdates(player: AVPlayer) {
        val interval = CMTimeMakeWithSeconds(1.0 / 60.0, NSEC_PER_SEC.toInt()) // approx. 60 fps
        timeObserverToken =
            player.addPeriodicTimeObserverForInterval(
                interval = interval,
                queue = dispatch_get_main_queue(),
                usingBlock = { time ->
                    val currentSeconds = CMTimeGetSeconds(time)
                    val durationSeconds = player.currentItem?.duration?.let { CMTimeGetSeconds(it) } ?: 0.0
                    _currentTime = currentSeconds
                    _duration = durationSeconds

                    // Update duration in metadata
                    if (durationSeconds > 0 && !durationSeconds.isNaN()) {
                        _metadata.duration = (durationSeconds * 1000).toLong()
                    }

                    if (!(userDragging || isLoading) &&
                        durationSeconds > 0 &&
                        !currentSeconds.isNaN() &&
                        !durationSeconds.isNaN()
                    ) {
                        sliderPos = ((currentSeconds / durationSeconds) * 1000).toFloat()
                    }
                    _positionText = if (currentSeconds.isNaN()) "00:00" else formatTime(currentSeconds.toFloat())
                    _durationText = if (durationSeconds.isNaN()) "00:00" else formatTime(durationSeconds.toFloat())

                    player.currentItem?.presentationSize?.useContents {
                        // Only update if dimensions are valid (greater than 0)
                        if (width > 0 && height > 0) {
                            // Try to use real aspect ratio if available, fallback to 16:9
                            val realAspect = width / height
                            _videoAspectRatio = realAspect

                            // Update width and height in metadata if they're not already set or if they're zero
                            if (_metadata.width == null ||
                                _metadata.width == 0 ||
                                _metadata.height == null ||
                                _metadata.height == 0
                            ) {
                                _metadata.width = width.toInt()
                                _metadata.height = height.toInt()
                                iosLogger.d {
                                    "Video resolution updated during playback: ${width.toInt()}x${height.toInt()}"
                                }
                            }
                        }
                    }
                },
            )
    }

    override suspend fun enterPip(): PipResult {
        if (!isPipSupported) return PipResult.NotSupported
        if (!isPipEnabled) return PipResult.NotEnabled
        pipController?.setCanStartPictureInPictureAutomaticallyFromInline(true)
        pipController?.startPictureInPicture()
        isPipActive = true
        return PipResult.Success
    }

    private fun setupObservers(
        player: AVPlayer,
        item: AVPlayerItem,
    ) {
        // KVO for timeControlStatus (Playing, Paused, Loading)
        timeControlStatusObserver =
            player.observe("timeControlStatus") { _ ->
                when (player.timeControlStatus) {
                    AVPlayerTimeControlStatusPlaying -> {
                        _isPlaying = true
                        _isLoading = false
                    }
                    AVPlayerTimeControlStatusPaused -> {
                        if (player.reasonForWaitingToPlay == null) {
                            _isPlaying = false
                        }
                        _isLoading = false
                    }
                    AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> {
                        _isLoading = true
                    }
                }
            }

        // KVO for status (Ready, Failed)
        statusObserver =
            item.observe("status") { _ ->
                when (item.status) {
                    AVPlayerItemStatusReadyToPlay -> {
                        _isLoading = false
                        iosLogger.d { "Player Item Ready" }
                    }
                    AVPlayerItemStatusFailed -> {
                        _isLoading = false
                        _isPlaying = false
                        iosLogger.e { "Player Item Failed: ${item.error?.localizedDescription}" }
                    }
                }
            }

        // Periodic Time Observer
        startPositionUpdates(player)

        // Notification for End of Playback
        endObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = null,
            ) { _ ->
                if (_loop) {
                    val zeroTime = CMTimeMake(0, 1)
                    player.seekToTime(
                        time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
                        toleranceBefore = zeroTime,
                        toleranceAfter = zeroTime,
                    ) { finished ->
                        if (finished) {
                            dispatch_async(dispatch_get_main_queue()) {
                                player.playImmediatelyAtRate(_playbackSpeed)
                                onRestart?.invoke()
                            }
                        }
                    }
                } else {
                    player.pause()
                    _isPlaying = false
                    onPlaybackEnded?.invoke()
                }
            }

        setupAppLifecycleObservers()
    }

    private fun stopPositionUpdates() {
        timeObserverToken?.let {
            player?.removeTimeObserver(it)
        }
        timeObserverToken = null
    }

    private fun setupAppLifecycleObservers() {
        // Remove any existing observers first
        removeAppLifecycleObservers()

        // Add observer for when app goes to background (screen lock)
        backgroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = UIApplication.sharedApplication,
                queue = null,
            ) { _ ->
                iosLogger.d { "App entered background (screen locked)" }
                // Store current playing state before background
                wasPlayingBeforeBackground = _isPlaying

                // If player is paused by the system, update our state to match
                player?.let { player ->
                    if (player.rate == 0.0f) {
                        iosLogger.d { "Player was paused by system, updating isPlaying state" }
                        _isPlaying = false
                    }
                }
            }

        // Add observer for when app comes to foreground (screen unlock)
        foregroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = UIApplication.sharedApplication,
                queue = null,
            ) { _ ->
                iosLogger.d { "App will enter foreground (screen unlocked)" }
                // If player was playing before going to background, resume playback
                if (wasPlayingBeforeBackground) {
                    iosLogger.d { "Player was playing before background, resuming" }
                    player?.let { player ->
                        // Only resume if the player is overridely paused
                        if (player.rate == 0.0f) {
                            player.playImmediatelyAtRate(_playbackSpeed)
                        }
                    }
                }
            }

        iosLogger.d { "App lifecycle observers set up" }
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
        // Remove KVOs
        timeControlStatusObserver?.let {
            player?.removeObserver(it, forKeyPath = "timeControlStatus")
            timeControlStatusObserver = null
        }

        statusObserver?.let {
            player?.currentItem?.removeObserver(it, forKeyPath = "status")
            statusObserver = null
        }

        endObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            endObserver = null
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
    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        iosLogger.d { "openUri called with uri: $uri, initializeplayerState: $initializeplayerState" }
        val nsUrl =
            NSURL.URLWithString(uri) ?: run {
                iosLogger.d { "Failed to create NSURL from uri: $uri" }
                return
            }

        // Clean up the current player completely before creating a new one
        cleanupCurrentPlayer()

        // Configure audio session
        configureAudioSession()

        // Reset playback speed to 1.0f when opening a new video
        _playbackSpeed = 1.0f

        // Set loading state to true at the beginning of loading a new video
        _isLoading = true

        // Reset metadata to default values
        _metadata = VideoMetadata(audioChannels = 2)

        _hasMedia = false
        // Don't set _isPlaying to true yet, as we haven't decided whether to play or pause

        // Process the asset on a background thread to avoid blocking the UI
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
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
                            iosLogger.d { "Video resolution from track: ${width.toInt()}x${height.toInt()}" }
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
            val durationSeconds = CMTimeGetSeconds(playerItem.duration)
            if (durationSeconds > 0 && !durationSeconds.isNaN()) {
                _metadata.duration = (durationSeconds * 1000).toLong()
            }

            // Try to extract title from the file name
            nsUrl.lastPathComponent?.let { _metadata.title = it }

            // Update UI on the main thread
            dispatch_async(dispatch_get_main_queue()) {
                // Check if disposed
                if (isDisposed) {
                    iosLogger.d { "player disposed, canceling initialization" }
                    return@dispatch_async
                }

                // Clean up any existing player before creating the new one
                cleanupCurrentPlayer()

                // Update metadata
                if (widthTemp != null && heightTemp != null) {
                    _metadata.width = widthTemp
                    _metadata.height = heightTemp
                    _videoAspectRatio = videoAspectRatioTemp
                }

                // Create the final player with the fully loaded asset
                val newPlayer =
                    AVPlayer(playerItem = playerItem).apply {
                        volume = this@DefaultVideoPlayerState.volume
                        // Don't set rate here, as it can cause auto-play
                        actionAtItemEnd = AVPlayerActionAtItemEndNone

                        // For HLS auto-playing needs to be true
                        automaticallyWaitsToMinimizeStalling = true

                        // Disable AirPlay
                        allowsExternalPlayback = false
                    }

                player = newPlayer
                _hasMedia = true

                setupObservers(newPlayer, playerItem)

                // Control initial playback state based on the parameter
                if (initializeplayerState == InitialPlayerState.PLAY) {
                    // For PLAY state, explicitly call play() which will set the rate
                    play()
                } else {
                    // For PAUSE state, ensure the player is paused
                    newPlayer.pause()
                }
            }
        }
    }

    override fun play() {
        iosLogger.d { "play called" }
        val currentPlayer = player
        if (currentPlayer == null) {
            iosLogger.d { "play: player is null" }
            return
        }
        // Configure audio session
        configureAudioSession()
        // If the player has reached the end, seek to the beginning first
        val currentItem = currentPlayer.currentItem
        if (currentItem != null) {
            val currentTime = CMTimeGetSeconds(currentItem.currentTime())
            val duration = CMTimeGetSeconds(currentItem.duration)
            if (duration > 0 && currentTime >= duration) {
                val zeroTime = CMTimeMake(0, 1)
                currentPlayer.seekToTime(
                    time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
                    toleranceBefore = zeroTime,
                    toleranceAfter = zeroTime,
                ) { finished ->
                    if (finished) {
                        dispatch_async(dispatch_get_main_queue()) {
                            currentPlayer.playImmediatelyAtRate(_playbackSpeed)
                        }
                    }
                }
                return
            }
        }
        currentPlayer.playImmediatelyAtRate(_playbackSpeed)
        // KVO will update isPlaying
    }

    override fun restart() {
        iosLogger.d { "restart called" }
        val currentPlayer = player ?: return
        configureAudioSession()
        val zeroTime = CMTimeMake(0, 1)
        currentPlayer.seekToTime(
            time = CMTimeMakeWithSeconds(0.0, NSEC_PER_SEC.toInt()),
            toleranceBefore = zeroTime,
            toleranceAfter = zeroTime,
        ) { finished ->
            if (finished) {
                dispatch_async(dispatch_get_main_queue()) {
                    currentPlayer.playImmediatelyAtRate(_playbackSpeed)
                }
            }
        }
    }

    override fun pause() {
        iosLogger.d { "pause called" }
        // Ensure the pause call is on the main thread:
        dispatch_async(dispatch_get_main_queue()) {
            player?.pause()
        }
        // KVO will update isPlaying
    }

    override fun stop() {
        iosLogger.d { "stop called" }
        player?.pause()
        player?.seekToTime(CMTimeMakeWithSeconds(0.0, 1))
        _isPlaying = false
        _isLoading = false
        _hasMedia = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    override fun seekTo(value: Float) {
        val currentPlayer = player ?: return
        if (_duration > 0) {
            // Set loading state to true to indicate seeking is happening
            _isLoading = true

            val targetTime = _duration * (value / 1000.0)
            val seekTime = CMTimeMakeWithSeconds(targetTime, NSEC_PER_SEC.toInt())
            val wasPlaying = _isPlaying

            // Create a zero time value for tolerance to ensure precise seeking
            val zeroTime = CMTimeMake(0, 1)

            currentPlayer.seekToTime(
                time = seekTime,
                toleranceBefore = zeroTime,
                toleranceAfter = zeroTime,
            ) { finished ->
                if (finished) {
                    dispatch_async(dispatch_get_main_queue()) {
                        _isLoading = false
                        if (wasPlaying) {
                            currentPlayer.playImmediatelyAtRate(_playbackSpeed)
                        }
                    }
                }
            }
        }
    }

    override fun clearError() {
        iosLogger.d { "clearError called" }
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        iosLogger.d { "toggleFullscreen called" }
        _isFullscreen = !_isFullscreen
    }

    override fun dispose() {
        iosLogger.d { "dispose called" }
        isDisposed = true
        cleanupCurrentPlayer()
        _hasMedia = false
        _isPlaying = false

        // Reset metadata
        _metadata = VideoMetadata(audioChannels = 2)
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        iosLogger.d { "openFile called with file: $file, initializeplayerState: $initializeplayerState" }
        // Use the getUri extension function to get a proper file URL
        val fileUrl = file.getUri()
        iosLogger.d { "Opening file with URL: $fileUrl" }
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

    override var subtitleTextStyle: TextStyle =
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )

    override var subtitleBackgroundColor: Color = Color.Black.copy(alpha = 0.5f)

    /**
     * Selects a subtitle track for display.
     * If track is null, disables subtitles.
     *
     * @param track The subtitle track to select, or null to disable subtitles
     */
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        iosLogger.d { "selectSubtitleTrack called with track: $track" }
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
        iosLogger.d { "disableSubtitles called" }
        // Update state
        currentSubtitleTrack = null
        subtitlesEnabled = false

        // iOS uses Compose-based subtitles, so we don't need to configure
        // the native player for subtitle display
    }
}

@OptIn(ExperimentalForeignApi::class)
private class KVOObserver(
    private val block: (Any?) -> Unit,
) : NSObject(),
    NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        block(change?.get(NSKeyValueChangeNewKey))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSObject.observe(
    keyPath: String,
    options: NSKeyValueObservingOptions = NSKeyValueObservingOptionNew,
    block: (Any?) -> Unit,
): NSObject {
    val observer = KVOObserver(block)
    this.addObserver(
        observer,
        forKeyPath = keyPath,
        options = options,
        context = null,
    )
    return observer
}
