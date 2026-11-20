package com.comicmanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderDatabaseMigrationTest {
    private val root = "/data/user/0/com.comicmanager/files"
    private val download = "jm_12345_a6187368-6ce5-4aa3-80bc-2f26acd6459a"

    @Test
    fun recognizesDownloadStorageFromThePreviousReleases() {
        assertTrue(isManagedJmDownload("$root/comics/$download", "$root/covers/$download.jpeg"))
        val alternateUser = root.replace("/0/", "/10/")
        assertTrue(isManagedJmDownload("$alternateUser/comics/$download", "$alternateUser/covers/$download.webp"))
    }

    @Test
    fun zipImportAndAmbiguousLegacySourcesAreNotPromoted() {
        assertFalse(isManagedJmDownload("$root/comics/12345", "$root/covers/12345_cover.jpg"))
        assertFalse(isManagedJmDownload("$root/comics/$download", "$root/covers/${download}_cover.jpg"))
        assertFalse(isManagedJmDownload("$root/zip_imports/pages/$download", "$root/covers/$download.jpg"))
    }

    @Test
    fun requiresACompleteMatchingDownloadPathAndCover() {
        assertFalse(isManagedJmDownload("$root/comics/jm_12345_incomplete", "$root/covers/jm_12345_incomplete.jpg"))
        assertFalse(isManagedJmDownload("$root/comics/$download", "$root/covers/something-else.jpg"))
        assertFalse(isManagedJmDownload("$root/comics/$download", "$root/covers/$download.jpg.bak"))
        assertFalse(isManagedJmDownload("$root/comics/$download", "$root/covers/subdir/$download.jpg"))
        assertFalse(isManagedJmDownload("$root/comics/$download/nested", "$root/covers/$download.jpg"))
    }
}
