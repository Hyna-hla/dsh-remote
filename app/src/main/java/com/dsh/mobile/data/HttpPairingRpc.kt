package com.dsh.mobile.data

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
}
