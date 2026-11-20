package com.comicmanager.ui.viewmodel

import com.comicmanager.model.Comic
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfPriorityTest {
    @Test
    fun jmDownloadsComeFirstInEverySortMode() {
        val books = listOf(
            comic("1", createdAt = 400, jmId = "12345"),
            comic("20", createdAt = 200, isJmDownload = true),
            comic("100", createdAt = 300),
            comic("60", createdAt = 100, isJmDownload = true),
            comic("30", createdAt = 500, isJmDownload = true),
            comic("5", createdAt = 50)
        )

        fun titles(mode: SortMode) = sortShelfComics(books, mode).map { it.name }

        assertEquals(listOf("30", "20", "60", "1", "100", "5"), titles(SortMode.TIME_DESC))
        assertEquals(listOf("60", "20", "30", "5", "100", "1"), titles(SortMode.TIME_ASC))
        assertEquals(listOf("60", "30", "20", "100", "5", "1"), titles(SortMode.NAME_NUM_DESC))
        assertEquals(listOf("20", "30", "60", "1", "5", "100"), titles(SortMode.NAME_NUM_ASC))
    }

    private fun comic(name: String, createdAt: Long, jmId: String = "", isJmDownload: Boolean = false) = Comic(
        name = name,
        coverPath = "",
        directoryPath = "",
        pageCount = 1,
        createdAt = createdAt,
        jmId = jmId,
        isJmDownload = isJmDownload
    )
}
