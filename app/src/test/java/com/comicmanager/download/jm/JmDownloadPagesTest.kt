package com.comicmanager.download.jm

import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JmDownloadPagesTest {
    @Test fun retriesAnEmptyCachedChapterWithoutClosingTheSharedClient() = runBlocking {
        val pages = listOf(image("101", 1), image("101", 2))
        val cache = mutableMapOf("101" to emptyList<JmImage>())
        val evicted = mutableListOf<String>()

        val resolved = resolveJmDownloadPages(
            albumId = "101",
            chapters = listOf(chapter("101", 1)),
            getChapterImages = { id -> cache.getOrPut(id) { pages } },
            evictChapter = { id ->
                evicted += id
                cache.remove(id)
            },
            getReaderImages = { error("No fallback needed after fresh chapter fetch") }
        )

        assertEquals(pages, resolved)
        assertEquals(listOf("101"), evicted)
    }

    @Test fun fallsBackToReaderMetadataAndDoesNotKeepAnEmptyChapterCached() = runBlocking {
        val pages = listOf(image("101", 1))
        val evicted = mutableListOf<String>()
        val readerRequests = mutableListOf<String>()

        val resolved = resolveJmDownloadPages(
            albumId = "101",
            chapters = emptyList(),
            getChapterImages = { emptyList() },
            evictChapter = { evicted += it },
            getReaderImages = { id ->
                readerRequests += id
                pages
            }
        )

        assertEquals(pages, resolved)
        assertEquals(listOf("101", "101"), evicted)
        assertEquals(listOf("101"), readerRequests)
    }

    @Test fun preservesChapterAndPageOrderWithoutAddingDuplicateChapterEntries() = runBlocking {
        val firstPage = image("102", 1)
        val secondPage = image("102", 2)
        val lastPage = image("103", 1)
        val requested = mutableListOf<String>()
        val resolved = resolveJmDownloadPages(
            albumId = "101",
            chapters = listOf(chapter("103", 2), chapter("102", 1), chapter("102", 3)),
            getChapterImages = { id ->
                requested += id
                if (id == "102") listOf(secondPage, firstPage) else listOf(lastPage)
            },
            evictChapter = { error("Complete chapter metadata should stay cached") },
            getReaderImages = { error("Complete chapter metadata should be used directly") }
        )

        assertEquals(listOf("102", "103"), requested)
        assertEquals(listOf(firstPage, secondPage, lastPage), resolved)
    }

    @Test fun doesNotReturnAnAlbumWithAnUnavailableChapter() = runBlocking {
        val resolved = resolveJmDownloadPages(
            albumId = "101",
            chapters = listOf(chapter("102", 1), chapter("103", 2)),
            getChapterImages = { id -> if (id == "102") listOf(image(id, 1)) else emptyList() },
            evictChapter = {},
            getReaderImages = { emptyList() }
        )

        assertNull(resolved)
    }

    @Test fun doesNotSilentlyOmitAMalformedChapterFromASeries() = runBlocking {
        val resolved = resolveJmDownloadPages(
            albumId = "101",
            chapters = listOf(chapter("102", 1), chapter("", 2)),
            getChapterImages = { error("Do not start an incomplete series") },
            evictChapter = {},
            getReaderImages = { error("Do not replace a missing chapter with pages from another book") }
        )

        assertNull(resolved)
    }

    @Test fun aFailedAlbumDoesNotBlockResolvingTheNextAlbum() = runBlocking {
        val getChapterImages: suspend (String) -> List<JmImage> = { id ->
            if (id == "102") listOf(image(id, 1)) else emptyList()
        }
        val first = resolveJmDownloadPages("101", emptyList(), getChapterImages, {}, { emptyList() })
        val second = resolveJmDownloadPages("102", emptyList(), getChapterImages, {}, { emptyList() })

        assertNull(first)
        assertEquals(listOf(image("102", 1)), second)
    }

    @Test fun propagatesCancellationBeforeRetryingOrFallingBack() = runBlocking {
        val failure = runCatching {
            resolveJmDownloadPages(
                albumId = "101",
                chapters = emptyList(),
                getChapterImages = { throw CancellationException("cancelled") },
                evictChapter = { error("Cancellation must skip retries") },
                getReaderImages = { error("Cancellation must skip the reader request") }
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun chapter(id: String, order: Int) = JmPhotoMeta(id, "Chapter $order", order)

    private fun image(chapterId: String, order: Int) = JmImage(
        chapterId, "0", "test_$order.jpg", "https://example.com/$chapterId/$order.jpg", "", order
    )
}
