package com.dsh.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretBox {
    fun encrypt(plain: String): String
    fun decrypt(enc: String): String?
}

/** AndroidKeyStore AES-256-GCM 加密盒（不依赖已弃用的 security-crypto）。 */
object SecretCipher : SecretBox {

    const val KEY_ALIAS = "dsh_secret_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)
    }.getOrNull()

    /** 幂等创建主密钥（DshApplication.onCreate 调用一次）。 */
    fun init(context: Context) {
        if (hasKey()) return
        runCatching {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    }

    fun hasKey(): Boolean = key() != null

    override fun encrypt(plain: String): String {
        val k = key() ?: throw IllegalStateException("secret key missing")
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, k)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(enc: String): String? = runCatching {
        val k = key() ?: return null
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        if (raw.size <= IV_LEN) return null
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
