package com.comicmanager.data

import com.comicmanager.model.Comic
import kotlinx.coroutines.flow.Flow

class ComicRepository(private val comicDao: ComicDao) {

    val allComics: Flow<List<Comic>> = comicDao.getAllComics()

    suspend fun getComicById(id: Long): Comic? = comicDao.getComicById(id)

    suspend fun insertComic(comic: Comic): Long = comicDao.insertComic(comic)

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
