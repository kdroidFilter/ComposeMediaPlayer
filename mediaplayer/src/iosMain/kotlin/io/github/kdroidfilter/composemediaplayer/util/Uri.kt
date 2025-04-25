package io.github.kdroidfilter.composemediaplayer.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

actual fun PlatformFile.getUri(): String {
    val filePath = this.path.toString()
    return if (filePath.startsWith("file://")) {
        filePath
    } else {
        "file://$filePath"
    }
}
