package com.dsh.mobile.data

import android.content.Context
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
 * GitHub 加速镜像（ghproxy 系）：国内直连 GitHub 慢/被墙，检查更新 API 与
 * Release 下载都按列表顺序尝试，逐个失败后自动切下一个，最后兜底直连。
 * 空串表示直连 GitHub（不经过镜像）。
 */
object UpdateMirrors {
    /** Release 直链下载镜像（https://github.com/... 前缀代理） */
    val DOWNLOAD_MIRRORS: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "", // 直连兜底
    )

    /** 检查更新 API 镜像（反代 api.github.com，支持度不一，失败即下一个） */
    val API_MIRRORS: List<String> = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "", // 直连兜底
    )

    /** 单个镜像尝试的硬超时（防止某个镜像挂死拖慢整体） */
    const val ATTEMPT_TIMEOUT_MS = 25_000L
}

/**
 * 应用内检查更新 + 下载：
 * - 检查：GET api.github.com/repos/Hyna-hla/harness-remote/releases/latest（多镜像）
 * - 下载：GitHub release 资产直链（多镜像失败切换），流式写盘带进度
 * - 安装：由调用方用 FileProvider/系统安装器完成
 */
object UpdateChecker {
    private const val REPO = "Hyna-hla/harness-remote"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "DSH-Remote-Updater/1.0"
    /** 启动自动检查的最小间隔（24h），避免每次打开都打 GitHub */
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

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

    /** 检查最新 release（多镜像依次尝试，最后直连；全部失败返回 null） */
    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (prefix in UpdateMirrors.API_MIRRORS) {
            val url = if (prefix.isEmpty()) API_LATEST else prefix + API_LATEST
            val r = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val call = client.newCall(request)
                call.timeout().timeout(UpdateMirrors.ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.decodeFromString<ReleaseInfo>(resp.body?.string() ?: return@use null)
                }
            }.getOrNull()
            if (r != null) return@withContext r
        }
        null
    }

    /** 启动自动检查：距上次检查超过 1 天才真正请求 GitHub（省电省流量） */
    suspend fun autoCheck(context: Context): ReleaseInfo? {
        val prefs = context.getSharedPreferences("dsh_update", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_auto_check", 0L)
        val now = System.currentTimeMillis()
        if (now - last < AUTO_CHECK_INTERVAL_MS) return null
        prefs.edit().putLong("last_auto_check", now).apply()
        return checkLatest()
    }

    /**
     * 流式下载到 dest（多镜像依次尝试，失败自动切下一个镜像，最后直连兜底）。
     * onProgress 回调 0..1（主线程安全：调用方自行切线程更新 UI）。
     * 全部失败抛 IOException（含最后一个错误）。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            var lastErr: IOException? = null
            val tmp = File(dest.parentFile, dest.name + ".part")
            for (prefix in UpdateMirrors.DOWNLOAD_MIRRORS) {
                val target = if (prefix.isEmpty()) url else prefix + url
                try {
                    downloadTo(target, tmp, onProgress)
                    if (tmp.length() > 0) {
                        tmp.renameTo(dest)
                        return@withContext
                    }
                } catch (e: IOException) {
                    lastErr = e
                    tmp.delete()
                }
            }
            throw lastErr ?: IOException("下载失败：所有镜像均不可用")
        }
    }

    /** 单次下载实现（写入 .part 临时文件） */
    private fun downloadTo(url: String, tmp: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = client.newCall(request)
        call.timeout().timeout(UpdateMirrors.ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val total = body.contentLength()
            tmp.parentFile?.mkdirs()
            tmp.outputStream().use { out ->
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

    /** 从 release 资产里挑 APK（优先 -min 精简签名包） */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk") && it.name.contains("-min") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }
}
