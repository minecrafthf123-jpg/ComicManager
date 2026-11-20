package com.comicmanager.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "comic_folders", indices = [Index(value = ["name"], unique = true)])
data class ComicFolder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String
)

@Entity(
    tableName = "comic_folder_memberships",
    primaryKeys = ["folder_id", "comic_id"],
    foreignKeys = [
        ForeignKey(
            entity = ComicFolder::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Comic::class,
            parentColumns = ["id"],
            childColumns = ["comic_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("comic_id")]
)
data class ComicFolderMembership(
    @ColumnInfo(name = "folder_id") val folderId: Long,
    @ColumnInfo(name = "comic_id") val comicId: Long
)

data class ComicFolderSummary(val id: Long, val name: String, val comicCount: Int)
