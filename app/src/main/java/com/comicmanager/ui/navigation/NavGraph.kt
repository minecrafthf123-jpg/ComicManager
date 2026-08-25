package com.comicmanager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.comicmanager.data.ComicRepository
import com.comicmanager.ui.screens.MainPagerScreen
import com.comicmanager.ui.screens.ReaderScreen
import com.comicmanager.ui.viewmodel.ReaderViewModel

object Routes {
    const val MAIN = "main"
    const val READER = "reader/{comicId}"

    fun reader(comicId: Long) = "reader/$comicId"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    repository: ComicRepository
) {
    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainPagerScreen(
                repository = repository,
                onComicClick = { comicId -> navController.navigate(Routes.reader(comicId)) }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("comicId") { type = NavType.LongType })
        ) { backStackEntry ->
            val comicId = backStackEntry.arguments?.getLong("comicId") ?: return@composable
            val readerViewModel: ReaderViewModel = viewModel(
                factory = ReaderViewModel.Factory(repository)
            )
            LaunchedEffect(comicId) { readerViewModel.loadComic(comicId) }
            ReaderScreen(viewModel = readerViewModel, onBack = { navController.popBackStack() })
        }
    }
}
