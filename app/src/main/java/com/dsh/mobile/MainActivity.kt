package com.dsh.mobile

import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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

        setContent {
            DshTheme {
                val context = LocalContext.current
                val settingsStore = remember { SettingsStore(context) }
                val appearance by settingsStore.appearance.collectAsState(initial = AppearanceConfig())

                Box(Modifier.fillMaxSize()) {
                    // 自定义背景图（设置 → 外观）
                    val bgUri = appearance.bgUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    if (bgUri != null) {
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setImageURI(bgUri)
                                }
                            },
                            update = { it.setImageURI(bgUri) },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = appearance.bgOpacity },
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background.copy(alpha = if (bgUri != null) 0.93f else 1f),
                    ) {
                        val navController = rememberNavController()
                        AppNavigation(
                            navController = navController,
                            connection = app.connection,
                        )
                    }

                    // 全局亮度（夜间模式）：黑层叠加，不拦截触摸
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
