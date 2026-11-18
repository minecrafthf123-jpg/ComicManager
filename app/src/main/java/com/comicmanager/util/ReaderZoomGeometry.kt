package com.comicmanager.util

/** Pinch coordinates are in the unscaled, clipped reader viewport. */
data class ReaderZoomResult(val scale: Float, val horizontalOffset: Float, val scrollOffset: Float)

object ReaderZoomGeometry {
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
        val newScale = (scale * zoom).coerceIn(1f, 4f)
        val ratio = newScale / scale
        val focusX = centroidX - viewportWidth / 2f
        val limit = viewportWidth * (newScale - 1f) / 2f
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
