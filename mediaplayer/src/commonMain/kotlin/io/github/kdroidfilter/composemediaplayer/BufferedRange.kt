package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Immutable

/**
 * A contiguous span of media that has already been buffered (downloaded and ready to be decoded),
 * expressed in seconds from the start of the media.
 *
 * Buffered ranges mirror the HTML5 `HTMLMediaElement.buffered` model: a player may hold several
 * disjoint ranges, for example after seeking forward into an unbuffered part of a stream.
 *
 * @property start inclusive start of the range, in seconds
 * @property end inclusive end of the range, in seconds
 */
@Immutable
data class BufferedRange(
    val start: Double,
    val end: Double,
) {
    /** Length of the range in seconds. Never negative. */
    val duration: Double get() = (end - start).coerceAtLeast(0.0)

    /** Returns `true` when [positionSeconds] falls inside this range. */
    operator fun contains(positionSeconds: Double): Boolean = positionSeconds in start..end
}

/**
 * Returns the end (in seconds) of the buffered range containing [positionSeconds], i.e. how far
 * playback can continue uninterrupted from that position.
 *
 * When no range covers the position, [positionSeconds] itself is returned, meaning "nothing is
 * buffered ahead".
 */
fun List<BufferedRange>.bufferedEndAt(positionSeconds: Double): Double =
    firstOrNull { positionSeconds in it }?.end ?: positionSeconds

/**
 * Returns how much of the media is buffered ahead of [positionSeconds], as a percentage
 * (`0.0`–`100.0`) of [durationSeconds]. Returns `0f` when the duration is unknown.
 */
fun List<BufferedRange>.bufferedPercentageAt(
    positionSeconds: Double,
    durationSeconds: Double,
): Float {
    if (durationSeconds <= 0.0 || durationSeconds.isNaN() || durationSeconds.isInfinite()) return 0f
    val end = bufferedEndAt(positionSeconds)
    return ((end / durationSeconds) * 100.0).toFloat().coerceIn(0f, 100f)
}

/**
 * Cleans up raw ranges coming from a platform player: drops empty or non-finite entries, clamps
 * them to `[0, durationSeconds]` when the duration is known, sorts them and merges the ones that
 * overlap or touch.
 *
 * Backends report ranges with varying degrees of tidiness, so every implementation funnels its
 * values through this function to give callers a single, predictable shape.
 */
internal fun normalizeBufferedRanges(
    ranges: List<BufferedRange>,
    durationSeconds: Double = 0.0,
): List<BufferedRange> {
    val hasDuration = durationSeconds > 0.0 && !durationSeconds.isNaN() && !durationSeconds.isInfinite()

    val cleaned =
        ranges
            .asSequence()
            .filter { it.start.isFinite() && it.end.isFinite() }
            .map { range ->
                val start = range.start.coerceAtLeast(0.0)
                val end = if (hasDuration) range.end.coerceAtMost(durationSeconds) else range.end
                BufferedRange(start, end)
            }.filter { it.end > it.start }
            .sortedBy { it.start }
            .toList()

    if (cleaned.isEmpty()) return emptyList()

    val merged = ArrayList<BufferedRange>(cleaned.size)
    var current = cleaned.first()
    for (range in cleaned.drop(1)) {
        current =
            if (range.start <= current.end) {
                BufferedRange(current.start, maxOf(current.end, range.end))
            } else {
                merged.add(current)
                range
            }
    }
    merged.add(current)
    return merged
}
