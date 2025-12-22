@file:Suppress("unused")

package io.github.kdroidfilter.composemediaplayer.jsinterop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Represents a media error in the HTMLMediaElement
 */
@OptIn(ExperimentalWasmJsInterop::class)
external class MediaError : JsAny {
    /**
     * Error code for the media error
     */
    val code: Short

    companion object {
        /**
         * The fetching process for the media resource was aborted by the user agent at the user's request.
         */
        val MEDIA_ERR_ABORTED: Short

        /**
         * A network error of some description caused the user agent to stop fetching the media resource,
         * after the resource was established to be usable.
         */
        val MEDIA_ERR_NETWORK: Short

        /**
         * An error of some description occurred while decoding the media resource,
         * after the resource was established to be usable.
         */
        val MEDIA_ERR_DECODE: Short

        /**
         * The media resource indicated by the src attribute or assigned media provider object
         * was not suitable.
         */
        val MEDIA_ERR_SRC_NOT_SUPPORTED: Short
    }
}
