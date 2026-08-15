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
}
