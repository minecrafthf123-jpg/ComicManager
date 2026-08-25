package com.comicmanager.data

import androidx.room.*
import com.comicmanager.model.Comic
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {

    @Query("SELECT * FROM comics ORDER BY last_read_at DESC")
    fun getAllComics(): Flow<List<Comic>>

    @Query("SELECT * FROM comics WHERE id = :id")
    suspend fun getComicById(id: Long): Comic?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComic(comic: Comic): Long

    @Query("UPDATE comics SET current_page = :page, last_read_at = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM comics WHERE id = :id")
    suspend fun deleteComic(id: Long)

    @Query("UPDATE comics SET name = :name, cover_path = :coverPath WHERE id = :id")
    suspend fun updateNameAndCover(id: Long, name: String, coverPath: String)

    @Query("UPDATE comics SET name = :name, jm_id = :jmId WHERE id = :id")
    suspend fun updateNameAndJmId(id: Long, name: String, jmId: String)

    @Query("UPDATE comics SET jm_id = :jmId WHERE id = :id")
    suspend fun updateJmId(id: Long, jmId: String)

    @Query("UPDATE comics SET directory_path = :directoryPath WHERE id = :id")
    suspend fun updateDirectoryPath(id: Long, directoryPath: String)
}
