package com.comicmanager.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class ComicShareArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun singleComicSharesOnlyItsImagesAndSupportsUuidImportDirectories() {
        val source = temporary.newFolder("import_12345678-1234-4321-1234-123456789abc")
        File(source, "000002.PNG").writeText("second")
        File(source, "000001.jpg").writeText("first")
        File(source, "db.sqlite").writeText("private database")
        File(source, "notes.txt").writeText("not a page")
        val archive = prepareComicShareArchive(
            listOf(ComicArchiveSource("[JM123] Comic.zip", source)), temporary.newFolder(), "bundle.zip"
        )
        assertEquals("[JM123] Comic.zip", archive.name)
        val entries = archive.inputStream().use(::readZip)
        assertEquals(listOf("000001.jpg", "000002.PNG"), entries.keys.toList())
        assertEquals("first", entries.getValue("000001.jpg").toString(Charsets.UTF_8))
    }

    @Test fun multipleComicsBecomeOneBundleWithSeparateUniquelyNamedArchives() {
        val first = temporary.newFolder("first").apply { resolve("000001.jpg").writeText("first") }
        val second = temporary.newFolder("second").apply { resolve("000001.jpg").writeText("second") }
        val progress = mutableListOf<Int>()
        val archive = prepareComicShareArchive(
            listOf(ComicArchiveSource("same.zip", first), ComicArchiveSource("same.zip", second)),
            temporary.newFolder(), "bundle.zip", onComicPacked = { progress += it }
        )
        val entries = archive.inputStream().use(::readZip)
        assertEquals(listOf("same.zip", "same (2).zip"), entries.keys.toList())
        assertEquals("first", readZip(ByteArrayInputStream(entries.getValue("same.zip"))).getValue("000001.jpg").toString(Charsets.UTF_8))
        assertEquals("second", readZip(ByteArrayInputStream(entries.getValue("same (2).zip"))).getValue("000001.jpg").toString(Charsets.UTF_8))
        assertEquals(listOf(1, 2), progress)
        assertEquals(listOf(archive), requireNotNull(archive.parentFile).listFiles()!!.toList())
    }

    @Test fun aMissingComicFailsTheBundleWithoutPublishingAPartialArchive() {
        val first = temporary.newFolder("first").apply { resolve("000001.jpg").writeText("first") }
        val shareDirectory = temporary.newFolder()
        try {
            prepareComicShareArchive(
                listOf(ComicArchiveSource("first.zip", first), ComicArchiveSource("missing.zip", File(first, "missing"))),
                shareDirectory, "bundle.zip"
            )
            fail("Must not silently omit a selected comic")
        } catch (_: IOException) {
            assertTrue(shareDirectory.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellingCopyRemovesItsIncompleteArchiveAndPreservesSourceImages() {
        val source = temporary.newFolder().apply { resolve("000001.jpg").writeBytes(ByteArray(128 * 1024)) }
        val target = File(temporary.newFolder(), "comic.zip")
        var checks = 0
        try {
            writeComicArchive(source, target) {
                if (++checks == 4) throw CancellationException("cancelled")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(target.exists())
            assertTrue(source.resolve("000001.jpg").exists())
        }
    }

    @Test fun archiveNameCannotEscapeShareSessionDirectory() {
        val source = temporary.newFolder().apply { resolve("000001.jpg").writeText("first") }
        val shareDirectory = temporary.newFolder()
        try {
            prepareComicShareArchive(listOf(ComicArchiveSource("../outside.zip", source)), shareDirectory, "bundle.zip")
            fail("Unsafe archive name accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(File(shareDirectory.parentFile, "outside.zip").exists())
        }
    }

    @Test fun newSharesDoNotRemoveRecentRecipientFilesAndOnlyExpiredSessionsAreReclaimed() {
        val cache = temporary.newFolder()
        val old = File(cache, "share/12345678-1234-4321-1234-123456789abc").apply { mkdirs() }
        File(old, "old.zip").writeText("old")
        val recent = File(cache, "share/12345678-1234-4321-1234-123456789abd").apply { mkdirs() }
        File(recent, "recent.zip").writeText("recipient still reading")
        val unrelated = File(cache, "share/not-our-session").apply { mkdirs() }
        old.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        reclaimExpiredComicShareSessions(cache)
        assertFalse(old.exists())
        assertTrue(File(recent, "recent.zip").exists())
        assertTrue(unrelated.exists())
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
