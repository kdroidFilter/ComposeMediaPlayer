package io.github.kdroidfilter.composemediaplayer

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import io.github.kdroidfilter.composemediaplayer.jsinterop.AnalyserNode
import io.github.kdroidfilter.composemediaplayer.jsinterop.AudioContext
import io.github.kdroidfilter.composemediaplayer.jsinterop.ChannelSplitterNode
import io.github.kdroidfilter.composemediaplayer.jsinterop.MediaElementAudioSourceNode
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLVideoElement

/**
 * Logger for WebAssembly audio level processor
 */
internal val wasmAudioLogger = Logger.withTag("WasmAudioProcessor")
    .apply { Logger.setMinSeverity(Severity.Warn) }

internal class AudioLevelProcessor(private val video: HTMLVideoElement) {

    private var audioContext: AudioContext? = null
    private var sourceNode: MediaElementAudioSourceNode? = null
    private var splitterNode: ChannelSplitterNode? = null

    private var leftAnalyser: AnalyserNode? = null
    private var rightAnalyser: AnalyserNode? = null

    private var leftData: Uint8Array? = null
    private var rightData: Uint8Array? = null

    // Audio properties
    private var _audioChannels: Int = 0
    private var _audioSampleRate: Int = 0

    // Getters for audio properties
    val audioChannels: Int get() = _audioChannels
    val audioSampleRate: Int get() = _audioSampleRate

    /**
     * Initializes Web Audio (creates a source, a splitter, etc.)
     * In case of error (CORS), we simply return false => the video remains managed by HTML
     * and audio levels will be set to 0
     * 
     * @return true if initialization was successful, false if there was a CORS error
     */
    fun initialize(): Boolean {
        if (audioContext != null) return true // already initialized?

        val ctx = AudioContext()
        audioContext = ctx

        val source = try {
            ctx.createMediaElementSource(video)
        } catch (e: Throwable) {
            wasmAudioLogger.w { "CORS/format error: Video doesn't have CORS headers. Audio levels will be set to 0. Error: ${e.message}" }
            // Clean up the audio context since we won't be using it
            audioContext = null
            return false
        }

        sourceNode = source
        splitterNode = ctx.createChannelSplitter(2)

        leftAnalyser = ctx.createAnalyser().apply { fftSize = 256 }
        rightAnalyser = ctx.createAnalyser().apply { fftSize = 256 }

        // Chaining
        source.connect(splitterNode!!)
        splitterNode!!.connect(leftAnalyser!!, 0, 0)
        splitterNode!!.connect(rightAnalyser!!, 1, 0)

        // To hear the sound via Web Audio
        splitterNode!!.connect(ctx.destination)

        val size = leftAnalyser!!.frequencyBinCount
        leftData = Uint8Array(size)
        rightData = Uint8Array(size)

        // Extract audio properties
        _audioSampleRate = ctx.sampleRate
        _audioChannels = source.channelCount


        wasmAudioLogger.d { "Web Audio successfully initialized and capturing audio. Sample rate: $_audioSampleRate Hz, Channels: $_audioChannels" }
        return true
    }

    /**
     * Returns (left%, right%) in range 0..100
     * 
     * Uses a logarithmic scale to match the Mac implementation:
     * 1. Calculate average level from frequency data
     * 2. Normalize to 0..1 range
     * 3. Convert to decibels: 20 * log10(level)
     * 4. Normalize: ((db + 60) / 60).coerceIn(0f, 1f)
     * 5. Convert to percentage: normalized * 100f
     */
    fun getAudioLevels(): Pair<Float, Float> {
        val la = leftAnalyser ?: return 0f to 0f
        val ra = rightAnalyser ?: return 0f to 0f
        val lb = leftData ?: return 0f to 0f
        val rb = rightData ?: return 0f to 0f

        la.getByteFrequencyData(lb)
        ra.getByteFrequencyData(rb)

        var sumLeft = 0
        for (i in 0 until lb.length) {
            sumLeft += lb[i].toInt()
        }
        var sumRight = 0
        for (i in 0 until rb.length) {
            sumRight += rb[i].toInt()
        }

        val avgLeft = sumLeft.toFloat() / lb.length
        val avgRight = sumRight.toFloat() / rb.length

        // Normalize to 0..1 range
        val normalizedLeft = avgLeft / 255f
        val normalizedRight = avgRight / 255f

        // Convert to logarithmic scale (same as Mac implementation)
        fun convertToPercentage(level: Float): Float {
            if (level <= 0f) return 0f
            // Conversion to decibels: 20 * log10(level)
            val db = 20 * kotlin.math.log10(level)
            // Assume that -60 dB corresponds to silence and 0 dB to maximum level.
            val normalized = ((db + 60) / 60).coerceIn(0f, 1f)
            return normalized * 100f
        }

        val leftPercent = convertToPercentage(normalizedLeft)
        val rightPercent = convertToPercentage(normalizedRight)

        return leftPercent to rightPercent
    }
}
