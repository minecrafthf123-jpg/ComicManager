package com.comicmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.comicmanager.model.Comic
import com.comicmanager.model.ComicFolder
import com.comicmanager.model.ComicFolderMembership
import com.comicmanager.model.ComicFolderSummary
import kotlinx.coroutines.flow.Flow

enum class FolderNameError { BLANK, DUPLICATE }

class FolderNameException(val reason: FolderNameError) : IllegalArgumentException(reason.name)

class FolderNotFoundException : IllegalStateException("Folder no longer exists")

class NoComicsForFolderException : IllegalStateException("The selected comics no longer exist")

@Dao
abstract class ComicFolderDao {
    @Query("""
        SELECT folder.id, folder.name, COUNT(membership.comic_id) AS comicCount
        FROM comic_folders AS folder
        LEFT JOIN comic_folder_memberships AS membership ON folder.id = membership.folder_id
        GROUP BY folder.id, folder.name
        ORDER BY folder.name COLLATE NOCASE, folder.id
    """)
    abstract fun observeFolders(): Flow<List<ComicFolderSummary>>

    @Query("""
        SELECT comics.* FROM comics
        JOIN comic_folder_memberships AS membership ON comics.id = membership.comic_id
        WHERE membership.folder_id = :folderId
        ORDER BY comics.last_read_at DESC, comics.id DESC
    """)
    abstract fun observeComicsInFolder(folderId: Long): Flow<List<Comic>>

    @Transaction
    open suspend fun createFolder(name: String, comicIds: Collection<Long>): Long {
        val savedName = validatedName(name)
        val existingComicIds = existingComicIds(comicIds)
        val folderId = insertFolder(ComicFolder(name = savedName))
        if (folderId < 0) throw FolderNameException(FolderNameError.DUPLICATE)
        insertComics(folderId, existingComicIds)
        return folderId
    }

    @Transaction
    open suspend fun renameFolder(folderId: Long, name: String) {
        val savedName = validatedName(name)
        val previousName = folderName(folderId) ?: throw FolderNotFoundException()
        if (previousName != savedName && updateFolderName(folderId, savedName) == 0) {
            throw FolderNameException(FolderNameError.DUPLICATE)
        }
    }

    @Query("DELETE FROM comic_folders WHERE id = :folderId")
    abstract suspend fun deleteFolder(folderId: Long)

    @Transaction
    open suspend fun addComics(folderId: Long, comicIds: Collection<Long>): Int {
        if (folderName(folderId) == null) throw FolderNotFoundException()
        return insertComics(folderId, existingComicIds(comicIds))
    }

    @Transaction
    open suspend fun removeComics(folderId: Long, comicIds: Collection<Long>) {
        comicIds.distinct().chunked(SQLITE_BATCH_SIZE).forEach { ids ->
            deleteMemberships(folderId, ids)
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertFolder(folder: ComicFolder): Long

    @Query("SELECT name FROM comic_folders WHERE id = :folderId")
    protected abstract suspend fun folderName(folderId: Long): String?

    @Query("UPDATE OR IGNORE comic_folders SET name = :name WHERE id = :folderId")
    protected abstract suspend fun updateFolderName(folderId: Long, name: String): Int

    @Query("SELECT id FROM comics WHERE id IN (:comicIds)")
    protected abstract suspend fun findComicIds(comicIds: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMemberships(memberships: List<ComicFolderMembership>): List<Long>

    @Query("DELETE FROM comic_folder_memberships WHERE folder_id = :folderId AND comic_id IN (:comicIds)")
    protected abstract suspend fun deleteMemberships(folderId: Long, comicIds: List<Long>)

    private suspend fun existingComicIds(requestedIds: Collection<Long>): List<Long> {
        if (requestedIds.isEmpty()) return emptyList()
        val ids = requestedIds.distinct().chunked(SQLITE_BATCH_SIZE).flatMap { findComicIds(it) }
        if (ids.isEmpty()) throw NoComicsForFolderException()
        return ids
    }

    private suspend fun insertComics(folderId: Long, comicIds: List<Long>): Int {
        if (comicIds.isEmpty()) return 0
        val memberships = comicIds.map { ComicFolderMembership(folderId, it) }
        return insertMemberships(memberships).count { it != -1L }
    }

    private fun validatedName(name: String): String = name.trim().ifEmpty {
        throw FolderNameException(FolderNameError.BLANK)
    }

    private companion object {
        // Android SQLite versions may enforce a 999-parameter limit per statement.
        const val SQLITE_BATCH_SIZE = 900
    }
}
