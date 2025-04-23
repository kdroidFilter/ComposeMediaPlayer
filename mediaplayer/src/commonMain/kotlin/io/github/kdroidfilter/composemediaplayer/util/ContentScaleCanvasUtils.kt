package io.github.kdroidfilter.composemediaplayer.util

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Converts a [ContentScale] into a [Modifier] that adjusts the composable's size and scaling
 * behavior based on the provided parameters.
 *
 * @param aspectRatio The aspect ratio to maintain when scaling the composable.
 * @param width The width of the composable if using [ContentScale.None]. If `null`, defaults to 0.
 * @param height The height of the composable if using [ContentScale.None]. If `null`, defaults to 0.
 * @return A [Modifier] instance configured according to the given [ContentScale] and parameters.
 */
@Composable
internal fun ContentScale.toCanvasModifier(aspectRatio: Float, width: Int?, height : Int?) : Modifier = when (this) {
    ContentScale.Fit,
    ContentScale.Inside ->
        Modifier
            .fillMaxHeight()
            .aspectRatio(aspectRatio)

    // ↳ Fills the entire width, ratio preserved
    ContentScale.FillWidth ->
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)

    // ↳ Fills the entire height, ratio preserved
    ContentScale.FillHeight ->
        Modifier
            .fillMaxHeight()
            .aspectRatio(aspectRatio)

    // ↳ Fills the entire container; the excess will be clipped in drawImage
    ContentScale.Crop,
    ContentScale.FillBounds ->
        Modifier.fillMaxSize()

    // ↳ No resizing: we use the actual size of the media
    ContentScale.None ->
        Modifier
            .width((width ?: 0).dp)
            .height((height ?: 0).dp)

    // ↳ Fallback value (should be impossible)
    else -> Modifier
}

/**
 * Draws [image] in this [DrawScope] respecting the requested [contentScale].
 *
 * @param image       The source bitmap to draw.
 * @param dstSize     Destination size in pixels on the canvas (typically size.toIntSize()).
 * @param contentScale How the image should be scaled inside [dstSize].
 */
internal fun DrawScope.drawScaledImage(
    image: ImageBitmap,
    dstSize: IntSize,
    contentScale: ContentScale
) {
    if (contentScale == ContentScale.Crop) {
        /* --------------------------------------------------------------
         * Central crop: scale the bitmap so it fully covers the canvas,
         * then take the required source rectangle to fit the aspect.
         * -------------------------------------------------------------- */
        val frameW = image.width
        val frameH = image.height

        // Scale factor so that the image fully covers dstSize
        val scale = maxOf(
            dstSize.width  / frameW.toFloat(),
            dstSize.height / frameH.toFloat()
        )

        // Visible area of the source bitmap after the covering scale
        val srcW = (dstSize.width  / scale).toInt()
        val srcH = (dstSize.height / scale).toInt()
        val srcX = ((frameW - srcW) / 2).coerceAtLeast(0)
        val srcY = ((frameH - srcH) / 2).coerceAtLeast(0)

        drawImage(
            image      = image,
            srcOffset  = IntOffset(srcX, srcY),
            srcSize    = IntSize(srcW, srcH),
            dstSize    = dstSize               // draw into full destination rect
        )
    } else {
        /* --------------------------------------------------------------
         * No cropping required (Fit / FillWidth / FillHeight / FillBounds).
         * The selected ContentScale was already handled via caller’s
         * graphicsLayer / Modifier.size, so we just draw the full bitmap.
         * -------------------------------------------------------------- */
        drawImage(
            image   = image,
            dstSize = dstSize
        )
    }
}