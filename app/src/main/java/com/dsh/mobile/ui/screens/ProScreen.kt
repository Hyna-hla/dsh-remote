package com.dsh.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.ProPlan
import com.dsh.mobile.data.ProTokenBank
import kotlinx.coroutines.flow.collectLatest

/**
 * 假 Pro 订阅中心（趣味彩蛋）：
 * - 从「Upgrade to Pro」进入，套餐全免费、额度随便给
 * - 余额是真实的：会话实际消耗的 token 会实时扣减
 * - 扣完可"续费"，享受无限订阅的乐趣
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProScreen(onBack: () -> Unit) {
    val state by ProTokenBank.state.collectAsState()
    val currentPlan = ProTokenBank.planOf(state.plan)
    var confirmPlan by remember { mutableStateOf<ProPlan?>(null) }
    var showSnack by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(showSnack) {
        showSnack?.let {
            snackbarHostState.showSnackbar(it)
            showSnack = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pro 订阅") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // ── 状态卡 ──
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (currentPlan != null) Icons.Default.WorkspacePremium else Icons.Default.StarOutline,
                            null,
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (currentPlan != null) "已订阅：${currentPlan.name}" else "尚未订阅（免费的快乐还没开始）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    // 剩余额度
                    Text(
                        "剩余额度",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        ProTokenBank.fmt(state.balance.coerceAtLeast(0)),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (state.balance <= 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.balance < 0) {
                        Text(
                            "已欠费 ${ProTokenBank.fmt(-state.balance)} token——你的 Pro 在负数里哭泣",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (currentPlan != null) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = {
                                (state.balance / currentPlan.tokens.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "套餐额度 ${ProTokenBank.fmt(currentPlan.tokens)} · 已消耗 ${ProTokenBank.fmt(state.consumed)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.balance <= 0 && currentPlan != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "额度耗尽。别慌，续费还是 ¥0.00（和真订阅一个价，但不用刷卡）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "选择你的假装套餐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            // ── 套餐卡 ──
            ProTokenBank.plans.forEach { plan ->
                val active = currentPlan?.id == plan.id
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = if (active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    plan.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (active) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {
                                        Text(
                                            "已订阅",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${plan.tagline} · ${ProTokenBank.fmt(plan.tokens)} token",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                plan.price,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "原价 ¥199.99",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { confirmPlan = plan },
                                enabled = !active,
                            ) {
                                Text(if (active) "订阅中" else if (currentPlan != null) "续费" else "订阅")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "⚠️ 免责声明：本页面纯属娱乐。所有套餐均为虚构，订阅不会产生任何费用，" +
                    "也不会解锁任何真实功能——但 token 计数器是真的，你的余额会真的被用完。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (currentPlan != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        ProTokenBank.reset()
                        showSnack = "已退订。你的 Pro 生涯结束了（随时可以再假装订阅）"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退订（也是免费的）")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── 订阅确认弹窗 ──
    confirmPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { confirmPlan = null },
            title = { Text("确认订阅 ${plan.name}？") },
            text = {
                Text(
                    "价格 ${plan.price}（假装扣款，实际一分不花）。\n" +
                        "订阅后将获得 ${ProTokenBank.fmt(plan.tokens)} token 额度，" +
                        "你的每次真实对话都会消耗它。\n\n这是假的，但很好玩。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ProTokenBank.subscribe(plan)
                        confirmPlan = null
                        showSnack = "🎉 欢迎加入 ${plan.name}！（假的）余额已到账"
                    },
                ) { Text("假装付款") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPlan = null }) { Text("再想想") }
            },
        )
    }
}
