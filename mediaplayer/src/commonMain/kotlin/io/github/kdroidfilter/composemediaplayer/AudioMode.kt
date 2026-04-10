package io.github.kdroidfilter.composemediaplayer

/**
 * Controls how the media player interacts with other apps' audio.
 */
enum class InterruptionMode {
    /** Exclusive audio focus. Other apps' audio is paused. */
    DoNotMix,
    /** Mix with other apps' audio. No audio focus requested. */
    MixWithOthers,
    /** Other apps' audio ducks (lowers volume) while this player is active. */
    DuckOthers,
}

/**
 * Configures how the media player interacts with the system audio session.
 *
 * On iOS, this maps to AVAudioSession category, mode, and options.
 * On Android, this maps to AudioAttributes and audio focus behavior.
 * On other platforms (JVM desktop, web), this has no effect.
 *
 * The default [AudioMode] requests exclusive audio focus and ignores the iOS silent switch,
 * matching standard media playback behavior.
 *
 * @param interruptionMode How this player interacts with other apps' audio.
 * @param playsInSilentMode iOS only: whether audio plays when the device silent switch is on.
 */
data class AudioMode(
    val interruptionMode: InterruptionMode = InterruptionMode.DoNotMix,
    val playsInSilentMode: Boolean = true,
)
