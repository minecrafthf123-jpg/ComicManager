package com.comicmanager.download.jm

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comicmanager.R
import com.comicmanager.ui.theme.pressScale
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmDetailScreen(
    albumId: String,
    onGetDetail: suspend (String) -> JmAlbum,
    onGetPageCount: suspend (JmAlbum) -> Int?,
    onGetComments: suspend (String, Int) -> List<JmComment>,
    onDownload: (String) -> Unit,
    isBusy: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var album by remember(albumId) { mutableStateOf<JmAlbum?>(null) }
    var error by remember(albumId) { mutableStateOf<String?>(null) }
    var tab by remember(albumId) { mutableIntStateOf(0) }
    var comments by remember(albumId) { mutableStateOf<List<JmComment>>(emptyList()) }
    var commentsLoaded by remember(albumId) { mutableStateOf(false) }
    var commentsError by remember(albumId) { mutableStateOf<String?>(null) }
    var totalPages by remember(albumId) { mutableStateOf<Int?>(null) }
    var pageCountLoaded by remember(albumId) { mutableStateOf(false) }
    val downloadInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(albumId) {
        try {
            val loadedAlbum = onGetDetail(albumId)
            check(loadedAlbum.id.trim() == albumId.trim()) {
                context.getString(R.string.detail_id_mismatch, loadedAlbum.id, albumId)
            }
            album = loadedAlbum
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = context.getString(R.string.load_failed, e.message.orEmpty())
        }
    }

    LaunchedEffect(album) {
        val loaded = album ?: return@LaunchedEffect
        try {
            totalPages = onGetPageCount(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            totalPages = null
        }
        pageCountLoaded = true
    }

    LaunchedEffect(albumId, tab) {
        if (tab == 2 && commentsError == null && !commentsLoaded) {
            try {
                comments = onGetComments(albumId, 1)
                commentsLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsError = context.getString(R.string.comments_load_failed, e.message.orEmpty())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jm_v2_details), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            val loaded = album
            val loadError = error
            when {
                loadError != null -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            loadError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                }
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    JmArtwork(
                                        loaded.id,
                                        Modifier.width(104.dp).aspectRatio(0.75f).clip(MaterialTheme.shapes.medium)
                                    )
                                    Column(
                                        Modifier.weight(1f).padding(top = 2.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            loaded.title.orEmpty().ifBlank { "JM${loaded.id}" },
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        loaded.authors?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { author ->
                                            Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            "JM${loaded.id}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Button(
                                    onClick = { onDownload(loaded.id) },
                                    enabled = !isBusy,
                                    interactionSource = downloadInteraction,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(downloadInteraction)
                                ) {
                                    Icon(painterResource(R.drawable.ic_material_download), null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(if (isBusy) R.string.downloading else R.string.download_to_library))
                                }
                            }
                        }
                    }
                    item {
                        TabRow(
                            selectedTabIndex = tab,
                            modifier = Modifier.clip(MaterialTheme.shapes.medium),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_introduction)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_catalog)) })
                            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_comments)) })
                        }
                    }

                    when (tab) {
                        0 -> {
                            item {
                                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 14.dp)) {
                                        StatItem(Modifier.weight(1f), "${loaded.likes ?: "0"}", stringResource(R.string.stat_likes))
                                        StatItem(Modifier.weight(1f), "${loaded.views ?: "0"}", stringResource(R.string.stat_views))
                                        StatItem(
                                            Modifier.weight(1f),
                                            totalPages?.toString() ?: if (pageCountLoaded) stringResource(R.string.jm_v2_pages_unknown) else "…",
                                            stringResource(R.string.stat_pages)
                                        )
                                    }
                                }
                            }
                            item {
                                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                loaded.description ?: loaded.title.orEmpty(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (!loaded.tags.isNullOrEmpty()) {
                                            MetadataTags(stringResource(R.string.tags), loaded.tags)
                                        }
                                        if (!loaded.authors.isNullOrEmpty()) {
                                            MetadataTags(stringResource(R.string.authors), loaded.authors)
                                        }
                                    }
                                }
                            }
                        }
                        1 -> items(loaded.photoMetas.size) { index ->
                            val meta = loaded.photoMetas[index]
                            ListItem(
                                headlineContent = {
                                    Text(meta.title ?: stringResource(R.string.chapter_default, meta.sortOrder.toString()))
                                },
                                supportingContent = { Text(stringResource(R.string.item_id, meta.id)) },
                                trailingContent = { Text("#${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.clip(MaterialTheme.shapes.medium)
                            )
                        }
                        else -> {
                            val commentLoadError = commentsError
                            if (commentLoadError != null || !commentsLoaded || comments.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        when {
                                            commentLoadError != null -> Text(commentLoadError, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                                            !commentsLoaded -> CircularProgressIndicator(Modifier.size(28.dp))
                                            else -> Text(stringResource(R.string.no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            } else {
                                items(comments.size) { index ->
                                    val comment = comments[index]
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                comment.nickname ?: comment.username ?: stringResource(R.string.anonymous),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        supportingContent = {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(comment.content.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    stringResource(R.string.comment_likes, comment.postDate.orEmpty(), comment.likes.toString()),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        modifier = Modifier.clip(MaterialTheme.shapes.medium)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(modifier: Modifier, value: String, label: String) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetadataTags(title: String, tags: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tags.forEach { tag ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "#$tag",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
