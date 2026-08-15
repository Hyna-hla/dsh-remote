package com.dsh.mobile

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
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.rememberNavController
import com.dsh.mobile.data.AppearanceConfig
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.ui.navigation.AppNavigation
import com.dsh.mobile.ui.theme.DshTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshApplication
        val settingsStore = SettingsStore(this)

        setContent {
            val themeMode by settingsStore.themeMode.collectAsState(initial = "dark")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "light" -> false
                "system" -> systemDark
                else -> true
            }
            val appearance by settingsStore.appearance.collectAsState(initial = AppearanceConfig())
            val bgUri = appearance.bgUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val bgActive = bgUri != null

            DshTheme(darkTheme = darkTheme, bgActive = bgActive, glass = appearance.panelGlass) {
                val (bgAlpha, _, _) = surfaceAlphaFor(appearance.panelGlass, bgActive)

                Box(Modifier.fillMaxSize()) {
                    // — 自定义背景图层：满清晰度图片 + 蒙层（深色→黑 / 浅色→白），对齐桌面端模板 —
                    if (bgUri != null) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                }
                            },
                            update = { view ->
                                view.setImageURI(bgUri)
                                // 饱和度（ColorMatrix，0..2）
                                view.colorFilter = ColorMatrixColorFilter(
                                    ColorMatrix().apply {
                                        setSaturation(appearance.bgSaturate.coerceIn(0f, 2f))
                                    },
                                )
                                // 模糊（RenderEffect，仅 API 31+；低版本跳过，优雅降级）
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (appearance.bgBlur > 0.5f) {
                                        view.setRenderEffect(
                                            RenderEffect.createBlurEffect(
                                                appearance.bgBlur,
                                                appearance.bgBlur,
                                                Shader.TileMode.CLAMP,
                                            ),
                                        )
                                    } else {
                                        view.setRenderEffect(null)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = appearance.bgOpacity.coerceIn(0.05f, 1f) },
                        )
                        // 蒙层：独立于图片的暗/亮层，保证文字可读
                        if (appearance.bgDim > 0.001f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        (if (darkTheme) Color.Black else Color.White)
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
}
