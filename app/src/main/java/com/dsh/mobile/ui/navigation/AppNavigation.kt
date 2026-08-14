package com.dsh.mobile.ui.navigation

import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.ui.screens.*

sealed class Screen(val route: String) {
    data object Connect : Screen("connect")
    data object Home : Screen("home")
    data object Session : Screen("session/{sessionId}") {
        fun createRoute(sessionId: String) = "session/$sessionId"
    }
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    connection: DshConnection,
) {
    val connState by connection.state.collectAsState()

    // 连接成功后自动进入首页
    LaunchedEffect(connState) {
        if (connState is DshConnection.State.Connected) {
            val current = navController.currentDestination?.route
            if (current == Screen.Connect.route) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Connect.route) { inclusive = true }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Connect.route,
    ) {
        composable(Screen.Connect.route) {
            ConnectScreen(connection = connection)
        }
        composable(Screen.Home.route) {
            HomeScreen(
                connection = connection,
                onSessionClick = { id -> navController.navigate(Screen.Session.createRoute(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onDisconnect = {
                    connection.disconnect()
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Screen.Session.route,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) {
            SessionScreen(
                sessionId = it.arguments?.getString("sessionId") ?: "",
                connection = connection,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                connection = connection,
                onBack = { navController.popBackStack() },
                onDisconnect = {
                    connection.disconnect()
                    navController.navigate(Screen.Connect.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
