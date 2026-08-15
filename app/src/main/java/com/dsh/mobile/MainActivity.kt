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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.dsh.mobile.data.AppearanceConfig
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.ui.navigation.AppNavigation
import com.dsh.mobile.ui.theme.DshTheme
import com.dsh.mobile.ui.theme.isLightMode
import com.dsh.mobile.ui.theme.surfaceAlphaFor

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

        setContent {
            val view = LocalView.current
            val context = LocalContext.current
            val themeModeRaw by settingsStore.themeMode.collectAsState(initial = "blue")
            // 兼容旧值：dark→blue，light→warm，system→blue
            val themeMode = when (themeModeRaw) {
                "warm", "light" -> "warm"
                "black" -> "black"
                else -> "blue"
            }
            val appearance by settingsStore.appearance.collectAsState(initial = AppearanceConfig())
            val bgUri = appearance.bgUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val bgActive = bgUri != null

            // 状态栏图标对比（暖白=深色图标）
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLightMode(themeMode)
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

            DshTheme(mode = themeMode, bgActive = bgActive, glass = appearance.panelGlass) {
                val (bgAlpha, _, _) = surfaceAlphaFor(appearance.panelGlass, bgActive)

                Box(Modifier.fillMaxSize()) {
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
                                        (if (isLightMode(themeMode)) Color.White else Color.Black)
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
