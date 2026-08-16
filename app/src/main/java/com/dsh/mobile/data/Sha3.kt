package com.dsh.mobile.data

/**
 * 纯 Kotlin SHA3-256（FIPS 202，Keccak-f[1600]，rate 1088 bit / 136 byte，domain 后缀 0x06）。
 * Android 各版本的 MessageDigest 对 SHA3 支持不一致（依赖 Conscrypt），故自带实现，
 * 仅供保险库解锁摘要（dsh-encrypt 的 /api/credentials.unlock 只收小写 hex digest，明文密码不上行）。
 */
object Sha3 {

    private val roundConstants = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL, 0x8000000080008000L,
        0x000000000000808bL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
        0x000000000000008aL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L, 0x8000000000008003L,
        0x8000000000008002L, 0x8000000000000080L, 0x000000000000800aL, 0x800000008000000aL,
        0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L,
    )

    /** r[x][y] 旋转偏移（x 为列，y 为行），下标 [x][y] 取值 x*5+y 处 lane 的偏移。 */
    private val rotations = arrayOf(
        intArrayOf(0, 36, 3, 41, 18),
        intArrayOf(1, 44, 10, 45, 2),
        intArrayOf(62, 6, 43, 15, 61),
        intArrayOf(28, 55, 25, 21, 56),
        intArrayOf(27, 20, 39, 8, 14),
    )

    fun digest256(input: ByteArray): ByteArray {
        val rate = 136
        val a = LongArray(25)
        // 吸收带填充的完整块：0x06 || 0x00… || 首尾字节 |= 0x80（pad10*1）
        val paddedLen = ((input.size / rate) + 1) * rate
        val padded = input.copyOf(paddedLen)
        padded[input.size] = (padded[input.size].toInt() or 0x06).toByte()
        padded[paddedLen - 1] = (padded[paddedLen - 1].toInt() or 0x80).toByte()
        for (off in padded.indices step rate) {
            for (i in 0 until rate step 8) {
                var lane = 0L
                for (b in 7 downTo 0) lane = (lane shl 8) or (padded[off + i + b].toLong() and 0xff)
                a[i / 8] = a[i / 8] xor lane
            }
            keccakF(a)
        }
        // 挤出 32 字节（小端 lane 序）
        val out = ByteArray(32)
        for (i in 0 until 32 step 8) {
            var lane = a[i / 8]
            for (b in 0 until 8) {
                out[i + b] = (lane and 0xff).toByte()
                lane = lane ushr 8
            }
        }
        return out
    }

    /** SHA3-256 小写 hex（dsh-encrypt 解锁摘要的既定格式）。 */
    fun digest256Hex(text: String): String =
        digest256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 64 位循环左移（手写避免依赖 stdlib rotateLeft 的版本可用性；Long 移位天然截断无溢出）。 */
    private fun rotl(x: Long, n: Int): Long = if (n == 0) x else (x shl n) or (x ushr (64 - n))

    private fun keccakF(a: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)
        for (rc in roundConstants) {
            // theta
            for (x in 0..4) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0..4) d[x] = c[(x + 4) % 5] xor rotl(c[(x + 1) % 5], 1)
            for (x in 0..4) for (y in 0..4) a[x + 5 * y] = a[x + 5 * y] xor d[x]
            // rho + pi
            for (x in 0..4) for (y in 0..4) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = rotl(a[x + 5 * y], rotations[x][y])
            }
            // chi
            for (x in 0..4) for (y in 0..4) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            // iota
            a[0] = a[0] xor rc
        }
    }
}
