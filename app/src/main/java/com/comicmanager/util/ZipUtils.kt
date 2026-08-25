package com.comicmanager.util

import android.content.Context
import android.net.Uri
import com.comicmanager.R
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipUtils {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    data class ExtractResult(
        val success: Boolean,
        val imageFiles: List<File> = emptyList(),
        val errorMessage: String? = null
    )

    fun extractZip(context: Context, uri: Uri): ExtractResult {
        return try {
            val zipName = getZipName(context, uri)
            val extractDir = File(context.filesDir, "comics/$zipName")
            if (extractDir.exists()) {
                extractDir.deleteRecursively()
            }
            extractDir.mkdirs()

            val imageFiles = mutableListOf<File>()

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val fileName = File(entry.name).name
                            val extension = fileName.substringAfterLast('.', "").lowercase()
                            if (extension in IMAGE_EXTENSIONS) {
                                val outFile = File(extractDir, fileName)
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                                imageFiles.add(outFile)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (imageFiles.isEmpty()) {
                extractDir.deleteRecursively()
                ExtractResult(false, errorMessage = context.getString(R.string.zip_no_images))
            } else {
                imageFiles.sortBy { it.name }
                ExtractResult(true, imageFiles)
            }
        } catch (e: Exception) {
            ExtractResult(
                false,
                errorMessage = context.getString(R.string.zip_extract_failed, e.message.orEmpty())
            )
        }
    }

    fun getZipName(context: Context, uri: Uri): String {
        val displayName = try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
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
