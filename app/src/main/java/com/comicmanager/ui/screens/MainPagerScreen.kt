package com.comicmanager.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var jmSwipeToHistoryEnabled by remember { mutableStateOf(false) }

    fun goToHistory() {
        scope.launch { pagerState.animateScrollToPage(1) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().swipeLeftToHistory(
            enabled = pagerState.currentPage == 2 && jmSwipeToHistoryEnabled,
            onSwipe = ::goToHistory
        ),
        beyondBoundsPageCount = 2,
        userScrollEnabled = !selectMode && pagerState.currentPage != 2
    ) { page ->
        when (page) {
            0 -> {
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(repository, LocalContext.current.applicationContext)
                )
                MainScreen(
                    viewModel = mainViewModel,
                    onComicClick = onComicClick,
                    onHistoryClick = ::goToHistory,
                    onJmDownloadClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    onJmDetail = { id ->
                        jmSwipeToHistoryEnabled = false
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
                    isActive = pagerState.currentPage == 2,
                    onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                    onSwipeToHistoryEnabledChange = { jmSwipeToHistoryEnabled = it },
                    externalOpenId = jmOpenId,
                    externalOpenToken = jmOpenToken,
                    onExternalConsumed = { jmOpenId = null }
                )
            }
        }
    }
}

@Composable
private fun Modifier.swipeLeftToHistory(enabled: Boolean, onSwipe: () -> Unit): Modifier {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    if (!enabled) return this

    return pointerInput(Unit) {
        val minimumDistance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var distance = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (overSlop < 0f) {
                    distance = overSlop
                    change.consume()
                }
            } ?: return@awaitEachGesture

            val finished = horizontalDrag(drag.id) { change ->
                distance += change.position.x - change.previousPosition.x
                change.consume()
            }
            if (finished && distance <= -minimumDistance) currentOnSwipe()
        }
    }
}
