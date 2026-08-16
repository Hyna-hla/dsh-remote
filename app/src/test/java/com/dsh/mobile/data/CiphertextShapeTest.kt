package com.dsh.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isCiphertextShape 形态判定纯函数测试：
 * 合法 Base64 且解码长度 >=28（12 IV + 16 tag）视为已加密（可能是密钥丢失后的残存密文）。
 */
class CiphertextShapeTest {

    private fun base64OfBytes(n: Int): String =
        java.util.Base64.getEncoder().encodeToString(ByteArray(n) { 'A'.code.toByte() })

    @Test
    fun validBase64AtLeast28BytesIsCiphertextShape() {
        assertTrue(isCiphertextShape(base64OfBytes(28))) // 临界值：恰好 12 IV + 16 tag
        assertTrue(isCiphertextShape(base64OfBytes(29)))
        assertTrue(isCiphertextShape(base64OfBytes(64)))
    }

    @Test
    fun validBase64ShorterThan28BytesIsNotCiphertextShape() {
        assertFalse(isCiphertextShape(base64OfBytes(27))) // 临界值减 1
        assertFalse(isCiphertextShape(base64OfBytes(16)))
        assertFalse(isCiphertextShape(base64OfBytes(1)))
    }

    @Test
    fun invalidBase64IsNotCiphertextShape() {
        assertFalse(isCiphertextShape("!!!!"))   // 非 base64 字符
        assertFalse(isCiphertextShape("abc"))    // 长度不对齐（3 不是 4 的倍数）
        assertFalse(isCiphertextShape("A"))      // 长度不对齐（单字符）
        assertFalse(isCiphertextShape("AB+CD"))  // 非对齐且含非法 padding 语义
    }

    @Test
    fun emptyOrBlankIsNotCiphertextShape() {
        assertFalse(isCiphertextShape(""))
        assertFalse(isCiphertextShape("   "))
        assertFalse(isCiphertextShape("\t\n"))
    }

    @Test
    fun plaintextIsNotCiphertextShape() {
        assertFalse(isCiphertextShape("secret123"))      // 旧明文（非 4 对齐）
        assertFalse(isCiphertextShape("my password!"))   // 含空格与标点
        assertFalse(isCiphertextShape("Secret!2026"))    // 含非 base64 字符
    }
}
