# JM 一键下载功能 - 开发文档

## 1. 概述

为"漫画管理器"新增一键下载功能，用户输入 JM (禁漫天堂) 的本子 ID 或 URL，应用自动完成：**抓取元数据 → 下载图片 → 解扰（descramble）→ 打包 ZIP → 导入书架**，全程无需手动操作。

---

## 2. JM 网站技术分析

### 2.1 站点结构

JM 的每个本子有一个唯一 ID（album_id），典型 URL 格式：

```
https://18comic.vip/photo/{album_id}/
https://18comic.vip/album/{album_id}/
```

页面以服务端渲染 HTML 返回，关键数据以 JavaScript 变量的形式嵌入在页面中。

### 2.2 页面数据提取

每个本子页面的 `<script>` 标签中包含关键信息：

```javascript
var scramble_id = "220980";       // 解扰种子
var aid = "123456";               // album ID
var imglist = [                   // 图片文件名列表
    "00001.webp",
    "00002.webp",
    // ...
];
var series_name = "本子标题";      // 标题（可能在别处）
```

提取方式：用正则匹配 `<script>` 中的 `var aid`、`var imglist`、`var scramble_id` 等变量。

### 2.3 图片 CDN 地址

每张图片的真实 URL 遵循固定模式：

```
https://cdn-msp.{domain}/media/photos/{album_id}/{filename}
```

其中 `{filename}` 来自 `imglist` 数组。常见图片格式为 `.webp` 或 `.jpg`。

### 2.4 域名问题

JM 主域名会周期性变动（`18comic.vip` → `18comic.org` 等）。方案：
- 应用内置一个可更新的域名列表
- 或用用户输入的完整 URL 解析域名
- 让用户自行输入当前可用的域名

---

## 3. 图片解扰算法（核心难点）

JM 对图片做了切割重组，直接下载的图片是乱序的，必须解扰。

### 3.1 算法原理

```
输入: 一张被扰乱的图片 + scramble_id（字符串，如 "220980"）
输出: 还原后的图片

步骤:
1. 将 scramble_id 每 2 位数字拆分为一组 → [22, 09, 80]
2. 每组数字代表图片纵轴切割点的比例（百分比 × 10）
   - 22 → 切割点在图片高度的 22%
   - 09 → 切割点在图片高度的 9%
   - 80 → 切割点在图片高度的 80%
3. 按切割点将图片切成若干块（从上到下）
4. 按照特定顺序重新拼接这些块
```

### 3.2 详细流程

```
假设 scramble_id = "220980"
图片尺寸: 800 × 1200

Step 1: 切分
  切点列表: [264px, 108px, 960px]  (按高度百分比换算)
  去重并排序: [0, 108, 264, 960, 1200]
  得到 4 块: [0-108], [108-264], [264-960], [960-1200]

Step 2: 重排
  原始索引: [0, 1, 2, 3]
  重排规则: 每 2 块一组交换位置，或按照特定置换表重排
  最终顺序由 scramble_id 的值决定
```

### 3.3 简化版实现思路

多个开源 JM 下载器（Python）采用了如下简化算法，经实测准确率较高：

```kotlin
// 伪代码
fun descramble(bitmap: Bitmap, scrambleId: String): Bitmap {
    val numPieces = scrambleId.length / 2  // 切成几块
    if (numPieces <= 1) return bitmap
    
    val pieceHeight = bitmap.height / numPieces
    
    // 构建重排映射表
    val order = IntArray(numPieces) { it }
    for (i in 0 until numPieces) {
        val swapIdx = scrambleId.substring(i*2, i*2+2).toInt() % numPieces
        // 交换 order[i] 和 order[swapIdx]
        val tmp = order[i]
        order[i] = order[swapIdx]
        order[swapIdx] = tmp
    }
    
    // 按新顺序拼接
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config)
    val canvas = Canvas(result)
    for (i in 0 until numPieces) {
        val srcIdx = order[i]
        val src = Bitmap.createBitmap(bitmap, 0, srcIdx * pieceHeight, 
                                       bitmap.width, pieceHeight)
        canvas.drawBitmap(src, 0f, i * pieceHeight.toFloat(), null)
    }
    return result
}
```

> **注意**：JM 的解扰算法有多个版本（v1/v2/v3），具体实现需要根据实际的页面 JS 代码微调。建议先实现基于切分+重排的通用框架，然后通过抓取一次真实页面来校准参数。

---

## 4. 系统架构

### 4.1 新增文件清单

```
app/src/main/java/com/comicmanager/
├── download/
│   ├── JmApiClient.kt          # JM API 客户端 (HTTP + 解析)
│   ├── JmImageDescrambler.kt   # 图片解扰器
│   ├── JmDownloadManager.kt    # 下载管理器 (编排全流程)
│   └── JmDownloadState.kt      # 下载状态数据类
├── ui/
│   ├── screens/
│   │   └── DownloadScreen.kt   # 下载页面 UI
│   └── viewmodel/
│       └── DownloadViewModel.kt # 下载 ViewModel
└── util/
    └── ImageUtils.kt            # 图片工具 (Bitmap操作, 压缩)
```

### 4.2 新增依赖

在 `app/build.gradle.kts` 中新增：

```kotlin
// HTTP 客户端
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// JSON 解析
implementation("com.google.code.gson:gson:2.10.1")
```

### 4.3 数据流

```
用户输入ID/URL
    │
    ▼
DownloadViewModel.download(id)
    │
    ▼
JmApiClient.fetchComicInfo(id)       ← HTTP GET 本子页面
    │  返回: ComicInfo(title, imgList, scrambleId)
    │
    ▼
JmDownloadManager.downloadAll(info)  ← 遍历 imgList
    │
    ├─► 并发下载每张图 (OkHttp)
    │       │
    │       ▼
    │   JmImageDescrambler.descramble(bitmap, scrambleId)
    │       │
    │       ▼
    │   保存到 filesDir/downloads/{title}/{index}.webp
    │
    ▼
打包为 ZIP 文件
    │
    ▼
触发 MainViewModel.importComics(zipUri)   ← 复用现有导入逻辑
    │
    ▼
书架自动刷新
```

### 4.4 下载状态

```kotlin
data class JmDownloadState(
    val phase: Phase = Phase.IDLE,       // IDLE / FETCHING / DOWNLOADING / PACKING / DONE / ERROR
    val comicTitle: String = "",
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val currentFile: String = "",
    val errorMessage: String? = null
)

enum class Phase { IDLE, FETCHING, DOWNLOADING, PACKING, DONE, ERROR }
```

---

## 5. UI 设计

### 5.1 入口

在主页面 TopAppBar 增加一个云下载图标按钮 → 导航到 `DownloadScreen`。

### 5.2 DownloadScreen 布局

```
┌─────────────────────────────┐
│  ← 返回    JM下载            │  TopAppBar
├─────────────────────────────┤
│                              │
│  ┌──────────────────────┐   │
│  │  输入本子ID或URL       │   │  TextField
│  └──────────────────────┘   │
│                              │
│  [ 开始下载 ]               │  Button (输入非空才可点击)
│                              │
│  ── 或 ──                  │
│                              │
│  ┌──────────────────────┐   │
│  │  小说标题              │   │  下载进度卡片
│  │  ████████░░ 8/45     │   │  LinearProgressIndicator
│  │  正在下载: 003.webp  │   │
│  └──────────────────────┘   │
│                              │
└─────────────────────────────┘
```

---

## 6. 实现步骤

### Phase 1: 基础网络层

| 步骤 | 文件 | 内容 |
|------|------|------|
| 1.1 | `JmDownloadState.kt` | 定义状态数据类 |
| 1.2 | `JmApiClient.kt` | OkHttp 客户端，封装 GET 请求，解析 HTML 提取 `aid`/`imglist`/`scramble_id` |
| 1.3 | `ImageUtils.kt` | Bitmap 裁剪、拼接、保存为文件工具方法 |

### Phase 2: 解扰引擎

| 步骤 | 文件 | 内容 |
|------|------|------|
| 2.1 | `JmImageDescrambler.kt` | 实现解扰算法框架（切分→重排→拼接），预留算法版本切换 |
| 2.2 | 测试 | 用真实 JM 图片验证解扰正确性，校准参数 |

### Phase 3: 下载编排

| 步骤 | 文件 | 内容 |
|------|------|------|
| 3.1 | `JmDownloadManager.kt` | 编排下载→解扰→保存→打包 ZIP 全流程 |
| 3.2 | `DownloadViewModel.kt` | 暴露 `StateFlow<JmDownloadState>` 给 UI |

### Phase 4: UI

| 步骤 | 文件 | 内容 |
|------|------|------|
| 4.1 | `DownloadScreen.kt` | 输入框 + 进度条 UI |
| 4.2 | `NavGraph.kt` | 添加下载页路由 |
| 4.3 | `MainScreen.kt` | 添加下载入口按钮 |

---

## 7. 风险与注意事项

| 风险 | 应对策略 |
|------|---------|
| 解扰算法版本变化 | 算法框架设计为可插拔，通过 `scramble_id` 特征自动选择版本 |
| 域名被封/变更 | 提供"自定义域名"设置项，用户可手动填写当前可用域名 |
| 图片下载失败 | 单张失败不中断整体，标记跳过并继续，最终报告成功/失败数 |
| 大本子内存溢出 | 下载一张处理一张立即释放，不全部加载到内存；Bitmap 用完及时 `recycle()` |
| 网络超时 | OkHttp 设置 30s 连接超时 + 60s 读取超时，失败自动重试 2 次 |
| 法律合规 | 在下载页面添加免责声明："请仅下载您拥有合法权利的漫画内容" |

---

## 8. 新增权限（AndroidManifest）

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 9. 文件变更汇总

| 操作 | 文件 |
|------|------|
| 新增 | `download/JmApiClient.kt` |
| 新增 | `download/JmImageDescrambler.kt` |
| 新增 | `download/JmDownloadManager.kt` |
| 新增 | `download/JmDownloadState.kt` |
| 新增 | `ui/screens/DownloadScreen.kt` |
| 新增 | `ui/viewmodel/DownloadViewModel.kt` |
| 新增 | `util/ImageUtils.kt` |
| 修改 | `app/build.gradle.kts` — 添加 OkHttp + Gson 依赖 |
| 修改 | `AndroidManifest.xml` — 添加网络权限 |
| 修改 | `ui/navigation/NavGraph.kt` — 添加下载页路由 |
| 修改 | `ui/screens/MainScreen.kt` — 添加下载入口图标 |
