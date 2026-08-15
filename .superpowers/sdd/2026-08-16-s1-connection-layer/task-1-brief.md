### Task 1: 娴嬭瘯璁炬柦 + HostProfile 鏁版嵁妯″瀷涓?JSON Codec

**Files:**
- Modify: `gradle/libs.versions.toml`锛堝姞 junit锛?- Modify: `app/build.gradle.kts`锛堝姞 testImplementation锛?- Create: `app/src/main/java/com/dsh/mobile/data/HostProfile.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/HostProfileTest.kt`

**Interfaces:**
- Consumes: 鏃狅紙绗竴涓换鍔★級
- Produces:
```kotlin
// data/HostProfile.kt
enum class ConnectionErrorCode { DNS_UNREACHABLE, PORT_UNREACHABLE, TLS_CERT_FAILED,
    AUTH_FAILED, VERSION_MISMATCH, PROXY_FAILED, PROTOCOL_ERROR, UNKNOWN }

@Serializable
data class ProxyConfig(
    val type: String = "none", // "none" | "http" | "socks5"
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
)

@Serializable
data class HostProfile(
    val id: String,                 // UUID
    val remark: String,
    val url: String,                // 宸茶鑼冨寲锛堝惈鍗忚銆佹棤灏炬枩鏉狅級
    val trustSelfSigned: Boolean = false,
    val caCertUri: String? = null,  // App 绉佹湁鐩綍鍐?CA 鏂囦欢璺緞
    val proxy: ProxyConfig? = null,
    val autoConnect: Boolean = false,
    val lastUsedAt: Long = 0,
    val lastErrorCode: String? = null,
)

object ProfileCodec {
    fun encode(profiles: List<HostProfile>): String
    fun decode(text: String): List<HostProfile>   // 瑙ｆ瀽澶辫触杩斿洖 emptyList
}
```
- `ConnectionErrorCode.name` 鍗?spec 搂6 琛ㄩ敭锛圱ask 7 鐨?ErrorMessages 渚濊禆锛夈€?
- [ ] **Step 1: 鍔?JUnit 娴嬭瘯渚濊禆**

`gradle/libs.versions.toml`锛?```toml
[versions]
junit = "4.13.2"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
```
`app/build.gradle.kts` 鐨?dependencies 鍧楁湯灏捐拷鍔狅細
```kotlin
    testImplementation(libs.junit)
```

- [ ] **Step 2: 鍐欏け璐ユ祴璇?*

`app/src/test/java/com/dsh/mobile/data/HostProfileTest.kt`锛?```kotlin
package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {

    private fun sample() = HostProfile(
        id = "p1", remark = "瀹堕噷", url = "http://192.168.1.100:8787",
        trustSelfSigned = true, caCertUri = "/data/certs/p1.pem",
        proxy = ProxyConfig(type = "socks5", host = "127.0.0.1", port = 1080,
            username = "u", password = "p"),
        autoConnect = true, lastUsedAt = 123L, lastErrorCode = "PORT_UNREACHABLE",
    )

    @Test
    fun codecRoundTrip() {
        val text = ProfileCodec.encode(listOf(sample()))
        val back = ProfileCodec.decode(text)
        assertEquals(1, back.size)
        assertEquals(sample(), back[0])
    }

    @Test
    fun codecEmptyList() {
        val back = ProfileCodec.decode(ProfileCodec.encode(emptyList()))
        assertTrue(back.isEmpty())
    }

    @Test
    fun codecGarbageReturnsEmpty() {
        assertTrue(ProfileCodec.decode("not json").isEmpty())
        assertTrue(ProfileCodec.decode("").isEmpty())
    }

    @Test
    fun codecDefaults() {
        val p = HostProfile(id = "p2", remark = "鍏徃", url = "http://10.0.0.2:8787")
        val back = ProfileCodec.decode(ProfileCodec.encode(listOf(p)))
        assertEquals(false, back[0].trustSelfSigned)
        assertEquals(null, back[0].proxy)
        assertEquals(false, back[0].autoConnect)
    }
}
```

- [ ] **Step 3: 杩愯娴嬭瘯纭澶辫触**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.HostProfileTest"`
Expected: 缂栬瘧澶辫触锛圚ostProfile/ProfileCodec 涓嶅瓨鍦級銆?
- [ ] **Step 4: 瀹炵幇妯″瀷涓?Codec**

`app/src/main/java/com/dsh/mobile/data/HostProfile.kt`锛?```kotlin
package com.dsh.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ConnectionErrorCode {
    DNS_UNREACHABLE,   // 鍩熷悕瑙ｆ瀽澶辫触
    PORT_UNREACHABLE,  // TCP 鎷掔粷/杩炴帴瓒呮椂
    TLS_CERT_FAILED,   // 璇佷功鏍￠獙澶辫触
    AUTH_FAILED,       // HTTP 401/403锛堝墠缃綉鍏筹級
    VERSION_MISMATCH,  // host.describe 鐗堟湰浣庝簬涓嬬晫
    PROXY_FAILED,      // 閰嶇疆浜嗕唬鐞嗕絾浠ｇ悊涓嶅彲杈?    PROTOCOL_ERROR,    // 闈?200 / 瑙ｆ瀽澶辫触 / RPC ok=false / 璇昏秴鏃?    UNKNOWN,
}

@Serializable
data class ProxyConfig(
    val type: String = "none", // "none" | "http" | "socks5"
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "", // TODO-S3: 杩?Keystore锛屾殏鏄庢枃瀛?DataStore
)

@Serializable
data class HostProfile(
    val id: String,
    val remark: String,
    val url: String,
    val trustSelfSigned: Boolean = false,
    val caCertUri: String? = null,
    val proxy: ProxyConfig? = null,
    val autoConnect: Boolean = false,
    val lastUsedAt: Long = 0,
    val lastErrorCode: String? = null,
)

object ProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(profiles: List<HostProfile>): String =
        json.encodeToString(ListSerializer(HostProfile.serializer()), profiles)

    fun decode(text: String): List<HostProfile> = runCatching {
        json.decodeFromString(ListSerializer(HostProfile.serializer()), text)
    }.getOrDefault(emptyList())
}

private fun <T> ListSerializer(element: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(element)
```

- [ ] **Step 5: 杩愯娴嬭瘯纭閫氳繃**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.HostProfileTest"`
Expected: 4 涓祴璇曞叏 PASS銆?
- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/dsh/mobile/data/HostProfile.kt app/src/test/java/com/dsh/mobile/data/HostProfileTest.kt
git commit -m "feat(s1): HostProfile 鏁版嵁妯″瀷 + JSON Codec + JUnit 娴嬭瘯璁炬柦"
```

---

### Task 2: ConnectionPolicy 鈥斺€?鐗堟湰瀹藉绛栫暐 / 閲嶈繛閫€閬?/ 閿欒鍒嗙被锛堢函閫昏緫锛?
**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/ConnectionPolicy.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt`

**Interfaces:**
- Consumes: Task 1 鐨?`ConnectionErrorCode`
- Produces:
```kotlin
// data/ConnectionPolicy.kt
enum class VersionVerdict { OK, UNKNOWN, MISMATCH }

object VersionPolicy {
    const val MIN_DSH_VERSION = "0.0.0"
    const val PLACEHOLDER_VERSION = "0.0.1"
    fun evaluate(version: String?, min: String = MIN_DSH_VERSION): VersionVerdict
}

object RetryPolicy {
    const val FAST_TIER_CAP_MS = 9_000L
    const val SLOW_TIER_CAP_MS = 30_000L
    fun isRecoverable(code: ConnectionErrorCode): Boolean
    /** attempt 浠?0 璧凤紱涓嶅彲鎭㈠杩斿洖 null */
    fun nextBackoff(code: ConnectionErrorCode, attempt: Int): Long?
}

object ErrorClassifier {
    fun fromException(t: Throwable, connectPhase: Boolean, hasProxy: Boolean): ConnectionErrorCode
    fun fromHttpStatus(status: Int): ConnectionErrorCode
}
```

- [ ] **Step 1: 鍐欏け璐ユ祴璇?*

`app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt`锛?```kotlin
package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.security.cert.CertPathValidatorException

class ConnectionPolicyTest {

    // ---- VersionPolicy ----
    @Test
    fun versionPlaceholderIsUnknown() {
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate("0.0.1"))
    }

    @Test
    fun versionUnparseableOrNullIsUnknown() {
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate(null))
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate("dev"))
        assertEquals(VersionVerdict.UNKNOWN, VersionPolicy.evaluate(""))
    }

    @Test
    fun versionAtOrAboveMinIsOk() {
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("0.1.0"))
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("1.2.3"))
    }

    @Test
    fun versionBelowInjectedMinIsMismatch() {
        assertEquals(VersionVerdict.MISMATCH, VersionPolicy.evaluate("0.0.5", min = "0.1.0"))
        assertEquals(VersionVerdict.OK, VersionPolicy.evaluate("0.1.0", min = "0.1.0"))
    }

    // ---- RetryPolicy ----
    @Test
    fun authAndVersionMismatchStopRetrying() {
        assertNull(RetryPolicy.nextBackoff(ConnectionErrorCode.AUTH_FAILED, 0))
        assertNull(RetryPolicy.nextBackoff(ConnectionErrorCode.VERSION_MISMATCH, 5))
        assertFalse(RetryPolicy.isRecoverable(ConnectionErrorCode.AUTH_FAILED))
    }

    @Test
    fun fastTierDoublesToCap() {
        assertEquals(3_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 0))
        assertEquals(6_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.DNS_UNREACHABLE, 1))
        assertEquals(9_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 2))
        assertEquals(9_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PORT_UNREACHABLE, 10))
    }

    @Test
    fun slowTierDoublesToCap() {
        assertEquals(3_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 0))
        assertEquals(6_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PROTOCOL_ERROR, 1))
        assertEquals(12_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.UNKNOWN, 2))
        assertEquals(24_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.PROXY_FAILED, 3))
        assertEquals(30_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 4))
        assertEquals(30_000L, RetryPolicy.nextBackoff(ConnectionErrorCode.TLS_CERT_FAILED, 99))
    }

    // ---- ErrorClassifier ----
    @Test
    fun classifiesDnsAndPort() {
        assertEquals(ConnectionErrorCode.DNS_UNREACHABLE,
            ErrorClassifier.fromException(UnknownHostException("nope"), false, false))
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(ConnectException("refused"), true, false))
        assertEquals(ConnectionErrorCode.PROXY_FAILED,
            ErrorClassifier.fromException(ConnectException("refused"), true, true))
    }

    @Test
    fun classifiesTimeoutByPhase() {
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(SocketTimeoutException("connect timed out"), true, false))
        assertEquals(ConnectionErrorCode.PORT_UNREACHABLE,
            ErrorClassifier.fromException(SocketTimeoutException("Connect timed out"), false, false))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR,
            ErrorClassifier.fromException(SocketTimeoutException("timeout"), false, false))
    }

    @Test
    fun classifiesTlsAndWrappedTls() {
        assertEquals(ConnectionErrorCode.TLS_CERT_FAILED,
            ErrorClassifier.fromException(SSLHandshakeException("cert"), true, false))
        val wrapped = RuntimeException("x", CertPathValidatorException("path"))
        assertEquals(ConnectionErrorCode.TLS_CERT_FAILED,
            ErrorClassifier.fromException(wrapped, true, false))
    }

    @Test
    fun classifiesHttpStatus() {
        assertEquals(ConnectionErrorCode.AUTH_FAILED, ErrorClassifier.fromHttpStatus(401))
        assertEquals(ConnectionErrorCode.AUTH_FAILED, ErrorClassifier.fromHttpStatus(403))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR, ErrorClassifier.fromHttpStatus(500))
        assertEquals(ConnectionErrorCode.PROTOCOL_ERROR, ErrorClassifier.fromHttpStatus(404))
    }
}
```

- [ ] **Step 2: 杩愯娴嬭瘯纭澶辫触**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"`
Expected: 缂栬瘧澶辫触锛圕onnectionPolicy 涓嶅瓨鍦級銆?
- [ ] **Step 3: 瀹炵幇 ConnectionPolicy**

`app/src/main/java/com/dsh/mobile/data/ConnectionPolicy.kt`锛?```kotlin
package com.dsh.mobile.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

enum class VersionVerdict { OK, UNKNOWN, MISMATCH }

object VersionPolicy {
    const val MIN_DSH_VERSION = "0.0.0"
    const val PLACEHOLDER_VERSION = "0.0.1"

    private val SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    fun evaluate(version: String?, min: String = MIN_DSH_VERSION): VersionVerdict {
        if (version.isNullOrBlank()) return VersionVerdict.UNKNOWN
        if (version.trim() == PLACEHOLDER_VERSION) return VersionVerdict.UNKNOWN
        val v = parse(version) ?: return VersionVerdict.UNKNOWN
        val m = parse(min) ?: return VersionVerdict.UNKNOWN
        return if (v >= m) VersionVerdict.OK else VersionVerdict.MISMATCH
    }

    /** 瑙ｆ瀽澶辫触杩斿洖 null锛泃riple 鎸夋暟鍊兼瘮杈?*/
    private fun parse(s: String): Triple<Int, Int, Int>? {
        val m = SEMVER.find(s.trim()) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
}

object RetryPolicy {
    const val FAST_TIER_CAP_MS = 9_000L
    const val SLOW_TIER_CAP_MS = 30_000L

    fun isRecoverable(code: ConnectionErrorCode): Boolean =
        code != ConnectionErrorCode.AUTH_FAILED && code != ConnectionErrorCode.VERSION_MISMATCH

    fun nextBackoff(code: ConnectionErrorCode, attempt: Int): Long? {
        if (!isRecoverable(code)) return null
        val cap = if (code == ConnectionErrorCode.PORT_UNREACHABLE ||
            code == ConnectionErrorCode.DNS_UNREACHABLE
        ) FAST_TIER_CAP_MS else SLOW_TIER_CAP_MS
        var ms = 3_000L
        repeat(attempt.coerceAtLeast(0)) { ms *= 2 }
        return ms.coerceAtMost(cap)
    }
}

object ErrorClassifier {
    fun fromException(t: Throwable, connectPhase: Boolean, hasProxy: Boolean): ConnectionErrorCode {
        if (t is UnknownHostException) return ConnectionErrorCode.DNS_UNREACHABLE
        if (t is ConnectException) {
            return if (hasProxy) ConnectionErrorCode.PROXY_FAILED else ConnectionErrorCode.PORT_UNREACHABLE
        }
        if (t is SocketTimeoutException) {
            val connectTimeout = connectPhase ||
                t.message.orEmpty().contains("connect", ignoreCase = true)
            return if (connectTimeout) {
                if (hasProxy) ConnectionErrorCode.PROXY_FAILED else ConnectionErrorCode.PORT_UNREACHABLE
            } else ConnectionErrorCode.PROTOCOL_ERROR
        }
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is SSLHandshakeException || cur is SSLPeerUnverifiedException ||
                cur is CertPathValidatorException
            ) return ConnectionErrorCode.TLS_CERT_FAILED
            cur = cur.cause
        }
        if (t is ApiException && t.code != null) {
            return fromHttpStatus(t.code!!.toIntOrNull() ?: 0)
        }
        return ConnectionErrorCode.UNKNOWN
    }

    fun fromHttpStatus(status: Int): ConnectionErrorCode = when (status) {
        401, 403 -> ConnectionErrorCode.AUTH_FAILED
        else -> ConnectionErrorCode.PROTOCOL_ERROR
    }
}
```

- [ ] **Step 4: 杩愯娴嬭瘯纭閫氳繃**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"`
Expected: 鍏ㄩ儴 PASS銆?
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/ConnectionPolicy.kt app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt
git commit -m "feat(s1): 鐗堟湰瀹藉绛栫暐 + 閲嶈繛閫€閬?+ 閿欒鍒嗙被绾€昏緫"
```

---

### Task 3: SettingsStore 鈥斺€?profiles 瀛樺偍銆佹椿璺冧富鏈恒€佹棫鏁版嵁杩佺Щ

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/SettingsStoreMigrationTest.kt`

**Interfaces:**
- Consumes: Task 1 鐨?`HostProfile` / `ProfileCodec` / `ConnectionErrorCode`
- Produces锛圫ettingsStore 鍐呮柊澧烇級锛?```kotlin
val profiles: Flow<List<HostProfile>>
val activeProfileId: Flow<String?>
suspend fun upsertProfile(profile: HostProfile)
suspend fun deleteProfile(id: String)
suspend fun setActiveProfile(id: String?)
/** 璁板綍涓€娆¤繛鎺ュ皾璇曪細鍐?lastUsedAt/lastErrorCode */
suspend fun markAttempt(profileId: String, errorCode: ConnectionErrorCode?, hostVersion: String?)
// 椤跺眰绾嚱鏁帮紙鍚屾枃浠讹紝渚涙祴璇曪級锛?internal fun applyLegacyMigration(prefs: MutablePreferences): Boolean
```

- [ ] **Step 1: 鍐欏け璐ユ祴璇曪紙绾嚱鏁拌縼绉婚€昏緫锛?*

`app/src/test/java/com/dsh/mobile/data/SettingsStoreMigrationTest.kt`锛?```kotlin
package com.dsh.mobile.data

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
        assertEquals("鏃ц繛鎺?, profiles[0].remark)
        assertTrue(profiles[0].autoConnect)
        assertEquals(profiles[0].id, p[stringPreferencesKey("active_profile_id")])
        assertFalse(p.contains(urlKey))
        assertFalse(p.contains(autoKey))
    }

    @Test
    fun noLegacyNoChange() {
        val prefs = mutablePreferencesOf<String>()
        val (changed, _) = migrated(prefs)
        assertFalse(changed)
    }

    @Test
    fun existingProfilesKeepAndCleanLegacy() {
        val existing = ProfileCodec.encode(listOf(
            HostProfile(id = "p1", remark = "瀹堕噷", url = "http://a:1"),
        ))
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("connection_profiles") to existing,
            urlKey to "http://old:1",
        )
        val (changed, p) = migrated(prefs)
        assertTrue(changed) // 鏃?key 琚竻鎺?        val profiles = ProfileCodec.decode(p[stringPreferencesKey("connection_profiles")] ?: "")
        assertEquals(1, profiles.size)
        assertEquals("p1", profiles[0].id) // 涓嶈鐩栧凡鏈夐厤缃?        assertFalse(p.contains(urlKey))
    }
}
```

- [ ] **Step 2: 杩愯娴嬭瘯纭澶辫触**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.SettingsStoreMigrationTest"`
Expected: 缂栬瘧澶辫触锛坅pplyLegacyMigration 涓嶅瓨鍦級銆?
- [ ] **Step 3: 瀹炵幇瀛樺偍涓庤縼绉?*

淇敼 `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`锛?
companion object 鍐呰拷鍔狅細
```kotlin
        private val PROFILE_LIST_KEY = stringPreferencesKey("connection_profiles")
        private val ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")
```

绫讳綋鍐呰拷鍔狅細
```kotlin
    val profiles: Flow<List<HostProfile>> = context.dataStore.data.map { prefs ->
        ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")
    }

    val activeProfileId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_KEY]
    }

    private suspend fun writeProfiles(profiles: List<HostProfile>) {
        context.dataStore.edit { prefs ->
            applyLegacyMigration(prefs) // 椤哄甫娓呯悊鏃?key锛堝箓绛夛級
            prefs[PROFILE_LIST_KEY] = ProfileCodec.encode(profiles)
        }
    }

    suspend fun upsertProfile(profile: HostProfile) {
        val current = profiles.first()
        writeProfiles(current.filterNot { it.id == profile.id } + profile)
    }

    suspend fun deleteProfile(id: String) {
        val current = profiles.first()
        writeProfiles(current.filterNot { it.id == id })
        if (activeProfileId.first() == id) setActiveProfile(null)
    }

    suspend fun setActiveProfile(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_PROFILE_KEY)
            else prefs[ACTIVE_PROFILE_KEY] = id
        }
    }

    suspend fun markAttempt(profileId: String, errorCode: ConnectionErrorCode?, hostVersion: String?) {
        val current = profiles.first()
        writeProfiles(current.map { p ->
            if (p.id == profileId) p.copy(
                lastUsedAt = System.currentTimeMillis(),
                lastErrorCode = errorCode?.name,
            ) else p
        })
    }
```

鏂囦欢搴曢儴锛堥《灞傚嚱鏁帮紝渚涙祴璇曚笌 writeProfiles 浣跨敤锛夛細
```kotlin
/**
 * 鏃х増鍗曞湴鍧€锛坰erver_url / auto_connect锛夆啋 鏂?profiles 鐨勪竴娆℃€ц縼绉汇€? * 杩斿洖鏄惁鍙戠敓浜嗗彉鏇淬€傚箓绛夛細鏃?key 涓嶅瓨鍦ㄦ椂杩斿洖 false銆? */
internal fun applyLegacyMigration(prefs: MutablePreferences): Boolean {
    val url = prefs[stringPreferencesKey("server_url")] ?: return false
    val auto = prefs[booleanPreferencesKey("auto_connect")] ?: true
    val existing = ProfileCodec.decode(prefs[stringPreferencesKey("connection_profiles")] ?: "")
    if (existing.isEmpty() && url.isNotBlank()) {
        val profile = HostProfile(
            id = java.util.UUID.randomUUID().toString(),
            remark = "鏃ц繛鎺?,
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
```

娉ㄦ剰锛歚applyLegacyMigration` 闇€寮曠敤 `MutablePreferences` / key 宸ュ巶锛屾枃浠堕《閮ㄨˉ import锛?```kotlin
import androidx.datastore.preferences.core.MutablePreferences
```
锛坄stringPreferencesKey`/`booleanPreferencesKey` 宸插湪鐜版湁 import 涓€傦級

- [ ] **Step 4: 杩愯娴嬭瘯纭閫氳繃**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.SettingsStoreMigrationTest"`
Expected: 3 涓祴璇?PASS銆傚悓鏃惰窇鍏ㄩ噺鏃㈡湁娴嬭瘯锛歚.\gradlew.bat :app:testDebugUnitTest` 纭鏃犲洖褰掋€?
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/SettingsStore.kt app/src/test/java/com/dsh/mobile/data/SettingsStoreMigrationTest.kt
git commit -m "feat(s1): SettingsStore 澶氫富鏈?profiles 瀛樺偍 + 鏃ч厤缃縼绉?
```

---

### Task 4: OkHttpClientFactory 鈥斺€?鎸変富鏈鸿瘉涔︿笌浠ｇ悊

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt`

**Interfaces:**
- Consumes: Task 1 鐨?`HostProfile` / `ProxyConfig`
- Produces:
```kotlin
// data/OkHttpClientFactory.kt 鈥斺€?Kotlin object = 杩涚▼绾у崟渚?object OkHttpClientFactory {
    /** 杩斿洖 (unary, stream)锛涙寜 profile.id 缂撳瓨锛岃皟鐢ㄦ柟鍏变韩杩炴帴姹?*/
    fun build(profile: HostProfile): Pair<OkHttpClient, OkHttpClient>
    /** 鍒囨崲涓绘満鏃跺湪鏃ц繛鎺?disconnect 瀹屾垚鍚庢樉寮忚皟鐢?*/
    fun release(profileId: String)
    // internal 绾嚱鏁帮紙渚涙祴璇曪級锛?    fun buildProxy(cfg: ProxyConfig?): java.net.Proxy?   // 椤跺眰鍑芥暟
    fun trustAllSslContext(): SSLContext                 // 椤跺眰鍑芥暟
    fun parseCaCertificate(bytes: ByteArray): X509Certificate? // 椤跺眰鍑芥暟
}
```

- [ ] **Step 1: 鍐欏け璐ユ祴璇?*

`app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt`锛?```kotlin
package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class OkHttpClientFactoryTest {

    @Test
    fun proxyMapping() {
        assertNull(buildProxy(null))
        assertNull(buildProxy(ProxyConfig(type = "none")))
        val http = buildProxy(ProxyConfig(type = "http", host = "10.0.0.1", port = 8080))
        assertEquals(Proxy.Type.HTTP, http!!.type())
        val socks = buildProxy(ProxyConfig(type = "socks5", host = "127.0.0.1", port = 1080))
        assertEquals(Proxy.Type.SOCKS, socks!!.type())
    }

    @Test
    fun trustAllSslContextBuilds() {
        val ctx = trustAllSslContext()
        assertNotNull(ctx)
        assertNotNull(ctx.socketFactory)
    }

    @Test
    fun parseCaCertificateHandlesGarbage() {
        assertNull(parseCaCertificate(byteArrayOf()))
        assertNull(parseCaCertificate("not a pem".toByteArray()))
        assertNull(parseCaCertificate(byteArrayOf(0, 1, 2, 3)))
    }
}
```

- [ ] **Step 2: 杩愯娴嬭瘯纭澶辫触**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"`
Expected: 缂栬瘧澶辫触銆?
- [ ] **Step 3: 瀹炵幇宸ュ巶**

`app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt`锛?```kotlin
package com.dsh.mobile.data

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Route
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun buildProxy(cfg: ProxyConfig?): Proxy? {
    if (cfg == null || cfg.type == "none" || cfg.host.isBlank() || cfg.port <= 0) return null
    val type = when (cfg.type) {
        "socks5" -> Proxy.Type.SOCKS
        else -> Proxy.Type.HTTP
    }
    return Proxy(type, InetSocketAddress(cfg.host, cfg.port))
}

fun trustAllSslContext(): SSLContext {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return ctx
}

/** PEM/DER 鈫?X509Certificate锛涙棤娉曡В鏋愯繑鍥?null */
fun parseCaCertificate(bytes: ByteArray): X509Certificate? = runCatching {
    val factory = CertificateFactory.getInstance("X.509")
    val cert = factory.generateCertificate(ByteArrayInputStream(bytes))
    cert as? X509Certificate
}.getOrNull()

object OkHttpClientFactory {

    private data class ClientPair(val unary: OkHttpClient, val stream: OkHttpClient)

    private val cache = HashMap<String, ClientPair>()

    @Synchronized
    fun build(profile: HostProfile): Pair<OkHttpClient, OkHttpClient> {
        cache[profile.id]?.let { return it.unary to it.stream }
        val pair = ClientPair(
            unary = newClient(profile, stream = false),
            stream = newClient(profile, stream = true),
        )
        cache[profile.id] = pair
        return pair.unary to pair.stream
    }

    @Synchronized
    fun release(profileId: String) {
        cache.remove(profileId)
    }

    private fun newClient(profile: HostProfile, stream: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (stream) {
            builder.connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
        } else {
            builder.connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
        }
        val ca = profile.caCertUri
        if (profile.trustSelfSigned) {
            val ctx = trustAllSslContext()
            builder.sslSocketFactory(ctx.socketFactory, trustAllX509())
            builder.hostnameVerifier { _, _ -> true }
        } else if (ca != null) {
            val ctx = mergedCaContext(ca)
            if (ctx != null) builder.sslSocketFactory(ctx.socketFactory, mergedX509())
        }
        buildProxy(profile.proxy)?.let { builder.proxy(it) }
        profile.proxy?.takeIf { it.username.isNotBlank() }?.let { p ->
            builder.proxyAuthenticator(proxyAuthenticator(p.username, p.password))
        }
        return builder.build()
    }

    private fun trustAllX509(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 绯荤粺閾?+ 瀵煎叆 CA 鍚堟垚锛汣A 鏂囦欢璇诲彇澶辫触杩斿洖 null锛堝洖閫€绯荤粺榛樿锛?*/
    private fun mergedCaContext(caUri: String): SSLContext? = runCatching {
        val bytes = java.io.File(caUri).readBytes()
        val ca = parseCaCertificate(bytes) ?: return null
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("imported-ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        ctx
    }.getOrNull()

    private fun mergedX509(): X509TrustManager? {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // 绯荤粺榛樿
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
    }

    private fun proxyAuthenticator(username: String, password: String) =
        Authenticator { _: Route, response ->
            if (response.request.header("Proxy-Authorization") != null) {
                null
            } else {
                response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(username, password))
                    .build()
            }
        }
}
```
璇存槑锛歚mergedCaContext` 鐢ㄣ€岀郴缁?tmf + 瀵煎叆 CA銆嶇殑鍚堟垚鏂规鍦?OkHttp 涓渶瑕?`CompositeTrustManager`锛堟妸绯荤粺 X509TrustManager 涓庡鍏?CA 鐨?X509TrustManager 鍚堝苟鏍￠獙锛夈€傚疄鐜颁负涓€涓唴閮ㄧ被锛?```kotlin
    private class CompositeTrustManager(
        private val primary: X509TrustManager,
        private val extra: X509TrustManager,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            primary.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = try {
            primary.checkServerTrusted(chain, authType)
        } catch (e: java.security.cert.CertificateException) {
            extra.checkServerTrusted(chain, authType)
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
```
骞跺湪 `mergedCaContext` 涓娇鐢?`CompositeTrustManager(systemTmf, importedTmf)` 鏋勯€狅紝`sslSocketFactory(ctx.socketFactory, composite)`銆備慨姝ｅ悗鐨?mergedCaContext锛?```kotlin
    private fun mergedCaContext(caUri: String): SSLContext? = runCatching {
        val bytes = java.io.File(caUri).readBytes()
        val ca = parseCaCertificate(bytes) ?: return null
        val imported = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("imported-ca", ca)
        }
        val importedTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(imported) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, null, null)
        // 杩斿洖 ctx 鍚屾椂鎸?CompositeTrustManager锛坆uild 澶勪娇鐢級
        lastComposite = CompositeTrustManager(systemTmf, importedTmf)
        ctx
    }.getOrNull()

    @Volatile private var lastComposite: X509TrustManager? = null
    private fun compositeX509(): X509TrustManager? = lastComposite
```
骞跺湪 `newClient` 鐨?CA 鍒嗘敮鏀圭敤 `builder.sslSocketFactory(ctx.socketFactory, compositeX509()!!)`銆傝嫢 CA 璇诲彇澶辫触锛坈tx 涓?null锛夊垯淇濇寔榛樿閾俱€?
- [ ] **Step 4: 杩愯娴嬭瘯纭閫氳繃**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"`
Expected: 3 涓祴璇?PASS銆?
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt
git commit -m "feat(s1): 鎸変富鏈?OkHttp 瀹㈡埛绔伐鍘傦紙鑷鍚嶄俊浠?CA 鍚堟垚/浠ｇ悊锛?
```

---

### Task 5: DshConnection 闆嗘垚 鈥斺€?connect(profile)銆侀敊璇垎绫汇€佸樊寮傚寲閲嶈繛銆佺綉缁滃垏鎹㈢洃鍚?
**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/data/DshConnection.kt`

**Interfaces:**
- Consumes: Task 1 `HostProfile`/`ConnectionErrorCode`锛汿ask 2 `VersionPolicy`/`RetryPolicy`/`ErrorClassifier`锛汿ask 4 `OkHttpClientFactory`
- Produces:
```kotlin
// DshConnection 鍐咃細
data class AttemptInfo(val profileId: String, val errorCode: ConnectionErrorCode?, val hostVersion: String?)

fun connect(profile: HostProfile, onAttempt: ((AttemptInfo) -> Unit)? = null)   // 鏇夸唬 connect(url: String)

sealed class State {
    data object Disconnected : State()
    data class Connecting(val baseUrl: String) : State()
    data class Connected(val baseUrl: String, val hostVersion: String? = null) : State()
    data class Error(val message: String, val code: ConnectionErrorCode?, val profileId: String?) : State()
}
```
- `connect(url: String)` 涓庢棫 `normalizeBaseUrl` 琛屼负淇濈暀锛堝唴閮ㄤ粛鐢?normalizeBaseUrl 瑙勮寖鍖?profile.url 閲嶇畻锛夈€?
- [ ] **Step 1: 淇敼 State 涓庡瓧娈?*

灏?`DshConnection` 涓細
```kotlin
    sealed class State {
        data object Disconnected : State()
        data class Connecting(val baseUrl: String) : State()
        data class Connected(val baseUrl: String) : State()
        data class Error(val message: String) : State()
    }
```
鏇挎崲涓轰笂闈?Produces 鐨勭増鏈紱鍒犻櫎 `companion object` 閲岀殑 `INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS`锛堥€昏緫绉诲叆 RetryPolicy锛夛紱`sharedUnaryClient`/`sharedStreamClient` 涓や釜 lazy 涓庡瓧娈?`unaryClient`/`streamClient` 鍒犻櫎锛堟敼鐢ㄥ伐鍘傦級銆傛柊澧烇細
```kotlin
    private var profileId: String? = null
    private var unaryClient: OkHttpClient = OkHttpClient()
    private var streamClient: OkHttpClient = OkHttpClient()
    private var onAttempt: ((AttemptInfo) -> Unit)? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
```

- [ ] **Step 2: 閲嶅啓 connect()**

```kotlin
    @Synchronized
    fun connect(profile: HostProfile, onAttempt: ((AttemptInfo) -> Unit)? = null) {
        val normalized = normalizeBaseUrl(profile.url)
        if (_state.value is State.Connected && baseUrl == normalized) return
        disconnectInternal()
        profileId = profile.id
        this.onAttempt = onAttempt
        baseUrl = normalized
        val (unary, stream) = OkHttpClientFactory.build(profile)
        unaryClient = unary
        streamClient = stream
        _state.value = State.Connecting(normalized)
        registerNetworkCallback()
        scope.launch {
            var attempt = 0
            while (isActive) {
                val result = try {
                    val value = call(DshEndpoints.HOST_DESCRIBE)
                    val version = runCatching {
                        value.jsonObject["version"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    ConnectionResult.Ok(version)
                } catch (e: Exception) {
                    val code = classifyConnectError(e)
                    ConnectionResult.Fail(e, code)
                }
                if (!isActive) break
                when (result) {
                    is ConnectionResult.Ok -> {
                        val verdict = VersionPolicy.evaluate(result.version)
                        if (verdict == VersionVerdict.MISMATCH) {
                            failPermanently(ConnectionErrorCode.VERSION_MISMATCH, "鐗堟湰涓嶅吋瀹癸紙杩滅 ${result.version}锛?)
                            break
                        }
                        onAttempt?.invoke(AttemptInfo(profile.id, null, result.version))
                        _state.value = State.Connected(normalized, result.version)
                        streamLoop("mux", "/api/events.mux")
                        streamLoop("host", "/api/events.host")
                        break
                    }
                    is ConnectionResult.Fail -> {
                        if (!RetryPolicy.isRecoverable(result.code)) {
                            failPermanently(result.code, result.e.message ?: result.code.name)
                            break
                        }
                        val backoff = RetryPolicy.nextBackoff(result.code, attempt) ?: break
                        onAttempt?.invoke(AttemptInfo(profile.id, result.code, null))
                        val msg = "杩炴帴澶辫触锛?{result.code.name}锛夛紝${backoff / 1000} 绉掑悗鑷姩閲嶈繛"
                        _events.tryEmit(Event.StreamError(msg))
                        _state.value = State.Error(msg, result.code, profile.id)
                        delay(backoff)
                        if (!isActive) break
                        _state.value = State.Connecting(normalized)
                        attempt++
                    }
                }
            }
        }
    }
```
閰嶅鏂板锛?```kotlin
    private sealed class ConnectionResult {
        data class Ok(val version: String?) : ConnectionResult()
        data class Fail(val e: Exception, val code: ConnectionErrorCode) : ConnectionResult()
    }

    private fun classifyConnectError(e: Exception): ConnectionErrorCode {
        val code = when (e) {
            is ApiException -> e.code?.toIntOrNull()
                ?.let { ErrorClassifier.fromHttpStatus(it) }
                ?: ConnectionErrorCode.PROTOCOL_ERROR
            else -> ErrorClassifier.fromException(e, connectPhase = true, hasProxy = currentProfileHasProxy())
        }
        return code
    }

    private fun currentProfileHasProxy(): Boolean = OkHttpClientFactory.build(
        // profile 寮曠敤缂撳瓨锛涜 Step 4 鐨?currentProfile 瀛楁
        currentProfile ?: return false
    ).let { false }.let { _ -> currentProfile?.proxy != null }

    private fun failPermanently(code: ConnectionErrorCode, detail: String) {
        onAttempt?.invoke(AttemptInfo(profileId ?: "", code, null))
        _events.tryEmit(Event.StreamError("$detail锛堝凡鍋滄鑷姩閲嶈繛锛?))
        _state.value = State.Error("$detail锛堝凡鍋滄鑷姩閲嶈繛锛?, code, profileId)
    }
```
锛坄currentProfileHasProxy` 鍐欐硶鍐楅暱锛岀畝鍖栦负锛氭柊澧炲瓧娈?`private var currentProfile: HostProfile? = null`锛宑onnect 鏃惰祴鍊硷紝disconnectInternal 鏃舵竻绌猴紱`currentProfileHasProxy() = currentProfile?.proxy != null`銆傦級

- [ ] **Step 3: 缃戠粶鍒囨崲鐩戝惉**

鍦?`DshConnection` 澧炲姞锛堥渶 import `android.net.ConnectivityManager`銆乣android.net.Network`锛夛細
```kotlin
    private fun registerNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 鏂扮綉缁滃彲鐢細鑻ュ浜庨噸杩炵瓑寰呬腑锛岀珛鍗抽噸缃€€閬块噸璇?                scope.launch { retryNow() }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    @Volatile private var retryNowPending = false
    private suspend fun retryNow() {
        if (retryNowPending) return
        retryNowPending = true
        // 鍙栨秷褰撳墠杩炴帴鍗忕▼鐨勫瓙浠诲姟锛堟帰娴?寤惰繜锛夛紝鐢?connect 寰幆閲嶅缓
        scope.coroutineContext.cancelChildren()
        delay(300)
        retryNowPending = false
        currentProfile?.let { p ->
            if (_state.value is State.Error) connect(p, onAttempt)
        }
    }
```
`appContext` 鐢辨瀯閫犳敞鍏ワ細`class DshConnection(private val appContext: Context? = null)`銆俙DshApplication.connection` 鍒涘缓澶勬敼涓?`DshConnection(this)`锛沗DshConnectionService` 閲?`DshConnection(this)`銆?
`disconnectInternal()` 杩藉姞锛?```kotlin
        unregisterNetworkCallback()
        currentProfile = null
        profileId?.let { OkHttpClientFactory.release(it) }
```

- [ ] **Step 4: 缂栬瘧楠岃瘉锛堟棤涓撻棬鍗曟祴锛岄€昏緫宸插湪 Task 2 瑕嗙洊锛?*

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL锛圕onnectScreen/Service 浠嶇敤鏃?`connect(url)` 鐨勮皟鐢ㄧ偣浼氭姤閿欌€斺€旇涓嬶級銆?
- [ ] **Step 5: 淇紪璇戣皟鐢ㄧ偣锛堟渶灏忚繃娓★級**

`ConnectScreen.kt` 鏃?`connection.connect(u)` 涓?`connection.connect(config.serverUrl)` 涓ゅ銆乣DshConnectionService.kt` 鐨?`connection.connect(config.serverUrl)` 涓€澶勶細涓存椂鏀逛负
```kotlin
connection.connect(
    HostProfile(id = "legacy-0", remark = "鏃ц繛鎺?, url = u, autoConnect = true),
)
```
锛圱ask 7/8 浼氭暣浣撴浛鎹紝姝ゅ鍙繚璇佺紪璇戙€傦級

- [ ] **Step 6: 缂栬瘧閫氳繃 + Commit**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL銆?
```bash
git add app/src/main/java/com/dsh/mobile/data/DshConnection.kt app/src/main/java/com/dsh/mobile/DshApplication.kt app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt
git commit -m "feat(s1): DshConnection 鎸変富鏈鸿繛鎺?閿欒鍒嗙被/宸紓鍖栭噸杩?缃戠粶鍒囨崲鑷剤"
```

---

### Task 6: DshConnectionService 閫傞厤 activeProfileId

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt`

**Interfaces:**
- Consumes: Task 3 `SettingsStore.profiles/activeProfileId`銆乀ask 5 `DshConnection.connect(profile)`
- Produces: 鏈嶅姟闅忔椿璺冧富鏈哄垏鎹㈤噸鍚?watcher锛涘幓閲嶉泦鍚堥殢鍒囨崲娓呯┖銆?
- [ ] **Step 1: 閲嶅啓 startWatching**

```kotlin
    private fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            val settings = SettingsStore(this@DshConnectionService)
            if (!settings.backgroundNotify.first()) {
                stopSelf()
                return@launch
            }
            // 璺熼殢娲昏穬涓绘満锛氬垏鎹㈠嵆閲嶅惎 watcher锛堝崟娲昏穬璇箟锛?            settings.activeProfileId
                .distinctUntilChanged()
                .collect { activeId ->
                    watcher?.disconnect()
                    watcher = null
                    seenApprovals.clear()
                    seenQuestions.clear()
                    sessionActive.clear()
                    completionJobs.values.forEach { it.cancel() }
                    completionJobs.clear()

                    val profile = if (activeId == null) null else
                        settings.profiles.first().firstOrNull { it.id == activeId }
                    if (profile == null || profile.url.isBlank()) {
                        updateForegroundText("鏈€夋嫨娲昏穬涓绘満")
                        return@collect
                    }
                    val connection = DshConnection(this@DshConnectionService)
                    watcher = connection
                    launch { connection.events.collect { handle(it) } }
                    launch {
                        connection.state.collect { st ->
                            val text = when (st) {
                                is DshConnection.State.Connected -> "宸茶繛鎺?" + st.baseUrl
                                is DshConnection.State.Connecting -> "杩炴帴涓€?
                                is DshConnection.State.Error ->
                                    if (st.code != null) "杩炴帴澶辫触锛?{st.code.name}锛夛紝鑷姩閲嶈繛涓? else st.message
                                else -> "鍚庡彴杩炴帴宸插紑鍚?
                            }
                            updateForegroundText(text)
                        }
                    }
                    connection.connect(profile) { info ->
                        if (info.errorCode != null) {
                            scope.launch {
                                settings.markAttempt(info.profileId, info.errorCode, info.hostVersion)
                            }
                        }
                    }
                }
        }
    }
```
import 琛ュ厖锛歚kotlinx.coroutines.flow.distinctUntilChanged`銆乣com.dsh.mobile.data.HostProfile`锛堝鏈紩鍏ワ級銆?
- [ ] **Step 2: 缂栬瘧楠岃瘉**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL銆?
- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt
git commit -m "feat(s1): 鍚庡彴鏈嶅姟璺熼殢娲昏穬涓绘満鍒囨崲 watcher"
```

---

### Task 7: ConnectScreen 閲嶆瀯 鈥斺€?涓绘満鍒楄〃 / 涓€閿垏鎹?/ 閿欒妯箙

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt`
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt`锛堟暣浣撻噸鍐欙級

**Interfaces:**
- Consumes: Task 3 `SettingsStore`銆乀ask 5 `DshConnection.connect(profile)/State.Error(code)`銆乀ask 1 `HostProfile`
- Produces: `ErrorMessages.reason(code)/advice(code)`锛圱ask 8 澶嶇敤锛夛紱`ConnectScreen(connection, onEditHost: (String?) -> Unit)`锛圱ask 9 鎺ョ嚎锛夈€?
- [ ] **Step 1: ErrorMessages锛坰pec 搂6 鏂囨琛級**

`app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt`锛?```kotlin
package com.dsh.mobile.data

object ErrorMessages {
    fun reason(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "鍩熷悕鏃犳硶瑙ｆ瀽"
        ConnectionErrorCode.PORT_UNREACHABLE -> "绔彛涓嶅彲杈撅紙杩炴帴琚嫆缁?瓒呮椂锛?
        ConnectionErrorCode.TLS_CERT_FAILED -> "HTTPS 璇佷功鏍￠獙澶辫触"
        ConnectionErrorCode.AUTH_FAILED -> "鍓嶇疆缃戝叧閴存潈澶辫触锛?01/403锛?
        ConnectionErrorCode.VERSION_MISMATCH -> "绉诲姩绔笌 PC 绔増鏈笉鍏煎"
        ConnectionErrorCode.PROXY_FAILED -> "浠ｇ悊涓嶅彲杈?
        ConnectionErrorCode.PROTOCOL_ERROR -> "鏈嶅姟鍝嶅簲寮傚父"
        ConnectionErrorCode.UNKNOWN -> "鏈煡閿欒"
    }

    fun advice(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "妫€鏌ュ湴鍧€鎷煎啓锛涘眬鍩熺綉鍦烘櫙鏀圭敤 IP"
        ConnectionErrorCode.PORT_UNREACHABLE -> "纭 PC 绔?DSH 宸插惎鍔ㄣ€佺鍙ｆ纭€侀槻鐏鏀捐"
        ConnectionErrorCode.TLS_CERT_FAILED -> "鑻ヤ负鑷鍚嶈瘉涔︼紝鍦ㄦ湰涓绘満閰嶇疆閲屽紑鍚€屼俊浠昏嚜绛惧悕銆嶆垨瀵煎叆鍏?CA"
        ConnectionErrorCode.AUTH_FAILED -> "DSH 鏈満鐩磋繛鏃犻壌鏉冿紱妫€鏌ヨ嚜寤哄弽浠?缃戝叧鐨勯壌鏉冮厤缃垨鍑瘉"
        ConnectionErrorCode.VERSION_MISMATCH -> "鍗囩骇 DSH 鎴栨湰 App"
        ConnectionErrorCode.PROXY_FAILED -> "妫€鏌ヤ唬鐞嗗湴鍧€/绔彛/璐﹀彿锛屾垨鍏抽棴璇ヤ富鏈虹殑浠ｇ悊"
        ConnectionErrorCode.PROTOCOL_ERROR -> "纭鍦板潃鎸囧悜 DSH web 鏈嶅姟锛涘鍑烘棩蹇楁帓鏌?
        ConnectionErrorCode.UNKNOWN -> "瀵煎嚭鏃ュ織鎺掓煡"
    }
}
```

- [ ] **Step 2: 閲嶅啓 ConnectScreen**

瀹屾暣鏇挎崲 `ConnectScreen.kt`锛堜繚鐣欏寘鍚嶄笌鐜版湁 import 闇€姹傦紱鏂板 import锛歚com.dsh.mobile.data.HostProfile`銆乣com.dsh.mobile.data.ErrorMessages`銆乣androidx.compose.material3.DropdownMenu` 绛夛級锛?
```kotlin
package com.dsh.mobile.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.R
import androidx.core.content.ContextCompat
import com.dsh.mobile.data.*
import com.dsh.mobile.service.DshConnectionService
import com.dsh.mobile.ui.theme.DshBrand
import com.dsh.mobile.ui.theme.DshSuccess
import com.dsh.mobile.ui.theme.DshShape
import com.dsh.mobile.ui.theme.brandGradient
import com.journeyapps.barcodescanner.CaptureActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(
    connection: DshConnection,
    onEditHost: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    val profiles by settingsStore.profiles.collectAsState(initial = emptyList())
    val activeId by settingsStore.activeProfileId.collectAsState(initial = null)
    val connState by connection.state.collectAsState()

    val sortedProfiles = profiles.sortedByDescending { it.lastUsedAt }
    val activeProfile = profiles.firstOrNull { it.id == activeId }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun onConnectedActions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DshConnectionService::class.java))
        }
    }

    fun connectTo(profile: HostProfile) {
        scope.launch {
            settingsStore.setActiveProfile(profile.id)
            settingsStore.upsertProfile(profile.copy(autoConnect = true))
        }
        connection.connect(profile) { info ->
            scope.launch { settingsStore.markAttempt(info.profileId, info.errorCode, info.hostVersion) }
        }
        onConnectedActions()
    }

    // 鈥斺€?鎵爜锛氳В鏋愮粨鏋?鈫?鏂板缓鎴栨洿鏂伴厤缃苟杩炴帴 鈥斺€?    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCAN_RESULT")?.trim().orEmpty()
            if (scanned.isNotEmpty()) {
                val existing = profiles.firstOrNull { it.url == scanned }
                val profile = existing?.copy(remark = existing.remark.ifBlank { scanned })
                    ?: HostProfile(
                        id = java.util.UUID.randomUUID().toString(),
                        remark = scanned,
                        url = scanned,
                    )
                scope.launch { settingsStore.upsertProfile(profile) }
                connectTo(profile)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scannerLauncher.launch(Intent(context, CaptureActivity::class.java))
        } else {
            Toast.makeText(context, "闇€瑕佺浉鏈烘潈闄愭墠鑳芥壂鐮佽繛鎺?, Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    // 鈥斺€?鑷姩杩炴帴 鈥斺€?    LaunchedEffect(Unit) {
        val auto = settingsStore.profiles.first().firstOrNull { it.autoConnect }
        if (auto != null) {
            scope.launch { settingsStore.setActiveProfile(auto.id) }
            connectTo(auto)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 閿欒妯箙锛坰pec 搂6锛氶敊璇爜 鈫?鍘熷洜 鈫?寤鸿锛涗笉鍙仮澶嶉敊璇爣娉ㄥ凡鍋滄閲嶈繛锛?        val st = connState
        if (st is DshConnection.State.Error && st.code != null) {
            val code = st.code
            val stopped = code == ConnectionErrorCode.AUTH_FAILED ||
                code == ConnectionErrorCode.VERSION_MISMATCH
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        ErrorMessages.reason(code) + if (stopped) "锛堝凡鍋滄鑷姩閲嶈繛锛? else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ErrorMessages.advice(code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("DSH Remote", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "閬ユ帶浣犵數鑴戜笂鐨?DeepSeek Harness 鏅鸿兘浣?,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { startScan() }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "鎵爜杩炴帴", tint = DshBrand)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 娲昏穬涓绘満鍗＄墖
            activeProfile?.let { p ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (connState) {
                                    is DshConnection.State.Connected -> DshSuccess
                                    is DshConnection.State.Error -> MaterialTheme.colorScheme.error
                                    is DshConnection.State.Connecting -> DshBrand
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Box(
                                    Modifier.size(10.dp)
                                        .background(dotColor, DshShape.pill),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    p.remark.ifBlank { p.url },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { onEditHost(p.id) }) { Text("缂栬緫") }
                            }
                            Text(
                                p.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                            if (connState is DshConnection.State.Connected) {
                                val connected = connState as DshConnection.State.Connected
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "宸茶繛鎺? + (connected.hostVersion?.let { " 路 涓绘満鐗堟湰 $it" } ?: " 路 鐗堟湰鏈煡"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DshSuccess,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "宸蹭繚瀛樼殑涓绘満",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (sortedProfiles.isEmpty()) {
                item {
                    Text(
                        "杩樻病鏈変富鏈洪厤缃細鎵爜鎴栫偣涓嬫柟銆屾坊鍔犱富鏈恒€?,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(sortedProfiles, key = { it.id }) { p ->
                    val isActive = p.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = DshShape.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        onClick = { if (!isActive) connectTo(p) },
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.remark.ifBlank { p.url }, style = MaterialTheme.typography.titleSmall)
                                    if (isActive) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "浣跨敤涓?,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DshBrand,
                                        )
                                    }
                                }
                                Text(
                                    p.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                p.lastErrorCode?.let { code ->
                                    runCatching { ConnectionErrorCode.valueOf(code) }.getOrNull()?.let { c ->
                                        Text(
                                            "涓婃閿欒锛?{ErrorMessages.reason(c)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onEditHost(null) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = DshShape.pill,
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("娣诲姞涓绘満")
                }
                Spacer(Modifier.height(20.dp))
                val versionName = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                Text(
                    "DSH Remote v$versionName 路 闈炲畼鏂瑰鎴风 路 鏁版嵁鍙瓨浣犵殑鎵嬫満涓庝綘鐨勬湇鍔″櫒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
```
娉ㄦ剰锛歚connectTo` 鍦ㄦ瘡娆″垏鎹㈠墠涓嶅啀鎵嬪姩 disconnect锛坄DshConnection.connect` 鍐呴儴宸?disconnectInternal锛夛紱鍒囨崲璇箟鐢?activeProfileId 椹卞姩鏈嶅姟绔?watcher锛圱ask 6锛夈€?
- [ ] **Step 3: 缂栬瘧楠岃瘉**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL锛坄AppNavigation` 涓?`ConnectScreen(connection = connection)` 鍥犳柊鍙傛暟鏈夐粯璁ゅ€兼棤闇€鏀瑰姩锛夈€?
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt
git commit -m "feat(s1): ConnectScreen 閲嶆瀯鈥斺€斾富鏈哄垪琛?涓€閿垏鎹?閿欒妯箙"
```

---

### Task 8: HostProfileScreen 鈥斺€?閰嶇疆缂栬緫 / 娴嬭瘯杩炴帴 / CA 瀵煎叆 / 浠ｇ悊

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/Diagnostics.kt`
- Create: `app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt`

**Interfaces:**
- Consumes: Task 1 `HostProfile/ProfileCodec`銆乀ask 3 `SettingsStore`銆乀ask 4 `OkHttpClientFactory`銆乀ask 7 `ErrorMessages`
- Produces:
```kotlin
// data/Diagnostics.kt
data class DiagStep(val name: String, val ok: Boolean, val detail: String?, val elapsedMs: Long)
suspend fun runDiagnostics(profile: HostProfile): List<DiagStep>

// ui/screens/HostProfileScreen.kt
@Composable
fun HostProfileScreen(
    profileId: String?,          // null = 鏂板缓
    onBack: () -> Unit,
)
```

- [ ] **Step 1: 瀹炵幇 Diagnostics**

`app/src/main/java/com/dsh/mobile/data/Diagnostics.kt`锛?```kotlin
package com.dsh.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

data class DiagStep(val name: String, val ok: Boolean, val detail: String?, val elapsedMs: Long)

suspend fun runDiagnostics(profile: HostProfile): List<DiagStep> = withContext(Dispatchers.IO) {
    val steps = mutableListOf<DiagStep>()
    val uri = runCatching { URI(profile.url) }.getOrNull()
    val host = uri?.host ?: ""
    val port = uri?.port ?: -1
    val https = profile.url.startsWith("https://")

    // 1. DNS
    steps += timed("DNS 瑙ｆ瀽") {
        val addrs = java.net.InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw IllegalStateException("鏃犺В鏋愮粨鏋?)
        addrs.joinToString(", ") { it.hostAddress }
    }

    // 2. TCP
    steps += timed("TCP 杩炴帴") {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
        }
        "宸茶繛鎺?$host:$port"
    }

    // 3. TLS锛堜粎 https锛?    if (https) {
        steps += timed("TLS 鎻℃墜") {
            val (unary, _) = OkHttpClientFactory.build(profile)
            val sf = unary.sslSocketFactory
            (sf as SSLSocketFactory).createSocket(host, port).use { s ->
                s.startHandshake()
            }
            "璇佷功鏍￠獙閫氳繃"
        }
    }

    // 4. host.describe 鐗堟湰鎺㈡祴
    steps += timed("鐗堟湰鎺㈡祴") {
        val (unary, _) = OkHttpClientFactory.build(profile)
        val rpcId = "diag-" + java.util.UUID.randomUUID()
        val body = """{"type":"client-request","rpcId":"$rpcId","method":"host.describe","payload":{}}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(profile.url + "/api/host.describe").post(body).build()
        unary.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            version?.let { "涓绘満鐗堟湰 $it" } ?: "鐗堟湰瀛楁缂哄け锛堣涓烘湭鐭ワ級"
        }
    }

    OkHttpClientFactory.release(profile.id)
    steps
}

private inline fun timed(name: String, block: () -> String): DiagStep {
    val t0 = System.currentTimeMillis()
    return try {
        val detail = block()
        DiagStep(name, true, detail, System.currentTimeMillis() - t0)
    } catch (e: Exception) {
        DiagStep(name, false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
    }
}
```

- [ ] **Step 2: 瀹炵幇 HostProfileScreen**

`app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt`锛?```kotlin
package com.dsh.mobile.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.theme.DshShape
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun HostProfileScreen(profileId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var original by remember {
        mutableStateOf<HostProfile?>(
            profileId?.let { id ->
                runBlocking { settingsStore.profiles.first().firstOrNull { it.id == id } }
            } ?: HostProfile(id = UUID.randomUUID().toString(), remark = "", url = "")
        )
    }
    var remark by remember { mutableStateOf(original?.remark ?: "") }
    var url by remember { mutableStateOf(original?.url ?: "") }
    var trustSelfSigned by remember { mutableStateOf(original?.trustSelfSigned ?: false) }
    var caCertUri by remember { mutableStateOf(original?.caCertUri) }
    var proxyType by remember { mutableStateOf(original?.proxy?.type ?: "none") }
    var proxyHost by remember { mutableStateOf(original?.proxy?.host ?: "") }
    var proxyPort by remember { mutableStateOf(original?.proxy?.port?.takeIf { it > 0 }?.toString() ?: "") }
    var proxyUser by remember { mutableStateOf(original?.proxy?.username ?: "") }
    var proxyPass by remember { mutableStateOf(original?.proxy?.password ?: "") }
    var autoConnect by remember { mutableStateOf(original?.autoConnect ?: false) }
    var diag by remember { mutableStateOf<List<DiagStep>?>(null) }
    var diagRunning by remember { mutableStateOf(false) }

    val caPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val dir = File(context.filesDir, "certs").apply { mkdirs() }
                    val target = File(dir, (original?.id ?: "new") + ".pem")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    caCertUri = target.absolutePath
                }.onFailure {
                    Toast.makeText(context, "CA 璇佷功瀵煎叆澶辫触锛?{it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun save() {
        val profile = (original ?: HostProfile(id = UUID.randomUUID().toString(), remark = "", url = ""))
            .copy(
                remark = remark.ifBlank { url },
                url = url.trim().trimEnd('/'),
                trustSelfSigned = trustSelfSigned,
                caCertUri = caCertUri,
                proxy = if (proxyType == "none") null else ProxyConfig(
                    type = proxyType, host = proxyHost,
                    port = proxyPort.toIntOrNull() ?: 0,
                    username = proxyUser, password = proxyPass,
                ),
                autoConnect = autoConnect,
            )
        if (profile.url.isBlank()) {
            Toast.makeText(context, "鍦板潃涓嶈兘涓虹┖", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            settingsStore.upsertProfile(profile)
            onBack()
        }
    }

    fun delete() {
        original?.let { p ->
            scope.launch {
                settingsStore.deleteProfile(p.id)
                onBack()
            }
        }
    }

    fun runDiag() {
        val profile = (original ?: return).copy(
            remark = remark, url = url.trim().trimEnd('/'),
            trustSelfSigned = trustSelfSigned, caCertUri = caCertUri,
            proxy = if (proxyType == "none") null else ProxyConfig(
                type = proxyType, host = proxyHost, port = proxyPort.toIntOrNull() ?: 0,
                username = proxyUser, password = proxyPass,
            ),
        )
        diagRunning = true
        scope.launch {
            diag = runDiagnostics(profile)
            diagRunning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "娣诲姞涓绘満" else "缂栬緫涓绘満") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "杩斿洖") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = remark, onValueChange = { remark = it },
                label = { Text("澶囨敞鍚?) },
                placeholder = { Text("濡傦細瀹堕噷 / 鍏徃 / 鏈嶅姟鍣?) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("鏈嶅姟鍣ㄥ湴鍧€") },
                placeholder = { Text("192.168.1.100:8787 鎴栦綘鐨?cpolar 鍩熷悕") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HTTPS 璇佷功", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("淇′换鑷鍚嶈瘉涔?, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "鈿?浠呭鏈富鏈虹敓鏁堬紝璺宠繃璇佷功鏍￠獙",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = trustSelfSigned, onCheckedChange = { trustSelfSigned = it })
                    }
                    OutlinedButton(onClick = { caPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream")) }) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("瀵煎叆 CA 璇佷功")
                    }
                    caCertUri?.let {
                        Text(
                            "宸插鍏ワ細$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("浠ｇ悊锛堟寜涓绘満锛?, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("none" to "鏃?, "http" to "HTTP", "socks5" to "SOCKS5").forEach { (v, label) ->
                            FilterChip(
                                selected = proxyType == v,
                                onClick = { proxyType = v },
                                label = { Text(label) },
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (proxyType != "none") {
                        OutlinedTextField(
                            value = proxyHost, onValueChange = { proxyHost = it },
                            label = { Text("浠ｇ悊涓绘満") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPort, onValueChange = { proxyPort = it },
                            label = { Text("绔彛") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyUser, onValueChange = { proxyUser = it },
                            label = { Text("璐﹀彿锛堝彲閫夛級") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPass, onValueChange = { proxyPass = it },
                            label = { Text("瀵嗙爜锛堝彲閫夛級") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("鍚姩鏃惰嚜鍔ㄨ繛鎺?, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
            }

            Button(
                onClick = { runDiag() },
                enabled = !diagRunning && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = DshShape.pill,
            ) {
                if (diagRunning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("璇婃柇涓€?)
                } else {
                    Text("娴嬭瘯杩炴帴")
                }
            }

            diag?.let { steps ->
                Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("璇婃柇缁撴灉", style = MaterialTheme.typography.titleSmall)
                        steps.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (s.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = if (s.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${s.name}锛?{s.elapsedMs}ms锛?, style = MaterialTheme.typography.bodySmall)
                                    s.detail?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = DshShape.pill,
            ) { Text("淇濆瓨") }

            if (original != null) {
                OutlinedButton(
                    onClick = { delete() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = DshShape.pill,
                ) { Text("鍒犻櫎璇ヤ富鏈?, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
```
import 琛ワ細`kotlinx.coroutines.runBlocking`銆?
- [ ] **Step 3: 缂栬瘧楠岃瘉**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL銆?
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/Diagnostics.kt app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt
git commit -m "feat(s1): 涓绘満缂栬緫椤碉紙CA 瀵煎叆/浠ｇ悊/娴嬭瘯杩炴帴璇婃柇锛?
```

---

### Task 9: 瀵艰埅鎺ョ嚎 + 鏀跺熬娓呯悊 + 鍏ㄩ噺鏋勫缓

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`锛堢Щ闄?`ConnectionConfig` 涓庢棫 flow锛?
**Interfaces:**
- Consumes: Task 7 `ConnectScreen(connection, onEditHost)`銆乀ask 8 `HostProfileScreen(profileId, onBack)`

- [ ] **Step 1: 瀵艰埅鎺ョ嚎**

`AppNavigation.kt` 涓?`Screen` 澧炲姞锛?```kotlin
    data object HostProfile : Screen("hostProfile/{profileId}") {
        fun createRoute(profileId: String?) = "hostProfile/${profileId ?: "new"}"
    }
```
NavHost 澧炲姞锛坄ConnectScreen` 璋冪敤澶勫甫鍥炶皟锛夛細
```kotlin
        composable(Screen.Connect.route) {
            ConnectScreen(
                connection = connection,
                onEditHost = { id -> navController.navigate(Screen.HostProfile.createRoute(id)) },
            )
        }
        composable(
            route = Screen.HostProfile.route,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) {
            HostProfileScreen(
                profileId = it.arguments?.getString("profileId")?.takeIf { v -> v != "new" },
                onBack = { navController.popBackStack() },
            )
        }
```

- [ ] **Step 2: 绉婚櫎鏃?ConnectionConfig**

`SettingsStore.kt` 鍒犻櫎锛?```kotlin
data class ConnectionConfig(
    /** 鏈嶅姟鍣ㄥ熀纭€鍦板潃锛屽 http://192.168.1.100:8787 鎴?cpolar 鍩熷悕 */
    val serverUrl: String = "",
    val autoConnect: Boolean = true,
)
```
涓?```kotlin
    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            serverUrl = prefs[URL_KEY] ?: "",
            autoConnect = prefs[AUTO_KEY] ?: true,
        )
    }
```
涓?```kotlin
    suspend fun saveConnection(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[URL_KEY] = config.serverUrl
            prefs[AUTO_KEY] = config.autoConnect
        }
    }
```
浠ュ強 companion 涓殑 `URL_KEY` / `AUTO_KEY` 瀹氫箟锛堣縼绉诲嚱鏁板唴浠ュ瓧闈㈤噺 key 鑷缓锛屼笉渚濊禆瀹冧滑锛夈€?
- [ ] **Step 3: 鍏ㄩ噺缂栬瘧 + 鍗曞厓娴嬭瘯**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 鍏ㄩ儴鍗曟祴 PASS锛圚ostProfileTest / ConnectionPolicyTest / SettingsStoreMigrationTest / OkHttpClientFactoryTest锛夈€?
Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL锛屼骇鐗?`app/build/outputs/apk/debug/app-debug.apk`銆?
- [ ] **Step 4: 鐪熸満楠屾敹锛坰pec 搂8 娓呭崟锛?*

瀹夎 app-debug.apk 鍚庨€愰」鎵ц锛?1. 鏃х増鍗囩骇杩佺Щ锛氳嫢涔嬪墠杩炶繃鍦板潃 鈫?鎵撳紑鍗宠銆屾棫杩炴帴銆嶉厤缃湪鍒楄〃棣栦綅涓旇嚜鍔ㄨ繛鎺ャ€?2. 澶氫富鏈猴細娣诲姞 鈮? 鏉￠厤缃紙鍚娉級锛屽垏鎹㈠嵆鏃剁敓鏁堬紝甯搁┗閫氱煡璺熼殢褰撳墠涓绘満銆?3. 閿欒鍦烘櫙锛氶敊鍩熷悕锛圖NS 妯箙锛夈€侀敊绔彛锛圥ORT 妯箙锛夈€佽嚜绛?HTTPS锛圱LS_CERT_FAILED 妯箙 鈫?缂栬緫椤靛紑銆屼俊浠昏嚜绛惧悕銆嶁啋 閲嶈繛鎴愬姛锛夈€?4. 缃戠粶鍒囨崲锛歐iFi鈫旇渹绐濆垏鎹㈠悗 鈮?s 闈欓粯鎭㈠銆?5. 浠ｇ悊锛欻TTP/SOCKS5 鍚勯獙璇佷竴娆★紙鍚处鍙峰瘑鐮侊級锛涢敊璇唬鐞?鈫?PROXY_FAILED銆?6. 娴嬭瘯杩炴帴锛氱紪杈戦〉閫愰」 鉁?鉁?灞曠ず銆?7. 鍥炲綊锛氬鎵?闂瓟閫氱煡銆佷换鍔″畬鎴愰€氱煡銆佷細璇濇敹鍙戝叏娴佺▼姝ｅ父銆?
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt app/src/main/java/com/dsh/mobile/data/SettingsStore.kt
git commit -m "feat(s1): 瀵艰埅鎺ュ叆涓绘満缂栬緫椤碉紝绉婚櫎鏃?ConnectionConfig"
```

---
