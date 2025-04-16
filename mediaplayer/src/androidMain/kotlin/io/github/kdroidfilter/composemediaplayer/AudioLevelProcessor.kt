package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

@UnstableApi
class AudioLevelProcessor : BaseAudioProcessor() {
    private var channelCount = 0
    private var sampleRateHz = 0
    private var bytesPerFrame = 0
    private var onAudioLevelUpdate: ((Float, Float) -> Unit)? = null

    fun setOnAudioLevelUpdateListener(listener: (Float, Float) -> Unit) {
        onAudioLevelUpdate = listener
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        sampleRateHz = inputAudioFormat.sampleRate
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        var leftSum = 0.0
        var rightSum = 0.0
        var sampleCount = 0

        // Copy the buffer so as not to affect the original position
        val buffer = inputBuffer.duplicate()

        while (buffer.remaining() >= 2) {
            // Reading 16-bit samples
            val sample = buffer.short / Short.MAX_VALUE.toFloat()

            if (channelCount >= 2) {
                // Stereo
                if (sampleCount % 2 == 0) {
                    leftSum += abs(sample.toDouble())
                } else {
                    rightSum += abs(sample.toDouble())
                }
            } else {
                // Mono - same value for both channels
                leftSum += abs(sample.toDouble())
                rightSum += abs(sample.toDouble())
            }
            sampleCount++
        }

        // Calculate RMS and convert to dB
        val samplesPerChannel = if (channelCount >= 2) sampleCount / 2 else sampleCount
        val leftRms = if (samplesPerChannel > 0) sqrt(leftSum / samplesPerChannel) else 0.0
        val rightRms = if (samplesPerChannel > 0) sqrt(rightSum / samplesPerChannel) else 0.0

        // Convert to percentage (0-100)
        val leftLevel = convertToPercentage(leftRms)
        val rightLevel = convertToPercentage(rightRms)

        onAudioLevelUpdate?.invoke(leftLevel, rightLevel)

        // Pass the original buffer as is
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    private fun convertToPercentage(rms: Double): Float {
        if (rms <= 0) return 0f
        // Apply a scaling factor to make Android values more consistent with wasmjs
        // wasmjs uses frequency domain data which typically results in lower values
        val scaledRms = rms * 0.3 // Scale down the RMS value to be more in line with wasmjs
        val db = 20 * log10(scaledRms)
        // Convert from -60dB..0dB to 0..100%
        // First normalize to 0..1 range
        val normalized = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        // Then convert to percentage
        return normalized * 100f
    }

    override fun onReset() {
        onAudioLevelUpdate?.invoke(0f, 0f)
    }
}
