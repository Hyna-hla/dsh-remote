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
 *
 * 设备记录（v1.6.0）：配对通过后经 device/info 抓取主机机型与 MAC 存入 HostProfile；
 * 重连（已配对）时校验 MAC 一致才放行——动态公网 IP 被回收再分配时不会误连他人主机。
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
                val profile = connection.currentProfile() ?: return@collect
                if (profile.paired) {
                    val still = runCatching {
                        HttpPairingRpc(connection, OkHttpClientFactory.build(profile).first)
                            .check(settingsStore.ensureDeviceId())
                    }.getOrNull()
                    when {
                        // 回查失败保守视为仍配对（网络错不打扰、不触发重新握手）
                        still == null -> return@collect
                        !still.paired -> {
                            settingsStore.upsertProfile(profile.copy(paired = false))
                            // 继续走握手（不 return）
                        }
                        else -> {
                            // 已配对：顺带刷新通道 token（PC 端重生成/本机丢失时静默补齐）
                            still.token?.takeIf { it.isNotBlank() && it != profile.channelToken }?.let { tok ->
                                settingsStore.upsertProfile(profile.copy(channelToken = tok))
                                OkHttpClientFactory.ChannelTokenRegistry.set(profile.id, tok)
                            }
                            // 重连设备校验：MAC 不匹配（IP 被回收到他人主机）→ 断开
                            verifyDevice(profile)
                            return@collect
                        }
                    }
                }
                if (handshakingFor == profile.id) return@collect
                handshakingFor = profile.id
                val result = runCatching {
                    // 握手前已取得 profile，直接用其 unary client（避免不可达的兜底分支）
                    val client = OkHttpClientFactory.build(profile).first
                    val rpc = HttpPairingRpc(connection, client)
                    PairingManager(rpc, scope)
                        .ensurePaired(
                            settingsStore.ensureDeviceId(),
                            settingsStore.deviceName.first().ifBlank { android.os.Build.MODEL },
                        )
                }.getOrElse { null }
                when (result) {
                    null -> {
                        _events.tryEmit("配对请求失败（网络或服务器错误），下次连接时重试")
                    }
                    PairingOutcome.PAIRED, PairingOutcome.APPROVED -> {
                        // 配对通过后立即回查拿通道 token（Bearer 鉴权凭证），注册表热生效
                        val rpc = HttpPairingRpc(connection, OkHttpClientFactory.build(profile).first)
                        val tok = runCatching {
                            rpc.check(settingsStore.ensureDeviceId()).token
                        }.getOrNull()
                        if (!tok.isNullOrBlank()) {
                            OkHttpClientFactory.ChannelTokenRegistry.set(profile.id, tok)
                        }
                        // 首次连接：抓取主机机型与 MAC 写入设备记录（token 已热生效，device/info 可通过鉴权）
                        val info = runCatching { rpc.deviceInfo() }.getOrNull()
                        settingsStore.upsertProfile(
                            profile.copy(
                                paired = true,
                                channelToken = tok ?: profile.channelToken,
                                deviceModel = info?.model?.takeIf { it.isNotBlank() } ?: profile.deviceModel,
                                deviceMac = info?.mac?.takeIf { it.isNotBlank() } ?: profile.deviceMac,
                            )
                        )
                        when {
                            !tok.isNullOrBlank() -> _events.tryEmit(
                                if (info?.model?.isNotBlank() == true) "配对完成，已记录设备：${info.model}"
                                else "配对完成，已获取远程通道令牌"
                            )
                            else -> _events.tryEmit("配对完成")
                        }
                    }
                    PairingOutcome.SKIPPED -> {
                        settingsStore.upsertProfile(profile.copy(paired = true))
                        _events.tryEmit("PC 端插件未开启配对确认，已跳过（可在插件更新后重启验证）")
                    }
                    PairingOutcome.DENIED -> {
                        _events.tryEmit("PC 端拒绝了本次配对，连接已断开")
                        connection.disconnect()
                    }
                    PairingOutcome.TIMEOUT -> {
                        _events.tryEmit("配对确认超时（请确认 PC 端已打开 设置→远程控制 页面），连接已断开")
                        connection.disconnect()
                    }
                }
                handshakingFor = null
            }
        }
    }

    /**
     * 重连设备校验（已配对 profile）：
     * - 旧插件（404）/网络错误 → null，降级不校验；
     * - 首见（旧版本升级前配对的记录无 MAC）→ 记录机型与 MAC；
     * - MAC 不一致 → 断开（该地址已被回收给别的设备）。
     */
    private suspend fun verifyDevice(profile: HostProfile) {
        val info = runCatching {
            HttpPairingRpc(connection, OkHttpClientFactory.build(profile).first).deviceInfo()
        }.getOrNull() ?: return
        if (info.mac.isBlank() && info.model.isBlank()) return
        if (profile.deviceMac.isBlank()) {
            settingsStore.upsertProfile(profile.copy(deviceModel = info.model, deviceMac = info.mac))
            return
        }
        if (info.mac.isNotBlank() && !info.mac.equals(profile.deviceMac, ignoreCase = true)) {
            _events.tryEmit(
                "设备校验失败：该地址当前不是「${profile.deviceModel.ifBlank { "已配对" }}」，已断开（公网 IP 可能被回收）",
            )
            connection.disconnect()
            return
        }
        if (info.model.isNotBlank() && info.model != profile.deviceModel) {
            settingsStore.upsertProfile(profile.copy(deviceModel = info.model))
        }
    }
}
