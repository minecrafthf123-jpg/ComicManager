package com.comicmanager.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val comicName: String = "",
    val pages: List<String> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val isPagerMode: Boolean = true,
    val isLoading: Boolean = true
)

class ReaderViewModel(private val repository: ComicRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var comicId: Long = 0

    fun loadComic(comicId: Long) {
        this.comicId = comicId
        viewModelScope.launch(Dispatchers.IO) {
            val comic = repository.getComicById(comicId)
            if (comic != null) {
                val pages = loadPages(comic)
                _uiState.value = ReaderUiState(
                    comicName = comic.name,
                    pages = pages,
                    currentPage = comic.currentPage.coerceIn(0, maxOf(0, comic.pageCount - 1)),
                    totalPages = pages.size,
                    isPagerMode = _uiState.value.isPagerMode,
                    isLoading = false
                )
            }
        }
    }

    fun updateCurrentPage(page: Int) {
        val current = _uiState.value
        if (page != current.currentPage && page in 0 until current.totalPages) {
            _uiState.value = current.copy(currentPage = page)
        }
    }

    fun toggleMode() {
        _uiState.value = _uiState.value.copy(isPagerMode = !_uiState.value.isPagerMode)
    }

    fun saveProgress() {
        val state = _uiState.value
        if (comicId != 0L && state.currentPage >= 0) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateProgress(comicId, state.currentPage)
            }
        }
    }

    private fun loadPages(comic: Comic): List<String> {
        val dir = File(comic.directoryPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { file ->
                val ext = file.extension.lowercase()
                ext in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
            }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReaderViewModel(repository) as T
        }
    }
}
