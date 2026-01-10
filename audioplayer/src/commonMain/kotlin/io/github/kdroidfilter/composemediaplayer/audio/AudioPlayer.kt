/*
 * Copyright 2025 Konstantin <hi@iamkonstantin.eu>.
 *  Use of this source code is governed by the BSD 3-Clause License that can be found in LICENSE file.
 */

package io.github.kdroidfilter.composemediaplayer.audio

import androidx.compose.runtime.*

/**
 * A minimalistic audio player
 *
 *
 * Example:
 *
 * ```kotlin
val player = ComposeMediaPlayer()
player.play(url = "...")
player.stop()
player.release()
```
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class AudioPlayer() {
    /**
     * Start playback of the audio resource at the provided [url].
     *
     * ### Resource URI
     *
     * Can be a remote HTTP(s) url, or a `files` URI obtained via `Res.getUri("files/sample.mp3")`.
     *
     * Check the [JetBrains docs on how to store raw](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources-usage.html#raw-files) files as part of multiplatform project resources.
     *
     * On Android, you can resolve the resource URI using something like:
     *
     * ```kotlin
     *  val uri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .appendPath("${R.raw.name_of_your_resource}")
                .build().toString()
     * ```
     *
     */
    fun play(url: String)


    /**
     * Resumes audio playback from the current position if it was previously paused.
     *
     * This function has no effect if the player is already in a playing state.
     */
    fun play()

    /**
     * Stop playback and return the play position to the beginning of time (position 0).
     *
     * Note: If the player is currently not playing, this action has no effect.
     */
    fun stop()


    /**
     * Pauses the audio playback without resetting the play position. To resume playback, call [play].
     *
     * If the player is currently not in a playing state, this method has no effect.
     */
    fun pause()

    /**
     * Pause and attempts to perform cleanup in order to dispose of any player resources.
     *
     */
    fun release()


    /**
     * Retrieves the current playback position in milliseconds.
     *
     * @return The current playback position in milliseconds, or null if it cannot be determined.
     */
    fun currentPosition(): Long?

    /**
     * Retrieves the total duration of the playback item in milliseconds.
     *
     * @return The duration of the playing item in milliseconds, or null if it cannot be determined.
     */
    fun currentDuration(): Long?

    /**
     * Retrieves the current state of the player
     *
     * @return The current state like [PLAYING], [BUFFERING], [IDLE], [PAUASED].
     */
    fun currentPlayerState(): AudioPlayerState?

    /**
     * Retrieves the current volume level of the player.
     *
     * A value of 0.0 indicates silence. A value of 1.0 indicates full audio volume for the player instance.
     * @return The current volume as a floating-point value, or null if it cannot be determined.
     */
    fun currentVolume(): Float?


    /**
     * Adjusts the volume level of the player.
     *
     * @param volume The desired volume level as a floating-point value, where 0.0 represents silence
     * and 1.0 represents the maximum audio volume for the player instance.
     *
     * Note: this method has no effect on the system/device volume, it only targets the player instance.
     */
    fun setVolume(volume: Float)


    /**
     * Adjusts the playback speed of the audio.
     *
     * @param rate The desired playback speed as a floating-point value, where 1.0 indicates normal speed,
     * values greater than 1.0 indicate faster playback, and values less than 1.0 indicate slower playback.
     *
     * The value must be positive (grater than 0.0).
     */
    fun setRate(rate: Float)


    /**
     * Seeks to the specified playback position in the currently playing media.
     *
     * @param time The desired playback position in milliseconds. Must be within the duration of the media.
     */
    fun seekTo(time: Long)

    fun setOnErrorListener(listener: ErrorListener)
}


/**
 * Checks whether the player is currently in a playing or buffering state.
 *
 * @return `true` if the player is in the PLAYING or BUFFERING state, otherwise `false`.
 */
fun AudioPlayer.isPlaying(): Boolean = currentPlayerState() in listOf(AudioPlayerState.PLAYING, AudioPlayerState.BUFFERING)


/**
 * Creates and remembers an instance of [ComposeAudioPlayerLiveState] that monitors and updates the state,
 * volume, position, and duration of a [AudioPlayer]. The player state is updated periodically
 * and cleans up resources when no longer needed.
 *
 * @return A [ComposeAudioPlayerLiveState] object containing the player instance, its current state, volume,
 * playback position, and duration.
 */
@Composable
fun rememberAudioPlayerLiveState(): ComposeAudioPlayerLiveState {
    val player = remember { AudioPlayer() }
    var state by remember { mutableStateOf(AudioPlayerState.IDLE) }
    var volume by remember { mutableStateOf(0f) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            // Fetch or update the data
            state = player.currentPlayerState() ?: AudioPlayerState.IDLE
            volume = player.currentVolume() ?: 0f
            position = player.currentPosition() ?: 0L
            duration = player.currentDuration() ?: 0L

            // Delay for 300 milliseconds
            kotlinx.coroutines.delay(300)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }
    return ComposeAudioPlayerLiveState(
        player, state,
        volume = volume,
        position = position,
        duration = duration
    )
}


data class ComposeAudioPlayerLiveState(
    val player: AudioPlayer,
    val state: AudioPlayerState,
    val volume: Float,
    val position: Long,
    val duration: Long
)