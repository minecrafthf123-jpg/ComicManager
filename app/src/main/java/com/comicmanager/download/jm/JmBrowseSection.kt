package com.comicmanager.download.jm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.comicmanager.R
import com.comicmanager.ui.theme.pressScale
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
    val searchInteraction = remember { MutableInteractionSource() }
    val downloadInteraction = remember { MutableInteractionSource() }
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
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val allLoadedSelected = results.isNotEmpty() && results.all { it.id in selectedIds }
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) stringResource(R.string.jm_v21_selected_count, selectedIds.size)
                        else stringResource(R.string.jm_v2_api_search),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (selectionMode || searchFocused) {
                        IconButton(onClick = ::handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = {
                            selectedIds = if (allLoadedSelected) emptySet() else results.mapTo(linkedSetOf()) { it.id }
                        }) {
                            Text(stringResource(if (allLoadedSelected) R.string.jm_v21_clear_selection else R.string.jm_v21_select_loaded))
                        }
                    } else if (results.isNotEmpty()) {
                        TextButton(onClick = {
                            dismissKeyboard()
                            selectionMode = true
                        }) {
                            Text(stringResource(R.string.jm_v21_multi_select))
                        }
                    }
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_jm_hint), maxLines = 1) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, stringResource(R.string.jm_clear_query))
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }, onDone = { submitSearch() }),
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyUp) submitSearch()
                                true
                            } else false
                        }
                )
                FilledIconButton(
                    onClick = ::submitSearch,
                    enabled = !loading && query.isNotBlank(),
                    interactionSource = searchInteraction,
                    modifier = Modifier.size(48.dp).pressScale(searchInteraction)
                ) {
                    Icon(painterResource(R.drawable.ic_material_search), stringResource(R.string.action_search))
                }
            }

            if (results.isNotEmpty()) {
                Text(
                    stringResource(R.string.jm_v21_loaded_results, results.size, total),
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectionMode) {
                Surface(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Button(
                            onClick = {
                                val selectedItems = results.filter { it.id in selectedIds }
                                if (!isBusy && selectedItems.isNotEmpty()) {
                                    onBatchDownload(selectedItems)
                                    clearSelection()
                                }
                            },
                            enabled = !isBusy && selectedIds.isNotEmpty(),
                            interactionSource = downloadInteraction,
                            modifier = Modifier.fillMaxWidth().pressScale(downloadInteraction)
                        ) {
                            Icon(painterResource(R.drawable.ic_material_download), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
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
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    error?.takeIf { results.isNotEmpty() }?.let { message ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(results, key = { it.id }) { item ->
                        val checked = item.id in selectedIds
                        JmSearchCard(
                            item = item,
                            selectionMode = selectionMode,
                            checked = checked,
                            onClick = { if (selectionMode) toggleSelection(item.id) else openDetail(item.id) },
                            onLongClick = {
                                dismissKeyboard()
                                selectionMode = true
                                toggleSelection(item.id)
                            },
                            onCheckedChange = { toggleSelection(item.id) }
                        )
                    }
                    if (hasMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else OutlinedButton(onClick = { doSearch(page + 1, submittedQuery) }) {
                                    Text(stringResource(R.string.action_load_more))
                                }
                            }
                        }
                    }
                }
                if (results.isEmpty()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(32.dp))
                        submittedQuery.isNotBlank() -> JmSearchFeedback(error)
                        else -> JmSearchPrompt()
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

@Composable
private fun JmSearchPrompt() {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_material_search), null, Modifier.size(30.dp))
            }
        }
        Text(
            text = stringResource(R.string.jm_search_prompt),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun JmSearchFeedback(error: String?) {
    Surface(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = if (error == null) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = error ?: stringResource(R.string.no_results),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JmSearchCard(
    item: JmSearchItem,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: () -> Unit
) {
    val selected = selectionMode && checked
    val shape = MaterialTheme.shapes.medium
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            Column {
                JmArtwork(item.id, Modifier.fillMaxWidth().aspectRatio(0.75f))
                Text(
                    item.name.ifBlank { "JM${item.id}" },
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectionMode) {
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp), shape = CircleShape) {
                    Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
                }
            }
        }
    }
}

@Composable
internal fun JmArtwork(albumId: String, modifier: Modifier) {
    SubcomposeAsyncImage(
        model = coverUrlFor(albumId),
        contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentScale = ContentScale.Crop,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_material_menu_book), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
