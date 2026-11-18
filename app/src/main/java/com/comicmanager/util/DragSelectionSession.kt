package com.comicmanager.util

/** A range gesture always applies to the selection that existed when it started. */
class DragSelectionSession(
    private val orderedIds: List<Long>,
    private val initialSelection: Set<Long>,
    private val anchorIndex: Int
) {
    init {
        require(anchorIndex in orderedIds.indices)
    }

    private val selecting = orderedIds[anchorIndex] !in initialSelection

    fun selectionAt(index: Int): Set<Long> {
        val end = index.coerceIn(orderedIds.indices)
        return initialSelection.toMutableSet().apply {
            for (position in minOf(anchorIndex, end)..maxOf(anchorIndex, end)) {
                val id = orderedIds[position]
                if (selecting) add(id) else remove(id)
            }
        }
    }
}
