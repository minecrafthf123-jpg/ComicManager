package com.comicmanager.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class ComicArchiveSource(val archiveName: String, val directory: File)

/** Keep recent sessions available to recipient apps; reclaim only our expired flat sessions. */
internal fun reclaimExpiredComicShareSessions(cacheDirectory: File, now: Long = System.currentTimeMillis()) {
    try {
        val root = File(cacheDirectory.canonicalFile, "share")
        if (!root.isDirectory || root.canonicalFile != root) return
        val sessionName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        root.listFiles().orEmpty().forEach { session ->
            if (!sessionName.matches(session.name) || !session.isDirectory ||
                session.canonicalFile != session || session.lastModified() >= cutoff) return@forEach
            val files = session.listFiles() ?: return@forEach
            if (files.any { !it.isFile || it.canonicalFile != it }) return@forEach
            if (files.all { it.delete() }) session.delete()
        }
    } catch (_: IOException) {
        // Cache cleanup must not prevent sharing if safe inspection is unavailable.
    } catch (_: SecurityException) {
        // Preserve sessions when permissions prevent safe inspection.
    }
}

/** Copies only the selected comic's flat image files, never database or application metadata. */
internal fun writeComicArchive(
    directory: File,
    destination: File,
    checkCancelled: () -> Unit = {}
) {
    val source = directory.canonicalFile
    if (!source.isDirectory) throw IOException("Comic image directory is missing")
    val images = source.listFiles()?.filter {
        it.isFile && it.canonicalFile == it &&
            it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    }?.sortedBy { it.name }.orEmpty()
    if (images.isEmpty()) throw IOException("Comic contains no readable image files")
    try {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            // Comic pages are already compressed. Store them without expensive recompression.
            zip.setLevel(Deflater.NO_COMPRESSION)
            for (image in images) {
                checkCancelled()
                zip.putNextEntry(ZipEntry(image.name))
                image.inputStream().use { copyArchiveBytes(it, zip, checkCancelled) }
                zip.closeEntry()
            }
        }
        checkCancelled()
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

/** One ACTION_SEND stream: a comic ZIP, or a bundle containing an independent ZIP for each comic. */
internal fun prepareComicShareArchive(
    sources: List<ComicArchiveSource>,
    sessionDirectory: File,
    bundleName: String,
    checkCancelled: () -> Unit = {},
    onComicPacked: (Int) -> Unit = {}
): File {
    require(sources.isNotEmpty()) { "No comics selected" }
    check(sessionDirectory.isDirectory || sessionDirectory.mkdirs()) { "Cannot create share directory" }
    fun checkedName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code < 32 }) { "Invalid archive filename" }
        return name
    }
    if (sources.size == 1) {
        val source = sources.single()
        return File(sessionDirectory, checkedName(source.archiveName)).also {
            writeComicArchive(source.directory, it, checkCancelled)
            onComicPacked(1)
        }
    }
    val bundle = File(sessionDirectory, checkedName(bundleName))
    val usedNames = mutableSetOf<String>()
    try {
        ZipOutputStream(FileOutputStream(bundle)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            sources.forEachIndexed { index, source ->
                checkCancelled()
                val original = checkedName(source.archiveName)
                var entryName = original
                var suffix = 2
                while (!usedNames.add(entryName.lowercase(Locale.ROOT))) {
                    entryName = "${original.removeSuffix(".zip")} (${suffix++}).zip"
                }
                val temporary = File.createTempFile("comic_", ".zip", sessionDirectory)
                try {
                    writeComicArchive(source.directory, temporary, checkCancelled)
                    zip.putNextEntry(ZipEntry(entryName))
                    temporary.inputStream().use { copyArchiveBytes(it, zip, checkCancelled) }
                    zip.closeEntry()
                } finally {
                    temporary.delete()
                }
                onComicPacked(index + 1)
            }
        }
        checkCancelled()
        return bundle
    } catch (error: Exception) {
        bundle.delete()
        throw error
    }
}

private fun copyArchiveBytes(input: InputStream, output: OutputStream, checkCancelled: () -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
