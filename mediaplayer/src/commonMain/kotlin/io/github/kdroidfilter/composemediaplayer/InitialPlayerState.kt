package io.github.kdroidfilter.composemediaplayer

/**
 * Represents the initial state of the player after opening a media file or URI.
 */
enum class InitialPlayerState {
    /**
     * The player will automatically start playing after opening the media.
     */
    PLAY,

    /**
     * The player will remain paused after opening the media.
     */
    PAUSE
}