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
    fun zoomingOutStopsAtHalfWidthAndCannotScrollAboveTheFirstPage() {
        val result = ReaderZoomGeometry.transform(800f, 2f, 300f, 10f, 0.1f, 500f, 100f, 600f, 100f)
        assertEquals(0.5f, result.scale, 0.001f)
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

    @Test
    fun shrinkingBelowFitWidthCentersPagesAndKeepsTheVerticalPointUnderTheFingers() {
        val result = ReaderZoomGeometry.transform(
            800f, 1f, 0f, 400f, 0.75f, 0f, 0f, 300f, 200f,
            firstVisiblePageIndex = 3
        )
        assertEquals(0.75f, result.scale, 0.001f)
        assertEquals(0f, result.horizontalOffset, 0.001f)
        assertEquals((400f + 200f) / 1f, (result.scrollOffset + 200f) / result.scale, 0.001f)
    }

    @Test
    fun narrowPagesCannotPanHorizontallyAndTheirBoundsNeverBecomeAnInvalidRange() {
        assertEquals(0f, ReaderZoomGeometry.horizontalPanLimit(800f, 0.5f), 0.001f)
        assertEquals(0f, ReaderZoomGeometry.horizontalPanLimit(800f, 0.99f), 0.001f)
        assertEquals(0f, ReaderZoomGeometry.horizontalPanLimit(800f, 1f), 0.001f)
        val result = ReaderZoomGeometry.transform(800f, 0.5f, 0f, 300f, 1f, 1000f, -80f, 400f, 200f)
        assertEquals(0f, result.horizontalOffset, 0.001f)
        assertEquals(380f, result.scrollOffset, 0.001f)
    }

    @Test
    fun crossingFitWidthRemovesTheOldHorizontalPanWithoutLosingVerticalFocus() {
        val result = ReaderZoomGeometry.transform(
            800f, 2f, 300f, 500f, 0.3f, 0f, 0f, 400f, 200f,
            firstVisiblePageIndex = 5
        )
        assertEquals(0.6f, result.scale, 0.001f)
        assertEquals(0f, result.horizontalOffset, 0.001f)
        assertEquals(350f, (result.scrollOffset + 200f) / result.scale, 0.001f)
    }

    @Test
    fun zoomingBackInFromHalfWidthUsesTheActualRatioAtTheMaximum() {
        val result = ReaderZoomGeometry.transform(
            800f, 0.5f, 0f, 10f, 20f, 0f, 0f, 400f, 100f,
            firstVisiblePageIndex = 5
        )
        assertEquals(4f, result.scale, 0.001f)
        assertEquals(10f / 0.5f + 100f / 0.5f, (result.scrollOffset + 100f) / result.scale, 0.001f)
    }

    @Test
    fun resettingNarrowOrEnlargedPagesPreservesTheVisiblePagesRelativePosition() {
        val fromNarrow = ReaderZoomGeometry.reset(0.5f, 125f)
        val fromEnlarged = ReaderZoomGeometry.reset(2f, 500f)
        assertEquals(250f, fromNarrow.scrollOffset, 0.001f)
        assertEquals(fromNarrow.scrollOffset, fromEnlarged.scrollOffset, 0.001f)
        assertEquals(1f, fromNarrow.scale, 0.001f)
        assertEquals(0f, fromNarrow.horizontalOffset, 0.001f)
    }
}
