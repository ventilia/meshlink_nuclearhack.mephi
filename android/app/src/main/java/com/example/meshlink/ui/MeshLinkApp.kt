// ФАЙЛ: C:\Users\GAMER\AndroidStudioProjects\meshlink_nuclearhack.mephi\android\app\src\main\java\com\example\meshlink\ui\MeshLinkApp.kt
package com.example.meshlink.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.meshlink.ui.navigation.Screen
import com.example.meshlink.ui.screen.ChatListScreen
import com.example.meshlink.ui.screen.ChatScreen
import com.example.meshlink.ui.screen.SettingsScreen
import com.example.meshlink.ui.screen.VideoCallScreen

@Composable
fun MeshLinkApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.ChatList.route
    ) {
        composable(Screen.ChatList.route) {
            ChatListScreen(
                onChatClick = { peerId ->
                    navController.navigate(Screen.Chat.createRoute(peerId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Chat.route) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            ChatScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() },
                // ИСПРАВЛЕНО: Принимаем флаг isIncoming и прокидываем в маршрут
                onVideoCallClick = { peerName, isIncoming ->
                    navController.navigate(Screen.VideoCall.createRoute(peerId, peerName, isIncoming))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.VideoCall.route) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: return@composable
            val peerName = backStackEntry.arguments?.getString("peerName") ?: ""
            val isIncoming = backStackEntry.arguments?.getString("isIncoming")?.toBoolean() ?: false

            VideoCallScreen(
                peerId = peerId,
                peerName = peerName,
                isIncoming = isIncoming,
                onDismiss = { navController.popBackStack() }
            )
        }
    }
}