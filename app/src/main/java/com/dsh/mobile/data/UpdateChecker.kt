package com.dsh.mobile.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * Release 下载都按列表顺序尝试，失败后自动切下一个，最后兜底直连。
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

    /** 检查更新 API 单镜像硬超时（响应体很小，25s 足够） */
    const val API_TIMEOUT_MS = 25_000L

    /**
     * 下载换源只看「停滞」不看「总时长」：连续这么久没收到任何一个字节才判死换源。
     * 慢速但持续有数据的下载（哪怕只有几十 KB/s）绝不换源。
     */
    const val DOWNLOAD_STALL_MS = 30_000L

    /** 极慢兜底：下载开始 GRACE 毫秒后整体平均速度仍低于此值才换源（20KB/s） */
    const val DOWNLOAD_SLOW_BPS = 20L * 1024

    /** 极慢判定的宽限期（ms）：镜像刚连上时速度低是正常的，先给它时间爬升 */
    const val DOWNLOAD_SLOW_GRACE_MS = 60_000L
}

/**
 * 应用内检查更新 + 下载：
 * - 检查：GET api.github.com/repos/Hyna-hla/dsh-remote/releases/latest（多镜像）
 * - 下载：GitHub release 资产直链（仅在停滞/极慢/出错时切换镜像），流式写盘带进度
 * - 安装：由调用方用 FileProvider/系统安装器完成
 */
object UpdateChecker {
    private const val REPO = "Hyna-hla/dsh-remote"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "DSH-Remote-Updater/1.0"
    /** 启动自动检查的最小间隔（24h），避免每次打开都打 GitHub */
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 下载专用 client：不设总时长限制（大文件慢速下载几十分钟也允许），
     * 仅靠 readTimeout 做「无数据停滞」检测——停 DOWNLOAD_STALL_MS 才断开换源。
     */
    private val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(UpdateMirrors.DOWNLOAD_STALL_MS, TimeUnit.MILLISECONDS)
            .build()
    }

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

    /**
     * 更新判定所用的"发布版本号"：**优先取 APK 资产名的真实版本**（App 的 versionName，
     * 例如 DSH-Remote-v1.8.1.apk → 1.8.1），解析不到才回退 tag_name。
     * 原因：本仓库同时承载插件（另有自己的版本号），若 release 用插件 tag（如 v2.4.1）
     * 而 APK 仍是 App 版本，按 tag 比较会让 App 误判"有新版本"而无限提示。
     */
    fun releaseAppVersion(info: ReleaseInfo): String {
        pickApk(info.assets)?.let { apk ->
            parseVersion(apk.name)?.let { v -> return "${v.first}.${v.second}.${v.third}" }
        }
        return info.tagName
    }

    /** 检查最新 release（镜像按「上次成功的镜像优先」排序，最后直连；全部失败返回 null） */
    suspend fun checkLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (prefix in orderedMirrors(UpdateMirrors.API_MIRRORS)) {
            val url = if (prefix.isEmpty()) API_LATEST else prefix + API_LATEST
            val r = runCatching {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                val call = client.newCall(request)
                call.timeout().timeout(UpdateMirrors.API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    json.decodeFromString<ReleaseInfo>(resp.body?.string() ?: return@use null)
                }
            }.getOrNull()
            if (r != null) {
                saveLastMirror(prefix)
                return@withContext r
            }
        }
        null
    }

    // ── 镜像偏好：记住上次成功的镜像，下次优先 ──

    @Volatile
    private var lastMirror: String? = null
    private var mirrorPrefs: android.content.SharedPreferences? = null

    /** 初始化镜像偏好（App 启动时调用一次） */
    fun init(context: Context) {
        if (mirrorPrefs != null) return
        mirrorPrefs = context.getSharedPreferences("dsh_update", Context.MODE_PRIVATE)
        lastMirror = mirrorPrefs?.getString("last_mirror", null)
    }

    private fun saveLastMirror(prefix: String) {
        lastMirror = prefix
        mirrorPrefs?.edit()?.putString("last_mirror", prefix)?.apply()
    }

    /** 上次成功的镜像排最前；其余按原顺序 */
    fun orderedMirrors(list: List<String>): List<String> {
        val saved = lastMirror
        return if (saved != null && list.contains(saved)) {
            listOf(saved) + list.filter { it != saved }
        } else list
    }

    /** 当前记住的镜像显示名（无则 null） */
    fun lastMirrorName(): String? = lastMirror?.let { mirrorName(it) }

    /** 启动自动检查：距上次检查超过 1 天才真正请求 GitHub（省电省流量） */
    suspend fun autoCheck(context: Context): ReleaseInfo? {
        val prefs = context.getSharedPreferences("dsh_update", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_auto_check", 0L)
        val now = System.currentTimeMillis()
        if (now - last < AUTO_CHECK_INTERVAL_MS) return null
        prefs.edit().putLong("last_auto_check", now).apply()
        return checkLatest()
    }

    // ── 透明下载事件流（v1.1.1：镜像切换/开始/速度全量上报，UI 全透明展示）──

    const val PHASE_CONNECTING = "connecting"
    const val PHASE_DOWNLOADING = "downloading"
    const val PHASE_FAILED = "failed"
    const val PHASE_DONE = "done"

    data class DownloadEvent(
        val phase: String,
        /** 当前尝试的镜像序号（0 起） */
        val mirrorIndex: Int,
        /** 镜像显示名（ghfast.top / gh-proxy.com / ghproxy.net / 直连 GitHub） */
        val mirrorName: String,
        /** 实际请求地址（透明展示用） */
        val url: String,
        /** 0..1 */
        val progress: Float = 0f,
        /** 实时速度（字节/秒） */
        val speedBytesPerSec: Long = 0L,
    )

    /** 镜像显示名 */
    fun mirrorName(prefix: String): String = when {
        prefix.isEmpty() -> "直连 GitHub"
        prefix.contains("ghfast") -> "ghfast.top"
        prefix.contains("gh-proxy") -> "gh-proxy.com"
        prefix.contains("ghproxy") -> "ghproxy.net"
        else -> prefix.removePrefix("https://").trimEnd('/')
    }

    /**
     * 下载事件流：连接镜像 → 实时进度（含速度）→ 成功。
     * 仅在镜像真坏（停滞 30s / 宽限期后均速 <20KB/s / 网络错误）时发 failed 并自动切下一个；
     * 慢速但持续下载中不换源。全部失败以异常结束。collect 的协程上下文决定执行线程。
     */
    fun downloadApkFlow(url: String, dest: File): Flow<DownloadEvent> = flow {
        var lastErr: IOException? = null
        val tmp = File(dest.parentFile, dest.name + ".part")
        // 上次成功的镜像优先
        orderedMirrors(UpdateMirrors.DOWNLOAD_MIRRORS).forEachIndexed { index, prefix ->
            val target = if (prefix.isEmpty()) url else prefix + url
            val name = mirrorName(prefix)
            emit(DownloadEvent(PHASE_CONNECTING, index, name, target))
            try {
                downloadTo(target, tmp) { done, total, speed ->
                    emit(
                        DownloadEvent(
                            PHASE_DOWNLOADING, index, name, target,
                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                            speed,
                        ),
                    )
                }
                if (tmp.length() > 0) {
                    tmp.renameTo(dest)
                    saveLastMirror(prefix)
                    emit(DownloadEvent(PHASE_DONE, index, name, target, 1f))
                    return@flow
                }
            } catch (e: IOException) {
                lastErr = e
                emit(DownloadEvent(PHASE_FAILED, index, name, target))
                tmp.delete()
            }
        }
        throw lastErr ?: IOException("下载失败：所有镜像均不可用")
    }

    /**
     * 流式下载到 dest（多镜像依次尝试，失败自动切下一个镜像，最后直连兜底）。
     * onProgress 回调 0..1（主线程安全：调用方自行切线程更新 UI）。
     * 全部失败抛 IOException（含最后一个错误）。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            downloadApkFlow(url, dest).collect { ev ->
                if (ev.phase == PHASE_DOWNLOADING) onProgress(ev.progress)
            }
        }
    }

    /** 单次下载实现（写入 .part 临时文件；按 150ms 节流上报速度；无总时长限制） */
    private suspend fun downloadTo(
        url: String,
        tmp: File,
        onProgress: suspend (doneBytes: Long, totalBytes: Long, speed: Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = downloadClient.newCall(request)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val total = body.contentLength()
            tmp.parentFile?.mkdirs()
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                val startAt = System.nanoTime()
                var lastEmitAt = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        // 速度：整体平均（防瞬时跳变）
                        val elapsedMs = ((now - startAt) / 1_000_000).coerceAtLeast(1)
                        val speed = done * 1000 / elapsedMs
                        if (now - lastEmitAt >= 150_000_000L || done >= total) {
                            lastEmitAt = now
                            // 极慢兜底：宽限期后均速仍 <20KB/s 才放弃该镜像；
                            // 单次 read 卡死由 readTimeout(30s) 自动断开
                            if (elapsedMs > UpdateMirrors.DOWNLOAD_SLOW_GRACE_MS &&
                                speed < UpdateMirrors.DOWNLOAD_SLOW_BPS
                            ) {
                                throw IOException("镜像过慢（${speed / 1024} KB/s），换下一源")
                            }
                            onProgress(done, total, speed)
                        }
                    }
                }
                if (total <= 0) onProgress(done, done, 0)
            }
        }
    }

    /** 从 release 资产里挑 APK（优先 debug 主包——功能最全；无主包时回退任意 apk，如 -min 精简包） */
    fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? =
        assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("-min") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }
}
