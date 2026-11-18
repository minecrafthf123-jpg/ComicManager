package com.comicmanager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionSessionTest {
    private val ids = (1L..12L).toList()

    @Test
    fun expandsAcrossRowsAndRestoresItemsWhenDraggingBack() {
        val gesture = DragSelectionSession(ids, setOf(10L), 1)
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L, 10L), gesture.selectionAt(6))
        assertEquals(setOf(2L, 3L, 10L), gesture.selectionAt(2))
    }

    @Test
    fun draggingFromSelectedItemDeselectsRangeAndRestoresPreviousSelections() {
        val gesture = DragSelectionSession(ids, setOf(2L, 3L, 5L, 10L), 1)
        assertEquals(setOf(10L), gesture.selectionAt(6))
        assertEquals(setOf(3L, 5L, 10L), gesture.selectionAt(1))
    }

    @Test
    fun crossingAnchorRestoresOldRangeAndSelectsNewRange() {
        val gesture = DragSelectionSession(ids, setOf(1L, 10L), 5)
        assertEquals(setOf(1L, 6L, 7L, 8L, 9L, 10L), gesture.selectionAt(8))
        assertEquals(setOf(1L, 3L, 4L, 5L, 6L, 10L), gesture.selectionAt(2))
    }

    @Test
    fun clampsScrollEndpointsWithoutLosingHiddenSelections() {
        val gesture = DragSelectionSession(ids, setOf(100L), 10)
        assertEquals(setOf(11L, 12L, 100L), gesture.selectionAt(Int.MAX_VALUE))
    }
}
