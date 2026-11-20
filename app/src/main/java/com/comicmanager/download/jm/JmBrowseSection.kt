package com.comicmanager.download.jm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.comicmanager.R
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun coverUrlFor(albumId: String): String {
    val domains = listOf(
        "https://cdn-msp.jmapiproxy1.cc",
        "https://cdn-msp.jmapiproxy2.cc",
        "https://cdn-msp2.jmapiproxy2.cc"
    )
    val h = albumId.hashCode() and Int.MAX_VALUE
    return domains[h % domains.size] + "/media/albums/" + albumId + "_3x4.jpg"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JmBrowseSection(
    isActive: Boolean,
    onSearch: suspend (String, Int) -> Pair<Int, List<JmSearchItem>>,
    onGetDetail: suspend (String) -> JmAlbum,
    onGetPageCount: suspend (JmAlbum) -> Int?,
    onGetComments: suspend (String, Int) -> List<JmComment>,
    onEnrichTitles: suspend (List<JmSearchItem>) -> List<JmSearchItem>,
    onDownload: (String) -> Unit,
    onBatchDownload: (List<JmSearchItem>) -> Unit,
    isBusy: Boolean,
    externalOpenId: String? = null,
    externalOpenToken: Int = 0,
    onExternalConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JmSearchItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailAlbumId by remember { mutableStateOf<String?>(null) }
    var detailRequestKey by remember { mutableIntStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchFocused by remember { mutableStateOf(false) }

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
        searchFocused = false
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun openDetail(id: String) {
        dismissKeyboard()
        detailAlbumId = id
        detailRequestKey++
    }

    fun doSearch(p: Int, submitted: String) {
        // IME, hardware Enter and the button all reach this synchronous guard.
        // Pagination keeps the submitted query even if the text is edited later.
        if (loading || submitted.isBlank()) return
        loading = true
        error = null
        if (p == 1) {
            submittedQuery = submitted
            results = emptyList()
            total = 0
            hasMore = false
            clearSelection()
        }
        scope.launch {
            try {
                val response = onSearch(submitted, p)
                val previous = if (p == 1) emptyList() else results
                var merged = (previous + response.second).filter { it.id.isNotBlank() }.distinctBy { it.id }
                val blankTitles = merged.filter { it.name.isBlank() }
                if (blankTitles.isNotEmpty()) {
                    val titles = onEnrichTitles(blankTitles).associateBy { it.id }
                    merged = merged.map { titles[it.id] ?: it }
                }
                results = merged
                total = maxOf(response.first, merged.size)
                page = p
                // Stop on an empty or duplicate-only API page even when its total is stale.
                hasMore = merged.size < total && merged.size > previous.size
                if (merged.isEmpty() && p == 1) error = context.getString(R.string.no_results)
                if (p == 1 && merged.isNotEmpty()) gridState.scrollToItem(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.getString(R.string.search_failed, e.message ?: context.getString(R.string.unknown_error))
            } finally {
                loading = false
            }
        }
    }

    fun submitSearch() {
        dismissKeyboard()
        doSearch(1, query.trim())
    }

    fun handleBack() {
        when {
            detailAlbumId != null -> detailAlbumId = null
            selectionMode -> clearSelection()
            searchFocused -> dismissKeyboard()
        }
    }

    LaunchedEffect(isActive) { if (!isActive && searchFocused) dismissKeyboard() }
    BackHandler(enabled = isActive && (selectionMode || searchFocused)) { handleBack() }

    LaunchedEffect(externalOpenToken, externalOpenId) {
        if (externalOpenToken > 0 && externalOpenId != null) {
            clearSelection()
            openDetail(externalOpenId)
            onExternalConsumed()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.jm_v2_api_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (selectionMode || searchFocused) {
                        IconButton(onClick = ::handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (results.isNotEmpty()) {
                        TextButton(onClick = {
                            dismissKeyboard()
                            if (selectionMode) clearSelection() else selectionMode = true
                        }) {
                            Text(stringResource(if (selectionMode) R.string.cancel_selection else R.string.jm_v21_multi_select))
                        }
                    }
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_jm_hint), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }, onDone = { submitSearch() }),
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyUp) submitSearch()
                                true
                            } else false
                        },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::submitSearch, enabled = !loading && query.isNotBlank()) {
                    Text(stringResource(R.string.action_search))
                }
            }

            if (results.isNotEmpty()) {
                Text(
                    stringResource(R.string.jm_v21_loaded_results, results.size, total),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectionMode) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.jm_v21_selected_count, selectedIds.size),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall
                            )
                            val allLoadedSelected = results.isNotEmpty() && results.all { it.id in selectedIds }
                            TextButton(onClick = {
                                selectedIds = if (allLoadedSelected) emptySet() else results.mapTo(linkedSetOf()) { it.id }
                            }) {
                                Text(stringResource(if (allLoadedSelected) R.string.jm_v21_clear_selection else R.string.jm_v21_select_loaded))
                            }
                        }
                        Button(
                            onClick = {
                                val selectedItems = results.filter { it.id in selectedIds }
                                if (!isBusy && selectedItems.isNotEmpty()) {
                                    onBatchDownload(selectedItems)
                                    clearSelection()
                                }
                            },
                            enabled = !isBusy && selectedIds.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isBusy) stringResource(R.string.jm_v21_queue_busy)
                                else stringResource(R.string.jm_v21_download_selected, selectedIds.size)
                            )
                        }
                        if (hasMore) {
                            Text(
                                stringResource(R.string.jm_v21_selection_scope),
                                Modifier.padding(vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (loading && results.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
            error?.let {
                Text(
                    it, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(results, key = { it.id }) { item ->
                    val checked = item.id in selectedIds
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { if (selectionMode) toggleSelection(item.id) else openDetail(item.id) },
                            onLongClick = {
                                dismissKeyboard()
                                selectionMode = true
                                toggleSelection(item.id)
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = if (selectionMode && checked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box {
                            Column {
                                SubcomposeAsyncImage(
                                    model = coverUrlFor(item.id), contentDescription = item.name,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)
                                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                                    contentScale = ContentScale.Crop,
                                    loading = {
                                        Box(Modifier.fillMaxSize().background(Color.LightGray), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        }
                                    },
                                    error = {
                                        Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) {
                                            Text(stringResource(R.string.cover), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                )
                                Text(
                                    item.name.ifBlank { "JM${item.id}" },
                                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                                )
                            }
                            if (selectionMode) {
                                Checkbox(
                                    checked = checked, onCheckedChange = { toggleSelection(item.id) },
                                    modifier = Modifier.align(Alignment.TopEnd).background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(bottomStart = 8.dp)
                                    )
                                )
                            }
                        }
                    }
                }
                if (hasMore) {
                    item(span = { GridItemSpan(3) }) {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else OutlinedButton(onClick = { doSearch(page + 1, submittedQuery) }) {
                                Text(stringResource(R.string.action_load_more))
                            }
                        }
                    }
                }
            }
        }
        detailAlbumId?.let { albumId ->
            BackHandler(enabled = isActive) { detailAlbumId = null }
            key(albumId, detailRequestKey) {
                JmDetailScreen(
                    albumId = albumId, onGetDetail = onGetDetail, onGetPageCount = onGetPageCount,
                    onGetComments = onGetComments, onDownload = onDownload, isBusy = isBusy,
                    onBack = { detailAlbumId = null }
                )
            }
        }
    }
}
