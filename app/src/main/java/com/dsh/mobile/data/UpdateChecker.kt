package com.dsh.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** GitHub Release 资产（camelCase 字段用 @SerialName 映射） */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/** GitHub Releases latest 响应子集 */
@Serializable
data class ReleaseInfo(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    val body: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)

/**
 * 应用内检查更新 + 下载：
 * - 检查：GET api.github.com/repos/Hyna-hla/harness-remote/releases/latest
 * - 下载：GitHub release 资产直链（browser_download_url），流式写盘带进度
 * - 安装：由调用方用 FileProvider 交给系统安装器
 */
object UpdateChecker {
    private const val REPO = "Hyna-hla/harness-remote"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "DSH-Remote-Updater/1.0"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** "1.0.21" / "v1.0.21" → (1,0,21)；解析失败返回 null */
    fun parseVersion(text: String?): Triple<Int, Int, Int>? {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(text?.trim() ?: return null) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** latest 比 current 新（任一解析失败按"不可更新"处理） */
    fun isNewer(latest: String?, current: String?): Boolean {
        val a = parseVersion(latest) ?: return false
        val b = parseVersion(current) ?: return false
        return (a.first > b.first) ||
            (a.first == b.first && a.second > b.second) ||
            (a.first == b.first && a.second == b.second && a.third > b.third)
    }

    /** 检查最新 release；网络/解析失败返回 null */
    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(API_LATEST).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                json.decodeFromString<ReleaseInfo>(resp.body?.string() ?: return@use null)
            }
        }.getOrNull()
    }

    /**
     * 流式下载到 dest，onProgress 回调 0..1（主线程安全：调用方自行切线程更新 UI）。
     * 失败抛 IOException。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("空响应")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
    }

    /** 从 release 资产里挑 APK（优先 -min 精简签名包） */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk") && it.name.contains("-min") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }
}
