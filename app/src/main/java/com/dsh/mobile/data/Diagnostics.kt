package com.dsh.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class DiagStep(val name: String, val ok: Boolean, val detail: String?, val elapsedMs: Long)

suspend fun runDiagnostics(profile: HostProfile): List<DiagStep> = withContext(Dispatchers.IO) {
    val steps = mutableListOf<DiagStep>()
    val uri = runCatching { URI(profile.url) }.getOrNull()
    val host = uri?.host ?: ""
    val https = profile.url.startsWith("https://")
    val port = uri?.port?.takeIf { it > 0 } ?: if (https) 443 else 80

    // 1. DNS
    steps += timed("DNS 解析") {
        val addrs = java.net.InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw IllegalStateException("无解析结果")
        addrs.joinToString(", ") { it.hostAddress }
    }

    // 2. TCP
    steps += timed("TCP 连接") {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
        }
        "已连接 $host:$port"
    }

    // 3. TLS（仅 https）
    if (https) {
        steps += timed("TLS 握手") {
            val (unary, _) = OkHttpClientFactory.build(profile)
            val sf = unary.sslSocketFactory
            ((sf as SSLSocketFactory).createSocket(host, port) as SSLSocket).use { s ->
                s.startHandshake()
            }
            "证书校验通过"
        }
    }

    // 4. host.describe 版本探测
    steps += timed("版本探测") {
        val (unary, _) = OkHttpClientFactory.build(profile)
        val rpcId = "diag-" + java.util.UUID.randomUUID()
        val body = """{"type":"client-request","rpcId":"$rpcId","method":"host.describe","payload":{}}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(profile.url + "/api/host.describe").post(body).build()
        unary.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            version?.let { "主机版本 $it" } ?: "版本字段缺失（视为未知）"
        }
    }

    OkHttpClientFactory.release(profile.id)
    steps
}

private inline fun timed(name: String, block: () -> String): DiagStep {
    val t0 = System.currentTimeMillis()
    return try {
        val detail = block()
        DiagStep(name, true, detail, System.currentTimeMillis() - t0)
    } catch (e: Exception) {
        DiagStep(name, false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
    }
}
