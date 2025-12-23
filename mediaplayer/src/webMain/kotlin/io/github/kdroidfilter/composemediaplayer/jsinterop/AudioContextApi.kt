@file:Suppress("unused")

package io.github.kdroidfilter.composemediaplayer.jsinterop

import kotlin.js.JsAny
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLMediaElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.definedExternally

/**
 * Represents the main audio context
 */
@OptIn(ExperimentalWasmJsInterop::class)
external class AudioContext : JsAny {
    constructor()
    val destination: AudioDestinationNode
    val state: String
    val sampleRate: Int

    fun createMediaElementSource(mediaElement: HTMLMediaElement): MediaElementAudioSourceNode
    fun createChannelSplitter(numberOfOutputs: Int = definedExternally): ChannelSplitterNode
    fun createAnalyser(): AnalyserNode
    fun resume()
    fun close()
}

/**
 * Represents a generic node of the Web Audio API
 */
@OptIn(ExperimentalWasmJsInterop::class)
open external class AudioNode : JsAny {
    fun connect(destination: AudioNode, output: Int = definedExternally, input: Int = definedExternally): AudioNode
    fun disconnect()
}

/**
 * Audio context output
 */
external class AudioDestinationNode : AudioNode {
    val maxChannelCount: Int
}

/**
 * Audio source based on a media element (audio or video)
 */
external class MediaElementAudioSourceNode : AudioNode {
    /** Number of channels actually présentes dans la piste audio. */
    val channelCount: Int
}

/**
 * Allows channel separation
 */
external class ChannelSplitterNode : AudioNode

/**
 * Analysis node to retrieve information about the spectrum or wave
 */
external class AnalyserNode : AudioNode {
    var fftSize: Int
    val frequencyBinCount: Int

    /**
     * dB value below which the signal is cut off (for frequency display).
     * Default is -100 dB.
     */
    var minDecibels: Double

    /**
     * dB value above which the signal is cut off (for frequency display).
     * Default is -30 dB.
     */
    var maxDecibels: Double

    /**
     * The smoothingTimeConstant (0..1) allows smoothing of data between two analyses
     * (values close to 1 => smoother, values close to 0 => more reactive).
     * Default is 0.8.
     */
    var smoothingTimeConstant: Double

    /**
     * Retrieves the frequency amplitude in a byte array (0..255).
     */
    fun getByteFrequencyData(array: Uint8Array)

    /**
     * Retrieves the raw waveform (time domain) in a byte array (0..255).
     * By default, 128 represents axis 0, so [0..255] -> [-1..+1].
     */
    fun getByteTimeDomainData(array: Uint8Array)

    /**
     * Additional methods if needed: retrieval in Float32.
     */
    fun getFloatFrequencyData(array: Float32Array)
    fun getFloatTimeDomainData(array: Float32Array)
}
