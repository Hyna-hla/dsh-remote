package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {

    private fun sample() = HostProfile(
        id = "p1", remark = "家里", url = "http://192.168.1.100:8787",
        trustSelfSigned = true, caCertUri = "/data/certs/p1.pem",
        proxy = ProxyConfig(type = "socks5", host = "127.0.0.1", port = 1080,
            username = "u", password = "p"),
        autoConnect = true, lastUsedAt = 123L, lastErrorCode = "PORT_UNREACHABLE",
    )

    @Test
    fun codecRoundTrip() {
        val text = ProfileCodec.encode(listOf(sample()))
        val back = ProfileCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals(sample(), back[0])
    }

    @Test
    fun codecEmptyList() {
        val back = ProfileCodec.decode(ProfileCodec.encode(emptyList()))
        assertTrue(back.isEmpty())
    }

    @Test
    fun codecGarbageReturnsEmpty() {
        assertTrue(ProfileCodec.decode("not json").isEmpty())
        assertTrue(ProfileCodec.decode("").isEmpty())
    }

    @Test
    fun codecDefaults() {
        val p = HostProfile(id = "p2", remark = "公司", url = "http://10.0.0.2:8787")
        val back = ProfileCodec.decode(ProfileCodec.encode(listOf(p)))
        assertEquals(false, back[0].trustSelfSigned)
        assertEquals(null, back[0].proxy)
        assertEquals(false, back[0].autoConnect)
        assertEquals(false, back[0].paired)
    }
}
