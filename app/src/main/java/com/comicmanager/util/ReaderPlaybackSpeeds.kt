package com.comicmanager.util

import kotlin.math.floor

/** Speeds describe the visible reading area, independent of display density. */
object ReaderPlaybackSpeeds {
    private val rates = floatArrayOf(0.05f, 0.12f, 0.3f, 0.7f, 1.5f)

    fun screensPerSecond(level: Int): Float = rates[level.coerceIn(1, rates.size) - 1]

    fun pixelsPerSecond(level: Int, viewportHeightPx: Int): Float =
        screensPerSecond(level) * viewportHeightPx.coerceAtLeast(0)
}

/** Retain subpixel movement so high-refresh-rate frames never lose distance. */
class ReaderScrollAccumulator {
    private var remainder = 0.0

    fun frameDistance(pixelsPerSecond: Float, seconds: Float): Float {
        remainder += pixelsPerSecond.toDouble() * seconds.coerceAtLeast(0f)
        val wholePixels = floor(remainder + 0.000001)
        remainder -= wholePixels
        return wholePixels.toFloat()
    }
}
