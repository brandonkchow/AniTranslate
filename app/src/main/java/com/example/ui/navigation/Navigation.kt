package com.example.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object Job : Screen("job/{jobId}") {
        fun createRoute(jobId: Long) = "job/$jobId"
    }
    object Editor : Screen("editor/{pageId}") {
        fun createRoute(pageId: Long) = "editor/$pageId"
    }
}
