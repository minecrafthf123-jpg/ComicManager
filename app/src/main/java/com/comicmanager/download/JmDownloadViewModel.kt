package com.comicmanager.download

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.jm.JmNativeDownloader
import com.comicmanager.download.jm.JmSearchItem
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val statusMessage: String = "",
    val canRetry: Boolean = false,
    val nativePhase: String = "",
    val nativeCurrent: Int = 0,
    val nativeTotal: Int = 0,
    val nativeMsg: String = "",
    val canCancel: Boolean = true,
    val isCancelling: Boolean = false
)

class JmDownloadViewModel(repository: ComicRepository) : ViewModel() {
    private val downloader = JmNativeDownloader(repository)
    private var appContext: Context? = null
    private var nativeJob: Job? = null
    private var lastAlbumId = ""
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
                    canCancel = progress.canCancel && !it.isCancelling
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
        _uiState.value = DownloadUiState(isDownloading = true)
        nativeJob = viewModelScope.launch {
            try {
                val result = downloader.downloadAlbum(context, lastAlbumId)
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

    fun cancelNativeDownload() {
        if (_uiState.value.isDownloading && downloader.cancel()) {
            _uiState.update { it.copy(isCancelling = true, canCancel = false) }
            nativeJob?.cancel()
        }
    }

    suspend fun searchJm(query: String, page: Int): Pair<Int, List<JmSearchItem>> = downloader.search(query, page)
    suspend fun enrichJmTitles(items: List<JmSearchItem>): List<JmSearchItem> = downloader.enrichTitles(items)
    suspend fun getJmAlbumDetail(albumId: String): JmAlbum = downloader.getAlbumDetail(albumId)
    suspend fun getJmPageCount(album: JmAlbum): Int? = downloader.getPageCount(album)
    suspend fun getJmComments(albumId: String, page: Int): List<JmComment> = downloader.getComments(albumId, page)
    fun retryDownload() = startNativeDownload(lastAlbumId)
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
