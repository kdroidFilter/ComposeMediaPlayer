package io.github.kdroidfilter.composemediaplayer.util

import io.github.vinceglb.filekit.PlatformFile
import org.w3c.dom.url.URL

actual fun PlatformFile.getUri(): String {
    return URL.createObjectURL(this.file)
}
