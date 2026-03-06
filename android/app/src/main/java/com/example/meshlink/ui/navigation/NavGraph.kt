package com.example.meshlink.ui.navigation

sealed class Screen(val route: String) {
    object ChatList : Screen("chat_list")
    object Chat : Screen("chat/{peerId}") {
        fun createRoute(peerId: String) = "chat/$peerId"
    }
    object Settings : Screen("settings")
    object VideoCall : Screen("video_call/{peerId}/{peerName}/{isIncoming}") {
        fun createRoute(peerId: String, peerName: String, isIncoming: Boolean) =
            "video_call/$peerId/${peerName.ifBlank { peerId.take(8) }}/$isIncoming"
    }
}