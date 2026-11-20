package com.comicmanager.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ZipImportArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uuid = "12345678-1234-4321-1234-123456789abc"

    @Test
    fun processRestartReclaimsOnlyGeneratedArtifactsInPrivateImportStorage() {
        val root = temporary.root
        val stalePage = file(root, "zip_imports/pages/import_$uuid/page_0.tmp")
        val orphanPage = file(root, "zip_imports/pages/import_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/000001.jpg")
        val staleCover = file(root, "zip_imports/covers/import_cover_$uuid.jpg")
        val oldComic = file(root, "comics/import_$uuid/000001.jpg")
        val oldCover = file(root, "covers/import_cover_12345.jpg")
        val unrelated = file(root, "zip_imports/pages/My comic/000001.jpg")

        ZipUtils.reclaimStaleImports(root, emptyList())

        assertFalse(requireNotNull(stalePage.parentFile).exists())
        assertFalse(requireNotNull(orphanPage.parentFile).exists())
        assertFalse(staleCover.exists())
        assertTrue(oldComic.exists())
        assertTrue(oldCover.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun databaseReferencesPreserveBothComicDirectoriesAndIndividualPages() {
        val root = temporary.root
        val page = file(root, "zip_imports/pages/import_$uuid/000001.jpg")
        val cover = file(root, "zip_imports/covers/import_cover_$uuid.webp")
        ZipUtils.reclaimStaleImports(root, listOf(requireNotNull(page.parent), cover.path))
        assertTrue(page.exists())
        assertTrue(cover.exists())
        ZipUtils.reclaimStaleImports(root, listOf(page.path, cover.path))
        assertTrue(page.exists())
        assertTrue(cover.exists())
    }

    @Test
    fun unexpectedNestedContentAndInvalidGeneratedNamesArePreserved() {
        val root = temporary.root
        val nested = file(root, "zip_imports/pages/import_$uuid/chapter/000001.jpg")
        val personalFile = file(root, "zip_imports/pages/import_aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/notes.txt")
        val invalidName = file(root, "zip_imports/pages/import_not-a-uuid/000001.jpg")
        val unrelatedCover = file(root, "zip_imports/covers/import_cover_title.jpg")
        ZipUtils.reclaimStaleImports(root, emptyList())
        assertTrue(nested.exists())
        assertTrue(personalFile.exists())
        assertTrue(invalidName.exists())
        assertTrue(unrelatedCover.exists())
    }

    @Test
    fun recoveryWaitsForTheActiveImportAndReadsItsCommittedReferences() = runBlocking {
        val root = temporary.root
        val created = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        var referencedPaths = emptyList<String>()
        var recoveryEntered = false
        val page = File(root, "zip_imports/pages/import_$uuid/000001.jpg")
        val active = launch {
            ZipUtils.withImportSession {
                file(root, "zip_imports/pages/import_$uuid/000001.jpg")
                created.complete(Unit)
                finishCommit.await()
                referencedPaths = listOf(requireNotNull(page.parent))
            }
        }
        created.await()
        val recovery = launch {
            ZipUtils.withImportSession {
                recoveryEntered = true
                ZipUtils.reclaimStaleImports(root, referencedPaths)
            }
        }
        yield()
        assertFalse(recoveryEntered)
        assertTrue(page.exists())
        finishCommit.complete(Unit)
        active.join()
        recovery.join()
        assertTrue(recoveryEntered)
        assertTrue(page.exists())
    }

    @Test
    fun newCoverFilesUseTheSamePrivateManagedNamespace() {
        val cover = ZipUtils.createImportCoverFile(temporary.root, "png")
        assertEquals(File(temporary.root, "zip_imports/covers").canonicalFile, requireNotNull(cover.canonicalFile.parentFile))
        assertTrue(cover.name.matches(Regex("import_cover_[0-9a-f-]{36}\\.png")))
        ZipUtils.reclaimStaleImports(temporary.root, emptyList())
        assertFalse(cover.exists())
    }

    private fun file(root: File, path: String): File = File(root, path).apply {
        requireNotNull(parentFile).mkdirs()
        writeText("image")
    }
}
