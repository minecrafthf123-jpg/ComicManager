# JM 一键下载功能 - 方案分析与修订

## 用户原始想法

> 用户打开 JM 软件 → 漫画管理器对 JM Comic 进行界面抓取 → 识别界面自动进行脚本操作

## 可行性分析

### 为什么"操控其他App"方案行不通

| 障碍 | 详情 |
|------|------|
| **Android 安全沙箱** | Android 禁止 App 之间直接交互，普通 App 无法读取另一个 App 的界面内容 |
| **AccessibilityService** | 技术上可行，但需要用户去系统设置里手动开启无障碍权限，Google Play 严格审查（非无障碍用途会被拒） |
| **JM App 反制** | JM 官方 App 大概率有反辅助检测，检测到无障碍服务运行时直接拒绝交互 |
| **维护成本极高** | JM App 每次更新 UI 变动就会导致脚本失效，无法保证长期可用 |
| **无法上架** | 利用无障碍服务做自动化下载，Google Play 直接拒绝 |

**结论：直接操控 JM App 这条路在 Android 生态下基本走不通。**

---

## 推荐方案：内置 WebView + JavaScript 注入

换一个思路：**不操控别人的 App，而是把自己变成浏览器**。

### 原理

```
┌─────────────────────────────────────────┐
│  漫画管理器 App                           │
│  ┌──────────────────────────────────┐   │
│  │  WebView (内置浏览器)              │   │
│  │                                  │   │
│  │  JM 网页正常加载、正常渲染          │   │
│  │  JM 自己的 JS 已经把图解扰了        │   │
│  │                                  │   │
│  │  ★ 我们注入一段 JS：              │   │
│  │    从 DOM 里抓取已经渲染好的图      │   │
│  │    → Canvas → Base64 → 传给 App  │   │
│  └──────────────────────────────────┘   │
│                  ↓                      │
│          App 收到 Base64 图片             │
│          → 保存到磁盘 → 打包 ZIP         │
│          → 导入书架                      │
└─────────────────────────────────────────┘
```

### 核心优势

- **不需要理解 JM 的解扰算法** — JM 网页自己会解扰，我们只需要从 DOM 里把渲染好的图抓出来
- **不需要模拟点击** — JavaScript 可以直接读取页面数据
- **不受 JM App 更新影响** — 网页结构比 App 结构更稳定
- **用户全程在 App 内操作** — 不需要安装其他 App，不需要任何系统权限
- **天然跨站** — 将来也可以兼容其他漫画站点

---

## 技术实现

### 新文件清单

```
app/src/main/java/com/comicmanager/
├── download/
│   ├── JmWebViewScreen.kt        # WebView 下载页面
│   ├── JmWebViewViewModel.kt     # WebView 状态管理
│   └── JsDownloadInterface.kt    # JS ↔ Kotlin 通信桥梁
└── util/
    └── ImageSaver.kt             # Base64 → 文件保存工具
```

### 核心代码预览

#### 1. JS 注入脚本 (String 常量)

```kotlin
// 注入到 JM 页面中，抓取当前页面所有漫画图片
val EXTRACT_SCRIPT = """
(function() {
    var images = document.querySelectorAll('.comicpage img, img.lazy, img[data-src]');
    var pages = [];
    images.forEach(function(img, i) {
        var src = img.src || img.getAttribute('data-src') || img.getAttribute('data-original');
        if (src && src.match(/\.(jpg|png|webp|jpeg)/i)) {
            pages.push({ index: i, url: src });
        }
    });
    return JSON.stringify(pages);
})();
"""
```

#### 2. JS ↔ Kotlin 通信桥梁

```kotlin
class JsDownloadInterface(
    private val onImageReceived: (index: Int, base64: String) -> Unit,
    private val onTitleFound: (title: String) -> Unit
) {
    @JavascriptInterface
    fun onImageData(index: Int, base64Data: String) {
        onImageReceived(index, base64Data)
    }

    @JavascriptInterface
    fun onComicTitle(title: String) {
        onTitleFound(title)
    }

    @JavascriptInterface
    fun onProgress(current: Int, total: Int) {
        // 更新下载进度
    }
}
```

#### 3. WebView 注入时机

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) {
        // 页面加载完成后，注入提取脚本
        webView.evaluateJavascript(EXTRACT_SCRIPT) { jsonResult ->
            val pages = parseImageList(jsonResult)
            // 对每张图片，再次注入脚本将其绘制到 Canvas 取出 Base64
            downloadAllPages(pages)
        }
    }
}
```

#### 4. 从 DOM 中取出已渲染图片

```kotlin
val CAPTURE_SINGLE_SCRIPT = """
(function() {
    var img = document.querySelector('img[src="%s"]');
    if (!img) return '';
    var canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    canvas.getContext('2d').drawImage(img, 0, 0);
    return canvas.toDataURL('image/jpeg', 0.9);
})();
"""

// 调用
webView.evaluateJavascript(
    CAPTURE_SINGLE_SCRIPT.format(imageUrl)
) { base64 ->
    // 去掉 "data:image/jpeg;base64," 前缀
    // decode → Bitmap → 保存为文件
    ImageSaver.saveBase64(context, base64, fileName)
}
```

---

## 流程图

```
用户输入 JM 本子 URL
    │
    ▼
WebView 加载页面
    │
    ▼ (onPageFinished)
注入 JS：提取 imglist + 标题
    │
    ▼
对每张图：注入 JS 将 <img> 绘制到 Canvas → 导出 Base64
    │
    ▼
Kotlin 收到 Base64 → decode Bitmap → 保存到磁盘
    │
    ▼ (全部完成)
打包 ZIP → 调用 importComics(zipUri)
    │
    ▼
书架自动刷新 ✓
```

---

## 与上版方案对比

| 维度 | API 方案 (上版) | WebView 方案 (本版) |
|------|----------------|---------------------|
| 解扰 | 需要自己实现，算法可能过期 | 不需要 — JM 自己的 JS 已经解了 |
| 稳定性 | 依赖 API 不变化 | 只要网页能打开就能抓 |
| 实现难度 | 高（解扰算法是核心难点） | 中（WebView + JS 注入是标准技术） |
| 可扩展性 | 只能用于 JM | 任何漫画网站都可用同类思路 |
| App 体积 | 小（仅 HTTP 库） | 中（WebView 是系统组件，不占体积） |

---

## 用户操作流程（最终效果）

```
1. 用户在主页面点击 [JM下载]
2. 进入下载页面，看到一个地址栏 + 内置浏览器
3. 用户可以:
   a. 手动输入 JM 本子 URL
   b. 在 WebView 中随意浏览，找到想下载的本子
4. 当页面加载到本子内容页时，App 自动弹窗:
   "检测到漫画「○○○○○」，共 45 页，确认下载？"
5. 用户点 [确认] → 全自动下载 → 导入书架
```

---

## 权限需求

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- WebView 不需要额外权限 -->
```

比 API 方案更简单，不需要任何特殊权限。

---

## 实施步骤

| 步骤 | 内容 |
|------|------|
| 1 | 创建 `JsDownloadInterface.kt` — JS 桥接层 |
| 2 | 创建 `ImageSaver.kt` — Base64 解码保存 |
| 3 | 创建 `JmWebViewScreen.kt` — WebView + 控制 UI |
| 4 | 创建 `JmWebViewViewModel.kt` — 状态管理 |
| 5 | 修改 `NavGraph.kt` — 添加下载页路由 |
| 6 | 修改 `MainScreen.kt` — 添加下载入口图标 |
