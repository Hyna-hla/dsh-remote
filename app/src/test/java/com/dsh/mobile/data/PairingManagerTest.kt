package com.dsh.mobile.data

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingManagerTest {

    private class FakeRpc(
        var requestResult: () -> String = { "pending" },
        var statuses: ArrayDeque<String> = ArrayDeque(listOf("approved")),
    ) : PairingRpc {
        override suspend fun request(deviceId: String, deviceName: String): String = requestResult()
        override suspend fun status(): String = statuses.removeFirstOrNull() ?: "pending"
    }

    @Test
    fun alreadyPaired() = runTest(UnconfinedTestDispatcher()) {
        val mgr = PairingManager(FakeRpc(requestResult = { "paired" }), backgroundScope)
        assertEquals(PairingOutcome.PAIRED, mgr.ensurePaired("d1", "iPhone"))
    }

    @Test
    fun pendingThenApproved() = runTest(UnconfinedTestDispatcher()) {
        val mgr = PairingManager(FakeRpc(statuses = ArrayDeque(listOf("pending", "approved"))), backgroundScope)
        assertEquals(PairingOutcome.APPROVED, mgr.ensurePaired("d1", "iPhone"))
    }

    @Test
    fun denied() = runTest(UnconfinedTestDispatcher()) {
        val mgr = PairingManager(FakeRpc(statuses = ArrayDeque(listOf("denied"))), backgroundScope)
        assertEquals(PairingOutcome.DENIED, mgr.ensurePaired("d1", "iPhone"))
    }

    @Test
    fun requestReturnsSkipLiteral() = runTest(UnconfinedTestDispatcher()) {
        val mgr = PairingManager(FakeRpc(requestResult = { "skip" }), backgroundScope)
        assertEquals(PairingOutcome.SKIPPED, mgr.ensurePaired("d1", "iPhone"))
    }

    @Test
    fun requestThrowsOtherThanUnavailable() = runTest(UnconfinedTestDispatcher()) {
        val mgr = PairingManager(FakeRpc(requestResult = { throw IllegalStateException("HTTP 500") }), backgroundScope)
        try {
            mgr.ensurePaired("d1", "iPhone")
            org.junit.Assert.fail("ensurePaired 应重抛非 CancellationException，而非吞成 SKIPPED")
        } catch (e: IllegalStateException) {
            assertEquals("HTTP 500", e.message)
        }
    }

    @Test
    fun timeoutAfterMaxAttempts() = runTest(UnconfinedTestDispatcher()) {
        val statuses = ArrayDeque(List(70) { "pending" })
        val mgr = PairingManager(FakeRpc(statuses = statuses), backgroundScope)
        assertEquals(PairingOutcome.TIMEOUT, mgr.ensurePaired("d1", "iPhone"))
    }
}
