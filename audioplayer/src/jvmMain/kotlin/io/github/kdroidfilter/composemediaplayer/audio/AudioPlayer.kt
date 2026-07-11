package io.github.kdroidfilter.composemediaplayer.audio

import dev.nucleusframework.rodio.PlaybackCallback
import dev.nucleusframework.rodio.PlaybackEvent
import dev.nucleusframework.rodio.RodioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Suppress(names = ["EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING"])
actual class AudioPlayer actual constructor() {
    private var player: RodioPlayer? = RodioPlayer()
    private var errorListener: ErrorListener? = null
    private var lastVolume: Float? = null
    @Volatile
    private var state: AudioPlayerState? = AudioPlayerState.IDLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackJob: Job? = null

    private val callback = object : PlaybackCallback {
        override fun onEvent(event: PlaybackEvent) {
            state = when (event) {
                PlaybackEvent.CONNECTING -> AudioPlayerState.BUFFERING
                PlaybackEvent.PLAYING -> AudioPlayerState.PLAYING
                PlaybackEvent.PAUSED -> AudioPlayerState.PAUSED
                PlaybackEvent.STOPPED -> AudioPlayerState.IDLE
            }
        }

        override fun onMetadata(key: String, value: String) {
        }

        override fun onError(message: String) {
            state = AudioPlayerState.IDLE
            errorListener?.onError(message)
        }
    }

    init {
        player?.setCallback(callback)
    }

    actual fun play(url: String, loop: Boolean) {
        val localPlayer = ensurePlayer()
        playbackJob?.cancel()
        playbackJob = scope.launch {
            runCatching {
                if (isRemoteUrl(url)) {
                    localPlayer.playUrl(url, loop = loop)
                } else {
                    localPlayer.playFile(resolveFilePath(url), loop = loop)
                }
                lastVolume?.let { localPlayer.setVolume(it) }
            }.onFailure { error ->
                state = AudioPlayerState.IDLE
                errorListener?.onError(error.message)
            }
        }
    }

    actual fun play() {
        val localPlayer = player ?: return
        val current = currentPlayerState()
        if (current == null || current == AudioPlayerState.IDLE) return
        runCatching { localPlayer.play() }
            .onFailure { errorListener?.onError(it.message) }
    }

    actual fun stop() {
        val localPlayer = player ?: return
        runCatching { localPlayer.stop() }
            .onFailure { errorListener?.onError(it.message) }
        state = AudioPlayerState.IDLE
    }

    actual fun pause() {
        val localPlayer = player ?: return
        runCatching { localPlayer.pause() }
            .onFailure { errorListener?.onError(it.message) }
        state = AudioPlayerState.PAUSED
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

    actual fun currentPlayerState(): AudioPlayerState? {
        val localPlayer = player ?: return null
        val snapshot = state ?: return null
        if (snapshot == AudioPlayerState.BUFFERING) return snapshot
        val isEmpty = runCatching { localPlayer.isEmpty() }.getOrDefault(true)
        if (isEmpty) return AudioPlayerState.IDLE
        val isPaused = runCatching { localPlayer.isPaused() }.getOrDefault(false)
        if (isPaused) return AudioPlayerState.PAUSED
        return AudioPlayerState.PLAYING
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
        state = AudioPlayerState.IDLE
        return newPlayer
    }

    private fun isRemoteUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }

    private fun resolveFilePath(url: String): String {
        if (url.startsWith("jar:", ignoreCase = true)) return extractToTempFile(url)
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

    /**
     * Resources bundled inside a jar (e.g. `Res.getUri(...)` in a packaged desktop app) cannot be
     * read by the native backend directly, so they are extracted once to the temp directory.
     */
    private fun extractToTempFile(url: String): String {
        val cached = extractedResources[url]
        if (cached != null && Files.exists(cached)) return cached.toString()
        val fileName = url.substringAfterLast('/').ifBlank { "audio" }
        val target = Files.createTempFile("composemediaplayer-audio-", "-$fileName")
        URI(url).toURL().openStream().use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        target.toFile().deleteOnExit()
        extractedResources[url] = target
        return target.toString()
    }

    private companion object {
        val extractedResources = ConcurrentHashMap<String, Path>()
    }
}
