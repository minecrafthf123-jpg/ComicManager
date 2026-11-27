package com.comicmanager.ui.viewmodel

import androidx.annotation.StringRes
import com.comicmanager.R
import com.comicmanager.model.Comic

enum class SortMode(@StringRes val labelRes: Int) {
    TIME_DESC(R.string.sort_recent),
    TIME_ASC(R.string.sort_oldest),
    NAME_NUM_DESC(R.string.sort_number_desc),
    NAME_NUM_ASC(R.string.sort_number_asc),
    CUSTOM(R.string.sort_custom)
}

internal fun sortShelfComics(
    comics: List<Comic>,
    mode: SortMode,
    customOrder: List<Long> = emptyList(),
    recentJmId: Long? = null
): List<Comic> {
    val sorted = when (mode) {
        SortMode.TIME_DESC, SortMode.CUSTOM -> comics.sortedByDescending { it.createdAt }
        SortMode.TIME_ASC -> comics.sortedBy { it.createdAt }
        SortMode.NAME_NUM_DESC -> comics.sortedByDescending { parseNameNumber(it.name) }
        SortMode.NAME_NUM_ASC -> comics.sortedBy { parseNameNumber(it.name) }
    }
    val leadingIds = if (mode == SortMode.CUSTOM) customOrder else listOfNotNull(recentJmId)
    if (leadingIds.isEmpty()) return sorted
    val remaining = sorted.associateByTo(linkedMapOf()) { it.id }
    val leading = leadingIds.mapNotNull(remaining::remove)
    return leading + remaining.values
}

internal fun reconcileCustomShelfOrder(comics: List<Comic>, savedOrder: List<Long>): List<Long> {
    val liveIds = comics.mapTo(hashSetOf()) { it.id }
    val existing = savedOrder.filter { it in liveIds }
    val rankedIds = existing.toHashSet()
    val arrivals = comics.filter { it.id !in rankedIds }.sortedWith(newestFirst)
    val (downloaded, local) = arrivals.partition { it.isJmDownload }
    return downloaded.map { it.id } + existing + local.map { it.id }
}

internal fun moveShelfComic(
    currentOrder: List<Long>,
    sourceId: Long,
    destinationId: Long
): List<Long>? {
    if (sourceId == destinationId) return null
    val sourceIndex = currentOrder.indexOf(sourceId)
    val targetIndex = currentOrder.indexOf(destinationId)
    if (sourceIndex == -1 || targetIndex == -1) return null
    return currentOrder.toMutableList().apply {
        removeAt(sourceIndex)
        add(targetIndex, sourceId)
    }
}

internal fun updateRecentJmDownload(
    comics: List<Comic>,
    previouslySeenIds: Set<Long>?,
    recentJmId: Long?,
    liveIds: Set<Long>
): Long? {
    if (previouslySeenIds == null) return null
    val newestArrival = comics.asSequence()
        .filter { it.isJmDownload && it.id !in previouslySeenIds }
        .minWithOrNull(newestFirst)
    return newestArrival?.id ?: recentJmId?.takeIf { it in liveIds }
}

private val newestFirst = compareByDescending<Comic> { it.createdAt }.thenByDescending { it.id }

private fun parseNameNumber(name: String): Long =
    name.toLongOrNull() ?: name.filter { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE
