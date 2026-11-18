package com.comicmanager.download.jm

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JmDownloadArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val managedName = "jm_123_12345678-1234-4321-1234-123456789abc"

    @Test fun removesOnlyUnreferencedDownloaderArtifacts() {
        val root = temporary.root
        val staging = File(root, "jm_dl/$managedName").apply { mkdirs() }
        File(staging, "000001.jpg").writeText("unfinished")
        val orphan = File(root, "comics/$managedName").apply { mkdirs() }
        File(orphan, "000001.jpg").writeText("not committed")
        val cover = File(root, "covers/$managedName.jpg").apply { requireNotNull(parentFile).mkdirs(); writeText("cover") }
        val existing = File(root, "comics/My comic").apply { mkdirs() }
        File(existing, "000001.jpg").writeText("existing")
        reclaimJmDownloadArtifacts(root, emptyList())
        assertFalse(staging.exists())
        assertFalse(orphan.exists())
        assertFalse(cover.exists())
        assertTrue(existing.exists())
    }

    @Test fun preservesDatabaseReferencedComicAndCover() {
        val root = temporary.root
        val comic = File(root, "comics/$managedName").apply { mkdirs() }
        File(comic, "000001.jpg").writeText("saved")
        val cover = File(root, "covers/$managedName.webp").apply { requireNotNull(parentFile).mkdirs(); writeText("saved") }
        reclaimJmDownloadArtifacts(root, listOf(comic.path, cover.path))
        assertTrue(comic.exists())
        assertTrue(cover.exists())
    }

    @Test fun preservesUnexpectedNestedContentAndNames() {
        val root = temporary.root
        val nested = File(root, "comics/$managedName/chapter").apply { mkdirs() }
        val other = File(root, "jm_dl/jm_123_not-a-uuid").apply { mkdirs() }
        reclaimJmDownloadArtifacts(root, emptyList())
        assertTrue(nested.exists())
        assertTrue(other.exists())
    }

    @Test fun preservesDirectoryContainingAReferencedPage() {
        val root = temporary.root
        val page = File(root, "comics/$managedName/000001.jpg").apply { requireNotNull(parentFile).mkdirs(); writeText("saved") }
        reclaimJmDownloadArtifacts(root, listOf(page.path))
        assertTrue(page.exists())
    }
}
