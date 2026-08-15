package com.dsh.mobile.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class VersionVerdict { OK, UNKNOWN, MISMATCH }

object VersionPolicy {
    const val MIN_DSH_VERSION = "0.0.0"
    const val PLACEHOLDER_VERSION = "0.0.1"

    private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    fun evaluate(version: String?, min: String = MIN_DSH_VERSION): VersionVerdict {
        if (version.isNullOrBlank()) return VersionVerdict.UNKNOWN
        if (version.trim() == PLACEHOLDER_VERSION) return VersionVerdict.UNKNOWN
        val v = parse(version) ?: return VersionVerdict.UNKNOWN
        val m = parse(min) ?: return VersionVerdict.UNKNOWN
        return if (v.atLeast(m)) VersionVerdict.OK else VersionVerdict.MISMATCH
    }

    /** 解析失败返回 null；Triple 按数值比较 */
    private fun parse(s: String): Triple<Int, Int, Int>? {
        val m = SEMVER.find(s.trim()) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    private fun Triple<Int, Int, Int>.atLeast(other: Triple<Int, Int, Int>): Boolean =
        compareValuesBy(this, other, { it.first }, { it.second }, { it.third }) >= 0
}

object RetryPolicy {
    const val FAST_TIER_CAP_MS = 9_000L
    const val SLOW_TIER_CAP_MS = 30_000L

    fun isRecoverable(code: ConnectionErrorCode): Boolean =
        code != ConnectionErrorCode.AUTH_FAILED && code != ConnectionErrorCode.VERSION_MISMATCH

    fun nextBackoff(code: ConnectionErrorCode, attempt: Int): Long? {
        if (!isRecoverable(code)) return null
        val cap = if (code == ConnectionErrorCode.PORT_UNREACHABLE ||
            code == ConnectionErrorCode.DNS_UNREACHABLE
        ) FAST_TIER_CAP_MS else SLOW_TIER_CAP_MS
        var ms = 3_000L
        repeat(attempt.coerceAtLeast(0)) { ms = minOf(ms * 2, cap) }
        return ms
    }
}

object ErrorClassifier {
    fun fromException(t: Throwable, connectPhase: Boolean, hasProxy: Boolean): ConnectionErrorCode {
        if (t is UnknownHostException) return ConnectionErrorCode.DNS_UNREACHABLE
        if (t is ConnectException) {
            return if (hasProxy) ConnectionErrorCode.PROXY_FAILED else ConnectionErrorCode.PORT_UNREACHABLE
        }
        if (t is SocketTimeoutException) {
            val connectTimeout = connectPhase ||
                t.message.orEmpty().contains("connect", ignoreCase = true)
            return if (connectTimeout) {
                if (hasProxy) ConnectionErrorCode.PROXY_FAILED else ConnectionErrorCode.PORT_UNREACHABLE
            } else ConnectionErrorCode.PROTOCOL_ERROR
        }
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is SSLHandshakeException || cur is SSLPeerUnverifiedException ||
                cur is CertPathValidatorException
            ) return ConnectionErrorCode.TLS_CERT_FAILED
            cur = cur.cause
        }
        if (t is ApiException && t.code != null) {
            return fromHttpStatus(t.code!!.toIntOrNull() ?: 0)
        }
        return ConnectionErrorCode.UNKNOWN
    }

    fun fromHttpStatus(status: Int): ConnectionErrorCode = when (status) {
        401, 403 -> ConnectionErrorCode.AUTH_FAILED
        else -> ConnectionErrorCode.PROTOCOL_ERROR
    }
}
