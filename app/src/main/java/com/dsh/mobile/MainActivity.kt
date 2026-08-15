package com.dsh.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.dsh.mobile.data.ApkInstaller
import com.dsh.mobile.data.AppearanceConfig
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.ReleaseInfo
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.data.TokenUsageWatcher
import com.dsh.mobile.data.UpdateChecker
import com.dsh.mobile.data.UpdateMirrors
import com.dsh.mobile.ui.navigation.AppNavigation
import com.dsh.mobile.ui.theme.DshTheme
import com.dsh.mobile.ui.theme.FontConfig
import com.dsh.mobile.ui.theme.ThemeDef
import com.dsh.mobile.ui.theme.ThemeRegistry
import com.dsh.mobile.ui.theme.ThemeRepository
import com.dsh.mobile.ui.theme.isLightMode
import com.dsh.mobile.ui.theme.surfaceAlphaFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onStart() {
        super.onStart()
        DshApplication.isAppInForeground = true
    }

    override fun onStop() {
        super.onStop()
        DshApplication.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshApplication
        val settingsStore = SettingsStore(this)
        consumeDeepLink(intent)
        ThemeRepository.init(applicationContext)

        setContent {
            val view = LocalView.current
            val context = LocalContext.current
            val themeModeRaw by settingsStore.themeMode.collectAsState(initial = "blue")
            val customThemes by ThemeRepository.themes.collectAsState()
            // 兼容旧值：dark→blue，light→warm，system→blue
            val themeMode = when (themeModeRaw) {
                "warm", "light" -> "warm"
                else -> themeModeRaw
            }
            val theme: ThemeDef = remember(themeMode, customThemes) {
                ThemeRegistry.resolve(themeMode, customThemes)
            }
            val appearance by settingsStore.appearance.collectAsState(initial = AppearanceConfig())
            val bgUri = appearance.bgUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val bgActive = bgUri != null
            // 字体设置（对齐 dsh-font 概念：界面字体 + 代码字体，即选即生效）
            val uiFont by settingsStore.uiFont.collectAsState(initial = "")
            val codeFont by settingsStore.codeFont.collectAsState(initial = "")

            // 状态栏图标对比（浅色主题=深色图标）
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = theme.light
            }

            // Android 13+ 通知运行时权限（缺权限时通知静默不显示）
            val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            DshTheme(
                theme = theme,
                bgActive = bgActive,
                glass = appearance.panelGlass,
                fonts = FontConfig(ui = uiFont.ifBlank { null }, code = codeFont.ifBlank { null }),
            ) {
                val (bgAlpha, _, _) = surfaceAlphaFor(appearance.panelGlass, bgActive)

                Box(Modifier.fillMaxSize()) {
                    // — 深蓝主题专属背景：鲸鱼娘宫殿夜景（dsh-deep-whale 皮肤资产，低透明度铺底；
                    //   用户自定义背景图优先）—
                    if (bgUri == null && theme.id == "blue") {
                        val palaceBitmap = remember(theme.id) {
                            decodeSampledBitmap(context, R.drawable.maid_palace_night)
                        }
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                            },
                            update = { iv ->
                                if (iv.tag !== "palace" && palaceBitmap != null) {
                                    iv.tag = "palace"
                                    iv.setImageBitmap(palaceBitmap)
                                }
                                iv.alpha = 0.30f
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.35f)),
                        )
                    }

                    // — 背景图层：满清晰度图片 + 蒙层（深色→黑 / 浅色→白），对齐桌面端模板 —
                    if (bgUri != null) {
                        val bgBitmap = remember(bgUri) { decodeSampledBitmap(context, bgUri) }
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                            },
                            update = { iv ->
                                // 只在该视图尚未挂载该图时重新设置，避免每次重组都重新解码
                                if (iv.tag !== bgUri && bgBitmap != null) {
                                    iv.tag = bgUri
                                    iv.setImageBitmap(bgBitmap)
                                }
                                iv.colorFilter = ColorMatrixColorFilter(
                                    ColorMatrix().apply { setSaturation(appearance.bgSaturate.coerceIn(0f, 2f)) },
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (appearance.bgBlur > 0.5f) {
                                        iv.setRenderEffect(
                                            RenderEffect.createBlurEffect(
                                                appearance.bgBlur, appearance.bgBlur, Shader.TileMode.CLAMP,
                                            ),
                                        )
                                    } else {
                                        iv.setRenderEffect(null)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = appearance.bgOpacity.coerceIn(0.05f, 1f) },
                        )
                        if (appearance.bgDim > 0.001f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        (if (theme.light) Color.White else Color.Black)
                                            .copy(alpha = appearance.bgDim.coerceIn(0f, 0.85f)),
                                    ),
                            )
                        }
                    }

                    // — 内容层：背景图开启时表面透明，玻璃通透由主题色板控制 —
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (bgActive) Color.Transparent else MaterialTheme.colorScheme.background,
                    ) {
                        val navController = rememberNavController()
                        AppNavigation(
                            navController = navController,
                            connection = app.connection,
                        )
                    }

                    // — 资源更新提示层（启动自动检测；无更新静默，有更新询问是否下载）—
                    UpdatePromptOverlay(context)

                    // — 全局 token 用量监听（假 Pro 扣费）：PC 端任何会话的回合都结算，
                    //   不限移动端打开的会话（后台服务连接另行挂载，双连接按 seq 去重）—
                    LaunchedEffect(Unit) {
                        app.connection.events.collect { ev ->
                            if (ev is DshConnection.Event.SessionEvent) {
                                TokenUsageWatcher.onSessionEvent(ev.sessionId, ev.event)
                            }
                        }
                    }

                    // — 全局亮度（夜间模式）：黑层叠加，不拦截触摸 —
                    if (appearance.brightness < 1f) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Color.Black.copy(
                                        alpha = ((1f - appearance.brightness) * 0.7f).coerceIn(0f, 0.85f),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun consumeDeepLink(intent: Intent?) {
        intent?.getStringExtra("open_session")?.takeIf { it.isNotBlank() }?.let {
            DshApplication.pendingOpenSessionId = it
        }
    }
}

/**
 * 资源更新提示层：
 * - App 打开时后台自动检查最新 Release（走镜像），无更新 → 完全不显示
 * - 有更新 → 弹窗「检测到资源更新，是否下载？」（是 / 否）
 * - 下载全程透明：当前镜像站、是否成功开始、实时进度与速度；镜像失败自动切换并展示
 */
@Composable
private fun UpdatePromptOverlay(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("idle") } // idle/available/downloading/done/error
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var ev by remember { mutableStateOf<UpdateChecker.DownloadEvent?>(null) }
    var failLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var skippedTag by remember { mutableStateOf<String?>(null) }

    // 启动自动检查（后台；无更新则静默）
    LaunchedEffect(Unit) {
        val latest = UpdateChecker.checkLatest() ?: return@LaunchedEffect
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        if (UpdateChecker.isNewer(latest.tagName, current)) {
            info = latest
            phase = "available"
        }
    }

    fun startDownload() {
        val r = info ?: return
        val asset = UpdateChecker.pickApk(r.assets)
        if (asset == null) {
            errMsg = "发布中没有可安装的 APK"
            phase = "error"
            return
        }
        phase = "downloading"
        ev = null
        failLog = emptyList()
        val target = File(context.cacheDir, "update/" + asset.name)
        scope.launch {
            try {
                UpdateChecker.downloadApkFlow(asset.browserDownloadUrl, target)
                    .flowOn(Dispatchers.IO)
                    .collect { e ->
                        ev = e
                        when (e.phase) {
                            UpdateChecker.PHASE_FAILED ->
                                failLog = failLog + "✗ ${e.mirrorName} 失败，自动切换下一源…"
                            UpdateChecker.PHASE_DONE -> {
                                apkFile = target
                                phase = "done"
                            }
                        }
                    }
            } catch (ex: Exception) {
                errMsg = ex.message
                phase = "error"
            }
        }
    }

    fun install() {
        val f = apkFile ?: return
        val err = ApkInstaller.install(context, f)
        if (err != null) {
            errMsg = err
            phase = "error"
        }
    }

    fun fmtSpeed(bps: Long): String = when {
        bps >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB/s", bps / 1024f / 1024f)
        bps >= 1024 -> String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024f)
        else -> "$bps B/s"
    }

    val versionTag = info?.tagName?.removePrefix("v") ?: ""

    when (phase) {
        "available" -> AlertDialog(
            onDismissRequest = {},
            title = { Text("检测到资源更新") },
            text = {
                Text(
                    "发现新版本 v$versionTag，是否下载？\n下载将自动选择最快的镜像站。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { startDownload() }) { Text("是，下载") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        skippedTag = info?.tagName
                        phase = "idle"
                    },
                ) { Text("否") }
            },
        )

        "downloading" -> {
            val e = ev
            val statusText = when (e?.phase) {
                null -> "准备中…"
                UpdateChecker.PHASE_CONNECTING -> "正在连接 ${e.mirrorName}…"
                UpdateChecker.PHASE_DOWNLOADING ->
                    if (e.progress <= 0f) "已连接 ${e.mirrorName}，开始下载…"
                    else "正在从 ${e.mirrorName} 下载（第 ${e.mirrorIndex + 1}/${UpdateMirrors.DOWNLOAD_MIRRORS.size} 个源）"
                else -> "下载中…"
            }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载 v$versionTag") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        e?.let {
                            Text(
                                "源站：${it.mirrorName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                it.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val progress = e?.progress ?: 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                        Text(
                            "${(progress * 100).toInt()}% · ${fmtSpeed(e?.speedBytesPerSec ?: 0L)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        failLog.takeLast(3).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        }

        "done" -> AlertDialog(
            onDismissRequest = { phase = "idle" },
            title = { Text("下载完成") },
            text = {
                Text(
                    "v$versionTag 已下载到本地：\n${apkFile?.absolutePath ?: ""}\n是否立即安装？",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { install() }) { Text("立即安装") }
            },
            dismissButton = {
                TextButton(onClick = { phase = "idle" }) { Text("稍后") }
            },
        )

        "error" -> AlertDialog(
            onDismissRequest = { phase = "idle" },
            title = { Text("下载失败") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(errMsg ?: "未知错误", style = MaterialTheme.typography.bodySmall)
                    failLog.takeLast(4).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { startDownload() }) { Text("重试") }
            },
            dismissButton = {
                TextButton(onClick = { phase = "idle" }) { Text("关闭") }
            },
        )
    }
}

/** 按屏幕尺寸采样解码背景图（大图不爆内存、不反复解码） */
private fun decodeSampledBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxW = context.resources.displayMetrics.widthPixels
        val maxH = context.resources.displayMetrics.heightPixels
        var sample = 1
        while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}

/** 资源图采样解码（主题内置背景） */
private fun decodeSampledBitmap(context: android.content.Context, resId: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxW = context.resources.displayMetrics.widthPixels
        val maxH = context.resources.displayMetrics.heightPixels
        var sample = 1
        while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeResource(context.resources, resId, opts)
    } catch (_: Exception) {
        null
    }
}
