package de.synvyropic.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.synvyropic.app.ui.screens.NewProjectScreen
import de.synvyropic.app.ui.screens.ProjectListScreen

object Routes {
    const val PROJECTS = "projects"
    const val NEW_PROJECT = "new_project"
}

@Composable
fun SynvyropicNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectListScreen(onNewProject = { nav.navigate(Routes.NEW_PROJECT) })
        }
        composable(Routes.NEW_PROJECT) {
            NewProjectScreen(onBack = { nav.popBackStack() })
        }
    }
}
