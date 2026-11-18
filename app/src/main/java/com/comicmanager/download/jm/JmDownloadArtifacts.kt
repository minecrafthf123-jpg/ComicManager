package com.comicmanager.download.jm

import java.io.File
import java.io.IOException

private val downloadDirectoryName = Regex("jm_[0-9]+_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val downloadCoverName = Regex("${downloadDirectoryName.pattern}\\.(jpg|jpeg|png|webp|gif|bmp)")

/** Called while the process-wide download mutex is held, before creating any new download files. */
internal fun reclaimJmDownloadArtifacts(filesDir: File, referencedPaths: List<String>) {
    try {
        val base = filesDir.canonicalFile
        // If any reference cannot be resolved, preserve all files rather than risk deleting a comic.
        val referenced = referencedPaths.filter { it.isNotBlank() }.map { File(it).canonicalFile }
        for (folder in listOf("jm_dl", "comics", "covers")) {
            val root = File(base, folder)
            if (root.canonicalFile != root || !root.isDirectory) continue
            for (candidate in root.listFiles().orEmpty()) {
                if (candidate.canonicalFile != candidate) continue
                val managed = if (folder == "covers") downloadCoverName.matches(candidate.name)
                    else downloadDirectoryName.matches(candidate.name)
                if (!managed) continue
                if (referenced.any {
                    it == candidate || it.path.startsWith(candidate.path + File.separator) ||
                        candidate.path.startsWith(it.path + File.separator)
                }) continue
                if (folder == "covers") {
                    if (candidate.isFile) candidate.delete()
                } else if (candidate.isDirectory) {
                    // This downloader creates flat image directories. Preserve unexpected nested content
                    // and symlinks, and never follow them during deletion.
                    val children = candidate.listFiles() ?: continue
                    if (children.any { !it.isFile || it.canonicalFile != it }) continue
                    if (children.all { it.delete() }) candidate.delete()
                }
            }
        }
    } catch (_: IOException) {
        // Cleanup is best effort; metadata and downloading remain available.
    } catch (_: SecurityException) {
        // Preserve files if filesystem permissions do not allow safe inspection.
    }
}
