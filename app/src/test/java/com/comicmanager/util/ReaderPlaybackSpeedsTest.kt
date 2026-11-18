package com.comicmanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackSpeedsTest {
    @Test
    fun relativeSpeedIsTheSameForDifferentViewportSizesAndDensities() {
        val smallViewport = ReaderPlaybackSpeeds.pixelsPerSecond(3, 600)
        val denseViewport = ReaderPlaybackSpeeds.pixelsPerSecond(3, 2400)
        assertEquals(0.3f, smallViewport / 600, 0.0001f)
        assertEquals(smallViewport / 600, denseViewport / 2400, 0.0001f)
    }

    @Test
    fun maximumTravelsOneAndAHalfScreensPerSecondAndIsMuchFasterThanTheOldLimit() {
        assertEquals(1.5f, ReaderPlaybackSpeeds.screensPerSecond(5), 0.0001f)
        // On a 600dp reading area at density 1, the previous maximum was 96px/s.
        assertTrue(ReaderPlaybackSpeeds.pixelsPerSecond(5, 600) > 96f * 9)
        assertEquals(0f, ReaderPlaybackSpeeds.pixelsPerSecond(5, 0), 0.0001f)
    }

    @Test
    fun fractionsAccumulateAt240HzInsteadOfBeingDiscardedEveryFrame() {
        val accumulator = ReaderScrollAccumulator()
        val speed = ReaderPlaybackSpeeds.pixelsPerSecond(1, 600)
        var distance = 0f
        repeat(240) { distance += accumulator.frameDistance(speed, 1f / 240) }
        assertEquals(30f, distance, 0.001f)
    }

    @Test
    fun frameRateDoesNotChangeTheDistanceTravelled() {
        fun distanceAt(refreshRate: Int): Float {
            val accumulator = ReaderScrollAccumulator()
            var distance = 0f
            repeat(refreshRate * 10) { distance += accumulator.frameDistance(180f, 1f / refreshRate) }
            return distance
        }
        assertEquals(distanceAt(60), distanceAt(144), 1f)
        assertEquals(1800f, distanceAt(144), 1f)
    }
}
