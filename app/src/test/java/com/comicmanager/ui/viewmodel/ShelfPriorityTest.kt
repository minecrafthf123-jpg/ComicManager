package com.comicmanager.ui.viewmodel

import com.comicmanager.model.Comic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfPriorityTest {
    @Test
    fun existingDownloadsUseTheSameSortAsLocalComics() {
        val books = listOf(
            comic("1", createdAt = 400),
            comic("20", createdAt = 200, isJmDownload = true),
            comic("100", createdAt = 300),
            comic("60", createdAt = 100, isJmDownload = true),
            comic("30", createdAt = 500, isJmDownload = true),
            comic("5", createdAt = 50)
        )

        fun titles(mode: SortMode) = sortShelfComics(books, mode).map { it.name }

        assertEquals(listOf("30", "1", "100", "20", "60", "5"), titles(SortMode.TIME_DESC))
        assertEquals(listOf("5", "60", "20", "100", "1", "30"), titles(SortMode.TIME_ASC))
        assertEquals(listOf("100", "60", "30", "20", "5", "1"), titles(SortMode.NAME_NUM_DESC))
        assertEquals(listOf("1", "5", "20", "30", "60", "100"), titles(SortMode.NAME_NUM_ASC))
    }

    @Test
    fun onlyNewDownloadsArePlacedAheadOfTheSelectedSort() {
        val existing = listOf(comic("10", 100), comic("20", 200, isJmDownload = true))
        val knownIds = existing.map { it.id }.toSet()
        assertNull(updateRecentJmDownload(existing, null, null, knownIds))
        val newDownload = comic("30", 400, isJmDownload = true)
        val newLocal = comic("40", 500)
        val books = existing + newDownload + newLocal
        val liveIds = books.map { it.id }.toSet()
        val recentId = updateRecentJmDownload(books, knownIds, null, liveIds)
        assertEquals(30L, recentId)
        assertEquals(
            listOf(30L, 10L, 20L, 40L),
            sortShelfComics(books, SortMode.NAME_NUM_ASC, recentJmId = recentId).map { it.id }
        )
        assertEquals(listOf(10L, 20L, 30L, 40L), sortShelfComics(books, SortMode.NAME_NUM_ASC).map { it.id })

        val later = books + comic("50", 600, isJmDownload = true)
        val latest = updateRecentJmDownload(later, liveIds, recentId, liveIds + 50L)
        assertEquals(
            listOf(50L, 10L, 20L, 30L, 40L),
            sortShelfComics(later, SortMode.NAME_NUM_ASC, recentJmId = latest).map { it.id }
        )
    }

    @Test
    fun customOrderPlacesArrivalsAndCanThenMoveAnyComicFreely() {
        val books = listOf(comic("10", 100, isJmDownload = true), comic("20", 200), comic("30", 300, isJmDownload = true))
        val saved = listOf(20L, 99L, 10L)
        val arrivals = books + comic("40", 500)
        val updated = reconcileCustomShelfOrder(arrivals, saved)
        assertEquals(listOf(30L, 20L, 10L, 40L), updated)
        val rearranged = requireNotNull(moveShelfComic(updated, 30L, 40L))
        assertEquals(listOf(20L, 10L, 40L, 30L), rearranged)
        assertEquals(rearranged, reconcileCustomShelfOrder(arrivals.reversed(), rearranged))
        assertEquals(rearranged, sortShelfComics(arrivals, SortMode.CUSTOM, customOrder = rearranged).map { it.id })
        assertEquals(listOf(30L, 20L, 10L, 40L), moveShelfComic(rearranged, 30L, 20L))
        assertNull(moveShelfComic(rearranged, 30L, 99L))
    }

    private fun comic(name: String, createdAt: Long, isJmDownload: Boolean = false) = Comic(
        id = name.toLong(),
        name = name,
        coverPath = "",
        directoryPath = "",
        pageCount = 1,
        createdAt = createdAt,
        isJmDownload = isJmDownload
    )
}
