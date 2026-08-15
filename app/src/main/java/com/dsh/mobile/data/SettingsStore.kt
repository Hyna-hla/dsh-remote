package com.dsh.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("dsh-mobile-settings")

data class ConnectionConfig(
    /** 服务器基础地址，如 http://192.168.1.100:8787 或 cpolar 域名 */
    val serverUrl: String = "",
    val autoConnect: Boolean = true,
)

/** 假 Pro 订阅状态（趣味彩蛋：真实 token 消耗扣减假订阅额度） */
data class ProState(
    /** 当前套餐 id（空 = 未订阅） */
    val plan: String = "",
    /** 剩余 token（可扣成负数 = 欠费） */
    val balance: Long = 0L,
    /** 累计消耗 token */
    val consumed: Long = 0L,
    /** 订阅时间戳 */
    val since: Long = 0L,
)

data class AppearanceConfig(
    val bgUri: String? = null,
    /** 图像不透明度 0.05–1（默认 100%，清晰可见） */
    val bgOpacity: Float = 1f,
    /** 背景模糊 0–30px（柔化背景，不影响文字） */
    val bgBlur: Float = 0f,
    /** 蒙层浓度 0–0.85（深色→黑 / 浅色→白，压在图片与界面之间保证文字可读） */
    val bgDim: Float = 0.3f,
    /** 背景饱和度 0.5–1.5 */
    val bgSaturate: Float = 1f,
    /** 面板通透 0–100（越高界面面板越透明，背景越清晰） */
    val panelGlass: Float = 65f,
    /** 全局亮度 0.4–1（夜间模式，整屏压暗） */
    val brightness: Float = 1f,
)

class SettingsStore(private val context: Context) {

    companion object {
        private val URL_KEY = stringPreferencesKey("server_url")
        private val AUTO_KEY = booleanPreferencesKey("auto_connect")
        private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        private val BG_URI_KEY = stringPreferencesKey("bg_uri")
        private val BG_OPACITY_KEY = floatPreferencesKey("bg_opacity")
        private val BG_BLUR_KEY = floatPreferencesKey("bg_blur")
        private val BG_DIM_KEY = floatPreferencesKey("bg_dim")
        private val BG_SATURATE_KEY = floatPreferencesKey("bg_saturate")
        private val PANEL_GLASS_KEY = floatPreferencesKey("panel_glass")
        private val BRIGHTNESS_KEY = floatPreferencesKey("brightness")
        private val BACKGROUND_NOTIFY_KEY = booleanPreferencesKey("background_notify")
        private val AUTO_MODEL_KEY = booleanPreferencesKey("auto_model")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val WORKSPACE_ID_KEY = stringPreferencesKey("workspace_id")
        private val UI_FONT_KEY = stringPreferencesKey("ui_font")
        private val CODE_FONT_KEY = stringPreferencesKey("code_font")
        private val PRO_PLAN_KEY = stringPreferencesKey("pro_plan")
        private val PRO_BALANCE_KEY = longPreferencesKey("pro_balance")
        private val PRO_CONSUMED_KEY = longPreferencesKey("pro_consumed")
        private val PRO_SINCE_KEY = longPreferencesKey("pro_since")
        private val PROFILE_LIST_KEY = stringPreferencesKey("connection_profiles")
        private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")
    }

    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            serverUrl = prefs[URL_KEY] ?: "",
            autoConnect = prefs[AUTO_KEY] ?: true,
        )
    }

    val profiles: Flow<List<HostProfile>> = context.dataStore.data.map { prefs ->
        ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")
    }

    val activeProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_KEY]
    }

    suspend fun upsertProfile(profile: HostProfile) {
        context.dataStore.edit { prefs ->
            applyLegacyMigration(prefs)
            val current = ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")
            prefs[PROFILE_LIST_KEY] = ProfileCodec.encode(current.filterNot { it.id == profile.id } + profile)
        }
    }

    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { prefs ->
            applyLegacyMigration(prefs)
            val current = ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")
            prefs[PROFILE_LIST_KEY] = ProfileCodec.encode(current.filterNot { it.id == id })
            if (prefs[ACTIVE_PROFILE_KEY] == id) prefs.remove(ACTIVE_PROFILE_KEY)
        }
    }

    suspend fun setActiveProfile(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_PROFILE_KEY)
            else prefs[ACTIVE_PROFILE_KEY] = id
        }
    }

    suspend fun markAttempt(profileId: String, errorCode: ConnectionErrorCode?, hostVersion: String?) {
        context.dataStore.edit { prefs ->
            applyLegacyMigration(prefs)
            val current = ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")
            prefs[PROFILE_LIST_KEY] = ProfileCodec.encode(current.map { p ->
                if (p.id == profileId) p.copy(
                    lastUsedAt = System.currentTimeMillis(),
                    lastErrorCode = errorCode?.name,
                ) else p
            })
        }
    }

    val darkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: true
    }

    /** 主题模式：blue（深蓝，默认）/ black（纯黑）/ warm（暖白）；旧值 dark/light/system 由 UI 层映射 */
    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY] ?: "blue"
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE_KEY] = mode }
    }

    /** 界面字体（Android 系统字体族名，空串 = 主题默认） */
    val uiFont: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[UI_FONT_KEY] ?: ""
    }

    suspend fun setUiFont(family: String) {
        context.dataStore.edit { it[UI_FONT_KEY] = family }
    }

    /** 代码字体（等宽族名，空串 = Monospace） */
    val codeFont: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[CODE_FONT_KEY] ?: ""
    }

    suspend fun setCodeFont(family: String) {
        context.dataStore.edit { it[CODE_FONT_KEY] = family }
    }

    // ── 假 Pro 订阅（趣味彩蛋）──

    /** 假订阅状态：plan 空 = 未订阅 */
    val proState: Flow<ProState> = context.dataStore.data.map { prefs ->
        ProState(
            plan = prefs[PRO_PLAN_KEY] ?: "",
            balance = prefs[PRO_BALANCE_KEY] ?: 0L,
            consumed = prefs[PRO_CONSUMED_KEY] ?: 0L,
            since = prefs[PRO_SINCE_KEY] ?: 0L,
        )
    }

    suspend fun saveProState(state: ProState) {
        context.dataStore.edit { prefs ->
            prefs[PRO_PLAN_KEY] = state.plan
            prefs[PRO_BALANCE_KEY] = state.balance
            prefs[PRO_CONSUMED_KEY] = state.consumed
            prefs[PRO_SINCE_KEY] = state.since
        }
    }

    /** 新对话默认工作区（空 = 用 DSH 默认工作区） */
    val workspaceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[WORKSPACE_ID_KEY] ?: ""
    }

    suspend fun setWorkspaceId(id: String) {
        context.dataStore.edit { it[WORKSPACE_ID_KEY] = id }
    }

    suspend fun saveConnection(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[URL_KEY] = config.serverUrl
            prefs[AUTO_KEY] = config.autoConnect
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[DARK_MODE_KEY] = enabled }
    }

    val appearance: Flow<AppearanceConfig> = context.dataStore.data.map { prefs ->
        AppearanceConfig(
            bgUri = prefs[BG_URI_KEY],
            bgOpacity = prefs[BG_OPACITY_KEY] ?: 1f,
            bgBlur = prefs[BG_BLUR_KEY] ?: 0f,
            bgDim = prefs[BG_DIM_KEY] ?: 0.3f,
            bgSaturate = prefs[BG_SATURATE_KEY] ?: 1f,
            panelGlass = prefs[PANEL_GLASS_KEY] ?: 65f,
            brightness = prefs[BRIGHTNESS_KEY] ?: 1f,
        )
    }

    suspend fun saveAppearance(cfg: AppearanceConfig) {
        context.dataStore.edit { prefs ->
            if (cfg.bgUri != null) prefs[BG_URI_KEY] = cfg.bgUri else prefs.remove(BG_URI_KEY)
            prefs[BG_OPACITY_KEY] = cfg.bgOpacity
            prefs[BG_BLUR_KEY] = cfg.bgBlur
            prefs[BG_DIM_KEY] = cfg.bgDim
            prefs[BG_SATURATE_KEY] = cfg.bgSaturate
            prefs[PANEL_GLASS_KEY] = cfg.panelGlass
            prefs[BRIGHTNESS_KEY] = cfg.brightness
        }
    }

    /** App 在后台时是否推送审批/确认提醒（默认开） */
    val backgroundNotify: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BACKGROUND_NOTIFY_KEY] ?: true
    }

    suspend fun setBackgroundNotify(enabled: Boolean) {
        context.dataStore.edit { it[BACKGROUND_NOTIFY_KEY] = enabled }
    }

    /** 自适应模型：按任务难度自动选择 Flash / Pro（默认开） */
    val autoModel: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_MODEL_KEY] ?: true
    }

    suspend fun setAutoModel(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_MODEL_KEY] = enabled }
    }
}

/**
 * 旧版单地址（server_url / auto_connect）→ 新 profiles 的一次性迁移。
 * 返回是否发生了变更。幂等：旧 key 不存在时返回 false。
 */
internal fun applyLegacyMigration(prefs: MutablePreferences): Boolean {
    val url = prefs[stringPreferencesKey("server_url")] ?: return false
    val auto = prefs[booleanPreferencesKey("auto_connect")] ?: true
    val existing = ProfileCodec.decode(prefs[stringPreferencesKey("connection_profiles")] ?: "")
    if (existing.isEmpty() && url.isNotBlank()) {
        val profile = HostProfile(
            id = java.util.UUID.randomUUID().toString(),
            remark = "旧连接",
            url = url,
            autoConnect = auto,
        )
        prefs[stringPreferencesKey("connection_profiles")] = ProfileCodec.encode(listOf(profile))
        prefs[stringPreferencesKey("active_profile_id")] = profile.id
    }
    prefs.remove(stringPreferencesKey("server_url"))
    prefs.remove(booleanPreferencesKey("auto_connect"))
    return true
}
