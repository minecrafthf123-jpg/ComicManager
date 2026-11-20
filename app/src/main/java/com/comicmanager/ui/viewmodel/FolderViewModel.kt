package com.comicmanager.ui.viewmodel

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.data.FolderNameError
import com.comicmanager.data.FolderNameException
import com.comicmanager.data.FolderNotFoundException
import com.comicmanager.model.ComicFolderSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderActionState(
    val isSaving: Boolean = false,
    @StringRes val error: Int? = null
)

class FolderViewModel(private val repository: ComicRepository) : ViewModel() {

    val folders: StateFlow<List<ComicFolderSummary>?> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _action = MutableStateFlow(FolderActionState())
    val action: StateFlow<FolderActionState> = _action.asStateFlow()

    fun comicsInFolder(folderId: Long) = repository.comicsInFolder(folderId)

    fun createFolder(name: String, onSuccess: () -> Unit) = edit(onSuccess) {
        repository.createFolder(name)
    }

    fun renameFolder(folderId: Long, name: String, onSuccess: () -> Unit) = edit(onSuccess) {
        repository.renameFolder(folderId, name)
    }

    fun deleteFolder(folderId: Long, onSuccess: () -> Unit) = edit(onSuccess) {
        repository.deleteFolder(folderId)
    }

    fun removeComic(folderId: Long, comicId: Long, onSuccess: () -> Unit) = edit(onSuccess) {
        repository.removeComicsFromFolder(folderId, listOf(comicId))
    }

    fun clearActionError() {
        if (!_action.value.isSaving) _action.value = FolderActionState()
    }

    private fun edit(onSuccess: () -> Unit, change: suspend () -> Unit) {
        if (_action.value.isSaving) return
        _action.value = FolderActionState(isSaving = true)
        viewModelScope.launch {
            val error = try {
                change()
                null
            } catch (cancelled: CancellationException) {
                _action.value = FolderActionState()
                throw cancelled
            } catch (invalidName: FolderNameException) {
                when (invalidName.reason) {
                    FolderNameError.BLANK -> R.string.folder_v22_name_required
                    FolderNameError.DUPLICATE -> R.string.folder_v22_name_exists
                }
            } catch (_: FolderNotFoundException) {
                R.string.folder_v22_missing
            } catch (_: Exception) {
                R.string.folder_v22_operation_failed
            }
            _action.value = FolderActionState(error = error)
            if (error == null) onSuccess()
        }
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderViewModel(repository) as T
    }
}
