@file:Suppress("unused", "UNUSED_PARAMETER")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer.jsinterop

import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

// =====================================================
// Encrypted Media Extensions (EME) API Bindings
// For Kotlin/WASM JS Interop
// =====================================================

/**
 * Provides access to a Key System for decryption.
 */
external class MediaKeySystemAccess : JsAny {
    val keySystem: JsString
    fun getConfiguration(): JsAny
    fun createMediaKeys(): Promise<MediaKeys>
}

/**
 * Represents a set of keys that an HTMLMediaElement can use for decryption.
 */
external class MediaKeys : JsAny {
    fun createSession(sessionType: JsString): MediaKeySession
    fun createSession(): MediaKeySession
}

/**
 * Represents a context for message exchange with a CDM.
 */
external class MediaKeySession : EventTarget {
    val sessionId: JsString
    val expiration: Double
    val closed: Promise<JsString>
    val keyStatuses: MediaKeyStatusMap
    
    fun generateRequest(initDataType: JsString, initData: ArrayBuffer): Promise<JsAny?>
    fun load(sessionId: JsString): Promise<JsAny>
    fun update(response: ArrayBuffer): Promise<JsAny?>
    fun close(): Promise<JsAny?>
    fun remove(): Promise<JsAny?>
}

/**
 * Read-only map of media key statuses.
 */
external class MediaKeyStatusMap : JsAny {
    val size: Int
    fun has(keyId: ArrayBuffer): Boolean
    fun get(keyId: ArrayBuffer): JsString?
}

/**
 * Event containing a message from the CDM.
 */
external class MediaKeyMessageEvent : Event {
    val messageType: JsString
    val message: ArrayBuffer
}

/**
 * Event fired when encrypted content is encountered.
 */
external class MediaEncryptedEvent : Event {
    val initDataType: JsString
    val initData: ArrayBuffer?
}

// =====================================================
// DRM Setup Result from JavaScript
// =====================================================

/**
 * Result object returned by DrmHelper.setup()
 */
external interface DrmSetupResult : JsAny {
    val mediaKeys: MediaKeys
    val cleanup: () -> Unit
}

// =====================================================
// DrmHelper Object (defined in drm-helper.js)
// =====================================================

/**
 * External declaration for the DrmHelper JavaScript object.
 * Must be loaded before use via drm-helper.js
 */
@JsName("DrmHelper")
external object DrmHelper : JsAny {
    /**
     * Check if EME is supported in the current browser.
     */
    fun isSupported(): Boolean
    
    /**
     * Get key system string for DRM type.
     * @param drmType - "WIDEVINE", "PLAYREADY", or "CLEARKEY"
     */
    fun getKeySystem(drmType: JsString): JsString
    
    /**
     * Setup DRM for a video element with full workflow.
     * This is the main entry point for DRM initialization.
     * 
     * @param video - The HTMLVideoElement to attach DRM to
     * @param keySystem - e.g., "com.widevine.alpha"
     * @param licenseUrl - The license server URL
     * @param licenseHeaders - Additional headers for license requests (can be null)
     * @return Promise that resolves to DrmSetupResult with cleanup function
     */
    fun setup(
        video: HTMLVideoElement,
        keySystem: JsString,
        licenseUrl: JsString,
        licenseHeaders: JsAny?
    ): Promise<DrmSetupResult>
    
    /**
     * Setup DASH playback with DRM using dash.js.
     * This handles both DASH manifest parsing and DRM.
     * 
     * @param video - The HTMLVideoElement
     * @param url - The DASH manifest URL (.mpd)
     * @param drmType - "WIDEVINE", "PLAYREADY", or "CLEARKEY"
     * @param licenseUrl - The license server URL
     * @param licenseHeaders - Additional headers for license requests (can be null)
     * @return DashSetupResult with player and cleanup function, or null if dash.js not loaded
     */
    fun setupDash(
        video: HTMLVideoElement,
        url: JsString,
        drmType: JsString,
        licenseUrl: JsString,
        licenseHeaders: JsAny?
    ): DashSetupResult?
    
    /**
     * Check if URL is a DASH manifest.
     */
    fun isDashUrl(url: JsString): Boolean
    
    /**
     * Parse JSON string to JS object.
     * Helper for WASM which can't call JSON.parse directly.
     */
    fun parseJson(jsonString: JsString): JsAny?
}

/**
 * Result object returned by DrmHelper.setupDash()
 */
external interface DashSetupResult : JsAny {
    val player: JsAny
    val cleanup: () -> Unit
}
