package com.comicmanager.download.jm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JmPageCountTest {
    @Test fun countsImagesAcrossChaptersInsteadOfReturningChapterCount() = runBlocking {
        val counts = mapOf("1" to 23, "2" to 41)
        assertEquals(64, resolveAlbumPageCount(0, 0, listOf("1", "2")) { counts[it] })
    }

    @Test fun missingChapterCountNeverProducesPartialTotal() = runBlocking {
        assertNull(resolveAlbumPageCount(0, 2, listOf("1", "2")) { if (it == "1") 23 else null })
    }

    @Test fun repeatedChapterMetadataDoesNotDoubleCountImages() = runBlocking {
        assertEquals(23, resolveAlbumPageCount(0, 0, listOf("1", "1")) { 23 })
    }

    @Test fun unknownCountIsNotZeroOrNumberOfChapters() = runBlocking {
        assertNull(resolveAlbumPageCount(0, 0, emptyList()) { error("Unexpected fetch") })
    }

    @Test fun authoritativePageCountDoesNotFetchChapterMetadata() = runBlocking {
        assertEquals(72, resolveAlbumPageCount(72, 0, listOf("1")) { error("Unexpected fetch") })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoUnknownPageCount() = runBlocking {
        resolveAlbumPageCount(0, null, listOf("1")) { throw CancellationException() }
        Unit
    }
}
