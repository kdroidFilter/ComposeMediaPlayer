package io.github.kdroidfilter.composemediaplayer

/**
 * Represents different types of errors that can occur during video playback in a video player.
 *
 * This sealed class is used for error reporting and handling within the video player system.
 * Each type of error is represented as a subclass of `VideoPlayerError` with an associated descriptive message.
 *
 * Subclasses:
 * - `CodecError`: Indicates an issue with the codec, such as unsupported formats.
 * - `NetworkError`: Represents network-related problems, like connectivity issues.
 * - `SourceError`: Relates to issues with the video source, such as an invalid or unavailable file/URL.
 * - `UnknownError`: Covers any issues that do not fit into the other categories.
 */
sealed class VideoPlayerError {
    data class CodecError(
        val message: String,
    ) : VideoPlayerError()

    data class NetworkError(
        val message: String,
    ) : VideoPlayerError()

    data class SourceError(
        val message: String,
    ) : VideoPlayerError()

    data class UnknownError(
        val message: String,
    ) : VideoPlayerError()
}
