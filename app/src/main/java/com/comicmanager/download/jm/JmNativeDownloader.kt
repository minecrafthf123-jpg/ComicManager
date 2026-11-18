package com.comicmanager.download.jm

import android.content.Context
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import com.comicmanager.util.JmClientFactory
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.SearchQuery
import io.github.jukomu.jmcomic.core.crypto.JmImageTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class JmNativeState(
    val phase: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val canCancel: Boolean = true
)

data class JmSearchItem(val id: String, val name: String, val author: String)
data class JmNativeDownloadResult(val comicName: String, val pageCount: Int)

class JmNativeDownloader(private val repository: ComicRepository) {
    private val _state = MutableStateFlow(JmNativeState())
    val state = _state.asStateFlow()
    private val imageClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val commitLock = Any()
    private var committing = false
    private var activeJob: Job? = null

    /** Once database insertion starts it finishes as a single, short commit. */
    fun cancel(): Boolean = synchronized(commitLock) {
        if (committing) false else {
            activeJob?.cancel()
            true
        }
    }

    suspend fun downloadAlbum(context: Context, albumId: String): JmNativeDownloadResult = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            require(albumId.matches(Regex("\\d+"))) { context.getString(R.string.jm_v2_invalid_id) }
            val downloadJob = currentCoroutineContext()[Job]
            synchronized(commitLock) {
                activeJob = downloadJob
                committing = false
            }
            val uniqueName = "jm_${albumId}_${UUID.randomUUID()}"
            val staging = File(context.filesDir, "jm_dl/$uniqueName")
            val finalDir = File(context.filesDir, "comics/$uniqueName")
            var cover: File? = null
            var committed = false
            try {
                val comics = repository.allComics.first()
                reclaimJmDownloadArtifacts(
                    context.filesDir,
                    comics.flatMap { listOf(it.directoryPath, it.coverPath) }
                )
                comics.firstOrNull { it.jmId == albumId }?.let {
                    return@withContext JmNativeDownloadResult(it.name, it.pageCount)
                }
                _state.value = JmNativeState(phase = context.getString(R.string.native_fetching_info))
                val client = JmClientFactory.get()
                val album = runInterruptible { client.getAlbum(albumId) }
                currentCoroutineContext().ensureActive()
                val comicName = album.title.orEmpty().replace(Regex("[/\\\\:*?\"<>|]"), "_")
                    .trim().ifBlank { "JM_$albumId" }
                val photos = album.photoMetas.orEmpty().distinctBy { it.id }.sortedBy { it.sortOrder }.map { meta ->
                    currentCoroutineContext().ensureActive()
                    runInterruptible { client.getPhoto(meta.id) }
                }
                // Flatten in chapter order, then page order. Filenames repeat across chapters.
                val images = photos.flatMap { it.images.orEmpty().sortedBy { image -> image.sortOrder } }
                check(images.isNotEmpty()) { context.getString(R.string.native_empty_directory) }
                check(staging.mkdirs()) { context.getString(R.string.jm_v2_create_directory_failed) }
                _state.value = JmNativeState(
                    phase = context.getString(R.string.native_downloading_images), total = images.size
                )
                val completed = AtomicInteger()
                val parallelism = Semaphore(3)
                coroutineScope {
                    images.mapIndexed { index, image ->
                        async {
                            parallelism.withPermit {
                                currentCoroutineContext().ensureActive()
                                val request = Request.Builder().url(image.downloadUrl).get().build()
                                val raw = imageClient.newCall(request).awaitImageBytes()
                                currentCoroutineContext().ensureActive()
                                val decoded = if (image.isGif) raw else JmImageTool.decryptImage(raw, image)
                                currentCoroutineContext().ensureActive()
                                val extension = image.filename.orEmpty().substringAfterLast('.', "jpg")
                                    .lowercase(Locale.ROOT).takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
                                File(staging, String.format(Locale.ROOT, "%06d.%s", index + 1, extension)).writeBytes(decoded)
                                val count = completed.incrementAndGet()
                                _state.update { previous -> JmNativeState(
                                    phase = context.getString(R.string.native_downloading_images),
                                    current = maxOf(previous.current, count), total = images.size,
                                    message = image.filename.orEmpty()
                                ) }
                            }
                        }
                    }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                // Do not replace existing titles or directories. Only a complete album is published.
                finalDir.parentFile?.mkdirs()
                check(staging.renameTo(finalDir)) { context.getString(R.string.jm_v2_move_failed) }
                val firstPage = finalDir.listFiles()?.sortedBy { it.name }?.firstOrNull()
                    ?: error(context.getString(R.string.native_empty_directory))
                val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
                val coverFile = File(coverDir, "$uniqueName.${firstPage.extension}")
                cover = coverFile
                firstPage.copyTo(coverFile)
                val job = currentCoroutineContext()[Job]!!
                synchronized(commitLock) {
                    job.ensureActive()
                    committing = true
                }
                _state.value = JmNativeState(
                    phase = context.getString(R.string.native_importing_library),
                    current = images.size, total = images.size, canCancel = false
                )
                withContext(NonCancellable) {
                    repository.insertComic(Comic(
                        name = comicName, coverPath = coverFile.absolutePath, directoryPath = finalDir.absolutePath,
                        pageCount = images.size, jmId = albumId
                    ))
                    committed = true
                }
                JmNativeDownloadResult(comicName, images.size)
            } finally {
                // Child coroutines are finished before cleanup, so late network callbacks cannot write files.
                staging.deleteRecursively()
                if (!committed) {
                    finalDir.deleteRecursively()
                    cover?.delete()
                }
                synchronized(commitLock) { activeJob = null; committing = false }
                _state.value = JmNativeState()
            }
        }
    }

    suspend fun search(query: String, page: Int): Pair<Int, List<JmSearchItem>> = runInterruptible(Dispatchers.IO) {
        val result = JmClientFactory.get().search(SearchQuery.Builder().text(query).page(page).build())
        result.totalItems to result.content.map { meta ->
            JmSearchItem(meta.id.orEmpty(), meta.title.orEmpty(), meta.authors?.joinToString(", ").orEmpty())
        }.filter { it.id.isNotBlank() }
    }

    suspend fun enrichTitles(items: List<JmSearchItem>): List<JmSearchItem> = items.map { item ->
        if (item.name.isNotBlank()) item else try {
            item.copy(name = getAlbumDetail(item.id).title.orEmpty().ifBlank { "JM${item.id}" })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            item.copy(name = "JM${item.id}")
        }
    }

    suspend fun getAlbumDetail(albumId: String): JmAlbum = runInterruptible(Dispatchers.IO) {
        JmClientFactory.get().getAlbum(albumId)
    }

    suspend fun getPageCount(album: JmAlbum): Int? = resolveAlbumPageCount(
        reportedPages = album.pageCount,
        imageCount = album.images?.size,
        chapterIds = album.photoMetas.orEmpty().map { it.id }
    ) { chapterId ->
        runInterruptible(Dispatchers.IO) { JmClientFactory.get().getPhoto(chapterId).images?.size }
    }

    suspend fun getComments(albumId: String, page: Int): List<JmComment> = runInterruptible(Dispatchers.IO) {
        val query = io.github.jukomu.jmcomic.api.model.ForumQuery.Builder().album(albumId).page(page).build()
        JmClientFactory.get().getComments(query).list()
    }

    fun close() {
        imageClient.dispatcher.cancelAll()
        imageClient.dispatcher.executorService.shutdown()
        imageClient.connectionPool.evictAll()
        // JmClientFactory is shared with the bookshelf; it owns its client's lifecycle.
    }

    private companion object {
        // Covers cleanup and publication together, including downloads from another view model.
        val downloadMutex = Mutex()
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
