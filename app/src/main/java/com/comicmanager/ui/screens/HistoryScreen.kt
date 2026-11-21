package com.comicmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.comicmanager.R
import com.comicmanager.model.Comic
import com.comicmanager.ui.viewmodel.HistoryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onComicClick: (Long) -> Unit
) {
    val comics by viewModel.recentComics.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reading_history)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        if (comics.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_material_history), null, Modifier.size(32.dp))
                    }
                }
                Text(
                    stringResource(R.string.empty_history),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            var isRefreshing by remember { mutableStateOf(false) }
            val pullRefreshState = rememberPullRefreshState(isRefreshing, {
                scope.launch { isRefreshing = true; delay(500); isRefreshing = false }
            })
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .pullRefresh(pullRefreshState)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(comics, key = { it.id }) { comic ->
                        HistoryItem(comic = comic, onClick = { onComicClick(comic.id) })
                    }
                }
                PullRefreshIndicator(
                    isRefreshing,
                    pullRefreshState,
                    Modifier.align(Alignment.TopCenter),
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private val coverExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

@Composable
internal fun CompactComicCover(comic: Comic, modifier: Modifier = Modifier) {
    val cover = remember(comic.coverPath, comic.directoryPath) {
        val savedCover = File(comic.coverPath)
        if (savedCover.isFile) savedCover else {
            File(comic.directoryPath).listFiles()
                ?.asSequence()
                ?.filter { it.extension.lowercase() in coverExtensions }
                ?.minByOrNull { it.name } ?: savedCover
        }
    }
    val context = LocalContext.current
    val request = remember(context, cover) { ImageRequest.Builder(context).data(cover).crossfade(false).build() }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.size(width = 48.dp, height = 66.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentScale = ContentScale.Crop,
        loading = {},
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_material_menu_book), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
private fun HistoryItem(comic: Comic, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = { CompactComicCover(comic) },
        headlineContent = {
            Text(comic.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (comic.pageCount > 0) {
            {
                Text(pluralStringResource(R.plurals.page_progress, comic.pageCount, comic.currentPage, comic.pageCount))
            }
        } else null,
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    )
}
