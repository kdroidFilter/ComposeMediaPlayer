package sample.app

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile

actual fun PlatformFile.getUri(): String {
    return when (val androidFile = this.androidFile) {
        is AndroidFile.UriWrapper -> androidFile.uri.toString()
        is AndroidFile.FileWrapper -> androidFile.file.toURI().toString()
    }
}
