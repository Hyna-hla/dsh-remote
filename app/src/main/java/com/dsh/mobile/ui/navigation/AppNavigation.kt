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
import com.dsh.mobile.data.ApprovalCenter
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.screens.*

sealed class Screen(val route: String) {
    data object Connect : Screen("connect")
    data object Home : Screen("home")
    data object Session : Screen("session/{sessionId}?focusSeq={focusSeq}") {
        fun createRoute(sessionId: String, focusSeq: Long? = null) =
            "session/$sessionId" + (focusSeq?.let { "?focusSeq=$it" } ?: "")
    }
    data object Settings : Screen("settings")
    data object Pending : Screen("pending")
    data object Pro : Screen("pro")
    data object Market : Screen("market")
    data object HostProfile : Screen("hostProfile/{profileId}") {
        fun createRoute(profileId: String?) = "hostProfile/${profileId ?: "new"}"
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    connection: DshConnection,
    approvalCenter: ApprovalCenter,
) {
    val connState by connection.state.collectAsState()
    val context = LocalContext.current

    // 配对决策（awaitingDecision != null = 活跃主机未配对，等待用户输配对码）
    val awaitingId = (context.applicationContext as? DshApplication)
        ?.pairingCoordinator?.awaitingDecision?.collectAsState(initial = null)?.value

    val opScope = rememberCoroutineScope()

    /** 用户主动断开：停后台服务 + 清各主机自动连接标记（连接页不再自动重连旧地址），清理完再回连接页 */
    fun onUserDisconnect() {
        connection.disconnect()
        runCatching { context.stopService(Intent(context, DshConnectionService::class.java)) }
        opScope.launch {
            val store = SettingsStore(context)
            store.profiles.first().filter { it.autoConnect }.forEach {
                store.upsertProfile(it.copy(autoConnect = false))
            }
            navController.navigate(Screen.Connect.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // 连接成功后：优先处理通知深链（打开指定会话），否则自动进入首页。
    // 首次连接未配对时绝不跳转——留在连接页弹「配对码」对话框（协调器异步置
    // awaitingDecision，存在时序竞态；未配对时直接跳会把对话框顶掉、会话也因无
    // 通道令牌加载不上）。配对完成（awaitingDecision 清空且内存 profile 已 paired）
    // 后本 Effect 随 awaitingId 变化重新触发再跳转。
    LaunchedEffect(connState, awaitingId) {
        if (connState !is DshConnection.State.Connected) return@LaunchedEffect
        val profile = connection.currentProfile()
        if (profile == null || !profile.paired) return@LaunchedEffect
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
                approvalCenter = approvalCenter,
                onPending = { navController.navigate(Screen.Pending.route) },
                onSessionClick = { id -> navController.navigate(Screen.Session.createRoute(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onUpgrade = { navController.navigate(Screen.Pro.route) },
                onDisconnect = { onUserDisconnect() },
            )
        }
        composable(
            route = Screen.Session.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("focusSeq") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            SessionScreen(
                sessionId = it.arguments?.getString("sessionId") ?: "",
                connection = connection,
                approvalCenter = approvalCenter,
                focusSeq = it.arguments?.getString("focusSeq")?.toLongOrNull(),
                onBack = { navController.popBackStack() },
                onPending = { navController.navigate(Screen.Pending.route) },
            )
        }
        composable(Screen.Pending.route) {
            PendingScreen(
                center = approvalCenter,
                connection = connection,
                onBack = { navController.popBackStack() },
                onOpenSession = { sid, seq -> navController.navigate(Screen.Session.createRoute(sid, seq)) },
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                connection = connection,
                onBack = { navController.popBackStack() },
                onUpgrade = { navController.navigate(Screen.Pro.route) },
                onOpenMarket = { navController.navigate(Screen.Market.route) },
                onDisconnect = { onUserDisconnect() },
            )
        }
        composable(Screen.Pro.route) {
            ProScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Market.route) {
            MarketScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Screen.HostProfile.route,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) {
            HostProfileScreen(
                profileId = it.arguments?.getString("profileId")?.takeIf { v -> v != "new" },
                connection = connection,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
