package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class OkHttpClientFactoryTest {

    @Test
    fun proxyMapping() {
        assertNull(buildProxy(null))
        assertNull(buildProxy(ProxyConfig(type = "none")))
        val http = buildProxy(ProxyConfig(type = "http", host = "10.0.0.1", port = 8080))
        assertEquals(Proxy.Type.HTTP, http!!.type())
        val socks = buildProxy(ProxyConfig(type = "socks5", host = "127.0.0.1", port = 1080))
        assertEquals(Proxy.Type.SOCKS, socks!!.type())
    }

    @Test
    fun trustAllSslContextBuilds() {
        val ctx = trustAllSslContext()
        assertNotNull(ctx)
        assertNotNull(ctx.socketFactory)
    }

    @Test
    fun parseCaCertificateHandlesGarbage() {
        assertNull(parseCaCertificate(byteArrayOf()))
        assertNull(parseCaCertificate("not a pem".toByteArray()))
        assertNull(parseCaCertificate(byteArrayOf(0, 1, 2, 3)))
    }

    @Test
    fun mergedCaContextGarbageReturnsNull() {
        assertNull(OkHttpClientFactory.mergedCaContext("not a pem".toByteArray()))
        assertNull(OkHttpClientFactory.mergedCaContext(byteArrayOf()))
    }

    @Test
    fun mergedCaContextValidPemTrustsChain() {
        // 自签名证书（CN=dsh-remote-test.local），系统链不认识 → 由导入链接住，证明 CA 已合入信任链
        val pem = "-----BEGIN CERTIFICATE-----\n" +
            "MIIDPDCCAiSgAwIBAgIQYHmZvkbZ8q5HKPlPstgj3jANBgkqhkiG9w0BAQsFADAgMR4wHAYDVQQD\n" +
            "DBVkc2gtcmVtb3RlLXRlc3QubG9jYWwwHhcNMjYwODE1MjIxMjM2WhcNMjcwODE1MjIyMjM1WjAg\n" +
            "MR4wHAYDVQQDDBVkc2gtcmVtb3RlLXRlc3QubG9jYWwwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw\n" +
            "ggEKAoIBAQCiWQ6PT4d2tX3DUfOkSDu4q23qA+TCJtv/GYDfvOkYDifTQNpumKoAiuL89HN949mQ\n" +
            "vlhSeyAtfEmTnLc+nCFlIgWVI6QeMkl9AtEZ7miClj6IVyQFnI4VK5qKEozKn9TRapfhQYyqZf5v\n" +
            "Y27C9U1YLloMQ4bTRTdYtLLWecaBKLjlyDpNUxffUnhlOgtFlX795S7hgLWjF57iwroPxT4qbvDF\n" +
            "3F0IrN9EXlm8BgMr/Dx0YXl1bjMxHLrrpbil5J2ssoEeRjjrnyVjE3MPghfU36oZmqCjTLa9233G\n" +
            "LeMw0LugDVg0MSDIAe6z3iXoBXVVJROAEcpwhy3wLIyV5Ft1AgMBAAGjcjBwMA4GA1UdDwEB/wQE\n" +
            "AwIFoDAdBgNVHSUEFjAUBggrBgEFBQcDAgYIKwYBBQUHAwEwIAYDVR0RBBkwF4IVZHNoLXJlbW90\n" +
            "ZS10ZXN0LmxvY2FsMB0GA1UdDgQWBBRPaK6UZlfK//JqQzO83Jgyyibh2zANBgkqhkiG9w0BAQsF\n" +
            "AAOCAQEAHaX3LBsP5F45IvM6bzxBrvje5oUMUC1As6/7YQED5Vtj8qo9Du30VGFilSZxFasrEO4s\n" +
            "vVCSxF00jOxCcbTEJCEivtbGBstoxBUAwzD5kkOLRYKsBBM4lf50bAShyInexSUWElYpp2hUc4Nx\n" +
            "DtHBRVhGQ/Ayapxcy+gtjEAuAihGpTN57zC+6yXHiMgNMAXVx/q963dquufSpx1fUpnJI/jxw7Pb\n" +
            "jA8vZpAaMEnCymvhegylfKVwlFsIhh6v/Oy1nX1qw0NWtq+iFiGiwuWFnEgif8pnBIrFLEwFjR2B\n" +
            "z1DpBJx4eL+kLJH7AJXTg1TmY1Mu1MAaIOkVp7+RDfJL3w==\n" +
            "-----END CERTIFICATE-----\n"
        val bytes = pem.toByteArray()
        val merged = OkHttpClientFactory.mergedCaContext(bytes)
        assertNotNull(merged)
        val leaf = parseCaCertificate(bytes)
        assertNotNull(leaf)
        // 不抛异常即通过：primary（系统链）拒绝后由 extra（导入 CA）接住
        merged!!.second.checkServerTrusted(arrayOf(leaf!!), "RSA")
    }
}
