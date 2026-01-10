/*
 * Copyright 2025 Konstantin <hi@iamkonstantin.eu>.
 *  Use of this source code is governed by the BSD 3-Clause License that can be found in LICENSE file.
 */

package io.github.kdroidfilter.composemediaplayer.audio

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kdroid.androidcontextprovider.ContextProvider

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class AudioPlayer actual constructor() {

    private var mediaPlayer = ExoPlayer.Builder(ContextProvider.getContext()).build()
    private var errorListener: ErrorListener? = null

    init {
        setup()
    }

    actual fun play(url: String) {
        if (mediaPlayer.isPlaying) {
            stop()
        }

        if (mediaPlayer.isCommandAvailable(Player.COMMAND_PREPARE)) mediaPlayer.prepare()

        val mediaItem = MediaItem.fromUri(url)

        if (mediaPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) mediaPlayer.setMediaItem(mediaItem)

        if(mediaPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) mediaPlayer.play()
    }

    @OptIn(UnstableApi::class)
    actual fun play() {
        if(mediaPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            if (currentPlayerState() == AudioPlayerState.IDLE)
                seekTo(0)
            mediaPlayer.play()
        }
    }


    /**
     * Android-specific implementation of the [play] method which uses a ContentResolver to calculate the Uri of a raw file resource bundled with the app.
     */
    fun play(rawResourceId: Int) {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .appendPath("$rawResourceId")
            .build().toString()
        play(uri)
    }

    actual fun currentPosition(): Long? {
        if (mediaPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            return mediaPlayer.currentPosition
        }
        return null
    }

    actual fun currentDuration(): Long? {
        if (mediaPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
            if (mediaPlayer.duration >= 0) return mediaPlayer.duration
        }
        return null
    }

    @UnstableApi
    actual fun currentPlayerState(): AudioPlayerState? {
        if (mediaPlayer.isReleased) {
            return null
        }
        // https://mofazhe.github.io/ExoPlayer-ffmpeg/listening-to-player-events.html
        val state = mediaPlayer.playbackState
        val playWhenReady = mediaPlayer.playWhenReady
        return when {
            state == Player.STATE_READY && playWhenReady -> AudioPlayerState.PLAYING
            state == Player.STATE_READY && !playWhenReady -> AudioPlayerState.PAUSED
            state == Player.STATE_BUFFERING -> AudioPlayerState.BUFFERING
            state == Player.STATE_IDLE -> AudioPlayerState.IDLE
            state == Player.STATE_ENDED -> AudioPlayerState.IDLE
            else -> AudioPlayerState.IDLE
        }
    }

    actual fun release() {
        mediaPlayer.stop()
        mediaPlayer.release()
    }

    actual fun stop() {
        mediaPlayer.stop()
    }

    actual fun pause() {
        mediaPlayer.pause()
    }

    actual fun currentVolume(): Float? {
        if(mediaPlayer.isCommandAvailable(Player.COMMAND_GET_VOLUME)) {
            return mediaPlayer.volume
        }
        return null
    }

    actual fun setVolume(volume: Float) {
        if (!mediaPlayer.isCommandAvailable(Player.COMMAND_GET_VOLUME)) return
        mediaPlayer.volume = volume
    }

    actual fun setRate(rate: Float) {
        if (!mediaPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        mediaPlayer.playbackParameters = mediaPlayer.playbackParameters.withSpeed(rate)
    }

    actual fun seekTo(time: Long) {
        if (!mediaPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        mediaPlayer.seekTo(time)
    }

    actual fun setOnErrorListener(listener: ErrorListener){
        errorListener = listener
    }

    private fun setup() {

        mediaPlayer.addListener(object :Player.Listener{
            override fun onPlayerError(error: PlaybackException) {
                errorListener?.onError(error.errorCodeName)
                super.onPlayerError(error)
            }
        })

    }
}
