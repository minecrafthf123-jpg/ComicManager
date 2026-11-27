package com.comicmanager.download

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.jm.JmBatchFailure
import com.comicmanager.download.jm.JmBatchProgress
import com.comicmanager.download.jm.JmDownloadQueue
import com.comicmanager.download.jm.JmNativeDownloadResult
import com.comicmanager.download.jm.JmNativeDownloader
import com.comicmanager.download.jm.JmSearchItem
import com.comicmanager.download.jm.runJmDownloadQueue
import com.comicmanager.util.JmIdParser
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val statusMessage: String = "",
    val canRetry: Boolean = false,
    val nativePhase: String = "",
    val nativeCurrent: Int = 0,
    val nativeTotal: Int = 0,
    val nativeMsg: String = "",
    val canCancel: Boolean = true,
    val isCancelling: Boolean = false,
    val isBatch: Boolean = false,
    val batchTotal: Int = 0,
    val batchCurrent: Int = 0,
    val batchCompleted: Int = 0,
    val batchSucceeded: Int = 0,
    val batchFailed: Int = 0,
    val batchCancelled: Int = 0,
    val currentAlbumId: String = "",
    val currentAlbumTitle: String = "",
    val queuedAlbums: List<JmSearchItem> = emptyList(),
    val downloadedAlbumIds: Set<String> = emptySet(),
    val failedAlbums: List<JmSearchItem> = emptyList(),
    val batchFailures: List<JmBatchFailure> = emptyList()
)

class JmDownloadViewModel(repository: ComicRepository) : ViewModel() {
    private class DownloadSession(val queue: JmDownloadQueue) {
        val cancellationRequested = AtomicBoolean(false)
    }

    private val downloader = JmNativeDownloader(repository)
    private var appContext: Context? = null
    private var activeSession: DownloadSession? = null
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allComics.collect { comics ->
                val ids = comics.mapNotNullTo(mutableSetOf()) { JmIdParser.normalize(it.jmId) }
                _uiState.update { it.copy(downloadedAlbumIds = ids) }
            }
        }
        viewModelScope.launch {
            downloader.state.collect { progress ->
                _uiState.update { it.copy(
                    nativePhase = progress.phase,
                    nativeCurrent = progress.current,
                    nativeTotal = progress.total,
                    nativeMsg = progress.message,
                    // A batch may be cancelled during a short database commit: that album
                    // finishes its commit, then the queue stops before starting another.
                    canCancel = (it.isBatch || progress.canCancel) && !it.isCancelling
                ) }
            }
        }
    }

    fun setAppContext(context: Context) {
        appContext = context.applicationContext.createConfigurationContext(context.resources.configuration)
    }

    fun startNativeDownload(albumId: String) =
        enqueue(listOf(JmSearchItem(albumId, "", "")), asBatch = false)

    fun startBatchDownload(items: List<JmSearchItem>) = enqueue(items, asBatch = true)

    private fun enqueue(items: List<JmSearchItem>, asBatch: Boolean) {
        val context = appContext ?: return
        val installed = _uiState.value.downloadedAlbumIds
        val eligible = items.filter { JmIdParser.normalize(it.id) !in installed }
        val running = activeSession
        if (running != null) {
            val queue = running.queue
            if (running.cancellationRequested.get() || queue.append(eligible) == 0) return
            _uiState.update {
                it.copy(
                    isBatch = true,
                    batchTotal = queue.size,
                    queuedAlbums = queue.waitingAfter(it.batchCurrent),
                    canCancel = true
                )
            }
            return
        }
        val queue = JmDownloadQueue(eligible)
        if (queue.size == 0) return
        val session = DownloadSession(queue)
        activeSession = session
        _uiState.update {
            DownloadUiState(
                isDownloading = true,
                isBatch = asBatch || queue.size > 1,
                batchTotal = queue.size,
                queuedAlbums = queue.waitingAfter(0),
                downloadedAlbumIds = it.downloadedAlbumIds
            )
        }
        viewModelScope.launch { drainQueue(context, session) }
    }

    private suspend fun drainQueue(context: Context, session: DownloadSession) {
        var lastResult: JmNativeDownloadResult? = null
        try {
            runJmDownloadQueue(
                queue = session.queue,
                isCancellationRequested = session.cancellationRequested::get,
                download = { item ->
                    downloader.downloadAlbum(context, item.id, session.cancellationRequested::get)
                        .also { lastResult = it }
                },
                onProgress = { updateBatchProgress(session.queue, it) }
            )
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
        } finally {
            activeSession = null
            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    isCancelling = false,
                    queuedAlbums = emptyList(),
                    statusMessage = downloadSummary(context, state, lastResult),
                    canRetry = state.failedAlbums.isNotEmpty()
                )
            }
        }
    }

    private fun downloadSummary(
        context: Context,
        state: DownloadUiState,
        lastResult: JmNativeDownloadResult?
    ): String = when {
        state.isBatch && state.batchCancelled > 0 -> context.getString(
            R.string.jm_batch_v21_cancelled_summary,
            state.batchSucceeded, state.batchFailed, state.batchCancelled
        )
        state.isBatch -> context.getString(
            R.string.jm_batch_v21_completed_summary,
            state.batchTotal, state.batchSucceeded, state.batchFailed
        )
        state.batchFailures.isNotEmpty() -> context.getString(
            R.string.status_download_failed_retry, state.batchFailures.first().message
        )
        lastResult != null -> context.resources.getQuantityString(
            R.plurals.native_added_pages, lastResult.pageCount, lastResult.comicName, lastResult.pageCount
        )
        else -> context.getString(R.string.native_cancelled)
    }

    private fun updateBatchProgress(queue: JmDownloadQueue, progress: JmBatchProgress) {
        _uiState.update { previous ->
            val albumId = progress.currentItem?.id.orEmpty()
            val state = if (albumId == previous.currentAlbumId) previous else previous.copy(
                nativePhase = "", nativeCurrent = 0, nativeTotal = 0, nativeMsg = ""
            )
            state.copy(
                batchTotal = progress.total,
                batchCurrent = progress.currentIndex,
                batchCompleted = progress.completed,
                batchSucceeded = progress.succeededItems.size,
                batchFailed = progress.failures.size,
                batchCancelled = progress.cancelledCount,
                currentAlbumId = albumId,
                currentAlbumTitle = progress.currentItem?.name.orEmpty(),
                queuedAlbums = if (progress.cancelled) emptyList() else queue.waitingAfter(progress.currentIndex),
                failedAlbums = progress.failures.map { it.item },
                batchFailures = progress.failures
            )
        }
    }

    fun cancelNativeDownload() {
        val session = activeSession ?: return
        val state = _uiState.value
        if (state.isCancelling || !state.canCancel) return
        session.cancellationRequested.set(true)
        _uiState.update { it.copy(isCancelling = true, canCancel = false) }
        // Leave the queue worker running so a commit already in progress can be counted.
        downloader.cancel()
    }

    suspend fun searchJm(query: String, page: Int): Pair<Int, List<JmSearchItem>> = downloader.search(query, page)
    suspend fun enrichJmTitles(items: List<JmSearchItem>): List<JmSearchItem> = downloader.enrichTitles(items)
    suspend fun getJmAlbumDetail(albumId: String): JmAlbum = downloader.getAlbumDetail(albumId)
    suspend fun getJmPageCount(album: JmAlbum): Int? = downloader.getPageCount(album)
    suspend fun getJmComments(albumId: String, page: Int): List<JmComment> = downloader.getComments(albumId, page)
    fun retryDownload() {
        val state = _uiState.value
        if (!state.isDownloading) enqueue(state.failedAlbums, asBatch = state.isBatch)
    }
    fun resetState() {
        _uiState.update { if (it.isDownloading) it else DownloadUiState(downloadedAlbumIds = it.downloadedAlbumIds) }
    }

    override fun onCleared() {
        activeSession?.cancellationRequested?.set(true)
        downloader.cancel()
        downloader.close()
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = JmDownloadViewModel(repository) as T
    }
}
