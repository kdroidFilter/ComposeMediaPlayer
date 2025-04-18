/*
 * This file includes code based on or inspired by the project available at:
 * https://github.com/Hamamas/Kotlin-Wasm-Html-Interop/blob/master/composeApp/src/wasmJsMain/kotlin/com/hamama/kwhi/HtmlView.kt
 *
 * License: Apache 2.0 (https://www.apache.org/licenses/LICENSE-2.0)
 *
 * Modifications made by kdroidFilter.
 */

package io.github.kdroidfilter.composemediaplayer.htmlinterop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.round
import kotlinx.browser.document
import kotlinx.dom.createElement
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.properties.Delegates

/**
 * Local composition to provide the container for HTML layers
 */
val LocalLayerContainer = staticCompositionLocalOf<Element> {
    document.body ?: error("Document body is not available")
}

/**
 * No-op update function for elements that don't need updates
 */
val NoOpUpdate: Element.() -> Unit = {}

/**
 * Holds information about a component and its container
 */
private class ComponentInfo<T : Element> {
    lateinit var container: Element
    lateinit var component: T
    lateinit var updater: Updater<T>

    // Track whether the component has been initialized
    var isInitialized = false
}

/**
 * Manages focus traversal between Compose and HTML elements
 */
private class FocusSwitcher<T : Element>(
    private val info: ComponentInfo<T>,
    private val focusManager: FocusManager
) {
    private val backwardRequester = FocusRequester()
    private val forwardRequester = FocusRequester()
    private var isRequesting = false

    fun moveBackward() {
        try {
            isRequesting = true
            backwardRequester.requestFocus()
        } finally {
            isRequesting = false
        }
        focusManager.moveFocus(FocusDirection.Previous)
    }

    fun moveForward() {
        try {
            isRequesting = true
            forwardRequester.requestFocus()
        } finally {
            isRequesting = false
        }
        focusManager.moveFocus(FocusDirection.Next)
    }

    @Composable
    fun Content() {
        Box(
            Modifier
                .focusRequester(backwardRequester)
                .onFocusChanged {
                    if (it.isFocused && !isRequesting) {
                        focusManager.clearFocus(force = true)
                        val component = info.container.firstElementChild
                        if (component != null) {
                            requestFocus(component)
                        } else {
                            moveForward()
                        }
                    }
                }
                .focusTarget()
        )
        Box(
            Modifier
                .focusRequester(forwardRequester)
                .onFocusChanged {
                    if (it.isFocused && !isRequesting) {
                        focusManager.clearFocus(force = true)

                        val component = info.container.lastElementChild
                        if (component != null) {
                            requestFocus(component)
                        } else {
                            moveBackward()
                        }
                    }
                }
                .focusTarget()
        )
    }
}

/**
 * Request focus for an HTML element
 */
private fun requestFocus(element: Element): Unit = js("""
    {
        if (element && typeof element.focus === 'function') {
            element.focus();
        }
    }
""")

/**
 * Initialize an HTML element with basic styling
 */
private fun initializeElement(element: Element): Unit = js("""
    {
        if (element && element.style) {
            element.style.position = 'absolute';
            element.style.margin = '0px';
            element.style.padding = '0px';
            element.style.boxSizing = 'border-box';
        }
    }
""")

/**
 * Update the position and size of an HTML element
 */
private fun updateElementGeometry(element: Element, width: Float, height: Float, x: Float, y: Float): Unit = js("""
    {
        if (element && element.style) {
            element.style.width = width + 'px';
            element.style.height = height + 'px';
            element.style.left = x + 'px';
            element.style.top = y + 'px';
        }
    }
""")

/**
 * A composable that integrates HTML elements into a Compose UI
 *
 * @param factory A function that creates the HTML element
 * @param modifier Compose modifier for the container
 * @param update A function to update the HTML element when state changes
 * @param T The type of HTML element to create
 */
@Composable
fun <T : Element> HtmlView(
    factory: Document.() -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate
) {
    val componentInfo = remember { ComponentInfo<T>() }
    val root = LocalLayerContainer.current
    val density = LocalDensity.current.density
    val focusManager = LocalFocusManager.current

    // Create a stable identity for the focus switcher
    val focusSwitcher = remember(componentInfo, focusManager) {
        FocusSwitcher(componentInfo, focusManager)
    }

    // This ensures we track whether we need to recreate our HTML element
    var factoryKey by remember { mutableIntStateOf(0) }

    // Create a key for the current factory to detect changes
    // We use a stable key that doesn't change when toggling fullscreen
    val currentFactoryKey = remember(factory) { ++factoryKey }

    // Create a stable key for the HTML element that doesn't change when toggling fullscreen
    val stableElementKey = remember { Any() }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (componentInfo.isInitialized) {
                val location = coordinates.positionInWindow().round()
                val size = coordinates.size
                updateElementGeometry(
                    componentInfo.component,
                    size.width / density,
                    size.height / density,
                    location.x / density,
                    location.y / density
                )
            }
        }
    ) {
        focusSwitcher.Content()
    }

    // Effect to create and destroy the HTML element
    // Use stableElementKey to ensure the element is not recreated when toggling fullscreen
    DisposableEffect(stableElementKey) {
        try {
            // Create container if not already created
            if (!componentInfo.isInitialized) {
                componentInfo.container = document.createElement("div", NoOpUpdate)
                root.insertBefore(componentInfo.container, root.firstChild)

                // Create the new component
                componentInfo.component = document.factory()
                componentInfo.container.append(componentInfo.component)
                componentInfo.updater = Updater(componentInfo.component, update)
                initializeElement(componentInfo.component)
                componentInfo.isInitialized = true
            }
        } catch (e: Throwable) {
        }

        onDispose {
            try {
                if (componentInfo.isInitialized) {
                    root.removeChild(componentInfo.container)
                    componentInfo.updater.dispose()
                    componentInfo.isInitialized = false
                }
            } catch (e: Throwable) {
            }
        }
    }

    // Effect to handle factory changes (e.g., when CORS mode changes)
    LaunchedEffect(currentFactoryKey) {
        if (componentInfo.isInitialized) {
            try {
                // Update the component with the new factory
                val newComponent = document.factory()

                // Replace the old component with the new one
                componentInfo.container.innerHTML = ""
                componentInfo.container.append(newComponent)

                // Update the component info
                componentInfo.component = newComponent
                componentInfo.updater.dispose()
                componentInfo.updater = Updater(newComponent, update)
                initializeElement(newComponent)
            } catch (e: Throwable) {
            }
        }
    }

    // Update effect - when the update function changes
    SideEffect {
        if (componentInfo.isInitialized) {
            componentInfo.updater.update = update
        }
    }
}

/**
 * Manages state observation and updates for an HTML element
 */
private class Updater<T : Element>(
    private val component: T,
    update: (T) -> Unit
) {
    private var isDisposed = false

    private val snapshotObserver = SnapshotStateObserver { command ->
        command()
    }

    private val scheduleUpdate = { _: T ->
        if (!isDisposed) {
            performUpdate()
        }
    }

    var update: (T) -> Unit = update
        set(value) {
            if (field != value) {
                field = value
                performUpdate()
            }
        }

    private fun performUpdate() {
        try {
            snapshotObserver.observeReads(component, scheduleUpdate) {
                update(component)
            }
        } catch (e: Throwable) {
        }
    }

    init {
        snapshotObserver.start()
        performUpdate()
    }

    fun dispose() {
        try {
            snapshotObserver.stop()
            snapshotObserver.clear()
            isDisposed = true
        } catch (e: Throwable) {
        }
    }
}
