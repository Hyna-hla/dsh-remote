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

data class AppearanceConfig(
    val bgUri: String? = null,
    val bgOpacity: Float = 0.6f,
    val brightness: Float = 1f,
)

class SettingsStore(private val context: Context) {

    companion object {
        private val URL_KEY = stringPreferencesKey("server_url")
        private val AUTO_KEY = booleanPreferencesKey("auto_connect")
        private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        private val BG_URI_KEY = stringPreferencesKey("bg_uri")
        private val BG_OPACITY_KEY = floatPreferencesKey("bg_opacity")
        private val BRIGHTNESS_KEY = floatPreferencesKey("brightness")
        private val BACKGROUND_NOTIFY_KEY = booleanPreferencesKey("background_notify")
    }

    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            serverUrl = prefs[URL_KEY] ?: "",
            autoConnect = prefs[AUTO_KEY] ?: true,
        )
    }

    val darkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: true
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
            bgOpacity = prefs[BG_OPACITY_KEY] ?: 0.6f,
            brightness = prefs[BRIGHTNESS_KEY] ?: 1f,
        )
    }

    suspend fun saveAppearance(cfg: AppearanceConfig) {
        context.dataStore.edit { prefs ->
            if (cfg.bgUri != null) prefs[BG_URI_KEY] = cfg.bgUri else prefs.remove(BG_URI_KEY)
            prefs[BG_OPACITY_KEY] = cfg.bgOpacity
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
}
