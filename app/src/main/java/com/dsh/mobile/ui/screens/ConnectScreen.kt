package com.dsh.mobile.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.*
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import com.journeyapps.barcodescanner.CaptureActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(
    connection: DshConnection,
    onEditHost: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val profiles by settingsStore.profiles.collectAsState(initial = emptyList())
    val activeId by settingsStore.activeProfileId.collectAsState(initial = null)
    val connState by connection.state.collectAsState()

    val sortedProfiles = profiles.sortedByDescending { it.lastUsedAt }
    val activeProfile = profiles.firstOrNull { it.id == activeId }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun onConnectedActions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DshConnectionService::class.java))
        }
    }

    fun connectTo(profile: HostProfile) {
        scope.launch {
            settingsStore.setActiveProfile(profile.id)
            settingsStore.upsertProfile(profile.copy(autoConnect = true))
        }
        connection.connect(profile) { info ->
            scope.launch { settingsStore.markAttempt(info.profileId, info.errorCode, info.hostVersion) }
        }
        onConnectedActions()
    }

    // —— 扫码：解析结果 → 新建或更新配置并连接 ——
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
            if (scanned.isNotEmpty()) {
                val existing = profiles.firstOrNull { it.url == scanned }
                val profile = existing?.copy(remark = existing.remark.ifBlank { scanned })
                    ?: HostProfile(
                        id = java.util.UUID.randomUUID().toString(),
                        remark = scanned,
                        url = scanned,
                    )
                scope.launch { settingsStore.upsertProfile(profile) }
                connectTo(profile)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scannerLauncher.launch(Intent(context, CaptureActivity::class.java))
        } else {
            Toast.makeText(context, "需要相机权限才能扫码连接", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    // —— 自动连接 ——
    LaunchedEffect(Unit) {
        settingsStore.ensureMigrated()
        val auto = settingsStore.profiles.first().firstOrNull { it.autoConnect }
        if (auto != null) {
            scope.launch { settingsStore.setActiveProfile(auto.id) }
            connectTo(auto)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 错误横幅（spec §6：错误码 → 原因 → 建议；不可恢复错误标注已停止重连）
        val st = connState
        if (st is DshConnection.State.Error && st.code != null) {
            val code = st.code
            val stopped = code == ConnectionErrorCode.AUTH_FAILED ||
                code == ConnectionErrorCode.VERSION_MISMATCH
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        ErrorMessages.reason(code) + if (stopped) "（已停止自动重连）" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ErrorMessages.advice(code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("DSH Remote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "遥控你电脑上的 DeepSeek Harness 智能体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { startScan() }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码连接", tint = DshBrand)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 活跃主机卡片
            activeProfile?.let { p ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (connState) {
                                    is DshConnection.State.Connected -> DshSuccess
                                    is DshConnection.State.Error -> MaterialTheme.colorScheme.error
                                    is DshConnection.State.Connecting -> DshBrand
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    Modifier.size(10.dp)
                                        .background(dotColor, DshShape.pill),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    p.remark.ifBlank { p.url },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { onEditHost(p.id) }) { Text("编辑") }
                            }
                            Text(
                                p.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            if (connState is DshConnection.State.Connected) {
                                val connected = connState as DshConnection.State.Connected
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "已连接" + (connected.hostVersion?.let { " · 主机版本 $it" } ?: " · 版本未知"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DshSuccess,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已保存的主机",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (sortedProfiles.isEmpty()) {
                item {
                    Text(
                        "还没有主机配置：扫码或点下方「添加主机」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(sortedProfiles, key = { it.id }) { p ->
                    val isActive = p.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = { if (!isActive) connectTo(p) },
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.remark.ifBlank { p.url }, style = MaterialTheme.typography.titleSmall)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DshBrand,
                                        )
                                    }
                                }
                                Text(
                                    p.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                p.lastErrorCode?.let { code ->
                                    runCatching { ConnectionErrorCode.valueOf(code) }.getOrNull()?.let { c ->
                                        Text(
                                            "上次错误：${ErrorMessages.reason(c)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onEditHost(null) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = DshShape.pill,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加主机")
                }
                Spacer(Modifier.height(20.dp))
                val versionName = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                Text(
                    "DSH Remote v$versionName · 非官方客户端 · 数据只存你的手机与你的服务器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
