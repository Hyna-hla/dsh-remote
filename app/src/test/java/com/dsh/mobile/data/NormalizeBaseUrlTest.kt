package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeBaseUrlTest {

    @Test
    fun emptyStringStaysEmpty() {
        assertEquals("", normalizeBaseUrl(""))
    }

    @Test
    fun noProtocolGetsHttp() {
        assertEquals("http://192.168.1.100:8787", normalizeBaseUrl("192.168.1.100:8787"))
    }

    @Test
    fun existingProtocolKept() {
        assertEquals("http://a:1", normalizeBaseUrl("http://a:1"))
    }

    @Test
    fun trailingSlashRemoved() {
        assertEquals("http://a:1", normalizeBaseUrl("http://a:1/"))
    }

    @Test
    fun httpsKept() {
        assertEquals("https://a:1", normalizeBaseUrl("https://a:1"))
    }
}
