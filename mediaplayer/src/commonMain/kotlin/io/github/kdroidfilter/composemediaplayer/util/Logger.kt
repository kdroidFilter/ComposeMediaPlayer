package io.github.kdroidfilter.composemediaplayer.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Logging level hierarchy for ComposeMediaPlayer internal logging.
 */
class ComposeMediaPlayerLoggingLevel private constructor(
    private val priority: Int,
) : Comparable<ComposeMediaPlayerLoggingLevel> {
    override fun compareTo(other: ComposeMediaPlayerLoggingLevel): Int =
        priority.compareTo(other.priority)

    companion object {
        @JvmField val VERBOSE = ComposeMediaPlayerLoggingLevel(0)
        @JvmField val DEBUG = ComposeMediaPlayerLoggingLevel(1)
        @JvmField val INFO = ComposeMediaPlayerLoggingLevel(2)
        @JvmField val WARN = ComposeMediaPlayerLoggingLevel(3)
        @JvmField val ERROR = ComposeMediaPlayerLoggingLevel(4)
    }
}

/** Global switch — set to `true` to enable ComposeMediaPlayer internal logging. */
var allowComposeMediaPlayerLogging: Boolean = false

/** Minimum severity to emit. Messages below this level are discarded. */
var composeMediaPlayerLoggingLevel: ComposeMediaPlayerLoggingLevel =
    ComposeMediaPlayerLoggingLevel.VERBOSE

private fun getCurrentTimestamp(): String {
    val now = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return "${now.date} ${now.hour.pad()}:${now.minute.pad()}:${now.second.pad()}" +
        ".${(now.nanosecond / 1_000_000).pad(3)}"
}

private fun Int.pad(len: Int = 2): String = toString().padStart(len, '0')

// -- Tagged logger ----------------------------------------------------------

internal class TaggedLogger(private val tag: String) {
    fun v(message: () -> String) = verboseln { "[$tag] ${message()}" }
    fun d(message: () -> String) = debugln { "[$tag] ${message()}" }
    fun i(message: () -> String) = infoln { "[$tag] ${message()}" }
    fun w(message: () -> String) = warnln { "[$tag] ${message()}" }
    fun e(message: () -> String) = errorln { "[$tag] ${message()}" }
}

// -- Top-level logging functions --------------------------------------------

internal fun verboseln(message: () -> String) {
    if (allowComposeMediaPlayerLogging &&
        composeMediaPlayerLoggingLevel <= ComposeMediaPlayerLoggingLevel.VERBOSE
    ) {
        println("[${getCurrentTimestamp()}] V: ${message()}")
    }
}

internal fun debugln(message: () -> String) {
    if (allowComposeMediaPlayerLogging &&
        composeMediaPlayerLoggingLevel <= ComposeMediaPlayerLoggingLevel.DEBUG
    ) {
        println("[${getCurrentTimestamp()}] D: ${message()}")
    }
}

internal fun infoln(message: () -> String) {
    if (allowComposeMediaPlayerLogging &&
        composeMediaPlayerLoggingLevel <= ComposeMediaPlayerLoggingLevel.INFO
    ) {
        println("[${getCurrentTimestamp()}] I: ${message()}")
    }
}

internal fun warnln(message: () -> String) {
    if (allowComposeMediaPlayerLogging &&
        composeMediaPlayerLoggingLevel <= ComposeMediaPlayerLoggingLevel.WARN
    ) {
        println("[${getCurrentTimestamp()}] W: ${message()}")
    }
}

internal fun errorln(message: () -> String) {
    if (allowComposeMediaPlayerLogging &&
        composeMediaPlayerLoggingLevel <= ComposeMediaPlayerLoggingLevel.ERROR
    ) {
        println("[${getCurrentTimestamp()}] E: ${message()}")
    }
}
