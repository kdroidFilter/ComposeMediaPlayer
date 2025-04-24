package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable


/**
 * Represents metadata information of a video file.
 *
 * This data class holds various attributes related to the video content,
 * including its title, artist, duration, dimensions, codec details, and audio properties.
 * This metadata is typically used to provide detailed information about a video
 * during playback or for insights in media management systems.
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
data class VideoMetadata(
    var title: String? = null,
    var duration: Long? = null, // Duration in milliseconds
    var width: Int? = null,
    var height: Int? = null,
    var bitrate: Long? = null, // Bitrate in bits per second
    var frameRate: Float? = null,
    var mimeType: String? = null,
    var audioChannels: Int? = null,
    var audioSampleRate: Int? = null,
) {
    /**
     * Checks if all properties of this metadata object are null.
     *
     * @return true if all properties are null, false otherwise.
     */
    fun isAllNull(): Boolean {
        return title == null &&
               duration == null &&
               width == null &&
               height == null &&
               bitrate == null &&
               frameRate == null &&
               mimeType == null &&
               audioChannels == null &&
               audioSampleRate == null
    }
}
