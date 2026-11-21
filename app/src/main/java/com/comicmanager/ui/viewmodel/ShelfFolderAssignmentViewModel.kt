package com.comicmanager.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.data.ComicRepository
import com.comicmanager.data.FolderNameError
import com.comicmanager.data.FolderNameException
import com.comicmanager.data.FolderNotFoundException
import com.comicmanager.data.NoComicsForFolderException
import com.comicmanager.model.ComicFolderSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShelfFolderIssue { NAME_REQUIRED, NAME_EXISTS, FOLDER_MISSING, NO_COMICS, LOAD_FAILED, SAVE_FAILED }

data class ShelfFolderNotice(
    val folderName: String,
    val created: Boolean,
    val addedCount: Int = 0
)

data class ShelfFolderAssignmentState(
    val isOpen: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val comicIds: List<Long> = emptyList(),
    val folders: List<ComicFolderSummary> = emptyList(),
    val folderId: Long? = null,
    val creating: Boolean = false,
    val newName: String = "",
    val issue: ShelfFolderIssue? = null
)

/** Owns only the add dialog. The bookshelf's selected IDs are never changed by a failed save. */
class ShelfFolderAssignmentViewModel(private val repository: ComicRepository) : ViewModel() {
    private val _state = MutableStateFlow(ShelfFolderAssignmentState())
    val state = _state.asStateFlow()
    private val noticeChannel = Channel<ShelfFolderNotice>(Channel.CONFLATED)
    val notices = noticeChannel.receiveAsFlow()
    private var foldersJob: Job? = null

    fun open(comicIds: Collection<Long>) {
        if (_state.value.isSaving) return
        val ids = comicIds.filter { it > 0 }.distinct()
        _state.value = ShelfFolderAssignmentState(
            isOpen = true,
            comicIds = ids,
            issue = if (ids.isEmpty()) ShelfFolderIssue.NO_COMICS else null
        )
        reloadFolders()
    }

    fun reloadFolders() {
        if (!_state.value.isOpen || _state.value.isSaving) return
        foldersJob?.cancel()
        _state.update { it.copy(isLoading = true, issue = if (it.comicIds.isEmpty()) ShelfFolderIssue.NO_COMICS else null) }
        foldersJob = viewModelScope.launch {
            var first = true
            try {
                repository.folders.collect { folders ->
                    _state.update { current ->
                        val chooseInitial = first && current.folderId == null && !current.creating && current.newName.isEmpty()
                        current.copy(
                            folders = folders,
                            isLoading = false,
                            folderId = if (chooseInitial) folders.firstOrNull()?.id else current.folderId,
                            creating = if (chooseInitial) folders.isEmpty() else current.creating
                        )
                    }
                    first = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, issue = ShelfFolderIssue.LOAD_FAILED) }
            }
        }
    }

    fun chooseFolder(folderId: Long) {
        if (_state.value.isSaving) return
        _state.update { it.copy(folderId = folderId, creating = false, issue = null) }
    }

    fun chooseNewFolder() {
        if (_state.value.isSaving) return
        _state.update { it.copy(creating = true, issue = null) }
    }

    fun updateName(name: String) {
        if (_state.value.isSaving) return
        _state.update { it.copy(newName = name, issue = null) }
    }

    fun dismiss() {
        if (_state.value.isSaving) return
        foldersJob?.cancel()
        _state.update { it.copy(isOpen = false) }
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.isOpen || snapshot.isSaving || snapshot.isLoading) return
        val name = snapshot.newName.trim()
        val folder = snapshot.folders.firstOrNull { it.id == snapshot.folderId }
        val issue = when {
            snapshot.comicIds.isEmpty() -> ShelfFolderIssue.NO_COMICS
            snapshot.creating && name.isBlank() -> ShelfFolderIssue.NAME_REQUIRED
            !snapshot.creating && folder == null -> ShelfFolderIssue.FOLDER_MISSING
            else -> null
        }
        if (issue != null) {
            _state.update { it.copy(issue = issue) }
            return
        }
        _state.update { it.copy(isSaving = true, issue = null) }
        viewModelScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    if (snapshot.creating) {
                        repository.createFolder(name, snapshot.comicIds)
                        0
                    } else repository.addComicsToFolder(requireNotNull(folder).id, snapshot.comicIds)
                }
                foldersJob?.cancel()
                val notice = ShelfFolderNotice(
                    folderName = if (snapshot.creating) name else requireNotNull(folder).name,
                    created = snapshot.creating,
                    addedCount = added
                )
                _state.update { it.copy(isOpen = false, isSaving = false) }
                noticeChannel.trySend(notice)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                val failure = when (error) {
                    is FolderNameException -> if (error.reason == FolderNameError.BLANK) ShelfFolderIssue.NAME_REQUIRED
                        else ShelfFolderIssue.NAME_EXISTS
                    is FolderNotFoundException -> ShelfFolderIssue.FOLDER_MISSING
                    is NoComicsForFolderException -> ShelfFolderIssue.NO_COMICS
                    else -> ShelfFolderIssue.SAVE_FAILED
                }
                _state.update { it.copy(isSaving = false, issue = failure) }
            }
        }
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ShelfFolderAssignmentViewModel(repository) as T
    }
}
