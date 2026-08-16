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
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
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
}
