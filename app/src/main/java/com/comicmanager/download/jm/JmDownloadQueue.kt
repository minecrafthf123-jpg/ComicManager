package com.comicmanager.download.jm

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
    .map { item -> item.copy(id = item.id.trim(), name = item.name.trim()) }
    .filter { it.id.isNotBlank() }
    .distinctBy { it.id }
    .map { item -> item.copy(name = item.name.ifBlank { "JM${item.id}" }) }

/** Albums run sequentially. Failure affects one album; cancellation stops the entire queue. */
internal suspend fun runJmDownloadQueue(
    items: List<JmSearchItem>,
    isCancellationRequested: () -> Boolean,
    download: suspend (JmSearchItem) -> JmNativeDownloadResult,
    onProgress: (JmBatchProgress) -> Unit
): JmBatchProgress {
    val queue = normalizeJmDownloadQueue(items)
    var progress = JmBatchProgress(total = queue.size)
    onProgress(progress)
    try {
        for ((index, item) in queue.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (isCancellationRequested()) break
            progress = progress.copy(currentIndex = index + 1, currentItem = item)
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
            onProgress(progress)
        }
        progress = progress.copy(
            currentItem = null,
            cancelled = isCancellationRequested() && progress.completed < progress.total
        )
        onProgress(progress)
        return progress
    } catch (cancelled: CancellationException) {
        onProgress(progress.copy(currentItem = null, cancelled = progress.completed < progress.total))
        throw cancelled
    }
}
