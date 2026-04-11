package io.github.kdroidfilter.composemediaplayer.util

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

actual fun PlatformFile.getUri(): String = this.path
