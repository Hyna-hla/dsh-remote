package com.dsh.mobile.data

import org.json.JSONObject

/**
 * dsh-encrypt 保险库状态（/api/credentials.status 的 value 快照）。
 * - format："encrypted"（已设密码）| "plain"（未设密码，明文存储）
 * - unlocked：当前是否已解锁（进程全局——手机解锁后 PC web 端同步解锁）
 * - lockoutRetryAfterMs：防爆破锁定剩余毫秒（>0 时unlock 会被 429 拒绝）
 */
data class VaultStatus(
    val format: String,
    val unlocked: Boolean,
    val lockoutRetryAfterMs: Long,
    val plaintextForbidden: Boolean,
) {
    val encrypted: Boolean get() = format == "encrypted"
    val locked: Boolean get() = encrypted && !unlocked
}

/** /api/credentials.unlock 的结果（错误码对齐 dsh-encrypt 的 VaultError code）。 */
sealed interface VaultUnlockResult {
    data class Success(val local: Boolean) : VaultUnlockResult
    object WrongPassword : VaultUnlockResult
    data class LockedOut(val retryAfterMs: Long) : VaultUnlockResult
    object NotEncrypted : VaultUnlockResult
    data class Failure(val code: String, val message: String) : VaultUnlockResult
}

/** 解析 status 响应体 {ok, value:{format, unlocked, lockout:{retryAfterMs}, plaintextForbidden}}。 */
internal fun parseVaultStatus(body: String): VaultStatus? = runCatching {
    val root = JSONObject(body)
    if (!root.optBoolean("ok", false)) return null
    val value = root.optJSONObject("value") ?: return null
    VaultStatus(
        format = value.optString("format", ""),
        unlocked = value.optBoolean("unlocked", false),
        lockoutRetryAfterMs = value.optJSONObject("lockout")?.optLong("retryAfterMs", 0L) ?: 0L,
        plaintextForbidden = value.optBoolean("plaintextForbidden", false),
    )
}.getOrNull()

/** 解析 unlock 响应体：成功 {ok:true, value:{local,…}}；失败 {ok:false, code, message, retryAfterMs?}。 */
internal fun parseVaultUnlock(body: String): VaultUnlockResult = runCatching {
    val root = JSONObject(body)
    if (root.optBoolean("ok", false)) {
        return VaultUnlockResult.Success(local = root.optJSONObject("value")?.optBoolean("local", false) ?: false)
    }
    when (val code = root.optString("code", "")) {
        "PASSWORD_WRONG", "PASSWORD_INVALID" -> VaultUnlockResult.WrongPassword
        "TOO_MANY_ATTEMPTS" -> VaultUnlockResult.LockedOut(root.optLong("retryAfterMs", 0L))
        "VAULT_NOT_ENCRYPTED" -> VaultUnlockResult.NotEncrypted
        else -> VaultUnlockResult.Failure(code, root.optString("message", ""))
    }
}.getOrDefault(VaultUnlockResult.Failure("bad-response", ""))
