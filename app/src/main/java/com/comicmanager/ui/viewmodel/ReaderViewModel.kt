package com.comicmanager.ui.viewmodel

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ReaderPage(val path: String, val aspectRatio: Float)

data class ReaderUiState(
    val comicName: String = "",
    val pages: List<ReaderPage> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val isPagerMode: Boolean = false,
    val isLoading: Boolean = true,
    val isAutoPlaying: Boolean = false,
    val scrollSpeed: Int = 3,
    val pageIntervalSeconds: Int = 5
)

class ReaderViewModel(private val repository: ComicRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    private var comicId: Long = 0
    private var loadJob: Job? = null
    private val progressMutex = Mutex()

    fun loadComic(comicId: Long) {
        loadJob?.cancel()
        this.comicId = comicId
        _uiState.update { it.copy(isLoading = true, isAutoPlaying = false) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val comic = repository.getComicById(comicId)
            val pages = comic?.let(::loadPages).orEmpty()
            currentCoroutineContext().ensureActive()
            _uiState.update {
                it.copy(
                    comicName = comic?.name.orEmpty(),
                    pages = pages,
                    currentPage = (comic?.currentPage ?: 0).coerceIn(0, maxOf(0, pages.size - 1)),
                    totalPages = pages.size,
                    isLoading = false
                )
            }
        }
    }

    fun updateCurrentPage(page: Int) {
        if (page !in _uiState.value.pages.indices || page == _uiState.value.currentPage) return
        _uiState.update { it.copy(currentPage = page) }
        saveProgress()
    }

    fun toggleMode() {
        _uiState.update { it.copy(isPagerMode = !it.isPagerMode, isAutoPlaying = false) }
    }

    fun toggleAutoPlay() {
        _uiState.update {
            it.copy(isAutoPlaying = !it.isAutoPlaying && !it.isLoading && it.pages.isNotEmpty())
        }
    }

    fun pauseAutoPlay() {
        _uiState.update { it.copy(isAutoPlaying = false) }
    }

    fun setScrollSpeed(speed: Int) {
        _uiState.update { it.copy(scrollSpeed = speed.coerceIn(1, 5)) }
    }

    fun setPageInterval(seconds: Int) {
        _uiState.update { it.copy(pageIntervalSeconds = seconds.coerceIn(2, 20)) }
    }

    fun saveProgress() {
        val id = comicId
        val snapshot = _uiState.value
        if (id == 0L || snapshot.isLoading || snapshot.pages.isEmpty()) return
        // Enter the finite Room write before navigation can clear this ViewModel.
        // NonCancellable only covers persistence, never playback or loading.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                progressMutex.withLock {
                    val page = if (id == comicId) _uiState.value.currentPage else snapshot.currentPage
                    repository.updateProgress(id, page)
                }
            }
        }
    }

    private fun loadPages(comic: Comic): List<ReaderPage> {
        val dir = File(comic.directoryPath)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif") }
            ?.sortedBy { it.name }
            ?.map { file ->
                // Decode bounds on IO so placeholders and decoded images occupy
                // the same height throughout scrolling and zooming.
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val ratio = if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight
                } else 0.7f
                ReaderPage(file.absolutePath, ratio)
            }
            ?: emptyList()
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(repository) as T
    }
}
