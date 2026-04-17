package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Represents metadata information of a video file.
 *
 * Properties are backed by [mutableStateOf] so mutations trigger Compose recomposition.
 * This matters when callers update fields in place (e.g. on `onVideoSizeChanged` or HLS
 * resolution changes) while the metadata instance is read from a composable.
 *
 * @property title The title of the video, if available.
 * @property duration The length of the video in milliseconds, if known.
 * @property width The width of the video in pixels, if available.
 * @property height The height of the video in pixels, if available.
 * @property bitrate The average data rate of the video in bits per second, if known.
 * @property frameRate The frame rate of the video in frames per second, if available.
 * @property mimeType The MIME type of the video file, indicating the format used.
 * @property audioChannels The number of audio channels in the video's audio track, if available.
 * @property audioSampleRate The sample rate of the audio track in the video, measured in Hz.
 */
@Stable
class VideoMetadata(
    title: String? = null,
    duration: Long? = null,
    width: Int? = null,
    height: Int? = null,
    bitrate: Long? = null,
    frameRate: Float? = null,
    mimeType: String? = null,
    audioChannels: Int? = null,
    audioSampleRate: Int? = null,
) {
    var title: String? by mutableStateOf(title)
    var duration: Long? by mutableStateOf(duration)
    var width: Int? by mutableStateOf(width)
    var height: Int? by mutableStateOf(height)
    var bitrate: Long? by mutableStateOf(bitrate)
    var frameRate: Float? by mutableStateOf(frameRate)
    var mimeType: String? by mutableStateOf(mimeType)
    var audioChannels: Int? by mutableStateOf(audioChannels)
    var audioSampleRate: Int? by mutableStateOf(audioSampleRate)

    /**
     * Checks if all properties of this metadata object are null.
     */
    fun isAllNull(): Boolean =
        title == null &&
            duration == null &&
            width == null &&
            height == null &&
            bitrate == null &&
            frameRate == null &&
            mimeType == null &&
            audioChannels == null &&
            audioSampleRate == null

    fun copy(
        title: String? = this.title,
        duration: Long? = this.duration,
        width: Int? = this.width,
        height: Int? = this.height,
        bitrate: Long? = this.bitrate,
        frameRate: Float? = this.frameRate,
        mimeType: String? = this.mimeType,
        audioChannels: Int? = this.audioChannels,
        audioSampleRate: Int? = this.audioSampleRate,
    ): VideoMetadata =
        VideoMetadata(
            title = title,
            duration = duration,
            width = width,
            height = height,
            bitrate = bitrate,
            frameRate = frameRate,
            mimeType = mimeType,
            audioChannels = audioChannels,
            audioSampleRate = audioSampleRate,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoMetadata) return false
        return title == other.title &&
            duration == other.duration &&
            width == other.width &&
            height == other.height &&
            bitrate == other.bitrate &&
            frameRate == other.frameRate &&
            mimeType == other.mimeType &&
            audioChannels == other.audioChannels &&
            audioSampleRate == other.audioSampleRate
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (duration?.hashCode() ?: 0)
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (bitrate?.hashCode() ?: 0)
        result = 31 * result + (frameRate?.hashCode() ?: 0)
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + (audioChannels ?: 0)
        result = 31 * result + (audioSampleRate ?: 0)
        return result
    }

    override fun toString(): String =
        "VideoMetadata(title=$title, duration=$duration, width=$width, height=$height, " +
            "bitrate=$bitrate, frameRate=$frameRate, mimeType=$mimeType, " +
            "audioChannels=$audioChannels, audioSampleRate=$audioSampleRate)"
}
