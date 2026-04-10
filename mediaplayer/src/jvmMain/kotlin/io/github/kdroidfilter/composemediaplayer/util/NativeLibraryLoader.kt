package io.github.kdroidfilter.composemediaplayer.util

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Loads native libraries following a two-stage strategy:
 * 1. Try [System.loadLibrary] (works for packaged apps / GraalVM native-image
 *    where the lib sits on `java.library.path`).
 * 2. Fallback: extract from the classpath (`composemediaplayer/native/<platform>/`)
 *    into a persistent cache and load from there.
 */
internal object NativeLibraryLoader {
    private const val RESOURCE_PREFIX = "composemediaplayer/native"
    private val loadedLibraries = mutableSetOf<String>()

    @Synchronized
    fun load(libraryName: String, callerClass: Class<*>): Boolean {
        if (libraryName in loadedLibraries) return true

        // 1. Try system library path (packaged app / GraalVM native-image)
        try {
            System.loadLibrary(libraryName)
            loadedLibraries += libraryName
            return true
        } catch (_: UnsatisfiedLinkError) {
            // Not on java.library.path, try classpath extraction
        }

        // 2. Extract from classpath to persistent cache
        val file = extractToCache(libraryName, callerClass) ?: return false
        System.load(file.absolutePath)
        loadedLibraries += libraryName
        return true
    }

    private fun extractToCache(libraryName: String, callerClass: Class<*>): File? {
        val platform = detectPlatform()
        val fileName = mapLibraryName(libraryName)
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"

        val resourceUrl = callerClass.classLoader?.getResource(resourcePath) ?: return null

        val cacheDir = resolveCacheDir(platform)
        cacheDir.mkdirs()
        val cachedFile = File(cacheDir, fileName)

        // Validate cache: re-extract if size differs or file is missing
        val resourceSize = resourceUrl.openConnection().contentLengthLong
        if (cachedFile.exists() && cachedFile.length() == resourceSize) {
            return cachedFile
        }

        // Atomic extract: write to temp then move
        val tmpFile = File(cacheDir, "$fileName.tmp")
        try {
            resourceUrl.openStream().use { input ->
                Files.copy(input, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tmpFile.toPath(), cachedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            cachedFile.setExecutable(true)
        } finally {
            tmpFile.delete()
        }

        return cachedFile
    }

    private fun resolveCacheDir(platform: String): File {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val base = when {
            os.contains("win") ->
                File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
            else ->
                File(System.getProperty("user.home"), ".cache")
        }
        return File(base, "composemediaplayer/native/$platform")
    }

    private fun detectPlatform(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        return when {
            os.contains("win") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "win32-arm64" else "win32-x86-64"
            os.contains("linux") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "linux-aarch64" else "linux-x86-64"
            os.contains("mac") ->
                if (arch.contains("aarch64") || arch.contains("arm")) "darwin-aarch64" else "darwin-x86-64"
            else -> "unknown"
        }
    }

    private fun mapLibraryName(name: String): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            os.contains("win") -> "$name.dll"
            os.contains("mac") -> "lib$name.dylib"
            else -> "lib$name.so"
        }
    }
}
