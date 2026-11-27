package com.comicmanager.ui.viewmodel

import android.content.Context
import android.content.ClipData
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
import com.comicmanager.data.LocalImportResult
import com.comicmanager.model.Comic
import com.comicmanager.util.JmIdParser
import com.comicmanager.util.ZipUtils
import com.comicmanager.util.ComicArchiveSource
import com.comicmanager.util.prepareComicShareArchive
import com.comicmanager.util.writeComicArchive
import com.comicmanager.util.reclaimExpiredComicShareSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ImportPhase { IDLE, PREPARING, CHECKING, EXTRACTING, SAVING, COMPLETED, CANCELLED }

data class ImportState(
    val batchId: Long = 0,
    val isImporting: Boolean = false,
    val phase: ImportPhase = ImportPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val extractedImages: Int = 0,
    val extractedBytes: Long = 0,
    val successCount: Int = 0,
    val repairedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val importMessage: String? = null
) {
    fun acknowledgeMessage(batchId: Long): ImportState =
        if (this.batchId == batchId && !isImporting) copy(importMessage = null) else this
}

data class BulkProgress(
    val isRunning: Boolean = false,
    @StringRes val labelRes: Int? = null,
    val current: Int = 0,
    val total: Int = 0
)

data class ComicShareState(
    val requestId: Long = 0,
    val isPreparing: Boolean = false,
    val pendingIntent: Intent? = null,
    val message: String? = null
)

class MainViewModel(
    private val repository: ComicRepository,
    private val sortPreferences: ShelfSortPreferences
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    // Restore before exposing the shelf flow, so the first order is already correct.
    private val _sortMode = MutableStateFlow(sortPreferences.read())
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()
    private val _customOrder = MutableStateFlow(sortPreferences.readCustomOrder())
    private val _recentJmDownload = MutableStateFlow<Long?>(null)
    private var previouslySeenIds: Set<Long>? = null

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    val allComics: StateFlow<List<Comic>> = repository.allComics
        .onEach(::updateShelfOrdering)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val displayComics: StateFlow<List<Comic>> = combine(
        allComics,
        _searchQuery,
        _sortMode,
        _customOrder,
        _recentJmDownload
    ) { comics, query, sort, customOrder, recentJmDownload ->
        val filtered = if (query.isBlank()) comics
        else comics.filter { it.name.contains(query, ignoreCase = true) }

        sortShelfComics(filtered, sort, customOrder.orEmpty(), recentJmDownload)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importState = MutableStateFlow(ImportState())
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    private val importInFlight = AtomicBoolean(false)
    private val importBatchSequence = AtomicLong(0)

    private val _bulkProgress = MutableStateFlow(BulkProgress())
    val bulkProgress: StateFlow<BulkProgress> = _bulkProgress.asStateFlow()
    private val _shareState = MutableStateFlow(ComicShareState())
    val shareState: StateFlow<ComicShareState> = _shareState.asStateFlow()
    private val shareSequence = AtomicLong(0)
    private val shareLock = Any()
    private var shareJob: Job? = null

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
        if (mode == SortMode.CUSTOM && _customOrder.value == null && previouslySeenIds != null) {
            val currentOrder = sortShelfComics(allComics.value, _sortMode.value, recentJmId = _recentJmDownload.value)
            saveCustomOrder(currentOrder.map { it.id })
        }
        _recentJmDownload.value = null
        sortPreferences.write(mode)
        _sortMode.value = mode
    }

    fun moveComicInCustomOrder(sourceId: Long, destinationId: Long): Boolean {
        if (_sortMode.value != SortMode.CUSTOM || _isSearchActive.value || _selectMode.value) return false
        val currentOrder = _customOrder.value ?: return false
        val moved = moveShelfComic(currentOrder, sourceId, destinationId) ?: return false
        saveCustomOrder(moved)
        return true
    }

    private fun updateShelfOrdering(comics: List<Comic>) {
        val liveIds = comics.mapTo(hashSetOf()) { it.id }
        if (previouslySeenIds == liveIds) return
        _recentJmDownload.value = updateRecentJmDownload(
            comics, previouslySeenIds, _recentJmDownload.value, liveIds
        )
        previouslySeenIds = liveIds
        val savedOrder = _customOrder.value
        if (savedOrder != null) {
            saveCustomOrder(reconcileCustomShelfOrder(comics, savedOrder))
        } else if (_sortMode.value == SortMode.CUSTOM) {
            saveCustomOrder(sortShelfComics(comics, SortMode.TIME_DESC).map { it.id })
        }
    }

    private fun saveCustomOrder(ids: List<Long>) {
        if (_customOrder.value == ids) return
        sortPreferences.writeCustomOrder(ids)
        _customOrder.value = ids
    }

    fun importComics(context: Context, uris: List<Uri>) {
        if (uris.isEmpty() || !importInFlight.compareAndSet(false, true)) return
        val batchId = importBatchSequence.incrementAndGet()
        val batchUris = uris.toList()
        val importContext = context.applicationContext.createConfigurationContext(context.resources.configuration)
        _importState.value = ImportState(
            batchId = batchId,
            isImporting = true,
            phase = ImportPhase.PREPARING,
            total = batchUris.size
        )
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            var repairedCount = 0
            var skippedCount = 0
            var failCount = 0
            val batchJmIds = mutableSetOf<String>()
            val coroutineContext = currentCoroutineContext()
            try {
                ZipUtils.withImportSession {
                    try {
                        val referencedComics = repository.allComics.first()
                        ZipUtils.reclaimStaleImports(
                            importContext.filesDir,
                            referencedComics.flatMap { listOf(it.directoryPath, it.coverPath) }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // If references cannot be read, preserve all files and let
                        // individual imports report any database failure normally.
                    }
                    for ((index, uri) in batchUris.withIndex()) {
                        coroutineContext.ensureActive()
                        _importState.update {
                            it.copy(phase = ImportPhase.CHECKING, currentName = "", extractedImages = 0, extractedBytes = 0)
                        }
                        var uncommittedDirectory: File? = null
                        var uncommittedCover: File? = null
                        try {
                            val zipName = ZipUtils.getZipName(importContext, uri)
                            _importState.update { it.copy(currentName = zipName) }
                            val fileJmId = JmIdParser.extract(zipName)
                            if (fileJmId != null && fileJmId in batchJmIds) {
                                skippedCount++
                                continue
                            }
                            val existing = repository.checkLocalImport(zipName, fileJmId)
                            if (existing != null) {
                                if (existing == LocalImportResult.LINKED) repairedCount++ else skippedCount++
                                fileJmId?.let(batchJmIds::add)
                                continue
                            }

                            _importState.update { it.copy(phase = ImportPhase.EXTRACTING) }
                            val result = ZipUtils.extractZip(
                                importContext,
                                uri,
                                onProgress = { progress ->
                                    _importState.update {
                                        it.copy(extractedImages = progress.imageCount, extractedBytes = progress.bytes)
                                    }
                                },
                                checkCancelled = { coroutineContext.ensureActive() }
                            )
                            if (!result.success || result.imageFiles.isEmpty()) {
                                failCount++
                                continue
                            }
                            uncommittedDirectory = result.imageFiles.first().parentFile
                            coroutineContext.ensureActive()
                            _importState.update { it.copy(phase = ImportPhase.SAVING) }
                            val coverFile = saveCoverImage(importContext, result.imageFiles.first())
                            uncommittedCover = coverFile
                            val comic = Comic(
                                name = zipName,
                                coverPath = coverFile.absolutePath,
                                directoryPath = result.imageFiles.first().parentFile!!.absolutePath,
                                pageCount = result.imageFiles.size,
                                jmId = fileJmId ?: ""
                            )
                            coroutineContext.ensureActive()
                            // Finish the short Room ownership transfer before cancellation can
                            // clean up the extracted files referenced by the inserted row.
                            withContext(NonCancellable) {
                                when (repository.importLocalComic(comic)) {
                                    LocalImportResult.IMPORTED -> {
                                        uncommittedDirectory = null
                                        uncommittedCover = null
                                        successCount++
                                    }
                                    LocalImportResult.LINKED -> repairedCount++
                                    LocalImportResult.EXISTING -> skippedCount++
                                }
                            }
                            fileJmId?.let(batchJmIds::add)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failCount++
                        } finally {
                            uncommittedDirectory?.deleteRecursively()
                            uncommittedCover?.delete()
                            if (coroutineContext.isActive) {
                                _importState.update {
                                    it.copy(
                                        completed = index + 1,
                                        successCount = successCount,
                                        repairedCount = repairedCount,
                                        skippedCount = skippedCount,
                                        failedCount = failCount
                                    )
                                }
                            }
                        }
                    }
                    _importState.update {
                        it.copy(
                            isImporting = false,
                            phase = ImportPhase.COMPLETED,
                            currentName = "",
                            importMessage = importSummary(importContext, batchUris.size, successCount, repairedCount, skippedCount, failCount)
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _importState.update {
                    it.copy(isImporting = false, phase = ImportPhase.CANCELLED,
                        importMessage = importContext.getString(R.string.shelf_v21_import_stopped, it.completed, it.total))
                }
                throw cancelled
            } finally {
                importInFlight.set(false)
            }
        }
    }

    private fun importSummary(context: Context, total: Int, imported: Int, repaired: Int, skipped: Int, failed: Int): String {
        val title = context.getString(
            when {
                failed == total -> R.string.shelf_v21_import_failed
                failed > 0 -> R.string.shelf_v21_import_finished_with_errors
                else -> R.string.shelf_v21_import_finished
            }
        )
        val counts = buildList {
            if (imported > 0) add(context.getString(R.string.import_summary_imported, imported))
            if (repaired > 0) add(context.getString(R.string.import_summary_repaired_ids, repaired))
            if (skipped > 0) add(context.getString(R.string.import_summary_skipped, skipped))
            if (failed > 0) add(context.getString(R.string.import_summary_failed, failed))
        }.joinToString(context.getString(R.string.list_separator))
        return "$title ($total / $total)\n$counts"
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
        val ids = _selectedIds.value.toSet()
        if (ids.isEmpty()) return
        if (_bulkProgress.value.isRunning || _shareState.value.pendingIntent != null) {
            _shareState.update { it.copy(message = context.getString(R.string.shelf_share_v21_busy)) }
            return
        }
        val shareContext = context.applicationContext.createConfigurationContext(context.resources.configuration)
        val requestId = shareSequence.incrementAndGet()
        _shareState.value = ComicShareState(requestId = requestId, isPreparing = true)
        _bulkProgress.value = BulkProgress(isRunning = true, labelRes = R.string.shelf_share_v21_preparing, total = ids.size)
        shareJob = viewModelScope.launch(Dispatchers.IO) {
            val sessionDirectory = File(shareContext.cacheDir, "share/${UUID.randomUUID()}")
            var published = false
            try {
                reclaimExpiredComicShareSessions(shareContext.cacheDir)
                val jobContext = currentCoroutineContext()
                val selected = repository.allComics.first().filter { it.id in ids }
                check(selected.size == ids.size) { shareContext.getString(R.string.shelf_share_v21_missing_selection) }
                val file = prepareComicShareArchive(
                    sources = selected.map { ComicArchiveSource(exportZipName(it), File(it.directoryPath)) },
                    sessionDirectory = sessionDirectory,
                    bundleName = shareContext.getString(R.string.shelf_share_v21_bundle_filename, selected.size),
                    checkCancelled = { jobContext.ensureActive() },
                    onComicPacked = { count -> _bulkProgress.update {
                        if (it.labelRes == R.string.shelf_share_v21_preparing) it.copy(current = count) else it
                    } }
                )
                val uri = FileProvider.getUriForFile(shareContext, "${shareContext.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, shareContext.getString(R.string.share_comics)).apply {
                    clipData = send.clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                synchronized(shareLock) {
                    jobContext.ensureActive()
                    _shareState.value = ComicShareState(requestId = requestId, pendingIntent = chooser)
                    published = true
                }
            } catch (_: CancellationException) {
                _shareState.update { if (it.requestId == requestId) it.copy(
                    isPreparing = false, message = shareContext.getString(R.string.shelf_share_v21_cancelled)
                ) else it }
            } catch (error: Exception) {
                _shareState.update { if (it.requestId == requestId) it.copy(
                    isPreparing = false,
                    message = shareContext.getString(R.string.shelf_share_v21_failed, error.message.orEmpty())
                ) else it }
            } finally {
                // Never remove an earlier session: recipient apps may still be reading its URI.
                if (!published) sessionDirectory.deleteRecursively()
                _shareState.update { if (it.requestId == requestId) it.copy(isPreparing = false) else it }
                if (_shareState.value.requestId == requestId && _bulkProgress.value.labelRes == R.string.shelf_share_v21_preparing) {
                    _bulkProgress.value = BulkProgress()
                }
            }
        }
    }

    fun cancelSharePreparation() = synchronized(shareLock) {
        if (_shareState.value.isPreparing) shareJob?.cancel()
    }

    fun onShareLaunched(requestId: Long) {
        _shareState.update { if (it.requestId == requestId) it.copy(pendingIntent = null) else it }
        _selectMode.value = false
        _selectedIds.value = emptySet()
    }

    fun onShareLaunchFailed(context: Context, requestId: Long, error: Exception) {
        _shareState.update { if (it.requestId == requestId) it.copy(
            pendingIntent = null,
            message = context.getString(R.string.shelf_share_v21_launch_failed, error.message.orEmpty())
        ) else it }
    }

    fun onShareChooserReturned(context: Context, requestId: Long) {
        // ACTION_SEND has no delivery result. RESULT_CANCELED is also returned by apps
        // which sent the file successfully, so do not misreport cancellation or success.
        _shareState.update { if (it.requestId == requestId) it.copy(
            message = context.getString(R.string.shelf_share_v21_returned)
        ) else it }
    }

    fun clearShareMessage(requestId: Long, message: String) {
        _shareState.update { if (it.requestId == requestId && it.message == message) it.copy(message = null) else it }
    }

    private fun createComicZip(comic: Comic, zipFile: File) {
        writeComicArchive(File(comic.directoryPath), zipFile)
    }

    private fun exportZipName(comic: Comic): String {
        val safeName = sanitizeComicName(comic.name)
            .ifBlank { "comic_${comic.id}" }
            .take(60)
        val jmId = JmIdParser.normalize(comic.jmId)
        return if (jmId != null) "[JM$jmId] $safeName.zip" else "$safeName.zip"
    }

    fun clearImportMessage(batchId: Long) {
        _importState.update { it.acknowledgeMessage(batchId) }
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

    private fun saveCoverImage(context: Context, imageFile: File): File {
        val extension = imageFile.extension.ifEmpty { "jpg" }
        val coverFile = ZipUtils.createImportCoverFile(context.filesDir, extension)
        try {
            imageFile.inputStream().use { input ->
                FileOutputStream(coverFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (error: Exception) {
            coverFile.delete()
            throw error
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

    class Factory(private val repository: ComicRepository, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val preferences = context.applicationContext.getSharedPreferences(ShelfSortPreferences.FILE_NAME, Context.MODE_PRIVATE)
            return MainViewModel(repository, ShelfSortPreferences(preferences)) as T
        }
    }
}
