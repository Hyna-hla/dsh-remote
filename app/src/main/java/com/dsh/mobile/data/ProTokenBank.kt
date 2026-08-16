package com.dsh.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 假订阅套餐（趣味彩蛋）
 */
data class ProPlan(
    val id: String,
    val name: String,
    /** 假价格（文案用） */
    val price: String,
    /** 假原价（划线价，各档位不同才像真的） */
    val originalPrice: String,
    /** 赠送 token 额度 */
    val tokens: Long,
    /** 幽默标签 */
    val tagline: String,
)

/**
 * 假 Pro 订阅银行：
 * - 套餐订阅/续费：余额 = 套餐额度
 * - 真实消耗扣减：会话实际消耗的 token（服务端 usage 优先，缺失按字符估算）实时减去余额
 * - 状态持久化（DataStore），App 重启不丢
 */
object ProTokenBank {

    val plans: List<ProPlan> = listOf(
        ProPlan("lite", "轻量 Pro", "¥0.00", "¥19.99", 100_000, "够你聊一宿，假装体面"),
        ProPlan("plus", "尊享 Pro", "¥0.00", "¥99.99", 1_000_000, "打工人的梦想额度"),
        ProPlan("infinity", "无限 Pro ∞", "¥0.00", "¥199.99", 100_000_000, "假装不差钱，横着聊"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ProState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    @Volatile
    private var store: SettingsStore? = null

    fun init(context: Context) {
        if (store != null) return
        store = SettingsStore(context.applicationContext)
        scope.launch {
            runCatching { store!!.proState.collect { _state.value = it } }
        }
    }

    fun planOf(id: String): ProPlan? = plans.firstOrNull { it.id == id }

    /** 订阅/续费：余额重置为套餐额度 */
    fun subscribe(plan: ProPlan) {
        _state.value = _state.value.copy(
            plan = plan.id,
            balance = plan.tokens,
            since = System.currentTimeMillis(),
        )
        persist()
    }

    /** 真实消耗扣减（会话每回合结束后调用） */
    fun consume(tokens: Long) {
        if (tokens <= 0) return
        _state.value = _state.value.copy(
            balance = _state.value.balance - tokens,
            consumed = _state.value.consumed + tokens,
        )
        persist()
    }

    /** 重置（取消订阅，清零） */
    fun reset() {
        _state.value = ProState()
        persist()
    }

    private fun persist() {
        val s = store ?: return
        val snapshot = _state.value
        scope.launch { runCatching { s.saveProState(snapshot) } }
    }

    /** 数字格式化：1.2M / 45K */
    fun fmt(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
