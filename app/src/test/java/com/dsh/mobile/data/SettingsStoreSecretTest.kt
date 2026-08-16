package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsStoreSecretTest {

    /** 假加密盒：前缀 "ENC:" 标记密文 */
    private class FakeBox : SecretBox {
        override fun encrypt(plain: String): String = "ENC:$plain"
        override fun decrypt(enc: String): String? =
            if (enc.startsWith("ENC:")) enc.removePrefix("ENC:") else null
    }

    private fun profileWithProxy() = HostProfile(
        id = "p1", remark = "家", url = "http://x:1",
        proxy = ProxyConfig(type = "http", host = "10.0.0.1", port = 8080,
            username = "u", password = "secret123"),
    )

    @Test
    fun encryptForStorageEncryptsPasswordOnly() {
        val enc = encryptProfileForStorage(profileWithProxy(), FakeBox())
        assertEquals("ENC:secret123", enc.proxy!!.password)
        assertEquals("u", enc.proxy!!.username) // 其余字段不动
        assertEquals("http://x:1", enc.url)
    }

    @Test
    fun decryptForStorageRestoresPlaintext() {
        val enc = encryptProfileForStorage(profileWithProxy(), FakeBox())
        val dec = decryptProfileFromStorage(enc, FakeBox())
        assertEquals("secret123", dec.proxy!!.password)
    }

    @Test
    fun decryptFailureFallsBackToLegacyPlaintext() {
        val legacy = profileWithProxy() // 旧明文
        val dec = decryptProfileFromStorage(legacy, FakeBox()) // 解密失败（无 ENC: 前缀）
        assertEquals("secret123", dec.proxy!!.password)
    }

    @Test
    fun nullProxyOrBlankPasswordUntouched() {
        val noProxy = HostProfile(id = "p2", remark = "x", url = "http://x:2")
        assertEquals(noProxy, encryptProfileForStorage(noProxy, FakeBox()))
        val blankPw = profileWithProxy().copy(
            proxy = profileWithProxy().proxy!!.copy(password = ""),
        )
        assertEquals(blankPw, encryptProfileForStorage(blankPw, FakeBox()))
    }

    @Test
    fun roundTripThroughCodecPreservesEncryptedForm() {
        val enc = encryptProfileForStorage(profileWithProxy(), FakeBox())
        val text = ProfileCodec.encode(listOf(enc))
        val back = ProfileCodec.decode(text)[0]
        assertNotEquals("secret123", back.proxy!!.password) // 落盘形态是密文
        assertEquals("secret123", decryptProfileFromStorage(back, FakeBox()).proxy!!.password)
    }

    @Test
    fun twoSequentialUpsertsKeepFirstPasswordIntact() {
        val box = FakeBox()
        val a = profileWithProxy().copy(id = "pa")
        val b = HostProfile(id = "pb", remark = "b", url = "http://x:2",
            proxy = ProxyConfig(type = "http", host = "1.2.3.4", port = 1, password = "pwB"))
        val afterA = upsertProfileInStore(emptyList(), a, box)
        val afterB = upsertProfileInStore(afterA, b, box)
        val aStored = afterB.first { it.id == "pa" }
        assertEquals("secret123", decryptProfileFromStorage(aStored, box).proxy!!.password)
        assertEquals("pwB", decryptProfileFromStorage(afterB.first { it.id == "pb" }, box).proxy!!.password)
    }

    @Test
    fun keyLossCiphertextNotTreatedAsPlaintext() {
        // 模拟密钥丢失：假箱对 28+ 字节 Base64 密文解密失败
        val fake = object : SecretBox {
            override fun encrypt(plain: String): String = java.util.Base64.getEncoder().encodeToString(
                ("0123456789012345678901234567" + plain).toByteArray())
            override fun decrypt(enc: String): String? = null // 一律失败 = 密钥丢失
        }
        val stored = encryptProfileForStorage(profileWithProxy(), fake)
        val read = decryptProfileFromStorage(stored, fake)
        assertEquals(stored.proxy!!.password, read.proxy!!.password) // 保留密文形态，不回退
    }

    @Test
    fun legacyPlaintextStillFallsBack() {
        // 旧明文（短、Base64 解码后 <28 字节）→ 回退原文（现有 FakeBox 行为已覆盖，再显式断言一次）
        val legacy = profileWithProxy() // password = "secret123"（9 字节）
        val dec = decryptProfileFromStorage(legacy, FakeBox())
        assertEquals("secret123", dec.proxy!!.password)
    }
}
