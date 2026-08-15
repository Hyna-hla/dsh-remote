package com.dsh.mobile.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.AppearanceConfig
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshError
import com.dsh.mobile.ui.theme.DshSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connection: DshConnection,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connState by connection.state.collectAsState()
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var appearance by remember { mutableStateOf(AppearanceConfig()) }
    var backgroundNotify by remember { mutableStateOf(true) }
    var themeMode by remember { mutableStateOf("blue") }
    var autoModel by remember { mutableStateOf(true) }
    var notifGranted by remember { mutableStateOf(true) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        notifGranted = g
    }

    LaunchedEffect(Unit) {
        appearance = settingsStore.appearance.first()
        backgroundNotify = settingsStore.backgroundNotify.first()
        themeMode = settingsStore.themeMode.first()
        autoModel = settingsStore.autoModel.first()
        notifGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setBackgroundNotify(enabled: Boolean) {
        backgroundNotify = enabled
        scope.launch { settingsStore.setBackgroundNotify(enabled) }
        if (enabled) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DshConnectionService::class.java))
            }
        } else {
            runCatching { context.stopService(Intent(context, DshConnectionService::class.java)) }
        }
    }

    fun persist(next: AppearanceConfig) {
        appearance = next
        scope.launch { settingsStore.saveAppearance(next) }
    }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            persist(appearance.copy(bgUri = uri.toString()))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "主题模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "blue" to "深蓝",
                            "black" to "纯黑",
                            "warm" to "暖白",
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = {
                                    themeMode = mode
                                    scope.launch { settingsStore.setThemeMode(mode) }
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { bgPicker.launch(arrayOf("image/*")) }) {
                            Icon(Icons.Default.Image, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (appearance.bgUri != null) "更换背景图" else "选择背景图")
                        }
                        if (appearance.bgUri != null) {
                            OutlinedButton(onClick = { persist(appearance.copy(bgUri = null)) }) {
                                Text("移除")
                            }
                        }
                    }

                    // 一键预设（对齐桌面端）
                    Text(
                        "一键预设",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            BgPreset("通透玻璃", 80f, 4f, 0.2f, 1.1f),
                            BgPreset("电影质感", 40f, 0f, 0.55f, 1.2f),
                            BgPreset("纯净原图", 95f, 0f, 0.08f, 1f),
                            BgPreset("柔和梦境", 65f, 12f, 0.25f, 1.05f),
                        ).forEach { (name, glass, blur, dim, saturate) ->
                            AssistChip(
                                onClick = {
                                    persist(
                                        appearance.copy(
                                            bgOpacity = 1f,
                                            bgBlur = blur,
                                            bgDim = dim,
                                            bgSaturate = saturate,
                                            panelGlass = glass,
                                        ),
                                    )
                                },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }

                    Text(
                        "图像不透明度：${(appearance.bgOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.bgOpacity,
                        onValueChange = { persist(appearance.copy(bgOpacity = it)) },
                        valueRange = 0.05f..1f,
                    )
                    Text(
                        "模糊：${appearance.bgBlur.toInt()}px",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.bgBlur,
                        onValueChange = { persist(appearance.copy(bgBlur = it)) },
                        valueRange = 0f..30f,
                    )
                    Text(
                        "蒙层浓度：${(appearance.bgDim * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.bgDim,
                        onValueChange = { persist(appearance.copy(bgDim = it)) },
                        valueRange = 0f..0.85f,
                    )
                    Text(
                        "饱和度：${(appearance.bgSaturate * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.bgSaturate,
                        onValueChange = { persist(appearance.copy(bgSaturate = it)) },
                        valueRange = 0.5f..1.5f,
                    )
                    Text(
                        "面板通透：${appearance.panelGlass.toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.panelGlass,
                        onValueChange = { persist(appearance.copy(panelGlass = it)) },
                        valueRange = 0f..100f,
                    )
                    Text(
                        "屏幕亮度：${(appearance.brightness * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = appearance.brightness,
                        onValueChange = { persist(appearance.copy(brightness = it)) },
                        valueRange = 0.4f..1f,
                    )
                    Text(
                        "背景图满清晰度显示，蒙层独立压暗/提亮保证文字可读；面板通透越高界面越透明。亮度调低 = 夜间模式（整屏变暗，不挡操作）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自适应模型", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "按任务难度自动选 Flash / Pro（短问答→Flash，复杂任务→Pro）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = autoModel,
                            onCheckedChange = {
                                autoModel = it
                                scope.launch { settingsStore.setAutoModel(it) }
                            },
                        )
                    }
                }
            }

            Text("提醒", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台审批提醒", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "App 在后台时，桌面端请求审批/确认会推送横幅通知（通知栏常驻一个连接小图标）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = backgroundNotify,
                            onCheckedChange = { setBackgroundNotify(it) },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (notifGranted) "通知权限：已开启" else "通知权限：未开启（审批/确认/完成不会弹通知）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notifGranted) DshSuccess else MaterialTheme.colorScheme.error,
                        )
                        if (!notifGranted) {
                            TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("授权")
                            }
                        }
                    }
                }
            }

            Text("连接", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when (connState) {
                                is DshConnection.State.Connected -> Icons.Default.CheckCircle
                                is DshConnection.State.Error -> Icons.Default.ErrorOutline
                                else -> Icons.Default.CloudOff
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = when (connState) {
                                is DshConnection.State.Connected -> DshSuccess
                                is DshConnection.State.Error -> DshError
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when (connState) {
                                is DshConnection.State.Connected -> "已连接 " + (connState as DshConnection.State.Connected).baseUrl
                                is DshConnection.State.Error -> "连接错误"
                                is DshConnection.State.Connecting -> "连接中…"
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = connState is DshConnection.State.Connected,
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("断开并重新连接")
                    }
                }
            }

            Text("远程连接（cpolar）", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "1. 在 PC 访问 cpolar.com 注册并下载安装客户端\n" +
                            "2. 运行：cpolar http 8787（8787 是 DSH 服务端口）\n" +
                            "3. 客户端会显示一个公网域名，形如 https://xxx.cpolar.top\n" +
                            "4. 把这个域名填进 App 的服务器地址即可，任何网络都能连\n" +
                            "（或在 DSH 设置 → 远程控制里一键生成地址）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.6,
                    )
                }
            }

            Text("关于", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 版本号从系统包信息读取，避免硬编码不同步
                    val versionName = remember {
                        runCatching {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        }.getOrNull() ?: "?"
                    }
                    SettingInfoRow("应用", "DSH Remote v$versionName")
                    SettingInfoRow("协议", "dsh-api (client-request / WS+SSE mux)")
                    SettingInfoRow("后端", "DeepSeek Harness")
                    SettingInfoRow("主题", "DSH 浅色/深色 · 背景图/蒙层/面板通透可调")
                }
            }
        }
    }
}

/** 背景一键预设（与桌面端 dsh-beautify 同名模板对齐） */
private data class BgPreset(
    val name: String,
    val glass: Float,
    val blur: Float,
    val dim: Float,
    val saturate: Float,
)

@Composable
private fun SettingInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
