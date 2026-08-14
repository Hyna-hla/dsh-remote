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

class SettingsStore(private val context: Context) {

    companion object {
        private val URL_KEY = stringPreferencesKey("server_url")
        private val AUTO_KEY = booleanPreferencesKey("auto_connect")
        private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
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
}
