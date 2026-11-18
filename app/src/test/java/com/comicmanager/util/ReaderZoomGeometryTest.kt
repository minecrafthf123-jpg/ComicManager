package com.comicmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderZoomGeometryTest {
    @Test
    fun pinchKeepsThePagePointUnderTheFingers() {
        val result = ReaderZoomGeometry.transform(800f, 1.5f, -10f, 120f, 2f, 0f, 0f, 300f, 300f)
        assertEquals(3f, result.scale, 0.001f)
        assertEquals(80f, result.horizontalOffset, 0.001f)
        assertEquals((120f + 300f) / 1.5f, (result.scrollOffset + 300f) / result.scale, 0.001f)
        assertEquals((-100f + 10f) / 1.5f, (-100f - result.horizontalOffset) / result.scale, 0.001f)
    }

    @Test
    fun reachingMaximumZoomUsesTheClampedRatioForTheAnchor() {
        val result = ReaderZoomGeometry.transform(800f, 3.5f, 0f, 200f, 2f, 0f, 0f, 400f, 100f)
        assertEquals(4f, result.scale, 0.001f)
        assertEquals(300f * (4f / 3.5f) - 100f, result.scrollOffset, 0.001f)
    }

    @Test
    fun horizontalPanningCannotExposeSpaceBeyondEitherPageEdge() {
        val left = ReaderZoomGeometry.transform(800f, 2f, 0f, 100f, 1f, -1000f, 20f, 400f, 200f)
        val right = ReaderZoomGeometry.transform(800f, 2f, 0f, 100f, 1f, 1000f, -20f, 400f, 200f)
        assertEquals(-400f, left.horizontalOffset, 0.001f)
        assertEquals(400f, right.horizontalOffset, 0.001f)
        assertEquals(80f, left.scrollOffset, 0.001f)
        assertEquals(120f, right.scrollOffset, 0.001f)
    }

    @Test
    fun zoomingOutStopsAtFitWidthAndCannotScrollAboveTheFirstPage() {
        val result = ReaderZoomGeometry.transform(800f, 2f, 300f, 10f, 0.1f, 500f, 100f, 600f, 100f)
        assertEquals(1f, result.scale, 0.001f)
        assertEquals(0f, result.horizontalOffset, 0.001f)
        assertEquals(0f, result.scrollOffset, 0.001f)
    }

    @Test
    fun zoomingOutNearTheTenthPageTopPreservesAnAnchorInThePrecedingPage() {
        val result = ReaderZoomGeometry.transform(
            800f, 2f, 0f, 10f, 0.5f, 0f, 0f, 400f, 300f,
            firstVisiblePageIndex = 9
        )
        assertEquals(-145f, result.scrollOffset, 0.001f)
        assertEquals((10f + 300f) / 2f, (result.scrollOffset + 300f) / result.scale, 0.001f)
    }
}
