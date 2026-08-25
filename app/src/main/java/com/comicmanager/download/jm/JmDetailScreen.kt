package com.comicmanager.download.jm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import coil.compose.SubcomposeAsyncImage
import com.comicmanager.R
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmComment

@Composable
fun JmDetailScreen(
    albumId: String,
    onGetDetail: suspend (String) -> JmAlbum,
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

    LaunchedEffect(albumId) {
        android.util.Log.d("JmLink", "JmDetailScreen albumId=" + albumId)
        try {
            val loadedAlbum = onGetDetail(albumId)
            check(loadedAlbum.id.trim() == albumId.trim()) {
                context.getString(R.string.detail_id_mismatch, loadedAlbum.id, albumId)
            }
            album = loadedAlbum
            android.util.Log.d("JmLink", "getAlbum返回 id=" + (album?.id) + " title=" + (album?.title ?: "").substring(0, Math.min(30, (album?.title ?: "").length)))
        } catch (e: Exception) {
            error = context.getString(R.string.load_failed, e.message.orEmpty())
        }
    }

    LaunchedEffect(albumId, tab) {
        if (tab == 2 && commentsError == null && !commentsLoaded) {
            try {
                comments = onGetComments(albumId, 1)
                commentsLoaded = true
            } catch (e: Exception) {
                commentsError = context.getString(R.string.comments_load_failed, e.message.orEmpty())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (album == null && error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(40.dp))
            }
        } else if (error != null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        } else {
            val a = album!!
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Box(Modifier.fillMaxWidth().height(420.dp)) {
                            SubcomposeAsyncImage(
                                model = coverUrlFor(a.id),
                                contentDescription = a.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                loading = {
                                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                                },
                                error = {
                                    Box(Modifier.fillMaxSize().background(Color.DarkGray))
                                }
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                        )
                                    )
                            )
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(4.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                    tint = Color.White
                                )
                            }
                            Column(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    a.title ?: "",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                val author = a.authors?.firstOrNull() ?: ""
                                if (author.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(author, color = Color(0xFFFFA726), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { onDownload(a.id) },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0672A))
                        ) {
                            Text(
                                stringResource(if (isBusy) R.string.downloading else R.string.download_to_library),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    item {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_introduction)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_catalog)) })
                            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.tab_comments)) })
                        }
                    }

                    if (tab == 0) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                            ) {
                                val totalPages = a.pageCount.takeIf { it > 0 }
                                    ?: (a.images?.size ?: 0).takeIf { it > 0 }
                                    ?: (a.photoMetas?.size ?: 0)
                                StatItem(Modifier.weight(1f), "${a.likes ?: "0"}", stringResource(R.string.stat_likes))
                                StatItem(Modifier.weight(1f), "${a.views ?: "0"}", stringResource(R.string.stat_views))
                                StatItem(Modifier.weight(1f), "$totalPages", stringResource(R.string.stat_pages))
                            }
                        }
                        item {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Text(stringResource(R.string.jm_identifier), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text("JM" + a.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    a.description ?: a.title ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                if (!a.tags.isNullOrEmpty()) {
                                    Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    FlowTagChips(a.tags)
                                    Spacer(Modifier.height(16.dp))
                                }
                                if (!a.authors.isNullOrEmpty()) {
                                    Text(stringResource(R.string.authors), style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    FlowTagChips(a.authors)
                                    Spacer(Modifier.height(16.dp))
                                }
                            }
                        }
                    } else if (tab == 1) {
                        items(a.photoMetas.size) { idx ->
                            val meta = a.photoMetas[idx]
                            ListItem(
                                headlineContent = { Text(meta.title ?: stringResource(R.string.chapter_default, meta.sortOrder.toString())) },
                                supportingContent = { Text(stringResource(R.string.item_id, meta.id)) },
                                trailingContent = { Text("#" + (idx + 1)) }
                            )
                        }
                    } else {
                        if (commentsError != null) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(commentsError!!, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else if (!commentsLoaded) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                }
                            }
                        } else if (comments.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_comments))
                                }
                            }
                        } else {
                            items(comments.size) { idx ->
                                val c = comments[idx]
                                ListItem(
                                    headlineContent = { Text(c.nickname ?: c.username ?: stringResource(R.string.anonymous), maxLines = 1) },
                                    supportingContent = {
                                        Column {
                                            Text(c.content ?: "", style = MaterialTheme.typography.bodySmall)
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                stringResource(
                                                    R.string.comment_likes,
                                                    c.postDate.orEmpty(),
                                                    c.likes.toString()
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                )
                                HorizontalDivider()
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
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FlowTagChips(tags: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.chunked(4).forEach { rowTags ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTags.forEach { tag ->
                    Text(
                        "#" + tag,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
