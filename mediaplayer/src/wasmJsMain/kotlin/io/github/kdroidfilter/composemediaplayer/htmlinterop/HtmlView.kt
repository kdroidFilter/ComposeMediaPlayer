package io.github.kdroidfilter.composemediaplayer.htmlinterop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.staticCompositionLocalOf
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

val LocalLayerContainer = staticCompositionLocalOf<Element> {
    document.body ?: error("Document body is not available")
}
val NoOpUpdate: Element.() -> Unit = {}

private class ComponentInfo<T : Element> {
    lateinit var container: Element
    lateinit var component: T
    lateinit var updater: Updater<T>
}


private class FocusSwitcher<T : Element>(
    private val info: ComponentInfo<T>,
    private val focusManager: FocusManager
) {
    private val backwardRequester = FocusRequester()
    private val forwardRequester = FocusRequester()
    private var isRequesting = false

    private fun requestFocusAndMove(requester: FocusRequester, direction: FocusDirection) {
        try {
            isRequesting = true
            requester.requestFocus()
        } finally {
            isRequesting = false
        }
        focusManager.moveFocus(direction)
    }

    fun moveBackward() = requestFocusAndMove(backwardRequester, FocusDirection.Previous)
    fun moveForward() = requestFocusAndMove(forwardRequester, FocusDirection.Next)

    @Composable
    fun Content() {
        Box(
            Modifier
                .focusRequester(backwardRequester)
                .onFocusChanged {
                    if (it.isFocused && !isRequesting) {
                        focusManager.clearFocus(force = true)
                        info.container.firstElementChild?.let { component ->
                            requestFocus(component)
                        } ?: moveForward()
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
                        info.container.lastElementChild?.let { component ->
                            requestFocus(component)
                        } ?: moveBackward()
                    }
                }
                .focusTarget()
        )
    }
}

private fun requestFocus(element: Element): Unit = js("element.focus()")

private fun initializingElement(element: Element): Unit = js("""
    {
        element.style.position = 'absolute';
        element.style.margin = '0px';
    }
""")

private fun changeCoordinates(
    element: Element,
    width: Float,
    height: Float,
    x: Float,
    y: Float
): Unit = js("""
    {
        element.style.width = width + 'px';
        element.style.height = height + 'px';
        element.style.left = x + 'px';
        element.style.top = y + 'px';
    }
""")

@Composable
internal fun <T : Element> HtmlView(
    factory: Document.() -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate
) {
    val componentInfo = remember { ComponentInfo<T>() }

    val root = LocalLayerContainer.current
    val density = LocalDensity.current.density
    val focusManager = LocalFocusManager.current
    val focusSwitcher = remember { FocusSwitcher(componentInfo, focusManager) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val location = coordinates.positionInWindow().round()
            val size = coordinates.size
            changeCoordinates(
                componentInfo.component, 
                size.width / density, 
                size.height / density, 
                location.x / density, 
                location.y / density
            )
        }
    ) {
        focusSwitcher.Content()
    }

    DisposableEffect(factory) {
        componentInfo.apply {
            container = document.createElement("div", NoOpUpdate)
            component = document.factory()
            updater = Updater(component, update)
        }

        with(componentInfo) {
            root.insertBefore(container, root.firstChild)
            container.append(component)
            initializingElement(component)
        }

        onDispose {
            root.removeChild(componentInfo.container)
            componentInfo.updater.dispose()
        }
    }

    SideEffect {
        componentInfo.updater.update = update
    }
}

private class Updater<T : Element>(
    private val component: T,
    update: (T) -> Unit
) {
    private var isDisposed = false
    private val snapshotObserver = SnapshotStateObserver { it() }

    var update: (T) -> Unit = update
        set(value) {
            if (field != value) {
                field = value
                performUpdate()
            }
        }

    private fun performUpdate() {
        snapshotObserver.observeReads(component, { if(!isDisposed) performUpdate() }) {
            update(component)
        }
    }

    init {
        snapshotObserver.start()
        performUpdate()
    }

    fun dispose() {
        snapshotObserver.stop()
        snapshotObserver.clear()
        isDisposed = true
    }
}
