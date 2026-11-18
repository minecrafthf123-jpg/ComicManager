package com.comicmanager.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import com.comicmanager.util.JmIdParser
import com.comicmanager.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class ImportState(
    val isImporting: Boolean = false,
    val importMessage: String? = null
)

data class BulkProgress(
    val isRunning: Boolean = false,
    @StringRes val labelRes: Int? = null,
    val current: Int = 0,
    val total: Int = 0
)

enum class SortMode(@StringRes val labelRes: Int) {
    TIME_DESC(R.string.sort_recent),
    TIME_ASC(R.string.sort_oldest),
    NAME_NUM_DESC(R.string.sort_number_desc),
    NAME_NUM_ASC(R.string.sort_number_asc)
}

class MainViewModel(private val repository: ComicRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.TIME_DESC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val allComics: StateFlow<List<Comic>> = repository.allComics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayComics: StateFlow<List<Comic>> = combine(
        allComics,
        _searchQuery,
        _sortMode
    ) { comics, query, sort ->
        val filtered = if (query.isBlank()) comics
        else comics.filter { it.name.contains(query, ignoreCase = true) }

        when (sort) {
            SortMode.TIME_DESC -> filtered.sortedByDescending { it.createdAt }
            SortMode.TIME_ASC -> filtered.sortedBy { it.createdAt }
            SortMode.NAME_NUM_DESC -> filtered.sortedByDescending { parseNameNumber(it.name) }
            SortMode.NAME_NUM_ASC -> filtered.sortedBy { parseNameNumber(it.name) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _bulkProgress = MutableStateFlow(BulkProgress())
    val bulkProgress: StateFlow<BulkProgress> = _bulkProgress.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
        }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun importComics(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _importState.value = ImportState(isImporting = true)
            var successCount = 0
            var repairedCount = 0
            var skippedCount = 0
            var failCount = 0
            val batchJmIds = mutableSetOf<String>()

            for (uri in uris) {
                try {
                    val zipName = ZipUtils.getZipName(context, uri)
                    val fileJmId = JmIdParser.extract(zipName)
                    if (fileJmId != null && !batchJmIds.add(fileJmId)) {
                        skippedCount++
                        continue
                    }

                    val existingComics = repository.allComics.first()
                    val sameName = existingComics.firstOrNull { it.name == zipName }
                    val claimedBy = fileJmId?.let { id ->
                        existingComics.firstOrNull { JmIdParser.normalize(it.jmId) == id }
                    }

                    if (sameName != null) {
                        if (
                            fileJmId != null &&
                            sameName.jmId.isBlank() &&
                            claimedBy == null
                        ) {
                            repository.updateJmId(sameName.id, fileJmId)
                            repairedCount++
                        } else {
                            skippedCount++
                        }
                        continue
                    }

                    if (claimedBy != null) {
                        skippedCount++
                        continue
                    }

                    val result = ZipUtils.extractZip(context, uri)
                    if (result.success && result.imageFiles.isNotEmpty()) {
                        val coverFile = saveCoverImage(context, result.imageFiles.first(), zipName)
                        val comic = Comic(
                            name = zipName,
                            coverPath = coverFile.absolutePath,
                            directoryPath = result.imageFiles.first().parentFile!!.absolutePath,
                            pageCount = result.imageFiles.size,
                            jmId = fileJmId ?: ""
                        )
                        repository.insertComic(comic)
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    failCount++
                }
            }

            _importState.value = ImportState(
                isImporting = false,
                importMessage = buildList {
                    if (successCount > 0) add(context.getString(R.string.import_summary_imported, successCount))
                    if (repairedCount > 0) add(context.getString(R.string.import_summary_repaired_ids, repairedCount))
                    if (skippedCount > 0) add(context.getString(R.string.import_summary_skipped, skippedCount))
                    if (failCount > 0) add(context.getString(R.string.import_summary_failed, failCount))
                }.joinToString(context.getString(R.string.list_separator))
                    .ifBlank { context.getString(R.string.no_importable_comics) }
            )
        }
    }

    fun deleteComic(comic: Comic) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteComic(comic.id)
            File(comic.directoryPath).deleteRecursively()
            File(comic.coverPath).delete()
        }
    }

    fun deleteDuplicates(): Int {
        val all = displayComics.value
        val grouped = all.groupBy { it.name }
        var deleted = 0
        for ((_, list) in grouped) {
            if (list.size > 1) {
                val keep = list.maxByOrNull { it.lastReadAt } ?: list.first()
                for (comic in list) {
                    if (comic.id != keep.id) {
                        deleteComic(comic)
                        deleted++
                    }
                }
            }
        }
        return deleted
    }

    fun renameNumericComics(context: Context, onResult: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.allComics.first()
            val repairedPaths = repairLegacyDirectoryPaths(all)
            val candidates = all.mapNotNull { comic ->
                JmIdParser.extract(comic.name)?.let { albumId -> comic to albumId }
            }
            if (candidates.isEmpty()) {
                onResult(
                    if (repairedPaths > 0) context.getString(R.string.paths_repaired, repairedPaths)
                    else context.getString(R.string.nothing_to_process)
                )
                return@launch
            }
            val total = candidates.size
            _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_rename, total = total)
            var renamed = 0
            var failed = 0
            var conflicts = 0
            var processed = 0

            try {
                val client = com.comicmanager.util.JmClientFactory.get()
                val claimedIds = mutableMapOf<String, MutableSet<Long>>()
                all.forEach { existing ->
                    JmIdParser.normalize(existing.jmId)?.let { id ->
                        claimedIds.getOrPut(id) { mutableSetOf() }.add(existing.id)
                    }
                }

                for ((comic, albumId) in candidates) {
                    try {
                        val claimedByOther = claimedIds[albumId]
                            .orEmpty()
                            .any { ownerId -> ownerId != comic.id }

                        if (claimedByOther) {
                            conflicts++
                        } else {
                            val album = client.getAlbum(albumId)
                            val returnedId = JmIdParser.normalize(album.id)
                            val newName = sanitizeComicName(album.title ?: "")

                            if (returnedId == albumId && newName.isNotBlank()) {
                                JmIdParser.normalize(comic.jmId)?.let { oldId ->
                                    claimedIds[oldId]?.remove(comic.id)
                                }
                                repository.updateNameAndJmId(comic.id, newName, albumId)
                                claimedIds.getOrPut(albumId) { mutableSetOf() }.add(comic.id)
                                renamed++
                            } else {
                                failed++
                            }
                        }
                    } catch (_: Exception) {
                        failed++
                    }

                    processed++
                    _bulkProgress.value = BulkProgress(
                        isRunning = true,
                        labelRes = R.string.progress_rename,
                        current = processed,
                        total = total
                    )
                }
            } catch (e: Exception) {
                failed += total - processed
            }
            _bulkProgress.value = BulkProgress()
            onResult(
                buildList {
                    add(context.getString(R.string.rename_summary, renamed))
                    if (repairedPaths > 0) add(context.getString(R.string.paths_repaired_summary, repairedPaths))
                    if (conflicts > 0) add(context.getString(R.string.id_conflicts_summary, conflicts))
                    if (failed > 0) add(context.getString(R.string.query_failures_summary, failed))
                }.joinToString(context.getString(R.string.list_separator))
            )
        }
    }

    fun exportAll(context: Context, onResult: (String) -> Unit) {        viewModelScope.launch(Dispatchers.IO) {
            val all = displayComics.value
            if (all.isEmpty()) { onResult(context.getString(R.string.library_empty)); return@launch }
            _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_export, total = all.size)
            var count = 0
            val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "漫画管理器")
            exportDir.mkdirs()
            for (comic in all) {
                try { createComicZip(comic, File(exportDir, exportZipName(comic))); count++ } catch (_: Exception) {}
                _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_export, current = count, total = all.size)
            }
            _bulkProgress.value = BulkProgress()
            onResult(context.getString(R.string.export_completed_path, count))
        }
    }

    fun deleteSelected(context: Context, onResult: (String) -> Unit) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val selected = displayComics.value.filter { it.id in ids }
            _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_delete, total = selected.size)
            var count = 0
            for (comic in selected) {
                repository.deleteComic(comic.id)
                File(comic.directoryPath).deleteRecursively()
                File(comic.coverPath).delete()
                count++
                _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_delete, current = count, total = selected.size)
            }
            _bulkProgress.value = BulkProgress()
            _selectMode.value = false
            _selectedIds.value = emptySet()
            onResult(context.getString(R.string.deleted_count, count))
        }
    }

    fun toggleSelectMode() {
        _selectMode.value = !_selectMode.value
        if (!_selectMode.value) _selectedIds.value = emptySet()
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) current.remove(id) else current.add(id)
        _selectedIds.value = current
    }

    fun replaceSelection(ids: Set<Long>) {
        if (_selectMode.value) _selectedIds.value = ids
    }

    fun selectAll() {
        _selectedIds.value = displayComics.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun exportSelected(context: Context, onResult: (String) -> Unit) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val selected = displayComics.value.filter { it.id in ids }
            _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_export, total = selected.size)
            var count = 0
            val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "漫画管理器")
            exportDir.mkdirs()
            for (comic in selected) {
                try { createComicZip(comic, File(exportDir, exportZipName(comic))); count++ } catch (_: Exception) {}
                _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.progress_export, current = count, total = selected.size)
            }
            _bulkProgress.value = BulkProgress()
            _selectMode.value = false
            _selectedIds.value = emptySet()
            onResult(context.getString(R.string.export_completed, count))
        }
    }

    fun shareSelected(context: Context) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val selected = displayComics.value.filter { it.id in ids }
            val shareDir = File(context.cacheDir, "share")
            shareDir.deleteRecursively()
            shareDir.mkdirs()
            val uris = mutableListOf<Uri>()
            for (comic in selected) {
                try {
                    val zipFile = File(shareDir, exportZipName(comic))
                    createComicZip(comic, zipFile)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
                    uris.add(uri)
                } catch (_: Exception) {}
            }
            _selectMode.value = false
            _selectedIds.value = emptySet()
            if (uris.isEmpty()) return@launch
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/zip"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_comics)))
        }
    }

    private fun createComicZip(comic: Comic, zipFile: File) {
        val srcDir = File(comic.directoryPath)
        if (!srcDir.isDirectory) return
        val images = srcDir.listFiles()?.filter {
            it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        }?.sortedBy { it.name } ?: return
        if (images.isEmpty()) return
        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for (img in images) {
                zos.putNextEntry(java.util.zip.ZipEntry(img.name))
                img.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    private fun exportZipName(comic: Comic): String {
        val safeName = sanitizeComicName(comic.name)
            .ifBlank { "comic_${comic.id}" }
            .take(60)
        val jmId = JmIdParser.normalize(comic.jmId)
        return if (jmId != null) "[JM$jmId] $safeName.zip" else "$safeName.zip"
    }

    fun clearImportMessage() {
        _importState.value = ImportState()
    }

    fun bindJmId(comic: Comic, jmId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateJmId(comic.id, jmId.trim())
        }
    }

    suspend fun resolveJmIdByName(name: String): String? {        return try {
            val c = com.comicmanager.util.JmClientFactory.get()
            val sq = io.github.jukomu.jmcomic.api.model.SearchQuery.Builder()
                .text(name)
                .page(1)
                .build()
            val normalized = name.replace(Regex("[/\\\\:*?\"<>|\\s]"), "")
            c.search(sq).content.firstOrNull { meta ->
                (meta.title ?: "").replace(Regex("[/\\\\:*?\"<>|\\s]"), "").equals(
                    normalized, ignoreCase = true
                )
            }?.id
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCoverImage(context: Context, imageFile: File, comicName: String): File {
        val coversDir = File(context.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        val extension = imageFile.extension.ifEmpty { "jpg" }
        val coverFile = File(coversDir, "${comicName}_cover.$extension")
        if (!coverFile.exists()) {
            imageFile.inputStream().use { input ->
                FileOutputStream(coverFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return coverFile
    }

    private fun sanitizeComicName(name: String): String = name
        .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()

    private suspend fun repairLegacyDirectoryPaths(comics: List<Comic>): Int {
        var repaired = 0
        for (comic in comics) {
            val storedDir = File(comic.directoryPath)
            if (storedDir.isDirectory) continue

            val parentDir = storedDir.parentFile ?: continue
            val candidates = listOf(
                File(parentDir, comic.name),
                File(parentDir, sanitizeComicName(comic.name))
            ).distinctBy { it.absolutePath }

            val replacement = candidates.firstOrNull { candidate ->
                if (!candidate.isDirectory) return@firstOrNull false
                val imageCount = candidate.listFiles()?.count { file ->
                    file.isFile && file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
                } ?: 0
                imageCount == comic.pageCount
            } ?: continue

            repository.updateDirectoryPath(comic.id, replacement.absolutePath)
            repaired++
        }
        return repaired
    }

    private fun parseNameNumber(name: String): Long {
        return name.toLongOrNull() ?: name.filter { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}
