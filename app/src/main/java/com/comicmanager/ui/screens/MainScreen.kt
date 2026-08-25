package com.comicmanager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.comicmanager.model.Comic
import com.comicmanager.ui.viewmodel.MainViewModel
import com.comicmanager.ui.viewmodel.SortMode
import java.io.File

private enum class ComicActionDialogMode {
    ACTIONS,
    BIND_JM_ID,
    DELETE_CONFIRMATION
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onComicClick: (Long) -> Unit,
    onHistoryClick: () -> Unit,
    onJmDownloadClick: () -> Unit,
    onJmDetail: (String) -> Unit = {},
    onSelectModeChange: (Boolean) -> Unit = {}
) {
    val allComics by viewModel.allComics.collectAsState()
    val displayComics by viewModel.displayComics.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val currentSortMode by viewModel.sortMode.collectAsState()
    val selectMode by viewModel.selectMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val bulkProgress by viewModel.bulkProgress.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var showActionMenu by remember { mutableStateOf<Comic?>(null) }
    var actionDialogMode by remember { mutableStateOf(ComicActionDialogMode.ACTIONS) }
    var bindText by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val duplicateJmIds = remember(allComics) {
        allComics
            .map { it.jmId.trim() }
            .filter { it.toLongOrNull()?.let { value -> value > 0 } == true }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) { viewModel.importComics(context, uris) }
    }

    LaunchedEffect(selectMode) { onSelectModeChange(selectMode) }

    LaunchedEffect(importState.importMessage) {
        importState.importMessage?.let { message ->
            snackbarHostState.showSnackbar(message = message)
            viewModel.clearImportMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text("搜索漫画名称...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                focusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedTextColor = MaterialTheme.colorScheme.onPrimary,
                                cursorColor = MaterialTheme.colorScheme.onPrimary,
                                focusedIndicatorColor = MaterialTheme.colorScheme.onPrimary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("漫画管理器")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSearch() }) {
                        Icon(
                            if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (isSearchActive) "关闭搜索" else "搜索",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "排序", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(mode.label)
                                            if (mode == currentSortMode) { Spacer(Modifier.width(8.dp)); Text("✓", color = MaterialTheme.colorScheme.primary) }
                                        }
                                    },
                                    onClick = { viewModel.setSortMode(mode); showSortMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (selectMode) "取消选择" else "选择漫画") },
                                onClick = { showSortMenu = false; viewModel.toggleSelectMode() }
                            )
                            DropdownMenuItem(
                                text = { Text("批量导入ID压缩包") },
                                onClick = { showSortMenu = false; zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) }
                            )
                            DropdownMenuItem(
                                text = { Text("删除重复漫画", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showSortMenu = false
                                    val count = viewModel.deleteDuplicates()
                                    scope.launch { snackbarHostState.showSnackbar(message = "已删除 $count 本重复漫画") }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出全部") },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.exportAll(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("按ZIP编号批量重命名") },
                                onClick = {
                                    showSortMenu = false
                                    viewModel.renameNumericComics { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                                }
                            )
                        }
                    }
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Filled.DateRange, contentDescription = "阅读历史", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = onJmDownloadClick) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "JM下载", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        if (importState.isImporting) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("正在导入漫画...")
                }
            }
        } else if (displayComics.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    if (isSearchActive) "没有匹配的漫画" else "还没有漫画\n点排序→导入漫画",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            var isRefreshing by remember { mutableStateOf(false) }
            val pullRefreshState = rememberPullRefreshState(isRefreshing, { scope.launch { isRefreshing = true; delay(500); isRefreshing = false } })

            Column(Modifier.fillMaxSize().padding(paddingValues)) {
                Box(Modifier.weight(1f).then(if (selectMode) Modifier else Modifier.pullRefresh(pullRefreshState))) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayComics, key = { it.id }) { comic ->
                            ComicGridItem(
                                comic = comic,
                                isSelected = comic.id in selectedIds,
                                selectMode = selectMode,
                                onClick = { if (selectMode) viewModel.toggleSelection(comic.id) else onComicClick(comic.id) },
                                onLongClick = {
                                    showActionMenu = comic
                                    actionDialogMode = ComicActionDialogMode.ACTIONS
                                    bindText = comic.jmId.trim()
                                }
                            )
                        }
                    }
                    PullRefreshIndicator(isRefreshing, pullRefreshState, Modifier.align(Alignment.TopCenter))
                }
                if (selectMode) {
                    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 8.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { viewModel.toggleSelectMode() }) { Text("取消", color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = {
                                if (selectedIds.size == displayComics.size) viewModel.clearSelection() else viewModel.selectAll()
                            }) { Text(if (selectedIds.size == displayComics.size) "反选" else "全选") }
                            Text("已选 ${selectedIds.size}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            TextButton(onClick = { viewModel.exportSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } } }) { Text("导出") }
                            TextButton(onClick = { viewModel.shareSelected(context) }) { Text("分享") }
                            TextButton(onClick = {
                                viewModel.deleteSelected(context) { msg -> scope.launch { snackbarHostState.showSnackbar(message = msg) } }
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }

        if (bulkProgress.isRunning) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Card(modifier = Modifier.padding(32.dp).widthIn(max = 300.dp)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text("正在${bulkProgress.label}...", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { bulkProgress.current.toFloat() / bulkProgress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${bulkProgress.current} / ${bulkProgress.total}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    showActionMenu?.let { comic ->
        val currentJmId = comic.jmId.trim()
            .takeIf { it.toLongOrNull()?.let { value -> value > 0 } == true }
        val hasDuplicateJmId = currentJmId != null && currentJmId in duplicateJmIds
        val enteredJmId = bindText.trim()
        val enteredJmIdIsValid = enteredJmId.toLongOrNull()?.let { it > 0 } == true
        val conflictingComic = if (enteredJmIdIsValid) {
            allComics.firstOrNull {
                it.id != comic.id && it.jmId.trim() == enteredJmId
            }
        } else {
            null
        }

        fun closeActionDialog() {
            showActionMenu = null
            actionDialogMode = ComicActionDialogMode.ACTIONS
            bindText = ""
        }

        AlertDialog(
            onDismissRequest = { closeActionDialog() },
            title = {
                Text(
                    when (actionDialogMode) {
                        ComicActionDialogMode.ACTIONS -> comic.name
                        ComicActionDialogMode.BIND_JM_ID -> "绑定JM编号"
                        ComicActionDialogMode.DELETE_CONFIRMATION -> "确认删除"
                    },
                    maxLines = 4
                )
            },
            text = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        Column {
                            Text("选择操作", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                currentJmId?.let { "当前JM编号：$it" } ?: "当前未绑定JM编号",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasDuplicateJmId) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "该编号同时绑定了其他漫画，请先核对并修改。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = {
                                bindText = currentJmId.orEmpty()
                                actionDialogMode = ComicActionDialogMode.BIND_JM_ID
                            }) {
                                Text(if (currentJmId == null) "绑定JM编号" else "修改JM编号")
                            }
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        Column {
                            Text(
                                if (hasDuplicateJmId) {
                                    "当前编号 $currentJmId 与其他漫画重复，请输入正确的JM作品编号："
                                } else {
                                    "请输入正确的JM作品编号："
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = bindText,
                                onValueChange = { bindText = it.filter(Char::isDigit) },
                                placeholder = { Text("输入编号，如 1037526") },
                                singleLine = true
                            )
                            if (conflictingComic != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "该编号已绑定到《${conflictingComic.name}》",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        Text("确定要删除 \"${comic.name}\" 吗？此操作不可撤销。")
                    }
                }
            },
            confirmButton = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        TextButton(onClick = {
                            android.util.Log.d(
                                "JmLink",
                                "书架详情 comicId=${comic.id} jmId='${comic.jmId}' duplicate=$hasDuplicateJmId"
                            )
                            if (currentJmId != null && !hasDuplicateJmId) {
                                closeActionDialog()
                                onJmDetail(currentJmId)
                            } else {
                                bindText = if (hasDuplicateJmId) "" else currentJmId.orEmpty()
                                actionDialogMode = ComicActionDialogMode.BIND_JM_ID
                            }
                        }) {
                            Text("进入JM详情")
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        TextButton(
                            enabled = enteredJmIdIsValid && conflictingComic == null,
                            onClick = {
                                android.util.Log.d(
                                    "JmLink",
                                    "手动绑定 comicId=${comic.id} jmId=$enteredJmId"
                                )
                                viewModel.bindJmId(comic, enteredJmId)
                                closeActionDialog()
                                onJmDetail(enteredJmId)
                            }
                        ) {
                            Text("绑定并打开")
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        TextButton(
                            onClick = {
                                viewModel.deleteComic(comic)
                                closeActionDialog()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("删除")
                        }
                    }
                }
            },
            dismissButton = {
                when (actionDialogMode) {
                    ComicActionDialogMode.ACTIONS -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.DELETE_CONFIRMATION
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    ComicActionDialogMode.BIND_JM_ID -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.ACTIONS
                        }) {
                            Text("返回")
                        }
                    }

                    ComicActionDialogMode.DELETE_CONFIRMATION -> {
                        TextButton(onClick = {
                            actionDialogMode = ComicActionDialogMode.ACTIONS
                        }) {
                            Text("取消")
                        }
                    }
                }
            }
        )
    }
}

private fun resolveCoverPath(comic: Comic): String {
    val f = File(comic.coverPath)
    if (f.exists()) return comic.coverPath
    val dir = File(comic.directoryPath)
    if (dir.isDirectory) {
        val first = dir.listFiles()?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif") }?.minByOrNull { it.name }
        if (first != null) return first.absolutePath
    }
    return comic.coverPath
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicGridItem(comic: Comic, isSelected: Boolean, selectMode: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = if (selectMode) null else onLongClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box {
            val coverPath = resolveCoverPath(comic)
            Column {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(coverPath).size(360, 512).crossfade(false).build(),
                    contentDescription = comic.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    contentScale = ContentScale.Crop,
                    loading = { Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) } },
                    error = { Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) { Text("📖", style = MaterialTheme.typography.headlineMedium) } }
                )
                Text(comic.name, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (selectMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp))
            }
        }
    }
}
