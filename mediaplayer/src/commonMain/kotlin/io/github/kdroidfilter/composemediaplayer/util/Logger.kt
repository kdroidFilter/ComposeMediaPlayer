package io.github.kdroidfilter.composemediaplayer.util

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.mutableLoggerConfigInit
import co.touchlab.kermit.platformLogWriter

fun buildLocalLogger(tag: String, minSeverity: Severity = Severity.Warn) =
    Logger(mutableLoggerConfigInit(listOf(platformLogWriter())), tag)
        .apply {
            mutableConfig.minSeverity = minSeverity
        }
