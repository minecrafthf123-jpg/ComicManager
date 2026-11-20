package com.comicmanager.download

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.jm.JmNativeDownloader
import com.comicmanager.download.jm.JmBatchFailure
import com.comicmanager.download.jm.JmBatchProgress
import com.comicmanager.download.jm.JmSearchItem
import com.comicmanager.download.jm.normalizeJmDownloadQueue
import com.comicmanager.download.jm.runJmDownloadQueue
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.CancellationException
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
    val failedAlbums: List<JmSearchItem> = emptyList(),
    val batchFailures: List<JmBatchFailure> = emptyList()
)

class JmDownloadViewModel(repository: ComicRepository) : ViewModel() {
    private val downloader = JmNativeDownloader(repository)
    private var appContext: Context? = null
    private var lastAlbumId = ""
    private val cancellationRequested = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState = _uiState.asStateFlow()

    init {
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

    fun startNativeDownload(albumId: String) {
        val context = appContext ?: return
        if (_uiState.value.isDownloading || albumId.isBlank()) return
        lastAlbumId = albumId.trim()
        cancellationRequested.set(false)
        _uiState.value = DownloadUiState(
            isDownloading = true, currentAlbumId = lastAlbumId, currentAlbumTitle = "JM$lastAlbumId"
        )
        viewModelScope.launch {
            try {
                val result = downloader.downloadAlbum(context, lastAlbumId, cancellationRequested::get)
                _uiState.update { it.copy(
                    statusMessage = context.resources.getQuantityString(
                        R.plurals.native_added_pages, result.pageCount, result.comicName, result.pageCount
                    ),
                    canRetry = false
                ) }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(
                    statusMessage = context.getString(R.string.native_cancelled), canRetry = false
                ) }
            } catch (error: Exception) {
                _uiState.update { it.copy(
                    statusMessage = context.getString(R.string.status_download_failed_retry, error.message.orEmpty()),
                    canRetry = true
                ) }
            } finally {
                _uiState.update { it.copy(isDownloading = false, isCancelling = false) }
            }
        }
    }

    fun startBatchDownload(items: List<JmSearchItem>) {
        val context = appContext ?: return
        if (_uiState.value.isDownloading) return
        val queue = normalizeJmDownloadQueue(items)
        if (queue.isEmpty()) return
        cancellationRequested.set(false)
        _uiState.value = DownloadUiState(isDownloading = true, isBatch = true, batchTotal = queue.size)
        viewModelScope.launch {
            try {
                runJmDownloadQueue(
                    items = queue,
                    isCancellationRequested = cancellationRequested::get,
                    download = { item -> downloader.downloadAlbum(context, item.id, cancellationRequested::get) },
                    onProgress = ::updateBatchProgress
                )
            } catch (_: CancellationException) {
                _uiState.update { it.copy(batchCancelled = it.batchTotal - it.batchCompleted) }
            } finally {
                _uiState.update { state ->
                    val summary = if (state.batchCancelled > 0) {
                        context.getString(
                            R.string.jm_batch_v21_cancelled_summary,
                            state.batchSucceeded, state.batchFailed, state.batchCancelled
                        )
                    } else {
                        context.getString(
                            R.string.jm_batch_v21_completed_summary,
                            state.batchTotal, state.batchSucceeded, state.batchFailed
                        )
                    }
                    state.copy(
                        isDownloading = false,
                        isCancelling = false,
                        statusMessage = summary,
                        canRetry = state.failedAlbums.isNotEmpty()
                    )
                }
            }
        }
    }

    private fun updateBatchProgress(progress: JmBatchProgress) {
        _uiState.update { state -> state.copy(
            batchTotal = progress.total,
            batchCurrent = progress.currentIndex,
            batchCompleted = progress.completed,
            batchSucceeded = progress.succeededItems.size,
            batchFailed = progress.failures.size,
            batchCancelled = progress.cancelledCount,
            currentAlbumId = progress.currentItem?.id.orEmpty(),
            currentAlbumTitle = progress.currentItem?.name.orEmpty(),
            failedAlbums = progress.failures.map { it.item },
            batchFailures = progress.failures
        ) }
    }

    fun cancelNativeDownload() {
        val state = _uiState.value
        if (!state.isDownloading || state.isCancelling) return
        // Publish the token before consulting the active job so mutex acquisition cannot
        // race through the gap between cancel() and the cancellation flag being set.
        cancellationRequested.set(true)
        val canInterrupt = downloader.cancel()
        if (canInterrupt || state.isBatch) {
            _uiState.update { it.copy(isCancelling = true, canCancel = false) }
            // Only the downloader's active job is interrupted. Cancelling the parent here
            // could discard an album result already committed on IO but awaiting Main dispatch.
            // A request waiting for the download mutex observes the token when it enters.
        }
    }

    suspend fun searchJm(query: String, page: Int): Pair<Int, List<JmSearchItem>> = downloader.search(query, page)
    suspend fun enrichJmTitles(items: List<JmSearchItem>): List<JmSearchItem> = downloader.enrichTitles(items)
    suspend fun getJmAlbumDetail(albumId: String): JmAlbum = downloader.getAlbumDetail(albumId)
    suspend fun getJmPageCount(album: JmAlbum): Int? = downloader.getPageCount(album)
    suspend fun getJmComments(albumId: String, page: Int): List<JmComment> = downloader.getComments(albumId, page)
    fun retryDownload() {
        val state = _uiState.value
        if (state.isBatch) startBatchDownload(state.failedAlbums) else startNativeDownload(lastAlbumId)
    }
    fun resetState() {
        if (!_uiState.value.isDownloading) _uiState.value = DownloadUiState()
    }

    override fun onCleared() {
        downloader.cancel()
        downloader.close()
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = JmDownloadViewModel(repository) as T
    }
}
