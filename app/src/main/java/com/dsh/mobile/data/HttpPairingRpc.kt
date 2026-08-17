package com.dsh.mobile.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 插件配对路由的 HTTP 客户端（普通 HTTP，非 RPC 信封）。
 * client 由调用方在握手前用活跃 profile 构建并传入（见 PairingCoordinator）。
 */
class HttpPairingRpc(
    private val connection: DshConnection,
    private val client: OkHttpClient,
) : PairingRpc {

    private suspend fun post(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(connection.baseUrl() + path)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 404/410 = 插件未更新（旧版无该路由），spec 降级为「跳过」；其余（5xx/超时）按瞬断抛异常
                if (resp.code == 404 || resp.code == 410) return@withContext "skip"
                throw IllegalStateException("HTTP ${resp.code}")
            }
            JSONObject(text).optString("state", "")
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(connection.baseUrl() + path).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            JSONObject(text).optString("state", "")
        }
    }

    override suspend fun request(deviceId: String, deviceName: String): String =
        post("/api/remote-access/pair/request", JSONObject()
            .put("deviceId", deviceId).put("deviceName", deviceName))

    override suspend fun status(): String =
        get("/api/remote-access/pair/status")

    /** pair/check 结果：paired + 通道 token（仅已配对设备下发；旧插件无 token 字段为 null） */
    data class CheckResult(val paired: Boolean, val token: String?)

    /** device/info：主机机型与 MAC（设备记录 + 重连校验）；旧插件 404/410 → null（降级不校验） */
    data class DeviceInfo(val name: String, val model: String, val mac: String, val platform: String)

    suspend fun deviceInfo(): DeviceInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(connection.baseUrl() + "/api/remote-access/device/info")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404 || resp.code == 410) return@withContext null
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val obj = JSONObject(resp.body?.string().orEmpty())
            if (!obj.optBoolean("ok", false)) return@withContext null
            DeviceInfo(
                name = obj.optString("name", ""),
                model = obj.optString("model", ""),
                mac = obj.optString("mac", ""),
                platform = obj.optString("platform", ""),
            )
        }
    }

    /** 回查该设备在 PC 端是否仍配对（撤销后 App 重新握手）；失败抛异常由调用方 runCatching 兜底。 */
    suspend fun check(deviceId: String): CheckResult = withContext(Dispatchers.IO) {
        val encoded = java.net.URLEncoder.encode(deviceId, "UTF-8")
        val req = Request.Builder()
            .url(connection.baseUrl() + "/api/remote-access/pair/check?deviceId=" + encoded)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val obj = JSONObject(text)
            CheckResult(obj.optBoolean("paired", false), obj.optString("token", "").ifBlank { null })
        }
    }

    /** pair/code/verify 结果：ok=true 已配对并直接下发 token；失败含 error 与剩余尝试次数 */
    data class CodeVerifyResult(
        val ok: Boolean,
        val token: String?,
        val error: String?,
        val attemptsLeft: Int?,
    )

    /**
     * 配对码校验（插件 v2.1.0）：PC 生成 6 位随机码，手机输入即完成配对并直接拿到通道 token。
     * 404/410 = 旧插件 → error="plugin_old"（UI 引导改用「等待 PC 确认」）；网络错误抛异常由调用方兜底。
     */
    suspend fun codeVerify(code: String, deviceId: String, deviceName: String): CodeVerifyResult =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(connection.baseUrl() + "/api/remote-access/pair/code/verify")
                .post(JSONObject()
                    .put("code", code)
                    .put("deviceId", deviceId)
                    .put("deviceName", deviceName)
                    .toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 404 || resp.code == 410) {
                    return@withContext CodeVerifyResult(false, null, "plugin_old", null)
                }
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val obj = JSONObject(text)
                if (obj.optBoolean("ok", false)) {
                    CodeVerifyResult(true, obj.optString("token", "").ifBlank { null }, null, null)
                } else {
                    CodeVerifyResult(
                        false, null, obj.optString("error", "unknown"),
                        if (obj.has("attemptsLeft")) obj.optInt("attemptsLeft") else null,
                    )
                }
            }
        }

    // ---------- M1 双向文件：App → PC 上传（插件 fs/write，App 友好 name 契约） ----------
    data class UploadResult(val ok: Boolean, val path: String?, val size: Long?, val error: String?)

    /** 上传文件到 PC：只给文件名，插件自动写入其 $DSH_HOME/remote-access/uploads/ 下。 */
    suspend fun uploadFile(name: String, bytes: ByteArray): UploadResult = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("name", name)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("overwrite", true)
        val req = Request.Builder()
            .url(connection.baseUrl() + "/api/remote-access/fs/write")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext UploadResult(false, null, null, "HTTP ${resp.code}")
            val obj = JSONObject(resp.body?.string().orEmpty())
            if (!obj.optBoolean("ok", false)) {
                UploadResult(false, null, null, obj.optString("error", "unknown"))
            } else {
                UploadResult(
                    ok = true,
                    path = obj.optString("path", "").ifBlank { null },
                    size = if (obj.has("size")) obj.optLong("size") else null,
                    error = null,
                )
            }
        }
    }
}
