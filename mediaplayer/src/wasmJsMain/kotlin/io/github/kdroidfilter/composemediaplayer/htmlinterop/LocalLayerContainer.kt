/*
 * This file includes code based on or inspired by the project available at:
 * https://github.com/Hamamas/Kotlin-Wasm-Html-Interop/blob/master/composeApp/src/wasmJsMain/kotlin/com/hamama/kwhi/LocalLayerContainer.kt
 *
 * License: Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
 *
 * Modifications may have been made by kdroidFilter.
 */

package io.github.kdroidfilter.composemediaplayer.htmlinterop

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.browser.document
import org.w3c.dom.Element

val LocalLayerContainer = staticCompositionLocalOf<Element> {
    document.body ?: error("Document body is not available")
}
