package com.dsh.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ConnectionErrorCode {
    DNS_UNREACHABLE,   // 域名解析失败
    PORT_UNREACHABLE,  // TCP 拒绝/连接超时
    TLS_CERT_FAILED,   // 证书校验失败
    AUTH_FAILED,       // HTTP 401/403（前置网关）
    VERSION_MISMATCH,  // host.describe 版本低于下界
    PROXY_FAILED,      // 配置了代理但代理不可达
    PROTOCOL_ERROR,    // 非 200 / 解析失败 / RPC ok=false / 读超时
    UNKNOWN,
}

@Serializable
data class ProxyConfig(
    val type: String = "none", // "none" | "http" | "socks5"
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "", // TODO-S3: 迁 Keystore，暂明文存 DataStore
)

@Serializable
data class HostProfile(
    val id: String,
    val remark: String,
    val url: String,
    val trustSelfSigned: Boolean = false,
    val caCertUri: String? = null,
    val proxy: ProxyConfig? = null,
    val autoConnect: Boolean = false,
    val lastUsedAt: Long = 0,
    val lastErrorCode: String? = null,
    val paired: Boolean = false,
    /** 远程通道 token（配对通过后由 pair/check 下发；密文落盘，请求时以 Bearer 头携带） */
    val channelToken: String = "",
)

/**
 * 规范化主机地址（连接、诊断、保存共享同一语义）：
 * trim → 无协议补 http:// → 去尾斜杠。
 */
fun normalizeBaseUrl(raw: String): String {
    var u = raw.trim()
    if (u.isEmpty()) return u
    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
    u = u.trimEnd('/')
    return u
}

object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(profiles: List<HostProfile>): String =
        json.encodeToString(ListSerializer(HostProfile.serializer()), profiles)

    fun decode(text: String): List<HostProfile> = runCatching {
        json.decodeFromString(ListSerializer(HostProfile.serializer()), text)
    }.getOrDefault(emptyList())
}

private fun <T> ListSerializer(element: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(element)
