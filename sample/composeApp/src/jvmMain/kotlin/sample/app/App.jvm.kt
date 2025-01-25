package sample.app

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

actual fun PlatformFile.getUri(): String {
    return "file://$path"
}
