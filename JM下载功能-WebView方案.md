# JM 一键下载 - WebView 自动化方案 (v3)

## 核心理念

不逆推 JM API、不手动解扰图片。**在 WebView 中加载 JM 网页，用 JS 脚本自动点击下载按钮、解数学验证码、拦截下载文件**。网站自己负责图片渲染和 ZIP 打包，App 只管自动化操作和接收文件。

---

## 完整流程

```
用户浏览 JM 网页 (WebView)
    │
    ▼ 用户点 "一键下载" 或 App 自动触发
注入 JS 脚本
    │
    ├─► 1. 找到页面中的"下载"按钮 → .click()
    │
    ▼  弹出数学验证码弹窗 (e.g. "3 + 5 = ?")
    │
    ├─► 2. 读取算式文本 → 用正则提取数字 → 计算答案
    ├─► 3. 填入答案到输入框
    ├─► 4. 点击确认/提交按钮
    │
    ▼  网站开始生成 ZIP 并触发浏览器下载
    │
    ├─► 5. WebView.setDownloadListener 拦截下载
    │       → 获取下载 URL
    │       → App 用 OkHttp 下载文件到本地
    │       → 解压 ZIP → 调用 importComics()
    │
    ▼  书架自动刷新 ✓
```

---

## 关键技术点

### 1. 验证码自动破解

JM 的验证码是**纯数字加法**（非图片验证码），直接读取文本计算即可：

```kotlin
// 注入的 JS 脚本
val CAPTCHA_SOLVE_SCRIPT = """
(function() {
    // 1. 找到算式文本 (尝试多种可能的选择器)
    var captchaEl = document.querySelector('.captcha-question') ||
                    document.querySelector('#captcha-text') ||
                    document.querySelector('[class*="math"]') ||
                    document.querySelector('[class*="captcha"] span');
    if (!captchaEl) return 'NO_CAPTCHA';
    
    var text = captchaEl.textContent || captchaEl.innerText;
    
    // 2. 正则提取 "数字 + 数字"
    var match = text.match(/(\d+)\s*\+\s*(\d+)/);
    if (!match) return 'CANNOT_PARSE';
    
    var answer = parseInt(match[1]) + parseInt(match[2]);
    
    // 3. 填入答案输入框
    var input = document.querySelector('input[type="text"]') ||
                document.querySelector('input[type="number"]') ||
                document.querySelector('.captcha-input') ||
                document.querySelector('input');
    if (input) {
        input.value = answer;
        input.dispatchEvent(new Event('input', { bubbles: true }));
    }
    
    // 4. 点击提交按钮
    var submit = document.querySelector('.captcha-submit') ||
                 document.querySelector('button[type="submit"]') ||
                 document.querySelector('.confirm-btn') ||
                 document.querySelector('button:last-of-type');
    if (submit) {
        submit.click();
    }
    
    return 'OK_' + answer;
})();
"""
```

### 2. 下载按钮点击

```kotlin
val CLICK_DOWNLOAD_SCRIPT = """
(function() {
    // 尝试多种选择器找到下载按钮
    var btns = document.querySelectorAll('button, a, .btn, [class*="download"]');
    for (var i = 0; i < btns.length; i++) {
        var text = btns[i].textContent || '';
        if (text.match(/下载|download|下載/i)) {
            btns[i].click();
            return 'CLICKED';
        }
    }
    return 'NOT_FOUND';
})();
"""
```

### 3. 拦截 WebView 下载

```kotlin
webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
    // url = 下载链接 (可能是 .zip 直链)
    // App 接管下载，不交给系统浏览器
    downloadManager.download(url, userAgent, contentDisposition)
}
```

当网站触发下载（`<a download>` 或 `Content-Disposition` 响应头），Android WebView 默认会交给系统 DownloadManager 处理。通过 `setDownloadListener` 截获，改为 App 自己下载。

### 4. App 接收并处理文件

```kotlin
// DownloadManager.kt 中
suspend fun download(url: String, userAgent: String?, contentDisposition: String?): File {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url(url)
        .addHeader("User-Agent", userAgent ?: "Mozilla/5.0")
        .addHeader("Referer", currentPageUrl)  // 关键：带上 Referer
        .build()
    
    val response = client.newCall(request).execute()
    
    // 从 Content-Disposition 提取文件名
    val fileName = parseFileName(contentDisposition) ?: "download.zip"
    val outputFile = File(filesDir, "downloads/$fileName")
    outputFile.parentFile?.mkdirs()
    
    response.body?.byteStream()?.use { input ->
        FileOutputStream(outputFile).use { output ->
            input.copyTo(output)
        }
    }
    
    return outputFile
}
```

---

## 文件清单

```
app/src/main/java/com/comicmanager/
├── download/
│   ├── JmDownloadScreen.kt          # WebView + 浮动按钮 UI
│   ├── JmDownloadViewModel.kt       # 下载状态管理
│   ├── JmWebViewManager.kt          # WebView 配置 + JS 注入 + DownloadListener
│   └── JmScripts.kt                 # JS 脚本常量 (验证码破解、按钮点击)
└── util/
    └── DownloadUtil.kt              # OkHttp 文件下载 + 文件名解析
```

### 需要修改的文件

| 文件 | 改动 |
|------|------|
| `app/build.gradle.kts` | 添加 `okhttp:4.12.0` 依赖 |
| `AndroidManifest.xml` | 添加 `INTERNET` + `ACCESS_NETWORK_STATE` 权限 |
| `NavGraph.kt` | 添加 `download` 路由 |
| `MainScreen.kt` | TopAppBar 添加下载入口图标 |

### 依赖

仅需新增 OkHttp（轻量 HTTP 客户端，比系统 `HttpURLConnection` 更好用）：

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

不需要 Gson、不需要任何其他库。

---

## UI 设计

```
┌──────────────────────────────────┐
│  ← 返回  JM下载                   │
├──────────────────────────────────┤
│                                  │
│  ┌────────────────────────┐     │
│  │ 输入 JM 本子 URL        │     │ 地址栏
│  │ https://...            │     │
│  │              [ 前往 ]   │     │
│  └────────────────────────┘     │
│                                  │
│  ┌────────────────────────────┐  │
│  │                            │  │
│  │   WebView                  │  │ 正常浏览 JM 网页
│  │   (JM 漫画内容)             │  │
│  │                            │  │
│  │                      ┌───┐ │  │
│  │                      │ ⬇ │ │  │ 浮动下载按钮
│  │                      └───┘ │  │ (Compose Overlay)
│  └────────────────────────────┘  │
│                                  │
│  状态: ████████░░ 下载中 8/45    │  进度条
└──────────────────────────────────┘
```

浮动按钮逻辑：
- 用户浏览到漫画内容页 → App 检测到 `imglist` 存在（注入 JS 探测）→ 显示浮动按钮
- 用户点击按钮 → 触发下载自动化流程
- 如果验证码自动破解失败 → 弹出 Toast "请手动完成验证码" → 用户手动输入后继续

---

## 异常处理

| 场景 | 策略 |
|------|------|
| 找不到下载按钮 | 提示用户："请手动点击页面上的下载按钮" |
| 验证码不是加法 | 提示用户手动输入：弹出对话框显示验证码算式，用户输入答案 |
| 下载链接失效 | 重新触发下载流程，最多重试 2 次 |
| WebView 报错 (无网络等) | 显示标准错误页面，提供重试按钮 |
| ZIP 内无图片 | 提示 "下载的文件中没有图片" |
| 同名本子已存在 | 提示 "该本子已在书架中" |

---

## 优势总结

| 传统 API 方案 | 本方案 |
|-------------|--------|
| 需要逆推 JM API | 不需要 |
| 需要实现图片解扰 | 不需要（网站自己解） |
| API 一变就失效 | 只要网页能打开就能用 |
| 需要研究 scramble_id 算法 | 不需要 |
| 只能用于 JM | 稍加修改就能兼容其他漫画站 |
