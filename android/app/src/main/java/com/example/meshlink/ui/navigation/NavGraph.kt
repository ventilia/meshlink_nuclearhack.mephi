package com.example.meshlink.ui.navigation

sealed class Screen(val route: String) {
    object ChatList : Screen("chat_list")

    object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }

    object Settings : Screen("settings")
}