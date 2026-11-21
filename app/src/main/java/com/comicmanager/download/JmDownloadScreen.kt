package com.comicmanager.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comicmanager.R
import com.comicmanager.download.jm.JmBrowseSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmDownloadScreen(
    viewModel: JmDownloadViewModel,
    isActive: Boolean = true,
    externalOpenId: String? = null,
    externalOpenToken: Int = 0,
    onExternalConsumed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    SideEffect { viewModel.setAppContext(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            if (state.isDownloading || state.statusMessage.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (state.isDownloading && state.isBatch) {
                            Text(
                                stringResource(R.string.jm_v21_batch_position, state.batchCurrent, state.batchTotal),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.jm_v21_batch_counts, state.batchCompleted, state.batchSucceeded, state.batchFailed),
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = {
                                    if (state.batchTotal > 0) (state.batchCompleted.toFloat() / state.batchTotal).coerceIn(0f, 1f)
                                    else 0f
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                state.currentAlbumTitle.ifBlank { "JM${state.currentAlbumId}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!state.isBatch || !state.isDownloading) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                        Icon(painterResource(R.drawable.ic_material_download), null, Modifier.size(20.dp))
                                    }
                                }
                            }
                            Text(
                                text = if (state.isDownloading) {
                                    if (state.isCancelling) stringResource(R.string.jm_v2_cancelling) else state.nativePhase
                                } else state.statusMessage,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.isDownloading) {
                                FilledTonalButton(onClick = viewModel::cancelNativeDownload, enabled = state.canCancel) {
                                    Text(stringResource(if (state.isBatch) R.string.jm_v21_cancel_batch else R.string.action_cancel))
                                }
                            } else {
                                if (state.canRetry) {
                                    TextButton(onClick = viewModel::retryDownload) {
                                        Text(stringResource(if (state.isBatch) R.string.jm_v21_retry_failed else R.string.action_retry))
                                    }
                                }
                                IconButton(onClick = viewModel::resetState) {
                                    Icon(Icons.Default.Close, stringResource(R.string.action_close))
                                }
                            }
                        }
                        if (state.isDownloading) {
                            if (state.nativeTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (state.nativeCurrent.toFloat() / state.nativeTotal).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    stringResource(R.string.jm_v21_page_progress, state.nativeCurrent, state.nativeTotal),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.nativeMsg.isNotBlank()) {
                                Text(
                                    state.nativeMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else if (state.failedAlbums.isNotEmpty()) {
                            Text(
                                stringResource(
                                    R.string.jm_v21_failed_titles,
                                    state.failedAlbums.joinToString(context.getString(R.string.list_separator)) { it.name.ifBlank { "JM${it.id}" } }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            JmBrowseSection(
                isActive = isActive,
                onSearch = viewModel::searchJm,
                onGetDetail = viewModel::getJmAlbumDetail,
                onGetPageCount = viewModel::getJmPageCount,
                onGetComments = viewModel::getJmComments,
                onEnrichTitles = viewModel::enrichJmTitles,
                onDownload = viewModel::startNativeDownload,
                onBatchDownload = viewModel::startBatchDownload,
                isBusy = state.isDownloading,
                externalOpenId = externalOpenId,
                externalOpenToken = externalOpenToken,
                onExternalConsumed = onExternalConsumed
            )
        }
    }
}
