package com.comicmanager.download

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.statusBarsPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.comicmanager.download.jm.JmBrowseSection

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JmDownloadScreen(
    viewModel: JmDownloadViewModel,
    onBack: () -> Unit,
    externalOpenId: String? = null,
    externalOpenToken: Int = 0,
    onExternalConsumed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isWebViewReady by remember { mutableStateOf(false) }
    var manualMode by remember { mutableStateOf(false) }
    var browseMode by remember { mutableStateOf(true) }

    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { isWebViewReady = false }
            override fun onPageFinished(view: WebView, url: String) {
                isWebViewReady = true
                viewModel.onPageFinished(url, view.title ?: "")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                isWebViewReady = true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                viewModel.onPageFinished(view.url ?: "", title)
            }
        }
        wv.setDownloadListener { url, ua, cd, _, _ ->
            viewModel.onDownloadIntercepted(context, url, ua, cd)
        }
        var dx = 0f
        var dy = 0f
        wv.setOnTouchListener { _, e ->
            if (manualMode) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { dx = e.x; dy = e.y }
                    MotionEvent.ACTION_UP -> {
                        if (kotlin.math.abs(e.x - dx) < 30 && kotlin.math.abs(e.y - dy) < 30) {
                            manualMode = false
                            viewModel.onJsDownloadClickResult("手动点击已记录")
                            retryCaptcha(wv, scope, viewModel)
                        }
                    }
                }
            }
            false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = browseMode,
                    onClick = { browseMode = true },
                    label = { Text("在线浏览") }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = !browseMode,
                    onClick = { browseMode = false },
                    label = { Text("网页") }
                )
                Spacer(Modifier.width(16.dp))
                if (!browseMode) {
                    TextButton(onClick = {
                        val wv = webView
                        wv?.clearHistory()
                        wv?.clearCache(true)
                        wv?.loadUrl(state.currentUrl)
                        isWebViewReady = false
                    }) { Text("刷新") }
                }
                TextButton(onClick = onBack) { Text("返回") }
            }

            if (browseMode) {
                Box(Modifier.weight(1f)) {
                    JmBrowseSection(
                        onSearch = { q, p ->
                            viewModel.setAppContext(context)
                            viewModel.searchJm(q, p)
                        },
                        onGetDetail = { id ->
                            viewModel.setAppContext(context)
                            viewModel.getJmAlbumDetail(id)
                        },
                        onGetComments = { id, p ->
                            viewModel.setAppContext(context)
                            viewModel.getJmComments(id, p)
                        },
                        onEnrichTitles = { items ->
                            viewModel.setAppContext(context)
                            viewModel.enrichJmTitles(items)
                        },
                        onDownload = { id ->
                            viewModel.setAppContext(context)
                            viewModel.initNativeDownloader()
                            viewModel.startNativeDownload(id)
                        },
                        isBusy = state.nativeRunning,
                        externalOpenId = externalOpenId,
                        externalOpenToken = externalOpenToken,
                        onExternalConsumed = onExternalConsumed
                    )
                }
            } else {
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.allowFileAccess = false
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.databaseEnabled = true
                                webView = this
                                loadUrl(state.currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (manualMode) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFFFF6B35).copy(alpha = 0.08f)),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text(
                                "请点击页面中的下载按钮",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.75f))
                                    .padding(horizontal = 24.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        if (state.nativeRunning) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Card(Modifier.padding(32.dp).widthIn(max = 340.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(state.nativePhase, style = MaterialTheme.typography.titleMedium)
                        if (state.nativeTotal > 0) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { state.nativeCurrent.toFloat() / state.nativeTotal.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${state.nativeCurrent} / ${state.nativeTotal}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.nativeMsg.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.nativeMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.cancelNativeDownload() }) { Text("取消") }
                    }
                }
            }
        }

        if (state.statusMessage.isNotBlank() && !state.isDownloading && !state.nativeRunning) {
            Surface(
                Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 4.dp
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.statusMessage.contains("失败") || state.statusMessage.contains("重试")) {
                        TextButton(onClick = { viewModel.retryDownload(context) }) { Text("重试") }
                    }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("debug", state.statusMessage))
                        viewModel.onJsDownloadClickResult("已复制")
                    }) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { viewModel.resetState() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    if (state.showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicateDialog() },
            title = { Text("漫画已存在") },
            text = { Text("书架中已有" + state.duplicateComicName + "，是否覆盖？") },
            confirmButton = {
                TextButton(onClick = { viewModel.overwriteDuplicate(context) }) { Text("覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDuplicateDialog() }) { Text("跳过") }
            }
        )
    }

    if (state.showCaptchaHelp) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCaptchaHelp() },
            title = { Text("验证码") },
            text = { Text("检测到验证码，已尝试自动填写。如果失败请手动完成。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissCaptchaHelp()
                    webView?.evaluateJavascript(JS_SOLVE_CAPTCHA) { r ->
                        viewModel.onJsCaptchaResult(r?.trim('"') ?: "")
                    }
                }) { Text("再试一次") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCaptchaHelp() }) { Text("关闭") }
            }
        )
    }
}

private fun parseCoords(jsResult: String?): Pair<Float, Float>? {
    val raw = jsResult?.trim('"')?.trim()?.replace("\\\"", "\"") ?: return null
    if (!raw.startsWith("{")) return null
    return try {
        val obj = JSONObject(raw)
        Pair(obj.getDouble("cx").toFloat(), obj.getDouble("cy").toFloat())
    } catch (_: Exception) {
        null
    }
}

private fun dispatchTap(webView: WebView, viewX: Float, viewY: Float) {
    val t = android.os.SystemClock.uptimeMillis()
    val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, viewX, viewY, 0)
    val u = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, viewX, viewY, 0)
    webView.dispatchTouchEvent(d)
    webView.dispatchTouchEvent(u)
    d.recycle()
    u.recycle()
}

private fun retryCaptcha(webView: WebView, scope: kotlinx.coroutines.CoroutineScope, viewModel: JmDownloadViewModel) {
    scope.launch {
        var solved = false
        for (i in 1..8) {
            delay(2000)
            if (solved) break
            webView.evaluateJavascript(JS_SOLVE_CAPTCHA) { r ->
                val v = r?.trim('"') ?: ""
                if (v.contains("SOLVED") || v != "NO_MATH") {
                    solved = true
                    viewModel.onJsCaptchaResult(v)
                }
                if (i == 8 && !solved) {
                    viewModel.onJsCaptchaResult("验证码超时，请手动操作")
                }
            }
        }
    }
}
