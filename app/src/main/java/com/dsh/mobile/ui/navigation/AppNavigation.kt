package com.dsh.mobile.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import android.content.Intent
import com.dsh.mobile.DshApplication
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.screens.*

sealed class Screen(val route: String) {
    data object Connect : Screen("connect")
    data object Home : Screen("home")
    data object Session : Screen("session/{sessionId}") {
        fun createRoute(sessionId: String) = "session/$sessionId"
    }
    data object Settings : Screen("settings")
    data object Pro : Screen("pro")
    data object HostProfile : Screen("hostProfile/{profileId}") {
        fun createRoute(profileId: String?) = "hostProfile/${profileId ?: "new"}"
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    connection: DshConnection,
) {
    val connState by connection.state.collectAsState()
    val context = LocalContext.current

    /** 用户主动断开：同时停掉后台提醒服务，避免它在用户断开后仍保持后台连接 */
    fun onUserDisconnect() {
        connection.disconnect()
        runCatching { context.stopService(Intent(context, DshConnectionService::class.java)) }
        navController.navigate(Screen.Connect.route) {
            popUpTo(0) { inclusive = true }
        }
    }

    // 连接成功后：优先处理通知深链（打开指定会话），否则自动进入首页
    LaunchedEffect(connState) {
        if (connState is DshConnection.State.Connected) {
            val pending = DshApplication.pendingOpenSessionId
            if (pending != null) {
                DshApplication.pendingOpenSessionId = null
                navController.navigate(Screen.Session.createRoute(pending))
                return@LaunchedEffect
            }
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
            ConnectScreen(
                connection = connection,
                onEditHost = { id -> navController.navigate(Screen.HostProfile.createRoute(id)) },
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(
                connection = connection,
                onSessionClick = { id -> navController.navigate(Screen.Session.createRoute(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onUpgrade = { navController.navigate(Screen.Pro.route) },
                onDisconnect = { onUserDisconnect() },
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
                onUpgrade = { navController.navigate(Screen.Pro.route) },
                onDisconnect = { onUserDisconnect() },
            )
        }
        composable(Screen.Pro.route) {
            ProScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.HostProfile.route,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) {
            HostProfileScreen(
                profileId = it.arguments?.getString("profileId")?.takeIf { v -> v != "new" },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
