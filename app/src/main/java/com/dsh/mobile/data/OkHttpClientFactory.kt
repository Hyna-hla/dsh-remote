package com.dsh.mobile.data

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Route
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun buildProxy(cfg: ProxyConfig?): Proxy? {
    if (cfg == null || cfg.type == "none" || cfg.host.isBlank() || cfg.port <= 0) return null
    val type = when (cfg.type) {
        "socks5" -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }
    return Proxy(type, InetSocketAddress(cfg.host, cfg.port))
}

fun trustAllSslContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return ctx
}

/** PEM/DER → X509Certificate；无法解析返回 null */
fun parseCaCertificate(bytes: ByteArray): X509Certificate? = runCatching {
    val factory = CertificateFactory.getInstance("X.509")
    val cert = factory.generateCertificate(ByteArrayInputStream(bytes))
    cert as? X509Certificate
}.getOrNull()

object OkHttpClientFactory {

    private data class ClientPair(val unary: OkHttpClient, val stream: OkHttpClient)

    private val cache = HashMap<String, ClientPair>()

    @Synchronized
    fun build(profile: HostProfile): Pair<OkHttpClient, OkHttpClient> {
        cache[profile.id]?.let { return it.unary to it.stream }
        val pair = ClientPair(
            unary = newClient(profile, stream = false),
            stream = newClient(profile, stream = true),
        )
        cache[profile.id] = pair
        return pair.unary to pair.stream
    }

    @Synchronized
    fun release(profileId: String) {
        cache.remove(profileId)
    }

    private fun newClient(profile: HostProfile, stream: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (stream) {
            builder.connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
        } else {
            builder.connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
        }
        val ca = profile.caCertUri
        if (profile.trustSelfSigned) {
            val ctx = trustAllSslContext()
            builder.sslSocketFactory(ctx.socketFactory, trustAllX509())
            builder.hostnameVerifier { _, _ -> true }
        } else if (ca != null) {
            val ctx = mergedCaContext(ca)
            if (ctx != null) builder.sslSocketFactory(ctx.socketFactory, compositeX509()!!)
        }
        buildProxy(profile.proxy)?.let { builder.proxy(it) }
        profile.proxy?.takeIf { it.username.isNotBlank() }?.let { p ->
            builder.proxyAuthenticator(proxyAuthenticator(p.username, p.password))
        }
        return builder.build()
    }

    private fun trustAllX509(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 系统链 + 导入 CA 合成；CA 文件读取失败返回 null（回退系统默认） */
    private fun mergedCaContext(caUri: String): SSLContext? = runCatching {
        val bytes = java.io.File(caUri).readBytes()
        val ca = parseCaCertificate(bytes) ?: return null
        val imported = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("imported-ca", ca)
        }
        val importedTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(imported) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, null, null)
        // 返回 ctx 同时挂 CompositeTrustManager（build 处使用）
        lastComposite = CompositeTrustManager(systemTmf, importedTmf)
        ctx
    }.getOrNull()

    @Volatile private var lastComposite: X509TrustManager? = null
    private fun compositeX509(): X509TrustManager? = lastComposite

    private class CompositeTrustManager(
        private val primary: X509TrustManager,
        private val extra: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            primary.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = try {
            primary.checkServerTrusted(chain, authType)
        } catch (e: java.security.cert.CertificateException) {
            extra.checkServerTrusted(chain, authType)
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun proxyAuthenticator(username: String, password: String) =
        Authenticator { _: Route?, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }
}
