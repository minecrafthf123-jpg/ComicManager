package com.comicmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.comicmanager.R
import com.comicmanager.data.ComicRepository
import com.comicmanager.download.JmDownloadScreen
import com.comicmanager.download.JmDownloadViewModel
import com.comicmanager.ui.theme.pressScale
import com.comicmanager.ui.viewmodel.FolderViewModel
import com.comicmanager.ui.viewmodel.HistoryViewModel
import com.comicmanager.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private enum class MainPage(@StringRes val label: Int, @DrawableRes val icon: Int) {
    LIBRARY(R.string.main_v22_library, R.drawable.ic_material_menu_book),
    HISTORY(R.string.main_v22_history, R.drawable.ic_material_history),
    JM(R.string.main_nav_jm_search, R.drawable.ic_material_search),
    FOLDERS(R.string.folder_v22_title, R.drawable.ic_material_folder)
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            MainPageNavigation(pagerState.currentPage) { page ->
                scope.launch { pagerState.animateScrollToPage(page) }
            }
        }
    ) { padding ->
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        MainPage.entries.forEachIndexed { index, page ->
            val interactionSource = remember { MutableInteractionSource() }
            NavigationBarItem(
                selected = index == currentPage,
                onClick = { onPageSelected(index) },
                interactionSource = interactionSource,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                icon = {
                    Icon(
                        painter = painterResource(page.icon),
                        contentDescription = null,
                        modifier = Modifier.pressScale(interactionSource)
                    )
                },
                label = {
                    Text(
                        text = stringResource(page.label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}
