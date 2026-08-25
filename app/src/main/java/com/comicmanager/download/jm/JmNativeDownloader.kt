package com.comicmanager.download.jm

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.*
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import com.comicmanager.util.JmClientFactory
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.SearchQuery
import io.github.jukomu.jmcomic.core.client.AbstractJmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import java.util.Locale

data class JmNativeState(
    val isRunning: Boolean = false,
    val phase: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val message: String = ""
)

data class JmSearchItem(
    val id: String,
    val name: String,
    val author: String
)

class JmNativeDownloader(
    private val repository: ComicRepository
) {
    private val _state = MutableStateFlow(JmNativeState())
    val state: StateFlow<JmNativeState> = _state.asStateFlow()

    @Volatile
    var cancelled = false

    private var client: AbstractJmClient? = null

    @Synchronized
    private fun getClient(): AbstractJmClient {
        if (client == null) {
            client = JmClientFactory.get()
        }
        return client!!
    }

    private fun update(phase: String, current: Int = 0, total: Int = 0, message: String = "") {
        _state.value = JmNativeState(isRunning = true, phase = phase, current = current, total = total, message = message)
    }

    // java.nio.file is backported to API 24/25 by desugar_jdk_libs_nio.
    @SuppressLint("NewApi")
    suspend fun downloadAlbum(context: Context, albumId: String): Result<String> = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            update("获取漫画信息...")
            val c = getClient()
            val album: JmAlbum = c.getAlbum(albumId)
            if (cancelled) return@withContext Result.failure(Exception("已取消"))

            val comicName = (album.title ?: "JM_$albumId")
                .replace(Regex("[/\\\\:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "JM_$albumId" }

            val saveDir = File(context.filesDir, "jm_dl/$albumId")
            saveDir.deleteRecursively()
            saveDir.mkdirs()

            val result = try {
                c.download(album)
                    .withPath(Paths.get(saveDir.absolutePath))
                    .withProgress { p ->
                        if (cancelled) {
                            throw kotlinx.coroutines.CancellationException("用户取消下载")
                        }
                        update(
                            phase = "下载图片",
                            current = p.completedImages(),
                            total = p.totalImages(),
                            message = p.photoTitle() ?: ""
                        )
                    }
                    .execute()
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@withContext Result.failure(Exception("已取消"))
            }
            if (cancelled) return@withContext Result.failure(Exception("已取消"))

            val imgFiles = mutableListOf<File>()
            saveDir.walkTopDown().forEach { f ->
                if (f.isFile && f.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png", "gif")) {
                    imgFiles.add(f)
                }
            }
            imgFiles.sortBy { it.name }

            if (imgFiles.isEmpty()) {
                saveDir.deleteRecursively()
                val failed = result.failedTasks.entries.firstOrNull()
                val reason = failed?.value?.message ?: "未知原因"
                return@withContext Result.failure(Exception("未下载到图片: $reason"))
            }

            val flatDir = File(context.filesDir, "comics/$comicName")
            flatDir.deleteRecursively()
            flatDir.mkdirs()
            imgFiles.forEachIndexed { idx, f ->
                f.renameTo(File(flatDir, String.format(Locale.ROOT, "%05d.%s", idx + 1, f.extension)))
            }
            saveDir.deleteRecursively()

            update("导入书架...", imgFiles.size, imgFiles.size)
            importToLibrary(context, albumId, comicName, flatDir)
            _state.value = JmNativeState()
            Result.success("《$comicName》共 ${imgFiles.size} 页已加入书架")
        } catch (e: Exception) {
            _state.value = JmNativeState()
            Result.failure(e)
        }
    }

    suspend fun search(query: String, page: Int): Pair<Int, List<JmSearchItem>> = withContext(Dispatchers.IO) {
        val c = getClient()
        val sq = SearchQuery.Builder()
            .text(query)
            .page(page)
            .build()
        val searchPage = c.search(sq)
        android.util.Log.d("JmSearch", "total=" + searchPage.totalItems + " items=" + searchPage.content.size)
        searchPage.content.take(3).forEach { meta ->
            android.util.Log.d("JmSearch", "meta.toString=" + meta.toString())
        }
        val items = searchPage.content.map { meta ->
            JmSearchItem(
                id = meta.id ?: "",
                name = (meta.title ?: "").ifBlank { "" },
                author = meta.authors?.joinToString(", ") ?: ""
            )
        }
        Pair(searchPage.totalItems, items)
    }

    suspend fun enrichTitles(items: List<JmSearchItem>): List<JmSearchItem> =
        withContext(Dispatchers.IO) {
            items.map { item ->
                if (item.name.isNotBlank()) item
                else try {
                    val album = getClient().getAlbum(item.id)
                    val title = album.title ?: ""
                    item.copy(name = title.ifBlank { "JM" + item.id })
                } catch (_: Exception) {
                    item.copy(name = "JM" + item.id)
                }
            }
        }

    suspend fun getAlbumDetail(albumId: String): JmAlbum = withContext(Dispatchers.IO) {
        getClient().getAlbum(albumId)
    }

    suspend fun getComments(albumId: String, page: Int): List<io.github.jukomu.jmcomic.api.model.JmComment> = withContext(Dispatchers.IO) {
        val query = io.github.jukomu.jmcomic.api.model.ForumQuery.Builder()
            .album(albumId)
            .page(page)
            .build()
        getClient().getComments(query).list()
    }

    private fun importToLibrary(context: Context, albumId: String, comicName: String, dir: File): File {
        val images = dir.listFiles()?.filter {
            it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png")
        }?.sortedBy { it.name } ?: throw Exception("目录为空")

        val coverDir = File(context.filesDir, "covers")
        coverDir.mkdirs()
        val coverFile = File(coverDir, "${comicName}_cover.${images.first().extension}")
        images.first().inputStream().use { input ->
            coverFile.outputStream().use { output -> input.copyTo(output) }
        }

        val comic = Comic(
            name = comicName,
            coverPath = coverFile.absolutePath,
            directoryPath = dir.absolutePath,
            pageCount = images.size,
            jmId = albumId
        )

        kotlinx.coroutines.runBlocking {
            repository.insertComic(comic)
        }
        return coverFile
    }

    fun close() {
        client?.close()
        client = null
    }
}
