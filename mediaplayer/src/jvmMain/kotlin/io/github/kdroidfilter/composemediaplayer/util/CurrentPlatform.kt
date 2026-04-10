package io.github.kdroidfilter.composemediaplayer.util

/**
 * Lightweight platform detection using System properties.
 * Replaces com.sun.jna.Platform to avoid the JNA dependency.
 */
internal object CurrentPlatform {
    enum class OS { WINDOWS, MAC, LINUX }

    val os: OS by lazy {
        val name = System.getProperty("os.name", "").lowercase()
        when {
            name.contains("win") -> OS.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OS.MAC
            else -> OS.LINUX
        }
    }
}
