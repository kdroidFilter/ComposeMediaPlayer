package io.github.kdroidfilter.composemediaplayer.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import org.w3c.dom.url.URL

actual fun PlatformFile.getUri(): String =
    when (val file = webFile) {
        is WebFile.FileWrapper -> URL.createObjectURL(file.file)
        is WebFile.DirectoryWrapper -> error("Cannot open a directory as a media file.")
    }
