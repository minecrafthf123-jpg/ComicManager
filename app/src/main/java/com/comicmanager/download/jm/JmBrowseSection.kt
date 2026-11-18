package com.comicmanager.download.jm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.comicmanager.R
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal fun coverUrlFor(albumId: String): String {
    val domains = listOf(
        "https://cdn-msp.jmapiproxy1.cc",
        "https://cdn-msp.jmapiproxy2.cc",
        "https://cdn-msp2.jmapiproxy2.cc"
    )
    val h = albumId.hashCode() and Int.MAX_VALUE
    return domains[h % domains.size] + "/media/albums/" + albumId + "_3x4.jpg"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmBrowseSection(
    onBack: () -> Unit,
    isActive: Boolean,
    onSearch: suspend (String, Int) -> Pair<Int, List<JmSearchItem>>,
    onGetDetail: suspend (String) -> JmAlbum,
    onGetPageCount: suspend (JmAlbum) -> Int?,
    onGetComments: suspend (String, Int) -> List<JmComment>,
    onEnrichTitles: suspend (List<JmSearchItem>) -> List<JmSearchItem>,
    onDownload: (String) -> Unit,
    isBusy: Boolean,
    externalOpenId: String? = null,
    externalOpenToken: Int = 0,
    onExternalConsumed: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JmSearchItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var detailRequestKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(externalOpenToken) {
        if (externalOpenToken > 0 && externalOpenId != null) {
            android.util.Log.d("JmLink", "JmBrowseSection 收到 externalOpenId=" + externalOpenId + " token=" + externalOpenToken)
            selected = externalOpenId
            detailRequestKey++
            onExternalConsumed()
        }
    }
    fun doSearch(p: Int, q: String) {
        loading = true
        error = null
        scope.launch {
            try {
                val r = onSearch(q, p)
                total = r.first
                var newResults = (if (p == 1) r.second else results + r.second).distinctBy { it.id }
                val blank = newResults.filter { it.name.isBlank() }
                if (blank.isNotEmpty()) {
                    val enriched = onEnrichTitles(blank)
                    val map = enriched.associateBy { it.id }
                    newResults = newResults.map { map[it.id] ?: it }
                }
                results = newResults
                page = p
                if (r.second.isEmpty() && p == 1) error = context.getString(R.string.no_results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.getString(
                    R.string.search_failed,
                    e.message ?: context.getString(R.string.unknown_error)
                )
            }
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.jm_v2_api_search)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
            }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.search_jm_hint), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (query.isNotBlank()) doSearch(1, query.trim()) },
                enabled = !loading
            ) { Text(stringResource(R.string.action_search)) }
        }

        if (loading && results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(32.dp))
            }
        }

        error?.let {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results, key = { it.id }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = item.id
                            detailRequestKey++
                        },
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column {
                        SubcomposeAsyncImage(
                            model = coverUrlFor(item.id),
                            contentDescription = item.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                            contentScale = ContentScale.Crop,
                            loading = {
                                Box(
                                    Modifier.fillMaxSize().background(Color.LightGray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            },
                            error = {
                                Box(
                                    Modifier.fillMaxSize().background(Color.Gray),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(stringResource(R.string.cover), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        )
                        Text(
                            item.name,
                            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            if (results.isNotEmpty() && results.size < total) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            OutlinedButton(onClick = { doSearch(page + 1, query.trim()) }) {
                                Text(stringResource(R.string.action_load_more))
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { albumId ->
        BackHandler(enabled = isActive) { selected = null }
        key(albumId, detailRequestKey) {
            JmDetailScreen(
                albumId = albumId,
                onGetDetail = onGetDetail,
                onGetPageCount = onGetPageCount,
                onGetComments = onGetComments,
                onDownload = { id ->
                    onDownload(id)
                },
                isBusy = isBusy,
                onBack = { selected = null }
            )
        }
    }
    }
}
