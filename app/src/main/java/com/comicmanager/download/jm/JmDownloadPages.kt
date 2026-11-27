package com.comicmanager.download.jm

import com.comicmanager.util.JmIdParser
import io.github.jukomu.jmcomic.api.model.JmImage
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Resolve all chapters before any files are written. An incomplete album cannot be imported. */
internal suspend fun resolveJmDownloadPages(
    albumId: String,
    chapters: List<JmPhotoMeta>,
    getChapterImages: suspend (String) -> List<JmImage>,
    evictChapter: (String) -> Unit,
    getReaderImages: suspend (String) -> List<JmImage>
): List<JmImage>? {
    val chapterIds = chapters.sortedBy { it.sortOrder }.map { JmIdParser.normalize(it.id) }
    val ids = if (chapterIds.isEmpty() || (chapterIds.size == 1 && chapterIds[0] == null)) {
        listOf(albumId)
    } else {
        chapterIds.map { it ?: return null }.distinct()
    }
    val pages = mutableListOf<JmImage>()

    for (chapterId in ids) {
        currentCoroutineContext().ensureActive()
        var images = getChapterImages(chapterId)
        if (images.isEmpty()) {
            currentCoroutineContext().ensureActive()
            // The API client caches successful metadata responses even when they have no pages.
            evictChapter(chapterId)
            images = getChapterImages(chapterId)
        }
        if (images.isEmpty()) {
            currentCoroutineContext().ensureActive()
            evictChapter(chapterId)
            images = getReaderImages(chapterId)
        }
        if (images.isEmpty()) return null
        pages.addAll(images.sortedBy { it.sortOrder })
    }
    return pages
}
