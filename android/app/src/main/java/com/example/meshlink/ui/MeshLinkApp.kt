package com.example.meshlink.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.meshlink.ui.navigation.Screen
import com.example.meshlink.ui.screen.ChatListScreen
import com.example.meshlink.ui.screen.ChatScreen
import com.example.meshlink.ui.screen.SettingsScreen

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
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}