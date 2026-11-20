package com.comicmanager.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "comics")
data class Comic(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "cover_path")
    val coverPath: String,
    @ColumnInfo(name = "directory_path")
    val directoryPath: String,
    @ColumnInfo(name = "page_count")
    val pageCount: Int,
    @ColumnInfo(name = "current_page")
    val currentPage: Int = 0,
    @ColumnInfo(name = "jm_id")
    val jmId: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_read_at")
    val lastReadAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_jm_download", defaultValue = "0")
    val isJmDownload: Boolean = false
)
