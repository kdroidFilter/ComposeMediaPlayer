package io.github.kdroidfilter.composemediaplayer.util

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

actual fun PlatformFile.getUri(): String =
    when (val androidFile = this.androidFile) {
        is AndroidFile.UriWrapper -> androidFile.uri.toString()
        is AndroidFile.FileWrapper -> androidFile.file.path
    }
