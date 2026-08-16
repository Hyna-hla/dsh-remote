package com.dsh.mobile.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.ApkInstaller
import com.dsh.mobile.data.AppearanceConfig
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.McpServer
import com.dsh.mobile.data.ReleaseInfo
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.data.Sha3
import com.dsh.mobile.data.VaultStatus
import com.dsh.mobile.data.UpdateChecker
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshError
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.ThemeRegistry
import com.dsh.mobile.ui.theme.ThemeRepository
import com.dsh.mobile.ui.theme.ThemeStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    connection: DshConnection,
    onBack: () -> Unit,
    onUpgrade: () -> Unit,
    onOpenMarket: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connState by connection.state.collectAsState()
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var appearance by remember { mutableStateOf(AppearanceConfig()) }
    var backgroundNotify by remember { mutableStateOf(true) }
    var notifyApprovals by remember { mutableStateOf(true) }
    var notifyCompletion by remember { mutableStateOf(true) }
    var themeMode by remember { mutableStateOf("blue") }
    var autoModel by remember { mutableStateOf(true) }
    var uiFont by remember { mutableStateOf("") }
    var codeFont by remember { mutableStateOf("") }
    var fontPicker by remember { mutableStateOf<String?>(null) } // "ui" / "code"
    var notifGranted by remember { mutableStateOf(true) }
    var themeImportError by remember { mutableStateOf<String?>(null) }
    var showAppearanceDetail by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("") }
    val customThemes by ThemeRepository.themes.collectAsState()
    val biometricAvailable = remember {
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        notifGranted = g
    }
    val themePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        themeImportError = null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            themeImportError = "无法读取主题文件"
            return@rememberLauncherForActivityResult
        }
        // 自动识别：PK 魔数 = zip 主题包，否则按 JSON 文本
        val def = ThemeRepository.importPayload(bytes, context)
        if (def == null) {
            themeImportError = "主题格式无效（zip 包需含 theme.json；JSON 需完整色板，格式见 docs/theme-package-format.md）"
            return@rememberLauncherForActivityResult
        }
        themeMode = def.id
        scope.launch { settingsStore.setThemeMode(def.id) }
    }

    LaunchedEffect(Unit) {
        appearance = settingsStore.appearance.first()
        backgroundNotify = settingsStore.backgroundNotify.first()
        notifyApprovals = settingsStore.notifyApprovals.first()
        notifyCompletion = settingsStore.notifyCompletion.first()
        themeMode = settingsStore.themeMode.first()
        autoModel = settingsStore.autoModel.first()
        uiFont = settingsStore.uiFont.first()
        codeFont = settingsStore.codeFont.first()
        biometricEnabled = settingsStore.biometricLockEnabled.first()
        deviceName = settingsStore.deviceName.first()
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

    fun setNotifyApprovals(on: Boolean) {
        notifyApprovals = on
        scope.launch { settingsStore.setNotifyApprovals(on) }
    }

    fun setNotifyCompletion(on: Boolean) {
        notifyCompletion = on
        scope.launch { settingsStore.setNotifyCompletion(on) }
    }

    /** 跳系统「通知渠道设置」页（可单独调审批/完成渠道的铃声、勿扰等） */
    fun openChannelSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeRegistry.available(customThemes).forEach { t ->
                            FilterChip(
                                selected = themeMode == t.id,
                                onClick = {
                                    themeMode = t.id
                                    scope.launch { settingsStore.setThemeMode(t.id) }
                                },
                                label = { Text(t.name) },
                            )
                        }
                    }
                    if (customThemes.isNotEmpty()) {
                        customThemes.forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val preview = remember(t.id) {
                                    runCatching {
                                        ThemeStore(context).previewFile(t.id)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                                    }.getOrNull()
                                }
                                if (preview != null) {
                                    Image(
                                        preview.asImageBitmap(), null,
                                        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Box(
                                        Modifier.size(12.dp).clip(CircleShape).background(t.colors.brand),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    t.name + (t.version?.let { " v" + it } ?: "") + "（自定义）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        if (themeMode == t.id) {
                                            themeMode = "blue"
                                            scope.launch { settingsStore.setThemeMode("blue") }
                                        }
                                        ThemeRepository.remove(t.id, context)
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Delete, "删除主题",
                                        Modifier.size(16.dp), tint = DshError,
                                    )
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { themePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "text/plain", "application/octet-stream")) }) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("导入主题 (zip/json)")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenMarket) {
                            Icon(Icons.Default.Storefront, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("主题市场")
                        }
                        themeImportError?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = DshError,
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    // 字体设置（对齐 dsh-font 概念：界面字体 + 代码字体分离，即选即生效）
                    Text(
                        "字体",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fontPicker = "ui" }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("界面字体", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            UI_FONTS.firstOrNull { it.second == uiFont }?.first ?: "默认",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DshBrand,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fontPicker = "code" }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("代码字体", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            CODE_FONTS.firstOrNull { it.second == codeFont }?.first ?: "默认",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DshBrand,
                        )
                    }
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

                    // 具体外观细节默认折叠，避免设置页冗杂
                    if (showAppearanceDetail) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )
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
                    }

                    // 折叠开关：卡片最底部的向下/向上小箭头
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppearanceDetail = !showAppearanceDetail },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (showAppearanceDetail) "收起外观细节" else "更多外观设置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            if (showAppearanceDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("审批与确认提醒", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "桌面端请求工具审批/确认时横幅提醒（需上方总开关开启）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = notifyApprovals,
                            onCheckedChange = { setNotifyApprovals(it) },
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text("任务完成提醒", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "会话任务执行完成时提醒（独立系统渠道，可单独调铃声/勿扰）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = notifyCompletion,
                            onCheckedChange = { setNotifyCompletion(it) },
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
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { openChannelSettings() }) {
                            Text("通知渠道设置")
                        }
                    }
                }
            }

            Text("安全", style = MaterialTheme.typography.titleMedium)
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
                            Text("生物锁", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (biometricAvailable) "回到前台需生物识别验证后才能操控电脑" else "此设备无可用生物识别",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = biometricAvailable && biometricEnabled,
                            enabled = biometricAvailable,
                            onCheckedChange = {
                                biometricEnabled = it
                                scope.launch { settingsStore.setBiometricLock(it) }
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = {
                            deviceName = it
                            scope.launch { settingsStore.setDeviceName(it) }
                        },
                        label = { Text("设备名称") },
                        placeholder = { Text(Build.MODEL) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "配对其他设备时显示的名称（留空 = 手机型号）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    UpdateSection(currentVersion = versionName, context = context, scope = scope)
                    // 趣味彩蛋入口：假 Pro 订阅中心
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpgrade() }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.WorkspacePremium,
                                null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Pro 订阅（趣味彩蛋）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "¥0.00",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SettingInfoRow("协议", "dsh-api (client-request / WS+SSE mux)")
                    SettingInfoRow("后端", "DeepSeek Harness")
                    SettingInfoRow("主题", "DSH 浅色/深色 · 背景图/蒙层/面板通透可调")
                }
            }

            // 保险库（dsh-encrypt 联动）：手机端输入密码解锁 PC 端凭证保险库——与 web 端走同一路由
            // （/api/credentials.unlock），密码本地 SHA3-256 后仅上传摘要，明文不上行；
            // 解锁为进程全局状态，手机解锁后 PC web 端同步解锁。未装 dsh-encrypt → 「不可用」。
            Text("保险库", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val connected = connState is DshConnection.State.Connected
                    var vault by remember { mutableStateOf<VaultStatus?>(null) }
                    var vaultLoaded by remember { mutableStateOf(false) }
                    var rememberedDigest by remember { mutableStateOf<String?>(null) }
                    var showUnlockDialog by remember { mutableStateOf(false) }
                    LaunchedEffect(connected) {
                        if (connected) {
                            vault = connection.vaultStatus()
                            vaultLoaded = true
                            rememberedDigest = settingsStore.vaultDigest.first()
                        } else {
                            vault = null
                            vaultLoaded = false
                        }
                    }
                    when {
                        !connected -> Text(
                            "连接后可查看保险库状态",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        !vaultLoaded -> Text(
                            "加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        vault == null -> Text(
                            "不可用（PC 端未安装 dsh-encrypt 插件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        vault != null && vault!!.locked -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (vault!!.lockoutRetryAfterMs > 0)
                                    "已锁定（防爆破冷却 ${vault!!.lockoutRetryAfterMs / 1000}s）"
                                else "已锁定 · 凭证不可用",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DshError,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { showUnlockDialog = true }) { Text("解锁") }
                        }
                        vault != null && vault!!.encrypted -> Text(
                            "已解锁",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DshSuccess,
                        )
                        else -> Text(
                            "未设置密码（明文存储）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showUnlockDialog) {
                        VaultUnlockDialog(
                            hasRemembered = rememberedDigest != null,
                            onDismiss = { showUnlockDialog = false },
                            onUnlock = { password, remember ->
                                scope.launch {
                                    val digest = rememberedDigest?.takeIf { password.isBlank() }
                                        ?: Sha3.digest256Hex(password)
                                    connection.vaultUnlock(digest)
                                    // 成功/失败统一以新一轮 status 呈现（密码错会触发防爆破计数，
                                    // 不做弹窗报错以免诱导连续尝试；冷却剩余由 lockout 字段带出）
                                    settingsStore.setVaultDigest(if (remember) digest else null)
                                    vault = connection.vaultStatus()
                                    vaultLoaded = true
                                    showUnlockDialog = false
                                }
                            },
                        )
                    }
                }
            }

            // MCP 服务（S5）：PC 插件 mcp/list 枚举的 MCP 服务与工具（serverName + 工具数徽章；
            // 上游无连接态 API，status 恒 unknown → 显示「连接状态未知」）；未连接/空 → 「无 MCP 服务」
            Text("MCP 服务", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val connected = connState is DshConnection.State.Connected
                    var mcpServers by remember { mutableStateOf<List<McpServer>?>(null) }
                    LaunchedEffect(connected) {
                        mcpServers = if (connected) connection.mcpList() else emptyList()
                    }
                    val servers = mcpServers
                    when {
                        !connected -> Text(
                            "无 MCP 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        servers == null -> Text(
                            "加载中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        servers.isEmpty() -> Text(
                            "无 MCP 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> servers.forEach { s ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    s.serverName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        "${s.tools.size} 工具",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "连接状态未知",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Text("开发人员", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingInfoRow("开发", "残星会虚质空间分部 达妮娅同学")
                    SettingInfoRow("B站", "最喜欢达妮娅了")
                    SettingInfoRow("签名", "CN=残星会虚质空间分部达妮娅同学")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "© 2026 残星会·虚质空间分部。本应用及其全部界面设计、主题素材与代码版权归开发者所有；" +
                            "未经授权禁止商用、二传或冒名分发。鲸鱼娘素材（CC BY-NC-SA 4.0）署名链见 README。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.5,
                    )
                }
            }
        }
    }

    // 字体选择对话框（UI 字体 / 代码字体）
    fontPicker?.let { kind ->
        FontPickerDialog(
            title = if (kind == "ui") "界面字体" else "代码字体",
            options = if (kind == "ui") UI_FONTS else CODE_FONTS,
            current = if (kind == "ui") uiFont else codeFont,
            onDismiss = { fontPicker = null },
            onPick = { family ->
                if (kind == "ui") {
                    uiFont = family
                    scope.launch { settingsStore.setUiFont(family) }
                } else {
                    codeFont = family
                    scope.launch { settingsStore.setCodeFont(family) }
                }
                fontPicker = null
            },
        )
    }
}

/** 界面字体选项（Android 系统字体族；label → 族名，"" = 主题默认） */
private val UI_FONTS = listOf(
    "默认（主题字体）" to "",
    "无衬线（标准黑体）" to "sans-serif",
    "细黑体" to "sans-serif-light",
    "中黑体" to "sans-serif-medium",
    "衬线（宋体感）" to "serif",
    "圆体（casual）" to "casual",
    "窄体（condensed）" to "sans-serif-condensed",
    "等宽（monospace）" to "monospace",
)

/** 代码字体选项（等宽族；"" = Monospace） */
private val CODE_FONTS = listOf(
    "默认（Monospace）" to "",
    "衬线等宽（serif-monospace）" to "serif-monospace",
    "窄等宽（condensed）" to "sans-serif-condensed",
    "系统无衬线" to "sans-serif",
)

/** 字体选择对话框：选项以各自字体渲染（对齐 dsh-font 的预览体验） */
@Composable
private fun FontPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (label, family) ->
                    val font = if (family.isEmpty()) null
                    else runCatching {
                        androidx.compose.ui.text.font.FontFamily(
                            android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL),
                        )
                    }.getOrNull()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(family) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == family, onClick = { onPick(family) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = font),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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

/**
 * 应用内检查更新：
 *   idle → checking → latest / found → downloading → downloaded →（系统安装器）
 * 检查走 GitHub Releases latest API；下载取 release 的 debug 主包资产（无主包时回退 -min 精简包；流式带进度）；
 * 安装经 FileProvider 交给系统安装器（用户确认，无需额外权限）。
 */
@Composable
private fun UpdateSection(
    currentVersion: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var phase by remember { mutableStateOf("idle") } // idle/checking/latest/found/downloading/downloaded/error
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var err by remember { mutableStateOf<String?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }

    val check: () -> Unit = {
        phase = "checking"
        err = null
        scope.launch {
            val r = UpdateChecker.checkLatest()
            if (r == null) {
                phase = "error"; err = "无法连接更新服务器（GitHub Releases）"
            } else if (UpdateChecker.isNewer(r.tagName, currentVersion)) {
                info = r; phase = "found"
            } else {
                phase = "latest"
            }
        }
    }
    val download: () -> Unit = {
        val asset = UpdateChecker.pickApk(info?.assets ?: emptyList())
        if (asset == null) {
            phase = "error"; err = "发布中没有可安装的 APK"
        } else {
            phase = "downloading"; progress = 0f
            val target = File(context.cacheDir, "update/" + asset.name)
            scope.launch {
                try {
                    UpdateChecker.downloadApk(asset.browserDownloadUrl, target) { p -> progress = p }
                    apkFile = target
                    phase = "downloaded"
                } catch (e: Exception) {
                    phase = "error"; err = e.message
                }
            }
        }
    }
    val install: () -> Unit = {
        val f = apkFile
        if (f != null && f.exists()) {
            // 多策略安装：系统安装器（INSTALL_PACKAGE / VIEW 双 intent）→ PackageInstaller 会话兜底
            val error = ApkInstaller.install(context, f)
            if (error != null) {
                phase = "error"
                err = error
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "更新",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (phase) {
            "idle" -> TextButton(onClick = check) { Text("检查更新") }
            "checking" -> Text("检查中…", style = MaterialTheme.typography.bodyMedium)
            "latest" -> Text("已是最新版本", style = MaterialTheme.typography.bodyMedium, color = DshSuccess)
            "found" -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    "发现新版本 v${info?.tagName?.removePrefix("v")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DshBrand,
                )
                // Release 说明摘录：纯文本化 + 去链接标记，最多 6 行（完整日志看仓库）
                info?.body?.takeIf { it.isNotBlank() }?.let { body ->
                    Text(
                        body
                            .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
                            .replace('#', '№')
                            .replace("**", "")
                            .lineSequence()
                            .map(String::trim)
                            .filter { it.isNotEmpty() }
                            .take(6)
                            .joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                }
                TextButton(onClick = download) { Text("下载更新") }
            }
            "downloading" -> Column(horizontalAlignment = Alignment.End) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.width(120.dp).height(4.dp),
                )
                Text(
                    "下载中 ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            "downloaded" -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    "下载完成 v${info?.tagName?.removePrefix("v")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DshSuccess,
                )
                TextButton(onClick = install) { Text("安装") }
            }
            else -> Column(horizontalAlignment = Alignment.End) {
                Text(
                    err ?: "更新失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = DshError,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = check) { Text("重试") }
            }
        }
    }
}

/** 保险库解锁对话框：密码留空 = 使用已记住的摘要直接解锁。 */
@Composable
private fun VaultUnlockDialog(
    hasRemembered: Boolean,
    onDismiss: () -> Unit,
    onUnlock: (password: String, remember: Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解锁保险库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasRemembered) Text(
                    "已记住密码——输入框留空直接解锁",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text("记住密码（本机加密存储）", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "密码仅在本机求 SHA3-256 摘要后上传（与 web 端一致），解锁后 PC 端同步生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUnlock(password, remember) },
                enabled = password.isNotBlank() || hasRemembered,
            ) { Text("解锁") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
