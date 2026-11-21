package com.comicmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.JmDownloadScreen
import com.comicmanager.download.JmDownloadViewModel
import com.comicmanager.ui.viewmodel.FolderViewModel
import com.comicmanager.ui.viewmodel.HistoryViewModel
import com.comicmanager.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private enum class MainPage(@StringRes val label: Int) {
    LIBRARY(R.string.main_v22_library),
    HISTORY(R.string.main_v22_history),
    JM(R.string.jm_v2_api_search),
    FOLDERS(R.string.folder_v22_title)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerScreen(
    repository: ComicRepository,
    onComicClick: (Long) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { MainPage.entries.size })
    val scope = rememberCoroutineScope()
    var jmOpenId by rememberSaveable { mutableStateOf<String?>(null) }
    var jmOpenToken by rememberSaveable { mutableIntStateOf(0) }
    val settledPage = pagerState.settledPage

    BackHandler(enabled = settledPage > 0 || pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            scope.launch { pagerState.animateScrollToPage(settledPage - 1) }
        }
    }

    Scaffold(bottomBar = {
        MainPageNavigation(pagerState.currentPage) { page ->
            scope.launch { pagerState.animateScrollToPage(page) }
        }
    }) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            // Retain the JM search and folder detail while switching between the four pages.
            beyondBoundsPageCount = MainPage.entries.lastIndex,
            key = { MainPage.entries[it].name }
        ) { index ->
            val isActive = index == settledPage && !pagerState.isScrollInProgress
            when (MainPage.entries[index]) {
                MainPage.LIBRARY -> {
                    val model: MainViewModel = viewModel(
                        factory = MainViewModel.Factory(repository, LocalContext.current.applicationContext)
                    )
                    MainScreen(
                        viewModel = model,
                        repository = repository,
                        isActive = isActive,
                        onComicClick = onComicClick,
                        onJmDetail = { id ->
                            jmOpenId = id
                            jmOpenToken++
                            scope.launch { pagerState.animateScrollToPage(MainPage.JM.ordinal) }
                        }
                    )
                }
                MainPage.HISTORY -> {
                    val model: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository))
                    HistoryScreen(viewModel = model, onComicClick = onComicClick)
                }
                MainPage.JM -> {
                    val model: JmDownloadViewModel = viewModel(factory = JmDownloadViewModel.Factory(repository))
                    JmDownloadScreen(
                        viewModel = model,
                        isActive = isActive,
                        externalOpenId = jmOpenId,
                        externalOpenToken = jmOpenToken,
                        onExternalConsumed = { jmOpenId = null }
                    )
                }
                MainPage.FOLDERS -> {
                    val model: FolderViewModel = viewModel(factory = FolderViewModel.Factory(repository))
                    FolderScreen(viewModel = model, onComicClick = onComicClick, isActive = isActive)
                }
            }
        }
    }
}

@Composable
private fun MainPageNavigation(currentPage: Int, onPageSelected: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp).selectableGroup()) {
            MainPage.entries.forEachIndexed { index, page ->
                val isCurrent = index == currentPage
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .selectable(selected = isCurrent, role = Role.Tab, onClick = { onPageSelected(index) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
                ) {
                    Box(
                        Modifier.size(width = 18.dp, height = 3.dp).background(
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(50)
                        )
                    )
                    Text(
                        stringResource(page.label),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
