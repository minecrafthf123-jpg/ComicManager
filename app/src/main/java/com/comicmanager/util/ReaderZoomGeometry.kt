package com.comicmanager.util

/** Pinch coordinates are in the unscaled, clipped reader viewport. */
data class ReaderZoomResult(val scale: Float, val horizontalOffset: Float, val scrollOffset: Float)

object ReaderZoomGeometry {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 4f

    fun horizontalPanLimit(viewportWidth: Float, scale: Float): Float =
        (viewportWidth * (scale - 1f) / 2f).coerceAtLeast(0f)

    /** Reset around the visible page's top, preserving its relative reading position. */
    fun reset(scale: Float, scrollOffset: Float): ReaderZoomResult =
        ReaderZoomResult(scale = 1f, horizontalOffset = 0f, scrollOffset = scrollOffset / scale)

    fun transform(
        viewportWidth: Float,
        scale: Float,
        horizontalOffset: Float,
        scrollOffset: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        centroidX: Float,
        centroidY: Float,
        firstVisiblePageIndex: Int = 0
    ): ReaderZoomResult {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        val focusX = centroidX - viewportWidth / 2f
        val limit = horizontalPanLimit(viewportWidth, newScale)
        val anchorOffset = (scrollOffset + centroidY) * ratio - centroidY - panY
        return ReaderZoomResult(
            scale = newScale,
            horizontalOffset = ((horizontalOffset - focusX) * ratio + focusX + panX).coerceIn(-limit, limit),
            // A negative offset for later pages reveals the preceding page's
            // bottom. LazyColumn resolves that anchor across item boundaries.
            scrollOffset = if (firstVisiblePageIndex == 0) anchorOffset.coerceAtLeast(0f) else anchorOffset
        )
    }
}
