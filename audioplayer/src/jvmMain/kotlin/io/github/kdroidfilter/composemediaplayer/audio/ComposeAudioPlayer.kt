package io.github.kdroidfilter.composemediaplayer.audio

import io.github.kdroidfilter.rodio.PlaybackCallback
import io.github.kdroidfilter.rodio.PlaybackEvent
import io.github.kdroidfilter.rodio.RodioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Paths

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class ComposeAudioPlayer actual constructor() {
    private var player: RodioPlayer? = RodioPlayer()
    private var errorListener: ErrorListener? = null
    private var lastVolume: Float? = null
    @Volatile
    private var state: ComposeAudioPlayerState? = ComposeAudioPlayerState.IDLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    private val callback = object : PlaybackCallback {
        override fun onEvent(event: PlaybackEvent) {
            state = when (event) {
                PlaybackEvent.CONNECTING -> ComposeAudioPlayerState.BUFFERING
                PlaybackEvent.PLAYING -> ComposeAudioPlayerState.PLAYING
                PlaybackEvent.PAUSED -> ComposeAudioPlayerState.PAUSED
                PlaybackEvent.STOPPED -> ComposeAudioPlayerState.IDLE
            }
        }

        override fun onMetadata(key: String, value: String) {
        }

        override fun onError(message: String) {
            state = ComposeAudioPlayerState.IDLE
            errorListener?.onError(message)
        }
    }

    init {
        player?.setCallback(callback)
    }

    actual fun play(url: String) {
        val localPlayer = ensurePlayer()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            runCatching {
                if (isRemoteUrl(url)) {
                    localPlayer.playUrl(url, loop = false)
                } else {
                    localPlayer.playFile(resolveFilePath(url), loop = false)
                }
                lastVolume?.let { localPlayer.setVolume(it) }
            }.onFailure { error ->
                state = ComposeAudioPlayerState.IDLE
                errorListener?.onError(error.message)
            }
        }
    }

    actual fun play() {
        val localPlayer = player ?: return
        val current = currentPlayerState()
        if (current == null || current == ComposeAudioPlayerState.IDLE) return
        runCatching { localPlayer.play() }
            .onFailure { errorListener?.onError(it.message) }
    }

    actual fun stop() {
        val localPlayer = player ?: return
        runCatching { localPlayer.stop() }
            .onFailure { errorListener?.onError(it.message) }
        state = ComposeAudioPlayerState.IDLE
    }

    actual fun pause() {
        val localPlayer = player ?: return
        runCatching { localPlayer.pause() }
            .onFailure { errorListener?.onError(it.message) }
        state = ComposeAudioPlayerState.PAUSED
    }

    actual fun release() {
        playbackJob?.cancel()
        playbackJob = null
        val localPlayer = player
        if (localPlayer != null) {
            runCatching { localPlayer.clearCallback() }
            runCatching { localPlayer.close() }
        }
        player = null
        state = null
    }

    actual fun currentPosition(): Long? {
        val localPlayer = player ?: return null
        return runCatching { localPlayer.getPositionMs() }.getOrNull()
    }

    actual fun currentDuration(): Long? {
        val localPlayer = player ?: return null
        return runCatching { localPlayer.getDurationMs() }.getOrNull()
    }

    actual fun currentPlayerState(): ComposeAudioPlayerState? {
        val localPlayer = player ?: return null
        val snapshot = state ?: return null
        if (snapshot == ComposeAudioPlayerState.BUFFERING) return snapshot
        val isEmpty = runCatching { localPlayer.isEmpty() }.getOrDefault(true)
        if (isEmpty) return ComposeAudioPlayerState.IDLE
        val isPaused = runCatching { localPlayer.isPaused() }.getOrDefault(false)
        if (isPaused) return ComposeAudioPlayerState.PAUSED
        return ComposeAudioPlayerState.PLAYING
    }

    actual fun currentVolume(): Float? {
        if (player == null) return null
        return lastVolume ?: 1f
    }

    actual fun setVolume(volume: Float) {
        lastVolume = volume
        val localPlayer = player ?: return
        runCatching { localPlayer.setVolume(volume) }
            .onFailure { errorListener?.onError(it.message) }
    }

    actual fun setRate(rate: Float) {
    }

    actual fun seekTo(time: Long) {
        val localPlayer = player ?: return
        runCatching { localPlayer.seekToMs(time) }
            .onFailure { errorListener?.onError(it.message) }
    }

    actual fun setOnErrorListener(listener: ErrorListener) {
        errorListener = listener
    }

    private fun ensurePlayer(): RodioPlayer {
        val existing = player
        if (existing != null) return existing
        val newPlayer = RodioPlayer()
        newPlayer.setCallback(callback)
        lastVolume?.let { volume ->
            runCatching { newPlayer.setVolume(volume) }
        }
        player = newPlayer
        state = ComposeAudioPlayerState.IDLE
        return newPlayer
    }

    private fun isRemoteUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun resolveFilePath(url: String): String {
        if (!url.startsWith("file:", ignoreCase = true)) return url
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri != null) {
            val path = runCatching { Paths.get(uri).toString() }.getOrNull()
            if (!path.isNullOrBlank()) return path
            val uriPath = uri.path
            if (!uriPath.isNullOrBlank()) return uriPath
        }
        return url.substring(5)
    }
}
