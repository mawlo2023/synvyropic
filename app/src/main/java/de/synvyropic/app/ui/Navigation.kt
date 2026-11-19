package de.synvyropic.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.synvyropic.app.ui.editor.EditorScreen
import de.synvyropic.app.ui.screens.ProjectListScreen
import de.synvyropic.app.ui.screens.VideosScreen

object Routes {
    const val PROJECTS = "projects"
    const val EDITOR = "editor/{projectId}"
    const val VIDEOS = "videos"
    fun editor(id: String) = "editor/$id"
}

@Composable
fun SynvyropicNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onOpen = { nav.navigate(Routes.editor(it)) },
                onVideos = { nav.navigate(Routes.VIDEOS) },
            )
        }
        composable(Routes.VIDEOS) {
            VideosScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument("projectId") { type = NavType.StringType })) {
            EditorScreen(onBack = { nav.popBackStack() })
        }
    }
}
