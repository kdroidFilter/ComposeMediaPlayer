package io.github.kdroidfilter.composemediaplayer.util

// commonMain
sealed interface PipResult {
    data object Success : PipResult
    data object NotSupported : PipResult
    data object NotEnabled : PipResult
    data object NotPossible : PipResult  // supported & enabled but conditions not met
}