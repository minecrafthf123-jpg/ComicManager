package com.comicmanager.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.model.Comic
import com.comicmanager.download.jm.JmNativeDownloadResult
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
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadUiState(
    val currentUrl: String = "https://18comic.vip/",
    val inputUrl: String = "https://18comic.vip/",
    val isDownloading: Boolean = false,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val statusMessage: String = "",
    val canRetry: Boolean = false,
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

    private enum class RetryTarget { WEB, NATIVE }

    private var appContext: Context? = null
    private var localeTag: String = ""

    fun setAppContext(context: Context) {
        val localizedContext = context.applicationContext.createConfigurationContext(
            context.resources.configuration
        )
        val nextLocaleTag = localizedContext.resources.configuration.locales[0].toLanguageTag()
        val localeChanged = localeTag.isNotEmpty() && localeTag != nextLocaleTag
        appContext = localizedContext
        localeTag = nextLocaleTag

        // Status strings are resolved when an event occurs. Clear an inactive,
        // already-rendered message after a locale switch so it cannot remain in
        // the previous language when this screen is opened again.
        if (
            localeChanged &&
            !downloadInFlight.get() &&
            !_uiState.value.isDownloading &&
            !_uiState.value.nativeRunning
        ) {
            retryTarget = null
            _uiState.value = _uiState.value.copy(
                statusMessage = "",
                canRetry = false,
                nativePhase = "",
                nativeMsg = ""
            )
        }
    }

    private fun text(resId: Int, vararg args: Any): String =
        appContext?.getString(resId, *args).orEmpty()

    private fun quantityText(resId: Int, count: Int, vararg args: Any): String =
        appContext?.resources?.getQuantityString(resId, count, *args).orEmpty()

    private val _uiState = MutableStateFlow(DownloadUiState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private var lastDownloadUrl: String = ""
    private var lastUserAgent: String? = null
    private var lastContentDisposition: String? = null
    private var pendingZipFile: File? = null
    private var pendingZipName: String = ""
    private var hasImported = false
    private val downloadInFlight = AtomicBoolean(false)
    private var retryTarget: RetryTarget? = null
    private var lastNativeAlbumId: String = ""

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
        val ctx = appContext ?: return
        val normalizedId = albumId.trim()
        if (normalizedId.isBlank() || !downloadInFlight.compareAndSet(false, true)) return

        lastNativeAlbumId = normalizedId
        retryTarget = RetryTarget.NATIVE
        _uiState.value = _uiState.value.copy(statusMessage = "", canRetry = false)
        viewModelScope.launch {
            try {
                val result = dl.downloadAlbum(ctx, normalizedId)
                _uiState.value = _uiState.value.copy(
                    statusMessage = result.fold(
                        onSuccess = { outcome: JmNativeDownloadResult ->
                            quantityText(
                                R.plurals.native_added_pages,
                                outcome.pageCount,
                                outcome.comicName,
                                outcome.pageCount
                            )
                        },
                        onFailure = { text(R.string.status_download_failed_retry, it.message.orEmpty()) }
                    ),
                    canRetry = result.isFailure
                )
                retryTarget = if (result.isSuccess) null else RetryTarget.NATIVE
            } finally {
                downloadInFlight.set(false)
            }
        }
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
            canRetry = false,
            isDownloading = false
        )
        retryTarget = null
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
        _uiState.value = _uiState.value.copy(statusMessage = result, canRetry = false)
    }

    fun onJsCaptchaResult(result: String) {
        when {
            result.startsWith("SOLVED:") || result.startsWith("SOLVED_CLICK:") -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = text(R.string.status_captcha_solved),
                    canRetry = false,
                    showCaptchaHelp = false
                )
            }
            result == "NO_MATH" -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = text(R.string.status_captcha_not_found),
                    canRetry = false
                )
            }
            result.startsWith("SOLVED_NO_SUBMIT:") -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = text(R.string.status_captcha_no_submit),
                    canRetry = false
                )
            }
            else -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = text(R.string.status_captcha_processing, result),
                    canRetry = false
                )
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
        lastDownloadUrl = url
        lastUserAgent = userAgent
        lastContentDisposition = contentDisposition
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
        if (downloadAndImport(context, cookies)) hasImported = true
    }

    private fun downloadAndImport(context: Context, cookies: String): Boolean {
        if (!downloadInFlight.compareAndSet(false, true)) return false

        retryTarget = RetryTarget.WEB
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            statusMessage = text(R.string.status_downloading_file),
            canRetry = false
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = DownloadUtil.parseFileName(lastContentDisposition)
                    ?: lastDownloadUrl.substringAfterLast('/').substringBefore('?').ifBlank { "jm_download.zip" }

                val downloadFile = File(context.filesDir, "downloads/$fileName")
                val result = DownloadUtil.downloadFile(
                    lastDownloadUrl, lastUserAgent, _uiState.value.currentUrl, downloadFile, cookies
                )

                if (result.isSuccess) {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = text(R.string.status_download_complete_importing),
                        canRetry = false
                    )
                    doImport(context, result.getOrThrow())
                } else {
                    retryTarget = RetryTarget.WEB
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        statusMessage = text(
                            R.string.status_download_failed_retry,
                            result.exceptionOrNull()?.message.orEmpty()
                        ),
                        canRetry = true
                    )
                }
            } catch (e: Exception) {
                retryTarget = RetryTarget.WEB
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    statusMessage = text(
                        R.string.status_download_failed_retry,
                        e.message.orEmpty()
                    ),
                    canRetry = true
                )
            } finally {
                downloadInFlight.set(false)
            }
        }
        return true
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
                statusMessage = text(R.string.status_import_failed, e.message.orEmpty()),
                canRetry = true
            )
        }
    }

    fun overwriteDuplicate(context: Context) {
        val zipFile = pendingZipFile ?: return
        val zipName = pendingZipName
        if (!downloadInFlight.compareAndSet(false, true)) return
        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            showDuplicateDialog = false,
            statusMessage = text(R.string.status_overwrite_importing),
            canRetry = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete old comic first
                val list = repository.allComics
                val old = list.first().find { it.name == zipName }
                if (old != null) {
                    repository.deleteComic(old.id)
                    File(old.directoryPath).deleteRecursively()
                    File(old.coverPath).delete()
                }
                finishImport(context, zipFile, zipName)
            } catch (e: Exception) {
                retryTarget = RetryTarget.WEB
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    statusMessage = text(R.string.status_import_failed, e.message.orEmpty()),
                    canRetry = true
                )
            } finally {
                downloadInFlight.set(false)
            }
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
                statusMessage = text(R.string.status_added_to_library, zipName),
                canRetry = false
            )
            zipFile.delete()
            lastDownloadUrl = ""
            retryTarget = null
        } else {
            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                statusMessage = text(R.string.status_zip_has_no_images),
                canRetry = true
            )
        }
    }

    fun retryDownload(context: Context) {
        when (retryTarget) {
            RetryTarget.NATIVE -> startNativeDownload(lastNativeAlbumId)
            RetryTarget.WEB -> {
                if (lastDownloadUrl.isNotBlank()) {
                    val cookies = CookieManager.getInstance().getCookie(lastDownloadUrl) ?: ""
                    downloadAndImport(context, cookies)
                } else {
                    retryTarget = null
                    _uiState.value = _uiState.value.copy(
                        statusMessage = text(R.string.status_trigger_download_first),
                        canRetry = false
                    )
                }
            }
            null -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = text(R.string.status_trigger_download_first),
                    canRetry = false
                )
            }
        }
    }

    fun resetState() {
        lastDownloadUrl = ""
        lastNativeAlbumId = ""
        retryTarget = null
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
