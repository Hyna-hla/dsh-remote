package com.dsh.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.dsh.mobile.ui.theme.ThemeRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 主题市场条目（dsh-theme-market 仓库 index.json 的 themes[] 元素）。
 * palette 内嵌 5 个色值 → 市场卡片零额外请求渲染色板预览（免下载预览图）。
 */
@Serializable
data class MarketTheme(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val version: String = "1.0",
    val palette: List<String> = emptyList(),
    val zip: String = "",
)

@Serializable
data class MarketIndex(
    val updated: String = "",
    val themes: List<MarketTheme> = emptyList(),
)

/**
 * 主题市场数据层：GitHub 仓库 dsh-theme-market 当零服务器后端——
 * - 清单：raw index.json（镜像加速，复用更新检查的镜像列表与偏好）
 * - 安装：raw 下载 .dshTheme.zip 字节流 → ThemeRepository.importPayload（既有 zip 导入管线，
 *   同 id 安装 = 热替换更新）
 * - 投稿：Issue 拖附件 / PR（仓库 README 有规范），App 不参与写路径
 */
object ThemeMarket {

    private const val REPO = "Hyna-hla/dsh-theme-market"
    private const val INDEX_URL = "https://raw.githubusercontent.com/$REPO/main/index.json"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/main/"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 拉市场清单（镜像按上次成功优先；全部失败返回 null，UI 显示重试态） */
    suspend fun fetchIndex(): MarketIndex? = withContext(Dispatchers.IO) {
        for (prefix in UpdateChecker.orderedMirrors(UpdateMirrors.DOWNLOAD_MIRRORS)) {
            val url = if (prefix.isEmpty()) INDEX_URL else prefix + INDEX_URL
            val r = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.decodeFromString<MarketIndex>(resp.body?.string() ?: return@use null)
                }
            }.getOrNull()
            if (r != null) return@withContext r
        }
        null
    }

    /** 下载并安装一个市场主题：成功返回主题 id（已热加载生效），失败 null（UI 提示） */
    suspend fun install(theme: MarketTheme, context: Context): Boolean = withContext(Dispatchers.IO) {
        if (theme.zip.isBlank()) return@withContext false
        val url = RAW_BASE + theme.zip.removePrefix("/")
        for (prefix in UpdateChecker.orderedMirrors(UpdateMirrors.DOWNLOAD_MIRRORS)) {
            val target = if (prefix.isEmpty()) url else prefix + url
            val bytes = runCatching {
                client.newCall(Request.Builder().url(target).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.bytes()
                }
            }.getOrNull()
            if (bytes != null && bytes.size > 4) {
                val def = ThemeRepository.importPayload(bytes, context)
                if (def != null) return@withContext true
            }
        }
        false
    }
}
