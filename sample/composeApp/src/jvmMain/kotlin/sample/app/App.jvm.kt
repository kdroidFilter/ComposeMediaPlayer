package sample.app

import io.github.vinceglb.filekit.PlatformFile

actual fun PlatformFile.getUri(): String {
    return file.toURI().toString()
}
