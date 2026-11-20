package com.comicmanager.data

import com.comicmanager.model.Comic
import com.comicmanager.util.JmIdParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalImportResult { IMPORTED, EXISTING, LINKED }

class ComicRepository(
    private val comicDao: ComicDao,
    private val folderDao: ComicFolderDao
) {

    val allComics: Flow<List<Comic>> = comicDao.getAllComics()
    val folders = folderDao.observeFolders()
    private val importMutex = Mutex()

    suspend fun getComicById(id: Long): Comic? = comicDao.getComicById(id)

    fun comicsInFolder(folderId: Long) = folderDao.observeComicsInFolder(folderId)

    suspend fun createFolder(name: String, comicIds: Collection<Long> = emptyList()): Long =
        folderDao.createFolder(name, comicIds)

    suspend fun renameFolder(folderId: Long, name: String) = folderDao.renameFolder(folderId, name)

    suspend fun deleteFolder(folderId: Long) = folderDao.deleteFolder(folderId)

    suspend fun addComicsToFolder(folderId: Long, comicIds: Collection<Long>): Int =
        folderDao.addComics(folderId, comicIds)

    suspend fun removeComicsFromFolder(folderId: Long, comicIds: Collection<Long>) =
        folderDao.removeComics(folderId, comicIds)

    suspend fun checkLocalImport(name: String, jmId: String?): LocalImportResult? =
        importMutex.withLock { findLocalImportConflict(name, jmId) }

    suspend fun importLocalComic(comic: Comic): LocalImportResult = importMutex.withLock {
        findLocalImportConflict(comic.name, JmIdParser.normalize(comic.jmId)) ?: run {
            comicDao.insertComic(comic.copy(isJmDownload = false))
            LocalImportResult.IMPORTED
        }
    }

    suspend fun importDownloadedComic(comic: Comic): Comic = importMutex.withLock {
        val jmId = JmIdParser.normalize(comic.jmId)
        val existing = jmId?.let { id -> allComics.first().firstOrNull { JmIdParser.normalize(it.jmId) == id } }
        existing ?: comic.copy(isJmDownload = true).let { downloaded ->
            downloaded.copy(id = comicDao.insertComic(downloaded))
        }
    }

    private suspend fun findLocalImportConflict(name: String, jmId: String?): LocalImportResult? {
        val comics = allComics.first()
        val sameName = comics.firstOrNull { it.name == name }
        val idTaken = jmId != null && comics.any { JmIdParser.normalize(it.jmId) == jmId }
        if (sameName == null && !idTaken) return null
        if (sameName != null && jmId != null && sameName.jmId.isBlank() && !idTaken) {
            comicDao.updateJmId(sameName.id, jmId)
            return LocalImportResult.LINKED
        }
        return LocalImportResult.EXISTING
    }

    suspend fun updateProgress(id: Long, page: Int) = comicDao.updateProgress(id, page)

    suspend fun deleteComic(id: Long) = comicDao.deleteComic(id)

    suspend fun updateNameAndCover(id: Long, name: String, coverPath: String) =
        comicDao.updateNameAndCover(id, name, coverPath)

    suspend fun updateNameAndJmId(id: Long, name: String, jmId: String) =
        comicDao.updateNameAndJmId(id, name, jmId)

    suspend fun updateJmId(id: Long, jmId: String) = comicDao.updateJmId(id, jmId)

    suspend fun updateDirectoryPath(id: Long, directoryPath: String) =
        comicDao.updateDirectoryPath(id, directoryPath)
}
