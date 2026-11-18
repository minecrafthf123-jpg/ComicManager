package com.comicmanager.download.jm

/** Chapter metadata counts chapters, never pages. A partial sum must not be presented as a total. */
internal suspend fun resolveAlbumPageCount(
    reportedPages: Int,
    imageCount: Int?,
    chapterIds: List<String>,
    fetchChapterCount: suspend (String) -> Int?
): Int? {
    if (reportedPages > 0) return reportedPages
    val chapters = chapterIds.filter { it.isNotBlank() }.distinct()
    if (chapters.isEmpty()) return imageCount?.takeIf { it > 0 }
    var total = 0L
    for (chapter in chapters) {
        val count = fetchChapterCount(chapter)?.takeIf { it > 0 } ?: return null
        total += count
        if (total > Int.MAX_VALUE) return null
    }
    return total.toInt()
}
