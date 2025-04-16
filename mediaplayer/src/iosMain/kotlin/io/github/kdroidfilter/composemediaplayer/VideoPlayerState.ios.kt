@file:OptIn(ExperimentalForeignApi::class)
package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.*
import platform.CoreGraphics.CGFloat
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
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

    actual val error: VideoPlayerError? = null

    // Observable instance of AVPlayer
    var player: AVPlayer? by mutableStateOf(null)
        private set

    // Periodic observer for position updates (≈60 fps)
    private var timeObserverToken: Any? = null

    // End-of-playback notification observer
    private var endObserver: Any? = null

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

                if (!userDragging && durationSeconds > 0 && !currentSeconds.isNaN() && !durationSeconds.isNaN()) {
                    sliderPos = ((currentSeconds / durationSeconds) * 1000).toFloat()
                }
                _positionText = if (currentSeconds.isNaN()) "00:00" else formatTime(currentSeconds.toFloat())
                _durationText = if (durationSeconds.isNaN()) "00:00" else formatTime(durationSeconds.toFloat())

                player?.currentItem?.presentationSize?.useContents {
                    val newAspect = if (height != 0.0) width / height else 16.0 / 9.0
                    if (newAspect != _videoAspectRatio) {
                        _videoAspectRatio = newAspect
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

    private fun removeEndObserver() {
        endObserver?.let {
            NSNotificationCenter.defaultCenter.removeObserver(it)
            endObserver = null
        }
    }

    actual fun openUri(uri: String) {
        Logger.d { "openUri called with uri: $uri" }
        val nsUrl = NSURL.URLWithString(uri) ?: run {
            Logger.d { "Failed to create NSURL from uri: $uri" }
            return
        }

        stopPositionUpdates()
        removeEndObserver()
        player?.pause()

        // Set loading state to true at the beginning of loading a new video
        _isLoading = true

        val playerItem = AVPlayerItem(nsUrl)
        playerItem.presentationSize.useContents {
            _videoAspectRatio = if (height != 0.0) width / height else 16.0 / 9.0
        }

        player = AVPlayer(playerItem = playerItem).apply {
            volume = this@VideoPlayerState.volume
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
                player?.play()
            } else {
                player?.pause()
                _isPlaying = false
            }
        }

        startPositionUpdates()
        _hasMedia = true
        play()
    }

    actual fun play() {
        Logger.d { "play called" }
        userInitiatedPause = false
        if (player == null) {
            Logger.d { "play: player is null" }
            return
        }
        player?.volume = volume
        player?.play()
        _isPlaying = true
        _hasMedia = true
        _isLoading = false
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

            // Reset loading state after a short delay
            dispatch_async(dispatch_get_main_queue()) {
                _isLoading = false
            }
        }
    }


    actual fun clearError() {
        Logger.d { "clearError called" }
    }

    actual fun dispose() {
        Logger.d { "dispose called" }
        stopPositionUpdates()
        removeEndObserver()
        player?.pause()
        player = null
        _hasMedia = false
        _isPlaying = false
    }

    actual fun openFile(file: PlatformFile) {
        Logger.d { "openFile called with file: $file" }
        openUri(file.toString())
    }

    actual val metadata: VideoMetadata
        get() = TODO("Not yet implemented")
    actual var subtitlesEnabled: Boolean
        get() = TODO("Not yet implemented")
        set(_) {}
    actual var currentSubtitleTrack: SubtitleTrack?
        get() = TODO("Not yet implemented")
        set(_) {}
    actual val availableSubtitleTracks: MutableList<SubtitleTrack>
        get() = TODO("Not yet implemented")

    actual fun selectSubtitleTrack(track: SubtitleTrack?) {
    }

    actual fun disableSubtitles() {
    }
}
