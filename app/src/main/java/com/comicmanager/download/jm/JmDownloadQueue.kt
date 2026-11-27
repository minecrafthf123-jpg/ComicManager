package com.comicmanager.download.jm

import com.comicmanager.util.JmIdParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class JmBatchFailure(val item: JmSearchItem, val message: String)

data class JmBatchProgress(
    val total: Int,
    val currentIndex: Int = 0,
    val currentItem: JmSearchItem? = null,
    val succeededItems: List<JmSearchItem> = emptyList(),
    val failures: List<JmBatchFailure> = emptyList(),
    val cancelled: Boolean = false
) {
    val completed: Int get() = succeededItems.size + failures.size
    val cancelledCount: Int get() = if (cancelled) total - completed else 0
}

internal fun normalizeJmDownloadQueue(items: List<JmSearchItem>): List<JmSearchItem> = items
    .mapNotNull { item ->
        val id = JmIdParser.normalize(item.id) ?: return@mapNotNull null
        item.copy(id = id, name = item.name.trim().ifBlank { "JM$id" })
    }
    .distinctBy { it.id }

/** The ViewModel appends and advances this queue on Main. Image downloads suspend off Main. */
internal class JmDownloadQueue(initial: List<JmSearchItem>) {
    private val items = normalizeJmDownloadQueue(initial).toMutableList()
    private val albumIds = items.mapTo(mutableSetOf()) { it.id }

    val size: Int get() = items.size

    fun append(albums: List<JmSearchItem>): Int {
        val added = normalizeJmDownloadQueue(albums).filter { albumIds.add(it.id) }
        items.addAll(added)
        return added.size
    }

    fun itemAt(index: Int): JmSearchItem? = items.getOrNull(index)

    fun waitingAfter(startedCount: Int): List<JmSearchItem> = items.drop(startedCount)
}

/** Albums run sequentially, including ones appended while a download is suspended. */
internal suspend fun runJmDownloadQueue(
    queue: JmDownloadQueue,
    isCancellationRequested: () -> Boolean,
    download: suspend (JmSearchItem) -> JmNativeDownloadResult,
    onProgress: (JmBatchProgress) -> Unit
): JmBatchProgress {
    var progress = JmBatchProgress(total = queue.size)
    onProgress(progress)
    try {
        var nextIndex = 0
        while (!isCancellationRequested()) {
            currentCoroutineContext().ensureActive()
            val item = queue.itemAt(nextIndex) ?: break
            nextIndex++
            progress = progress.copy(total = queue.size, currentIndex = nextIndex, currentItem = item)
            onProgress(progress)
            try {
                val result = download(item)
                progress = progress.copy(
                    succeededItems = progress.succeededItems + item.copy(name = result.comicName)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                progress = progress.copy(failures = progress.failures + JmBatchFailure(item, error.message.orEmpty()))
            }
            progress = progress.copy(total = queue.size)
            onProgress(progress)
        }
        progress = progress.copy(
            total = queue.size,
            currentItem = null,
            cancelled = isCancellationRequested() && progress.completed < queue.size
        )
        onProgress(progress)
        return progress
    } catch (cancelled: CancellationException) {
        onProgress(progress.copy(total = queue.size, currentItem = null, cancelled = progress.completed < queue.size))
        throw cancelled
    }
}
