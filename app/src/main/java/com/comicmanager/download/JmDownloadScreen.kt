package com.comicmanager.download

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comicmanager.R
import com.comicmanager.download.jm.JmBrowseSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmDownloadScreen(
    viewModel: JmDownloadViewModel,
    onBack: () -> Unit,
    isActive: Boolean = true,
    externalOpenId: String? = null,
    externalOpenToken: Int = 0,
    onExternalConsumed: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    SideEffect { viewModel.setAppContext(context) }
    BackHandler(enabled = isActive, onBack = onBack)

    Scaffold(
        bottomBar = {
            if (state.isDownloading || state.statusMessage.isNotBlank()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                TextButton(onClick = viewModel::cancelNativeDownload, enabled = state.canCancel) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            } else {
                                if (state.canRetry) TextButton(onClick = viewModel::retryDownload) {
                                    Text(stringResource(R.string.action_retry))
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
                                Text("${state.nativeCurrent} / ${state.nativeTotal}", style = MaterialTheme.typography.bodySmall)
                            } else LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.nativeMsg.isNotBlank()) Text(state.nativeMsg, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            JmBrowseSection(
                onBack = onBack,
                isActive = isActive,
                onSearch = viewModel::searchJm,
                onGetDetail = viewModel::getJmAlbumDetail,
                onGetPageCount = viewModel::getJmPageCount,
                onGetComments = viewModel::getJmComments,
                onEnrichTitles = viewModel::enrichJmTitles,
                onDownload = viewModel::startNativeDownload,
                isBusy = state.isDownloading,
                externalOpenId = externalOpenId,
                externalOpenToken = externalOpenToken,
                onExternalConsumed = onExternalConsumed
            )
        }
    }
}
