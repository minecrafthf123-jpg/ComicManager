package com.comicmanager.util

import android.content.Context
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadUtil {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun downloadFile(
        url: String,
        userAgent: String?,
        referer: String?,
        outputFile: File,
        cookies: String = ""
    ): Result<File> {
        return try {
            val requestBuilder = Request.Builder().url(url)
            if (!userAgent.isNullOrBlank()) {
                requestBuilder.addHeader("User-Agent", userAgent)
            }
            if (!referer.isNullOrBlank()) {
                requestBuilder.addHeader("Referer", referer)
            }
            if (cookies.isNotBlank()) {
                requestBuilder.addHeader("Cookie", cookies)
            }
            requestBuilder.addHeader("Accept", "*/*")

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }

            outputFile.parentFile?.mkdirs()
            response.body?.byteStream()?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun downloadFileSafe(
        url: String,
        userAgent: String?,
        referer: String?,
        outputFile: File
    ): File? {
        return downloadFile(url, userAgent, referer, outputFile).getOrNull()
    }

    fun saveBase64Image(context: Context, base64: String, fileName: String): File? {
        return try {
            val clean = base64.substringAfter("base64,").ifBlank { base64 }
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val dir = File(context.filesDir, "jm_downloads")
            dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun parseFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val match = Regex("filename[*]?\\s*=\\s*[\"']?([^\"';\\s]+)").find(contentDisposition)
        return match?.groupValues?.getOrNull(1)
    }
}
