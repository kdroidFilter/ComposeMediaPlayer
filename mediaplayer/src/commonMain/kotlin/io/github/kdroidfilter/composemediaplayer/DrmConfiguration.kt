package io.github.kdroidfilter.composemediaplayer

/**
 * DRM type enumeration for supported DRM systems.
 * Currently only Widevine is supported for web platform.
 * ClearKey is included for testing purposes.
 */
enum class DrmType(val keySystem: String) {
    WIDEVINE("com.widevine.alpha"),
    PLAYREADY("com.microsoft.playready"),
    CLEARKEY("org.w3.clearkey")
}

/**
 * Configuration for DRM-protected content playback.
 * 
 * @property drmType The DRM system to use
 * @property licenseUrl The URL to request licenses from
 * @property licenseHeaders Optional HTTP headers to include in license requests
 * @property initDataTypes Initialization data types (e.g., "cenc", "keyids", "webm")
 */
data class DrmConfiguration(
    val drmType: DrmType,
    val licenseUrl: String,
    val licenseHeaders: Map<String, String> = emptyMap(),
    val initDataTypes: List<String> = listOf("cenc", "keyids", "webm")
) {
    companion object {
        /**
         * Creates a Widevine DRM configuration.
         * 
         * @param licenseUrl The Widevine license server URL
         * @param headers Optional headers for license requests
         */
        fun widevine(
            licenseUrl: String,
            headers: Map<String, String> = emptyMap()
        ) = DrmConfiguration(
            drmType = DrmType.WIDEVINE,
            licenseUrl = licenseUrl,
            licenseHeaders = headers
        )

        /**
         * Creates a ClearKey DRM configuration.
         * 
         * @param licenseUrl The ClearKey license server URL
         * @param headers Optional headers for license requests
         */
        fun clearKey(
            licenseUrl: String,
            headers: Map<String, String> = emptyMap()
        ) = DrmConfiguration(
            drmType = DrmType.CLEARKEY,
            licenseUrl = licenseUrl,
            licenseHeaders = headers
        )

        /**
         * Creates a PlayReady DRM configuration.
         * 
         * @param licenseUrl The PlayReady license server URL
         * @param headers Optional headers for license requests
         */
        fun playReady(
            licenseUrl: String,
            headers: Map<String, String> = emptyMap()
        ) = DrmConfiguration(
            drmType = DrmType.PLAYREADY,
            licenseUrl = licenseUrl,
            licenseHeaders = headers
        )
    }
}
