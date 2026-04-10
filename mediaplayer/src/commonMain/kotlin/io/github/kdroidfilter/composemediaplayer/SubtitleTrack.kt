package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class SubtitleTrack(
    val label: String,
    val language: String,
    val src: String,
)
