package com.comicmanager.util

import android.content.Context
import android.net.Uri
import com.comicmanager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

object ZipUtils {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val importMutex = Mutex()
    private val importId = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val importDirectoryName = Regex("import_$importId")
    private val importCoverName = Regex("import_cover_$importId\\.(jpg|jpeg|png|webp|bmp|gif)")
    private val importPageName = Regex("([0-9]{6,}\\.(jpg|jpeg|png|webp|bmp|gif)|page_[0-9]+\\.tmp)")

    /** Hold throughout recovery, extraction and publication, including the database write. */
    internal suspend fun <T> withImportSession(block: suspend () -> T): T = importMutex.withLock { block() }

    internal fun createImportCoverFile(filesDir: File, extension: String): File {
        require(extension in IMAGE_EXTENSIONS)
        val directory = storageDirectory(filesDir, "covers").apply {
            check(isDirectory || mkdirs()) { "Cannot create import cover directory" }
        }
        return File(directory, "import_cover_${UUID.randomUUID()}.$extension").apply {
            check(createNewFile()) { "Cannot create import cover" }
        }
    }

    /** Call only while withImportSession is held, before starting a new batch. */
    internal fun reclaimStaleImports(filesDir: File, referencedPaths: List<String>) {
        try {
            val referenced = referencedPaths.filter(String::isNotBlank).map { File(it).canonicalFile }
            for (folder in listOf("pages", "covers")) {
                val root = storageDirectory(filesDir, folder)
                if (!root.isDirectory) continue
                for (candidate in root.listFiles().orEmpty()) {
                    if (candidate.canonicalFile != candidate) continue
                    val managed = if (folder == "pages") importDirectoryName.matches(candidate.name)
                        else importCoverName.matches(candidate.name)
                    if (!managed || referenced.any {
                        it == candidate || it.path.startsWith(candidate.path + File.separator) ||
                            candidate.path.startsWith(it.path + File.separator)
                    }) continue
                    if (folder == "covers") {
                        if (candidate.isFile) candidate.delete()
                    } else if (candidate.isDirectory) {
                        val children = candidate.listFiles() ?: continue
                        // Preserve unexpected nested/user content and never follow symlinks.
                        if (children.any {
                            !it.isFile || it.canonicalFile != it || !importPageName.matches(it.name)
                        }) continue
                        if (children.all { it.delete() }) candidate.delete()
                    }
                }
            }
        } catch (_: IOException) {
            // Recovery is best effort. Unresolvable references preserve the entire batch.
        } catch (_: SecurityException) {
            // Permission failures must not affect existing comics or prevent imports.
        }
    }

    private fun storageDirectory(filesDir: File, child: String): File {
        val base = filesDir.canonicalFile
        val root = File(base, "zip_imports")
        val directory = File(root, child)
        if (root.canonicalFile != root || directory.canonicalFile != directory) {
            throw IOException("Import directory must stay inside app storage")
        }
        return directory
    }

    data class ExtractResult(
        val success: Boolean,
        val imageFiles: List<File> = emptyList(),
        val errorMessage: String? = null
    )

    data class ExtractProgress(val imageCount: Int, val bytes: Long)

    fun extractZip(
        context: Context,
        uri: Uri,
        onProgress: (ExtractProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ExtractResult {
        // Each import owns a fresh directory. A failed or duplicate archive must
        // never replace the files of an existing library item.
        val extractDir = File(storageDirectory(context.filesDir, "pages"), "import_${UUID.randomUUID()}")
        return try {
            checkCancelled()
            check(extractDir.mkdirs()) { "Cannot create import directory" }
            val imageFiles = context.contentResolver.openInputStream(uri)?.use { input ->
                extractImages(input, extractDir, onProgress, checkCancelled)
            } ?: throw IOException("Cannot open archive")
            if (imageFiles.isEmpty()) {
                extractDir.deleteRecursively()
                ExtractResult(false, errorMessage = context.getString(R.string.zip_no_images))
            } else {
                ExtractResult(true, imageFiles)
            }
        } catch (cancelled: CancellationException) {
            extractDir.deleteRecursively()
            throw cancelled
        } catch (e: Exception) {
            extractDir.deleteRecursively()
            ExtractResult(
                false,
                errorMessage = context.getString(R.string.zip_extract_failed, e.message.orEmpty())
            )
        }
    }

    /** Java-only extraction core, shared by Android imports and ZIP regression tests. */
    internal fun extractImages(
        input: InputStream,
        directory: File,
        onProgress: (ExtractProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): List<File> {
        val pages = mutableListOf<Pair<String, File>>()
        var bytes = 0L
        var lastProgressNanos = System.nanoTime()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastProgressNanos >= 100_000_000L) {
                onProgress(ExtractProgress(pages.size, bytes))
                lastProgressNanos = now
            }
        }
        onProgress(ExtractProgress(0, 0))
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                checkCancelled()
                val name = entry.name
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!entry.isDirectory && extension in IMAGE_EXTENSIONS) {
                    // Entry paths never become filesystem paths. Temporary names
                    // also preserve pages with identical basenames in different folders.
                    val target = File(directory, "page_${pages.size}.tmp")
                    FileOutputStream(target).use { output ->
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            bytes += count
                            report()
                        }
                    }
                    pages += name to target
                    report()
                } else {
                    // Read ignored entries in chunks too, so even a large non-image
                    // entry cannot make coroutine cancellation wait for closeEntry().
                    while (true) {
                        checkCancelled()
                        val count = zip.read(buffer)
                        if (count < 0) break
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        checkCancelled()
        // Older imports flattened then sorted by File.name. Preserve that order
        // whenever basenames are unique; chapter paths only disambiguate collisions.
        val basenames = pages.map { File(it.first).name }
        val sorted = if (basenames.distinct().size == basenames.size) {
            pages.sortedBy { File(it.first).name }
        } else pages.sortedBy { it.first }
        val files = sorted.mapIndexed { index, (sourceName, temporaryFile) ->
            checkCancelled()
            val extension = sourceName.substringAfterLast('.').lowercase(Locale.ROOT)
            val page = File(directory, String.format(Locale.ROOT, "%06d.%s", index + 1, extension))
            check(temporaryFile.renameTo(page)) { "Cannot finish extracted image" }
            page
        }
        report(force = true)
        return files
    }

    fun getZipName(context: Context, uri: Uri): String {
        val displayName = try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
        return displayName?.substringBeforeLast('.') ?: "unknown_${System.currentTimeMillis()}"
    }

    fun copyUriToFile(context: Context, uri: Uri, destFile: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
