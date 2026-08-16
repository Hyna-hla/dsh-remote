package com.dsh.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.MarketTheme
import com.dsh.mobile.data.ThemeMarket
import com.dsh.mobile.ui.theme.ThemeRepository
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import kotlinx.coroutines.launch

/**
 * 主题市场：dsh-theme-market 仓库的清单浏览 + 一键安装/更新。
 * 卡片渲染色板（5 色圆点，palette 内嵌清单）——免下载预览图，零额外请求。
 * 投稿入口提示 Issue/PR（写路径不经 App）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var index by remember { mutableStateOf<com.dsh.mobile.data.MarketIndex?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    // id → 正在安装 / 已装版本（安装成功后刷新，用于「更新」判定）
    var installing by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val installedVersions by ThemeRepository.themes.collectAsState()

    fun refresh() {
        loading = true; failed = false
        scope.launch {
            val r = ThemeMarket.fetchIndex()
            index = r
            loading = false
            failed = r == null
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            toast?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("市场加载失败（网络/镜像不可用）", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { refresh() }) { Text("重试") }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val list = index?.themes ?: emptyList()
                    items(list, key = { it.id }) { t ->
                        MarketThemeCard(
                            theme = t,
                            installedVersion = installedVersions.firstOrNull { it.id == t.id }?.version,
                            installing = installing == t.id,
                            onInstall = {
                                installing = t.id
                                scope.launch {
                                    val ok = ThemeMarket.install(t, context)
                                    installing = null
                                    toast = if (ok) "已安装「${t.name}」（设置 → 外观 应用）"
                                    else "安装失败（下载或解析失败）"
                                }
                            },
                        )
                    }
                    item {
                        Text(
                            "自制主题？投稿：github.com/Hyna-hla/dsh-theme-market 发 Issue 拖入 zip 或直接 PR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketThemeCard(
    theme: MarketTheme,
    installedVersion: String?,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    val updatable = installedVersion != null && installedVersion != theme.version
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(theme.name, style = MaterialTheme.typography.titleMedium)
                        if (installedVersion != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Text(
                                    if (updatable) "v$installedVersion → v${theme.version}" else "已装 v$installedVersion",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    if (theme.author.isNotBlank()) {
                        Text(
                            "by ${theme.author}${theme.description.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (installing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onInstall) {
                        Text(
                            when {
                                updatable -> "更新"
                                installedVersion != null -> "重装"
                                else -> "安装"
                            },
                            color = if (updatable) DshSuccess else DshBrand,
                        )
                    }
                }
            }
            // 色板预览：5 个圆点（背景/表面/品牌/主文字/描边）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                theme.palette.take(5).forEach { hex ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .background(
                                parseHex(hex) ?: MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(13.dp),
                            )
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(13.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** "#RRGGBB" / "#AARRGGBB" → Color；非法输入返回 null */
internal fun parseHex(hex: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(hex.trim()))
}.getOrNull()
