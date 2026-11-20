package com.comicmanager.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipUtilsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun preservesSameNamedImagesFromDifferentChaptersAndSortsTheirPaths() {
        val archive = archive(
            "chapter2/001.jpg" to "second".toByteArray(),
            "chapter1/001.jpg" to "first".toByteArray(),
            "notes.txt" to "ignore".toByteArray()
        )
        val progress = mutableListOf<ZipUtils.ExtractProgress>()
        val files = ZipUtils.extractImages(archive, temporary.newFolder(), progress::add)
        assertEquals(listOf("first", "second"), files.map { it.readText() })
        assertEquals(files.size, files.distinctBy { it.canonicalPath }.size)
        assertEquals(files, files.sortedBy { it.name })
        assertEquals(ZipUtils.ExtractProgress(2, 11), progress.last())
    }

    @Test
    fun uniqueBasenamesKeepThePreviousFlattenedPageOrder() {
        val files = ZipUtils.extractImages(
            archive("a/002.jpg" to "second".toByteArray(), "z/001.jpg" to "first".toByteArray()),
            temporary.newFolder()
        )
        assertEquals(listOf("first", "second"), files.map { it.readText() })
    }

    @Test
    fun basenameOrderingAndCollisionDetectionRemainCaseSensitive() {
        val files = ZipUtils.extractImages(
            archive("a/001.jpg" to "lower".toByteArray(), "z/001.JPG" to "upper".toByteArray()),
            temporary.newFolder()
        )
        assertEquals(listOf("upper", "lower"), files.map { it.readText() })
    }

    @Test
    fun reportsProgressBeforeALargeSingleImageFinishes() {
        val progress = mutableListOf<ZipUtils.ExtractProgress>()
        val image = ByteArray(256 * 1024) { (it % 251).toByte() }
        val files = ZipUtils.extractImages(
            archive("large.jpg" to image), temporary.newFolder(), progress::add,
            checkCancelled = { Thread.sleep(5) }
        )
        assertTrue(progress.any { it.imageCount == 0 && it.bytes > 0 })
        assertEquals(image.size.toLong(), progress.last().bytes)
        assertArrayEquals(image, files.single().readBytes())
    }

    @Test
    fun cancellationIsPropagatedDuringImageCopy() {
        var checks = 0
        try {
            ZipUtils.extractImages(
                archive("large.jpg" to ByteArray(128 * 1024)), temporary.newFolder(),
                checkCancelled = { if (++checks == 5) throw CancellationException("stop") }
            )
            fail("Cancellation was swallowed")
        } catch (cancelled: CancellationException) {
            assertEquals("stop", cancelled.message)
        }
    }

    @Test
    fun archiveEntryPathsCannotEscapeTheImportDirectory() {
        val directory = temporary.newFolder()
        val files = ZipUtils.extractImages(archive("../outside.jpg" to byteArrayOf(7)), directory)
        assertEquals(directory.canonicalFile, requireNotNull(files.single().canonicalFile.parentFile))
        assertFalse(requireNotNull(directory.parentFile).resolve("outside.jpg").exists())
    }

    @Test
    fun archivesWithoutImagesDoNotCreateLibraryPages() {
        val files = ZipUtils.extractImages(archive("notes.txt" to byteArrayOf(1)), temporary.newFolder())
        assertTrue(files.isEmpty())
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}
