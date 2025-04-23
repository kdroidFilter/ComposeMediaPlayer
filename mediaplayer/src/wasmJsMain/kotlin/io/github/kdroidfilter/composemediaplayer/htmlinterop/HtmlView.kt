package io.github.kdroidfilter.composemediaplayer.htmlinterop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.ContentScale
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

private fun setElementPosition(
    element: HTMLElement, 
    width: Float, 
    height: Float, 
    x: Float, 
    y: Float, 
    isFullscreen: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit,
    videoRatio: Float? = null
) {
    element.style.apply {
        position = "absolute"
        margin = "0px"

        if (isFullscreen) {
            // In fullscreen mode, make the element fill the entire screen
            this.width = "100%"
            this.height = "100%"
            left = "0"
            top = "0"
        } else {
            // Calculate dimensions based on contentScale and container size
            val containerWidth = width
            val containerHeight = height

            if (videoRatio != null) {
                val containerRatio = containerWidth / containerHeight

                when (contentScale) {
                    ContentScale.Fit, ContentScale.Inside -> {
                        // Scale to fit within container while maintaining aspect ratio
                        this.width = "${containerWidth}px"
                        this.height = "${containerHeight}px"
                        left = "${x}px"
                        top = "${y}px"
                        objectFit = "contain" // Use CSS object-fit to maintain aspect ratio and fit within container
                    }
                    ContentScale.Crop -> {
                        // Scale to cover container while maintaining aspect ratio
                        this.width = "${containerWidth}px"
                        this.height = "${containerHeight}px"
                        left = "${x}px"
                        top = "${y}px"
                        objectFit = "cover" // Use CSS object-fit to maintain aspect ratio and cover container
                    }
                    ContentScale.FillWidth -> {
                        // Fill width, maintain aspect ratio
                        val scaledHeight = containerWidth / videoRatio
                        this.width = "${containerWidth}px"
                        this.height = "${scaledHeight}px"
                        left = "${x}px"
                        top = "${y + (containerHeight - scaledHeight) / 2}px"
                        objectFit = "none" // Don't use CSS object-fit for this case
                    }
                    ContentScale.FillHeight -> {
                        // Fill height, maintain aspect ratio
                        val scaledWidth = containerHeight * videoRatio
                        this.width = "${scaledWidth}px"
                        this.height = "${containerHeight}px"
                        left = "${x + (containerWidth - scaledWidth) / 2}px"
                        top = "${y}px"
                        objectFit = "none" // Don't use CSS object-fit for this case
                    }
                    ContentScale.FillBounds -> {
                        // Fill the entire container without respecting aspect ratio
                        this.width = "${containerWidth}px"
                        this.height = "${containerHeight}px"
                        left = "${x}px"
                        top = "${y}px"
                        objectFit = "fill" // Use CSS object-fit to stretch without preserving ratio
                    }
                    else -> {
                        // Default positioning based on the container
                        this.width = "${width}px"
                        this.height = "${height}px"
                        left = "${x}px"
                        top = "${y}px"
                        objectFit = "contain" // Default to maintain aspect ratio
                    }
                }
            } else {
                // No video ratio available, use default positioning
                this.width = "${width}px"
                this.height = "${height}px"
                left = "${x}px"
                top = "${y}px"

                // Set object-fit based on contentScale even when ratio is unknown
                when (contentScale) {
                    ContentScale.FillBounds -> objectFit = "fill" // Stretch without preserving ratio
                    ContentScale.Crop -> objectFit = "cover" // Cover while maintaining aspect ratio
                    else -> objectFit = "contain" // Default to maintain aspect ratio
                }
            }
        }
    }
}

@Composable
internal fun <T : Element> HtmlView(
    factory: Document.() -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate,
    isFullscreen: Boolean = false,
    contentScale: ContentScale = ContentScale.Fit,
    videoRatio: Float? = null
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
            pos.y / density,
            isFullscreen,
            contentScale,
            videoRatio
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
        setElementPosition(info.component as HTMLElement, 0f, 0f, 0f, 0f, false, contentScale, videoRatio)

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
