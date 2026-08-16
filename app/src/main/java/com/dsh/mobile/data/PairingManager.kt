package com.dsh.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

interface PairingRpc {
    suspend fun request(deviceId: String, deviceName: String): String
    suspend fun status(): String
}

enum class PairingOutcome { PAIRED, APPROVED, DENIED, TIMEOUT, SKIPPED }

class PairingManager(
    private val rpc: PairingRpc,
    private val scope: CoroutineScope,
) {
    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_ATTEMPTS = 60
    }

    suspend fun ensurePaired(deviceId: String, deviceName: String): PairingOutcome {
        val initial = try {
            rpc.request(deviceId, deviceName)
        } catch (e: CancellationException) {
            throw e
        }
        if (initial == "paired") return PairingOutcome.PAIRED
        if (initial == "skip") return PairingOutcome.SKIPPED // 404/410 = 插件未更新，spec 降级
        repeat(MAX_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val s = try {
                rpc.status()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "pending"
            }
            when (s) {
                "approved" -> return PairingOutcome.APPROVED
                "denied" -> return PairingOutcome.DENIED
            }
        }
        return PairingOutcome.TIMEOUT
    }
}
