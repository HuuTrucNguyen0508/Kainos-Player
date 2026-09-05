package com.universalmusic.player.ui.navigation

enum class AppDestination(
    val label: String,
    val route: String,
) {
    Home("Home", "home"),
    Search("Search", "search"),
    Library("Library", "library"),
    Settings("Settings", "settings"),
}
