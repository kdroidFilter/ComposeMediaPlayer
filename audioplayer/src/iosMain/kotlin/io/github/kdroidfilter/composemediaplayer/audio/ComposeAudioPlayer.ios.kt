/*
 * Copyright 2025 Konstantin <hi@iamkonstantin.eu>.
 *  Use of this source code is governed by the BSD 3-Clause License that can be found in LICENSE file.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.audio

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSURL
import platform.darwin.NSEC_PER_SEC

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class ComposeAudioPlayer actual constructor() {
    private var player: AVPlayer? = null
    private var playerObserver: CupertinoAVPlayerObserver? = null
    private var errorListener: ErrorListener? = null
    private var lastVolume: Float? = null
    private var lastRate: Float? = null

    actual fun play(url: String) {
        release()
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
        val nsUrl = NSURL(string = url)
        val asset = AVURLAsset(uRL = nsUrl, options = mapOf(AVURLAssetPreferPreciseDurationAndTimingKey to true))
        val item = AVPlayerItem(asset = asset, automaticallyLoadedAssetKeys = listOf("duration", "playable"))

        if (player == null)
            player = AVPlayer.playerWithPlayerItem(item)
        else
            player!!.replaceCurrentItemWithPlayerItem(item)

        lastVolume?.let { player?.volume = it }

        setup()
        player?.play()
        lastRate?.let { player?.rate = it }
    }

    actual fun play() {
        // https://developer.apple.com/documentation/avfoundation/avplayer/play()
        if (player?.currentItem != null) {
            if (currentPlayerState() == ComposeAudioPlayerState.IDLE)
                seekTo(0)
            player?.play()
            lastVolume?.let { player?.volume = it }
            lastRate?.let { player?.rate = it }
        }
    }


    actual fun release() {
        playerObserver?.detach()
        player?.pause()
        player = null
        _state = null
    }

    actual fun stop() {
        player?.pause()
        player?.replaceCurrentItemWithPlayerItem(null)
        _state = ComposeAudioPlayerState.IDLE
    }

    actual fun pause() {
        player?.pause()
    }

    actual fun currentPosition(): Long? {
        try {
            val currentTimeSeconds = player?.currentTime()?.useContents {
                if (this.timescale == 0) return@useContents null
                this.value / this.timescale
            }
            if (currentTimeSeconds != null && currentTimeSeconds >= 0) {
                return (currentTimeSeconds * 1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    actual fun currentDuration(): Long? {
        val item = player?.currentItem ?: return null
        val itemSeconds = CMTimeGetSeconds(item.duration)

        // Try player item's duration
        if (!itemSeconds.isNaN() && !itemSeconds.isInfinite() && itemSeconds >= 0)
            return (itemSeconds * 1000.0).toLong()

        // Fallback to underlying asset's duration
        val assetSeconds = CMTimeGetSeconds(item.asset.duration)
        if (!assetSeconds.isNaN() && !assetSeconds.isInfinite() && assetSeconds >= 0)
            return (assetSeconds * 1000.0).toLong()

        return null
    }

    actual fun currentVolume(): Float? {
        // https://developer.apple.com/documentation/avfoundation/avplayer/volume
        return player?.volume
    }

    actual fun setVolume(volume: Float) {
        lastVolume = volume
        player?.volume = volume
    }

    actual fun setRate(rate: Float) {
        // https://developer.apple.com/documentation/avfoundation/controlling-the-transport-behavior-of-a-player#Control-the-playback-rate
        lastRate = rate
        player?.rate = rate
    }


    actual fun seekTo(time: Long) {
        // https://developer.apple.com/documentation/avfoundation/avplayer/seek(to:)-87h2r
        val duration = CMTimeMake(time, 1000)
        player?.seekToTime(duration)
    }

    actual fun currentPlayerState(): ComposeAudioPlayerState? {
        return _state
    }

    private var _state: ComposeAudioPlayerState? = null


    private fun setup() {
        playerObserver?.detach()

        val observer = CupertinoAVPlayerObserver(player)
        observer.attach(
            onAVPlayerUpdated = {
                val rate: Float = player?.rate ?: 0f
                val hasItem = player?.currentItem != null
                val avStatus = player?.currentItem?.status
                val bufferingEnding = player?.currentItem?.isPlaybackLikelyToKeepUp() == true
                val bufferIsEmpty = player?.currentItem?.isPlaybackBufferEmpty() == true
                // https://developer.apple.com/documentation/avfoundation/avplayer/timecontrolstatus-swift.property/#Discussion
                val waitingToPlayAtRate =
                    player?.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
                _state = when {
                    rate > 0 -> ComposeAudioPlayerState.PLAYING
                    !hasItem -> ComposeAudioPlayerState.IDLE
                    avStatus == AVPlayerItemStatusFailed -> ComposeAudioPlayerState.IDLE
                    !bufferingEnding || bufferIsEmpty || waitingToPlayAtRate -> ComposeAudioPlayerState.BUFFERING
                    else -> ComposeAudioPlayerState.PAUSED
                }
            },
            onAVPlayerEnded = {
                _state = ComposeAudioPlayerState.IDLE
            },
            onAVPlayerStalled = {
                _state = ComposeAudioPlayerState.BUFFERING
            },
            onAVPlayerError = {
                errorListener?.onError(it)
            }
        )

        playerObserver = observer
    }

    actual fun setOnErrorListener(listener: ErrorListener) {
        errorListener = listener
    }
}

class CupertinoAVPlayerObserver(private val player: AVPlayer?) {
    // based on https://developer.apple.com/documentation/avfoundation/monitoring-playback-progress-in-your-app
    private var timeObserver: Any? = null
    private var endObserver: Any? = null
    private var stallObserver: Any? = null
    private var errorObserver: Any? = null

    @OptIn(ExperimentalForeignApi::class)
    fun attach(
        onAVPlayerUpdated: () -> Unit,
        onAVPlayerEnded: () -> Unit,
        onAVPlayerStalled: () -> Unit,
        onAVPlayerError: (message: String) -> Unit
    ) {
        detach()
        if (player == null) return

        // Buffering state
        onAVPlayerUpdated()

        val interval = CMTimeMakeWithSeconds(0.5, NSEC_PER_SEC.toInt()) // update every ~0.5 seconds
        timeObserver = player.addPeriodicTimeObserverForInterval(interval, null) { _: CValue<CMTime> ->
            onAVPlayerUpdated()
        }

        player.currentItem?.let { item ->
            endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = null,
            ) { _ ->
                onAVPlayerEnded()
            }

            stallObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemPlaybackStalledNotification,
                `object` = item,
                queue = null,
            ) { _ ->
                onAVPlayerStalled()
            }

            errorObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = AVPlayerItemFailedToPlayToEndTimeNotification,
                `object` = item,
                queue = null,
            ) { notification ->
                val error = notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? String
                onAVPlayerError(error?:"Not available")
            }


        }
    }

    fun detach() {
        timeObserver?.let { player?.removeTimeObserver(it) }
        timeObserver = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        stallObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        stallObserver = null
        errorObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        errorObserver = null
    }
}
