/*
 * Copyright 2025 Konstantin <hi@iamkonstantin.eu>.
 *  Use of this source code is governed by the BSD 3-Clause License that can be found in LICENSE file.
 */

package io.github.kdroidfilter.composemediaplayer.audio

import kotlinx.browser.document
import kotlinx.dom.appendElement
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@OptIn(ExperimentalUuidApi::class)
actual class AudioPlayer actual constructor() {

    private val htmlId = Uuid.random().toString()
    private var _state: AudioPlayerState? = AudioPlayerState.IDLE
    private val events = mutableListOf<() -> Unit>()
    private var lastVolume: Double? = null
    private var lastRate: Double? = null
    private var errorListener: ErrorListener? = null

    private fun attachEventListeners(el: HTMLAudioElement) {
        detachEventListeners()

        val onPlaying: (Event) -> Unit = { _state = AudioPlayerState.PLAYING }
        val onPlay: (Event) -> Unit = { _state = AudioPlayerState.PLAYING }
        val onPause: (Event) -> Unit = { _state = AudioPlayerState.PAUSED }
        val onEnded: (Event) -> Unit = { _state = AudioPlayerState.IDLE }
        val onWaiting: (Event) -> Unit = { _state = AudioPlayerState.BUFFERING }
        val onStalled: (Event) -> Unit = { _state = AudioPlayerState.BUFFERING }
        val onError: (Event) -> Unit = { errorListener?.onError(null) }

        el.addEventListener("playing", onPlaying)
        events += { el.removeEventListener("playing", onPlaying) }
        el.addEventListener("play", onPlay)
        events += { el.removeEventListener("play", onPlay) }
        el.addEventListener("pause", onPause)
        events += { el.removeEventListener("pause", onPause) }
        el.addEventListener("ended", onEnded)
        events += { el.removeEventListener("ended", onEnded) }
        el.addEventListener("waiting", onWaiting)
        events += { el.removeEventListener("waiting", onWaiting) }
        el.addEventListener("stalled", onStalled)
        events += { el.removeEventListener("stalled", onStalled) }
        el.addEventListener("error", onError)
        events += { el.removeEventListener("error", onError) }
    }

    private fun detachEventListeners() {
        events.forEach { it.invoke() }
        events.clear()
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual fun play(url: String) {
        release()
        document.body?.appendElement("audio") {
            this as HTMLAudioElement
            this.id = htmlId
            this.src = url
        }

        val playerEl = getPlayerElement()
        playerEl?.let { attachEventListeners(it) }
        playerEl?.play()
        lastVolume?.let { getPlayerElement()?.volume = it }
        lastRate?.let { getPlayerElement()?.playbackRate = it }
    }

    @OptIn(ExperimentalWasmJsInterop::class)
    actual fun play() {
        getPlayerElement()?.play()
        lastVolume?.let { getPlayerElement()?.volume = it }
        lastRate?.let { getPlayerElement()?.playbackRate = it }
    }

    actual fun stop() {
        getPlayerElement()?.pause()
        getPlayerElement()?.currentTime = 0.0
        _state = AudioPlayerState.IDLE
    }

    actual fun pause() {
        getPlayerElement()?.pause()
        _state = AudioPlayerState.PAUSED
    }

    /**
     * Stops playback and removes the player element from the DOM.
     */
    actual fun release() {
        val playerEl = getPlayerElement()
        playerEl?.pause()
        playerEl?.remove()
        detachEventListeners()
        _state = null
    }

    private fun getPlayerElement(): HTMLAudioElement? {
        return document.getElementById(htmlId) as? HTMLAudioElement
    }

    actual fun currentPosition(): Long? {
        // https://www.w3schools.com/jsref/prop_audio_currenttime.asp
        val currentTimeSeconds =  getPlayerElement()?.currentTime?.toLong()
        if (currentTimeSeconds != null && currentTimeSeconds >= 0) {
            return currentTimeSeconds * 1000
        }
        return null
    }

    actual fun currentDuration(): Long? {
        // https://www.w3schools.com/jsref/prop_audio_duration.asp
        val currentTimeSeconds =  getPlayerElement()?.duration?.toLong()
        if (currentTimeSeconds != null && currentTimeSeconds >= 0) {
            return currentTimeSeconds * 1000
        }
        return null
    }

    actual fun currentPlayerState(): AudioPlayerState? {
        // https://developer.mozilla.org/en-US/docs/Web/HTML/Element/audio#events
        return _state
    }

    actual fun currentVolume(): Float? {
        // https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/volume
        return getPlayerElement()?.volume?.toFloat()
    }


    actual fun setVolume(volume: Float) {
        // https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/volume
        lastVolume = volume.toDouble()
        getPlayerElement()?.volume = lastVolume!!
    }

    actual fun setRate(rate: Float) {
        // https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/playbackRate
        // Web browsers may choose to mute playback if rate is outside the useful range
        // Acceptable values are 0.25 to 4.0
        lastRate = rate.toDouble()
        getPlayerElement()?.playbackRate = lastRate!!
    }

    actual fun seekTo(time: Long) {
        // https://developer.mozilla.org/en-US/docs/Web/API/HTMLMediaElement/currentTime
        getPlayerElement()?.currentTime = time.toDouble() / 1000.0
    }

    actual fun setOnErrorListener(listener: ErrorListener) {
        errorListener = listener
    }
}
