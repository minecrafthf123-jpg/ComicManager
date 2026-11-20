package com.comicmanager.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE comics ADD COLUMN is_jm_download INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS comic_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL COLLATE NOCASE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_comic_folders_name ON comic_folders (name)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS comic_folder_memberships (
                folder_id INTEGER NOT NULL,
                comic_id INTEGER NOT NULL,
                PRIMARY KEY(folder_id, comic_id),
                FOREIGN KEY(folder_id) REFERENCES comic_folders(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(comic_id) REFERENCES comics(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_comic_folder_memberships_comic_id ON comic_folder_memberships (comic_id)")

        db.query("SELECT id, directory_path, cover_path FROM comics WHERE directory_path LIKE '%/files/comics/jm_%'").use { rows ->
            db.compileStatement("UPDATE comics SET is_jm_download = 1 WHERE id = ?").use { markDownloaded ->
                while (rows.moveToNext()) {
                    if (isManagedJmDownload(rows.getString(1), rows.getString(2))) {
                        markDownloaded.bindLong(1, rows.getLong(0))
                        markDownloaded.executeUpdateDelete()
                    }
                }
            }
        }
    }
}

private val jmDirectoryName = Regex("jm_[0-9]+_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val jmCoverExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** The 2.0–2.1 downloader used matching names for its image directory and cover file. */
internal fun isManagedJmDownload(directoryPath: String, coverPath: String): Boolean {
    // Stored paths are Android paths regardless of where the migration tests run.
    val comicRoot = directoryPath.substringBeforeLast('/')
    val directoryName = directoryPath.substringAfterLast('/')
    if (!directoryPath.startsWith('/') || !comicRoot.endsWith("/files/comics") ||
        !jmDirectoryName.matches(directoryName)
    ) return false

    val expectedCoverPrefix = "${comicRoot.removeSuffix("/comics")}/covers/$directoryName."
    return coverPath.startsWith(expectedCoverPrefix) &&
        coverPath.removePrefix(expectedCoverPrefix) in jmCoverExtensions
}
