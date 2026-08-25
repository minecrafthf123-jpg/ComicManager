package com.comicmanager.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.JmDownloadScreen
import com.comicmanager.download.JmDownloadViewModel
import com.comicmanager.ui.viewmodel.HistoryViewModel
import com.comicmanager.ui.viewmodel.MainViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerScreen(
    repository: ComicRepository,
    onComicClick: (Long) -> Unit
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var selectMode by remember { mutableStateOf(false) }
    var jmOpenId by remember { mutableStateOf<String?>(null) }
    var jmOpenToken by remember { mutableIntStateOf(0) }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !selectMode && pagerState.currentPage != 2
    ) { page ->
        when (page) {
            0 -> {
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(repository)
                )
                MainScreen(
                    viewModel = mainViewModel,
                    onComicClick = onComicClick,
                    onHistoryClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    onJmDownloadClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onJmDetail = { id ->
                        android.util.Log.d("JmLink", "MainScreen长按 → onJmDetail id=" + id)
                        jmOpenId = id
                        jmOpenToken++
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                    onSelectModeChange = { selectMode = it }
                )
            }
            1 -> {
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = HistoryViewModel.Factory(repository)
                )
                HistoryScreen(
                    viewModel = historyViewModel,
                    onComicClick = onComicClick,
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } }
                )
            }
            2 -> {
                val jmViewModel: JmDownloadViewModel = viewModel(
                    factory = JmDownloadViewModel.Factory(repository)
                )
                JmDownloadScreen(
                    viewModel = jmViewModel,
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                    externalOpenId = jmOpenId,
                    externalOpenToken = jmOpenToken,
                    onExternalConsumed = { jmOpenId = null }
                )
            }
        }
    }
}
