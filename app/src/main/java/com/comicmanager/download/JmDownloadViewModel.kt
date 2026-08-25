package com.comicmanager.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import com.comicmanager.download.jm.JmNativeDownloader
import com.comicmanager.util.DownloadUtil
import com.comicmanager.util.ZipUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

data class DownloadUiState(
    val currentUrl: String = "https://18comic.vip/",
    val inputUrl: String = "https://18comic.vip/",
    val isDownloading: Boolean = false,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val statusMessage: String = "",
    val comicTitle: String = "",
    val showCaptchaHelp: Boolean = false,
    val captchaText: String = "",
    val showDuplicateDialog: Boolean = false,
    val duplicateComicName: String = "",
    val nativeRunning: Boolean = false,
    val nativePhase: String = "",
    val nativeCurrent: Int = 0,
    val nativeTotal: Int = 0,
    val nativeMsg: String = ""
)

class JmDownloadViewModel(
    private val repository: ComicRepository
) : ViewModel() {

    private var appContext: Context? = null

    fun setAppContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private var lastDownloadUrl: String = ""
    private var lastUserAgent: String? = null
    private var lastContentDisposition: String? = null
    private var pendingZipFile: File? = null
    private var pendingZipName: String = ""
    private var hasImported = false

    var nativeDownloader: JmNativeDownloader? = null
        private set

    fun initNativeDownloader() {
        if (nativeDownloader == null) {
            nativeDownloader = JmNativeDownloader(repository).also { dl ->
                viewModelScope.launch {
                    dl.state.collect { s ->
                        _uiState.value = _uiState.value.copy(
                            nativeRunning = s.isRunning,
                            nativePhase = s.phase,
                            nativeCurrent = s.current,
                            nativeTotal = s.total,
                            nativeMsg = s.message
                        )
                    }
                }
            }
        }
    }

    fun startNativeDownload(albumId: String) {
        initNativeDownloader()
        val dl = nativeDownloader ?: return
        if (dl.state.value.isRunning) return
        val ctx = appContext ?: return
        viewModelScope.launch { dl.downloadAlbum(ctx, albumId.trim()) }
    }

    suspend fun searchJm(query: String, page: Int): Pair<Int, List<com.comicmanager.download.jm.JmSearchItem>> {
        initNativeDownloader()
        return nativeDownloader!!.search(query, page)
    }

    suspend fun enrichJmTitles(items: List<com.comicmanager.download.jm.JmSearchItem>): List<com.comicmanager.download.jm.JmSearchItem> {
        initNativeDownloader()
        return nativeDownloader!!.enrichTitles(items)
    }

    suspend fun getJmAlbumDetail(albumId: String): io.github.jukomu.jmcomic.api.model.JmAlbum {
        initNativeDownloader()
        return nativeDownloader!!.getAlbumDetail(albumId)
    }

    suspend fun getJmComments(albumId: String, page: Int): List<io.github.jukomu.jmcomic.api.model.JmComment> {
        initNativeDownloader()
        return nativeDownloader!!.getComments(albumId, page)
    }

    fun cancelNativeDownload() {
        nativeDownloader?.cancelled = true
    }

    fun updateInputUrl(url: String) {
        _uiState.value = _uiState.value.copy(inputUrl = url)
    }

    fun navigate() {
        val url = _uiState.value.inputUrl.trim()
        val finalUrl = if (android.webkit.URLUtil.isValidUrl(url)) url
        else if (url.matches(Regex("\\d+"))) "https://jmcomic3.netlify.app/photo/$url"
        else url
        _uiState.value = _uiState.value.copy(
            currentUrl = finalUrl,
            inputUrl = finalUrl,
            statusMessage = "",
            isDownloading = false
        )
    }

    fun onPageFinished(url: String, title: String) {
        _uiState.value = _uiState.value.copy(
            currentUrl = url,
            inputUrl = url,
            comicTitle = title.ifBlank { _uiState.value.comicTitle }
        )
        if (!_uiState.value.isDownloading) hasImported = false
    }

    fun onJsDownloadClickResult(result: String) {
        _uiState.value = _uiState.value.copy(statusMessage = result)
    }

    fun onJsCaptchaResult(result: String) {
        when {
            result.startsWith("SOLVED:") || result.startsWith("SOLVED_CLICK:") -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "验证码已解答，等待下载...",
                    showCaptchaHelp = false
                )
            }
            result == "NO_MATH" -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "未检测到验证码"
                )
            }
            result.startsWith("SOLVED_NO_SUBMIT:") -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "验证码已计算但无法提交，可尝试手动操作"
                )
            }
            else -> {
                _uiState.value = _uiState.value.copy(statusMessage = "验证码处理: $result")
            }
        }
    }

    fun onJsAutoCaptchaDetected(expression: String) {
        _uiState.value = _uiState.value.copy(
            captchaText = expression,
            showCaptchaHelp = true
        )
    }

    fun dismissCaptchaHelp() {
        _uiState.value = _uiState.value.copy(showCaptchaHelp = false)
    }

    fun onDownloadIntercepted(context: Context, url: String, userAgent: String?, contentDisposition: String?) {
        if (hasImported) return
        hasImported = true
        lastDownloadUrl = url
        lastUserAgent = userAgent
        lastContentDisposition = contentDisposition
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            statusMessage = "正在下载文件..."
        )
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
        downloadAndImport(context, cookies)
    }

    private fun downloadAndImport(context: Context, cookies: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = DownloadUtil.parseFileName(lastContentDisposition)
                ?: lastDownloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "jm_download.zip" }

            val downloadFile = File(context.filesDir, "downloads/$fileName")
            val result = DownloadUtil.downloadFile(
                lastDownloadUrl, lastUserAgent, _uiState.value.currentUrl, downloadFile, cookies
            )

            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(statusMessage = "下载完成，导入中...")
                doImport(context, result.getOrThrow())
            } else {
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    statusMessage = "下载失败: ${result.exceptionOrNull()?.message}，请重试"
                )
            }
        }
    }

    private suspend fun doImport(context: Context, zipFile: File) {
        try {
            val zipName = zipFile.nameWithoutExtension
                .ifBlank { _uiState.value.comicTitle.ifBlank { "JM_${System.currentTimeMillis()}" } }

            // Check duplicate
            val existing = repository.allComics
            val list = existing.first()
            if (list.any { it.name == zipName }) {
                pendingZipFile = zipFile
                pendingZipName = zipName
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    showDuplicateDialog = true,
                    duplicateComicName = zipName
                )
                return
            }

            finishImport(context, zipFile, zipName)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                statusMessage = "导入失败: ${e.message}"
            )
        }
    }

    fun overwriteDuplicate(context: Context) {
        val zipFile = pendingZipFile ?: return
        val zipName = pendingZipName
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            showDuplicateDialog = false,
            statusMessage = "覆盖导入中..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            // Delete old comic first
            val list = repository.allComics
            val old = list.first().find { it.name == zipName }
            if (old != null) {
                repository.deleteComic(old.id)
                File(old.directoryPath).deleteRecursively()
                File(old.coverPath).delete()
            }
            finishImport(context, zipFile, zipName)
        }
    }

    fun dismissDuplicateDialog() {
        pendingZipFile?.delete()
        pendingZipFile = null
        _uiState.value = _uiState.value.copy(showDuplicateDialog = false)
    }

    private suspend fun finishImport(context: Context, zipFile: File, zipName: String) {
        val result = ZipUtils.extractZip(context, Uri.fromFile(zipFile))
        if (result.success && result.imageFiles.isNotEmpty()) {
            val coverDir = File(context.filesDir, "covers")
            coverDir.mkdirs()
            val coverFile = File(coverDir, "${zipName}_cover.${result.imageFiles.first().extension}")
            result.imageFiles.first().inputStream().use { input ->
                FileOutputStream(coverFile).use { output -> input.copyTo(output) }
            }
            val comic = Comic(
                name = zipName,
                coverPath = coverFile.absolutePath,
                directoryPath = result.imageFiles.first().parentFile!!.absolutePath,
                pageCount = result.imageFiles.size
            )
            repository.insertComic(comic)
            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                statusMessage = "《$zipName》已加入书架！"
            )
            zipFile.delete()
            lastDownloadUrl = ""
        } else {
            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                statusMessage = "导入失败：ZIP 中没有图片"
            )
        }
    }

    fun retryDownload(context: Context) {
        if (lastDownloadUrl.isNotBlank()) {
            val cookies = CookieManager.getInstance().getCookie(lastDownloadUrl) ?: ""
            downloadAndImport(context, cookies)
        } else {
            _uiState.value = _uiState.value.copy(
                statusMessage = "请先触发下载再重试"
            )
        }
    }

    fun resetState() {
        lastDownloadUrl = ""
        _uiState.value = DownloadUiState(
            currentUrl = _uiState.value.currentUrl,
            inputUrl = _uiState.value.inputUrl
        )
    }

    class Factory(private val repository: ComicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JmDownloadViewModel(repository) as T
        }
    }
}
