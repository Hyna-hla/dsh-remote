package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.security.cert.CertPathValidatorException

class ConnectionPolicyTest {

    // ---- VersionPolicy ----
    @Test
    fun versionPlaceholderIsUnknown() {
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate("0.0.1"))
    }

    @Test
    fun versionUnparseableOrNullIsUnknown() {
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate(null))
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate("dev"))
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate(""))
    }

    @Test
    fun versionAtOrAboveMinIsOk() {
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("0.1.0"))
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("1.2.3"))
    }

    @Test
    fun versionBelowInjectedMinIsMismatch() {
        assertEquals(VersionVerdict.MISMATCH, VersionPolicy.evaluate("0.0.5", min = "0.1.0"))
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("0.1.0", min = "0.1.0"))
    }

    // ---- RetryPolicy ----
    @Test
    fun authAndVersionMismatchStopRetrying() {
        assertNull(RetryPolicy.nextBackoff(ConnectionErrorCode.AUTH_FAILED, 0))
        assertNull(RetryPolicy.nextBackoff(ConnectionErrorCode.VERSION_MISMATCH, 5))
        assertFalse(RetryPolicy.isRecoverable(ConnectionErrorCode.AUTH_FAILED))
    }

    @Test
    fun fastTierDoublesToCap() {
        assertEquals(3_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 0))
        assertEquals(6_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.DNS_UNREACHABLE, 1))
        assertEquals(9_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 2))
        assertEquals(9_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 10))
    }

    @Test
    fun slowTierDoublesToCap() {
        assertEquals(3_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 0))
        assertEquals(6_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PROTOCOL_ERROR, 1))
        assertEquals(12_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.UNKNOWN, 2))
        assertEquals(24_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PROXY_FAILED, 3))
        assertEquals(30_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 4))
        assertEquals(30_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 99))
    }

    // ---- ErrorClassifier ----
    @Test
    fun classifiesDnsAndPort() {
        assertEquals(ConnectionErrorCode.DNS_UNREACHABLE,
            ErrorClassifier.fromException(UnknownHostException("nope"), false, false))
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(ConnectException("refused"), true, false))
        assertEquals(ConnectionErrorCode.PROXY_FAILED,
            ErrorClassifier.fromException(ConnectException("refused"), true, true))
    }

    @Test
    fun classifiesTimeoutByPhase() {
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(SocketTimeoutException("connect timed out"), true, false))
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(SocketTimeoutException("Connect timed out"), false, false))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR,
            ErrorClassifier.fromException(SocketTimeoutException("timeout"), false, false))
    }

    @Test
    fun classifiesTlsAndWrappedTls() {
        assertEquals(ConnectionErrorCode.TLS_CERT_FAILED,
            ErrorClassifier.fromException(SSLHandshakeException("cert"), true, false))
        val wrapped = RuntimeException("x", CertPathValidatorException("path"))
        assertEquals(ConnectionErrorCode.TLS_CERT_FAILED,
            ErrorClassifier.fromException(wrapped, true, false))
    }

    @Test
    fun classifiesHttpStatus() {
        assertEquals(ConnectionErrorCode.AUTH_FAILED, ErrorClassifier.fromHttpStatus(401))
        assertEquals(ConnectionErrorCode.AUTH_FAILED, ErrorClassifier.fromHttpStatus(403))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR, ErrorClassifier.fromHttpStatus(500))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR, ErrorClassifier.fromHttpStatus(404))
    }
}
