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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

val LocalLayerContainer = staticCompositionLocalOf<Element> { document.body ?: error("Document body unavailable") }
val NoOpUpdate: Element.() -> Unit = {}

private class ComponentInfo<T : Element> {
    lateinit var container: Element
    lateinit var component: T
    lateinit var updater: Updater<T>
}

private class FocusSwitcher<T : Element>(private val info: ComponentInfo<T>, private val focusManager: FocusManager) {
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
        Box(Modifier
            .focusRequester(backwardRequester)
            .onFocusChanged {
                if (it.isFocused && !isRequesting) {
                    focusManager.clearFocus(force = true)
                    info.container.firstElementChild?.let { (it as HTMLElement).focus() } ?: moveForward()
                }
            }
            .focusTarget()
        )
        Box(Modifier
            .focusRequester(forwardRequester)
            .onFocusChanged {
                if (it.isFocused && !isRequesting) {
                    focusManager.clearFocus(force = true)
                    info.container.lastElementChild?.let { (it as HTMLElement).focus() } ?: moveBackward()
                }
            }
            .focusTarget()
        )
    }
}

private fun setElementPosition(element: HTMLElement, width: Float, height: Float, x: Float, y: Float) {
    element.style.apply {
        position = "absolute"
        margin = "0px"
        this.width = "${width}px"
        this.height = "${height}px"
        left = "${x}px"
        top = "${y}px"
    }
}

@Composable
internal fun <T : Element> HtmlView(
    factory: Document.() -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate
) {
    val info = remember { ComponentInfo<T>() }
    val root = LocalLayerContainer.current
    val density = LocalDensity.current.density
    val focusManager = LocalFocusManager.current
    val focusSwitcher = remember { FocusSwitcher(info, focusManager) }

    Box(modifier.onGloballyPositioned { coords ->
        val pos = coords.positionInWindow().round()
        val size = coords.size
        setElementPosition(
            info.component as HTMLElement,
            size.width / density,
            size.height / density,
            pos.x / density,
            pos.y / density
        )
    }) {
        focusSwitcher.Content()
    }

    DisposableEffect(factory) {
        info.apply {
            container = document.createElement("div")
            component = document.factory()
            updater = Updater(component, update)
        }

        root.insertBefore(info.container, root.firstChild)
        info.container.append(info.component)
        setElementPosition(info.component as HTMLElement, 0f, 0f, 0f, 0f)

        onDispose {
            root.removeChild(info.container)
            info.updater.dispose()
        }
    }

    SideEffect {
        info.updater.update = update
    }
}

private class Updater<T : Element>(
    private val component: T,
    update: (T) -> Unit
) {
    private var isDisposed = false
    private val observer = SnapshotStateObserver { it() }

    var update: (T) -> Unit = update
        set(value) {
            if (field != value) {
                field = value
                performUpdate()
            }
        }

    private fun performUpdate() {
        observer.observeReads(component, { if(!isDisposed) performUpdate() }) {
            update(component)
        }
    }

    init {
        observer.start()
        performUpdate()
    }

    fun dispose() {
        observer.stop()
        observer.clear()
        isDisposed = true
    }
}