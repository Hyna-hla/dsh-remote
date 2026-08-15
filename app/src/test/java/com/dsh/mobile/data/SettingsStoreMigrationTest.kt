package com.dsh.mobile.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreMigrationTest {

    private val urlKey = stringPreferencesKey("server_url")
    private val autoKey = booleanPreferencesKey("auto_connect")

    private fun migrated(prefs: MutablePreferences): Pair<Boolean, MutablePreferences> {
        val changed = applyLegacyMigration(prefs)
        return changed to prefs
    }

    @Test
    fun legacyCreatesProfileAndActive() {
        val prefs = mutablePreferencesOf(
            urlKey to "http://192.168.1.10:8787",
            autoKey to true,
        )
        val (changed, p) = migrated(prefs)
        assertTrue(changed)
        val profiles = ProfileCodec.decode(p[stringPreferencesKey("connection_profiles")] ?: "")
        assertEquals(1, profiles.size)
        assertEquals("http://192.168.1.10:8787", profiles[0].url)
        assertEquals("旧连接", profiles[0].remark)
        assertTrue(profiles[0].autoConnect)
        assertEquals(profiles[0].id, p[stringPreferencesKey("active_profile_id")])
        assertFalse(p.contains(urlKey))
        assertFalse(p.contains(autoKey))
    }

    @Test
    fun noLegacyNoChange() {
        val prefs = mutablePreferencesOf()
        val (changed, _) = migrated(prefs)
        assertFalse(changed)
    }

    @Test
    fun existingProfilesKeepAndCleanLegacy() {
        val existing = ProfileCodec.encode(listOf(
            HostProfile(id = "p1", remark = "家里", url = "http://a:1"),
        ))
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("connection_profiles") to existing,
            urlKey to "http://old:1",
        )
        val (changed, p) = migrated(prefs)
        assertTrue(changed) // 旧 key 被清掉
        val profiles = ProfileCodec.decode(p[stringPreferencesKey("connection_profiles")] ?: "")
        assertEquals(1, profiles.size)
        assertEquals("p1", profiles[0].id) // 不覆盖已有配置
        assertFalse(p.contains(urlKey))
    }

    @Test
    fun migrationSurvivesSubsequentUpsert() {
        val prefs = mutablePreferencesOf(
            urlKey to "http://legacy:8787",
        )
        // 第一步：迁移生成 profile 并设为活跃
        applyLegacyMigration(prefs)
        val migratedId = prefs[stringPreferencesKey("active_profile_id")]

        // 第二步：模拟 upsert 的 edit 内序列（迁移→重读→写回）
        applyLegacyMigration(prefs)
        val current = ProfileCodec.decode(prefs[stringPreferencesKey("connection_profiles")] ?: "")
        val newProfile = HostProfile(id = "new-1", remark = "新主机", url = "http://new:1")
        prefs[stringPreferencesKey("connection_profiles")] = ProfileCodec.encode(
            current.filterNot { it.id == newProfile.id } + newProfile,
        )

        val finalProfiles = ProfileCodec.decode(prefs[stringPreferencesKey("connection_profiles")] ?: "")
        assertEquals(2, finalProfiles.size)
        assertTrue(finalProfiles.any { it.id == migratedId && it.url == "http://legacy:8787" })
        assertTrue(finalProfiles.any { it.id == "new-1" })
    }
}
