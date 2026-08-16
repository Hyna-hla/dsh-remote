package com.dsh.mobile.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 连接成功后的配对握手协调器：
 * 监听 connection.state，Connected 且活跃 profile 未配对且未在握手中时，发起一次性配对确认。
 * 结果事件供 UI 层 Toast/snackbar 展示。
 */
class PairingCoordinator(
    private val connection: DshConnection,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    private var handshakingFor: String? = null

    fun start() {
        scope.launch {
            connection.state.collect { st ->
                if (st !is DshConnection.State.Connected) {
                    handshakingFor = null
                    return@collect
                }
                val activeId = settingsStore.activeProfileId.first() ?: return@collect
                val profile = settingsStore.profiles.first().firstOrNull { it.id == activeId }
                    ?: return@collect
                if (profile.paired || handshakingFor == activeId) return@collect
                handshakingFor = activeId
                val outcome = runCatching {
                    // 握手前已取得 profile，直接用其 unary client（避免不可达的兜底分支）
                    val client = OkHttpClientFactory.build(profile).first
                    val rpc = HttpPairingRpc(connection, client)
                    PairingManager(rpc, scope)
                        .ensurePaired(
                            settingsStore.ensureDeviceId(),
                            settingsStore.deviceName.first().ifBlank { android.os.Build.MODEL },
                        )
                }.getOrElse { PairingOutcome.SKIPPED }
                when (outcome) {
                    PairingOutcome.PAIRED, PairingOutcome.APPROVED, PairingOutcome.SKIPPED -> {
                        settingsStore.upsertProfile(profile.copy(paired = true))
                        if (outcome == PairingOutcome.SKIPPED) {
                            _events.tryEmit("PC 端插件未开启配对确认，已跳过（可在插件更新后重启验证）")
                        }
                    }
                    PairingOutcome.DENIED -> {
                        _events.tryEmit("PC 端拒绝了本次配对，连接已断开")
                        connection.disconnect()
                    }
                    PairingOutcome.TIMEOUT -> {
                        _events.tryEmit("配对确认超时，连接已断开")
                        connection.disconnect()
                    }
                }
                handshakingFor = null
            }
        }
    }
}
