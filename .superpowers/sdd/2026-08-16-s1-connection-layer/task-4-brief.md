### Task 4: OkHttpClientFactory —— 按主机证书与代理

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `HostProfile` / `ProxyConfig`
- Produces:
```kotlin
// data/OkHttpClientFactory.kt —— Kotlin object = 进程级单例
object OkHttpClientFactory {
    /** 返回 (unary, stream)；按 profile.id 缓存，调用方共享连接池 */
    fun build(profile: HostProfile): Pair<OkHttpClient, OkHttpClient>
    /** 切换主机时在旧连接 disconnect 完成后显式调用 */
    fun release(profileId: String)
    // internal 纯函数（供测试）：
    fun buildProxy(cfg: ProxyConfig?): java.net.Proxy?   // 顶层函数
    fun trustAllSslContext(): SSLContext                 // 顶层函数
    fun parseCaCertificate(bytes: ByteArray): X509Certificate? // 顶层函数
}
```

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt`：
```kotlin
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

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"`
Expected: 编译失败。

- [ ] **Step 3: 实现工厂**

`app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt`：
```kotlin
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

/** PEM/DER → X509Certificate；无法解析返回 null */
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

    /** 系统链 + 导入 CA 合成；CA 文件读取失败返回 null（回退系统默认） */
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
        tmf.init(null as KeyStore?) // 系统默认
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
说明：`mergedCaContext` 用「系统 tmf + 导入 CA」的合成方案在 OkHttp 中需要 `CompositeTrustManager`（把系统 X509TrustManager 与导入 CA 的 X509TrustManager 合并校验）。实现为一个内部类：
```kotlin
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
并在 `mergedCaContext` 中使用 `CompositeTrustManager(systemTmf, importedTmf)` 构造，`sslSocketFactory(ctx.socketFactory, composite)`。修正后的 mergedCaContext：
```kotlin
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
        // 返回 ctx 同时挂 CompositeTrustManager（build 处使用）
        lastComposite = CompositeTrustManager(systemTmf, importedTmf)
        ctx
    }.getOrNull()

    @Volatile private var lastComposite: X509TrustManager? = null
    private fun compositeX509(): X509TrustManager? = lastComposite
```
并在 `newClient` 的 CA 分支改用 `builder.sslSocketFactory(ctx.socketFactory, compositeX509()!!)`。若 CA 读取失败（ctx 为 null）则保持默认链。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"`
Expected: 3 个测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt
git commit -m "feat(s1): 按主机 OkHttp 客户端工厂（自签名信任/CA 合成/代理）"
```

---

### Task 5: DshConnection 集成 —— connect(profile)、错误分类、差异化重连、网络切换监听

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/data/DshConnection.kt`

**Interfaces:**
- Consumes: Task 1 `HostProfile`/`ConnectionErrorCode`；Task 2 `VersionPolicy`/`RetryPolicy`/`ErrorClassifier`；Task 4 `OkHttpClientFactory`
- Produces:
```kotlin
// DshConnection 内：
data class AttemptInfo(val profileId: String, val errorCode: ConnectionErrorCode?, val hostVersion: String?)

fun connect(profile: HostProfile, onAttempt: ((AttemptInfo) -> Unit)? = null)   // 替代 connect(url: String)

sealed class State {
    data object Disconnected : State()
    data class Connecting(val baseUrl: String) : State()
    data class Connected(val baseUrl: String, val hostVersion: String? = null) : State()
    data class Error(val message: String, val code: ConnectionErrorCode?, val profileId: String?) : State()
}
```
- `connect(url: String)` 与旧 `normalizeBaseUrl` 行为保留（内部仍用 normalizeBaseUrl 规范化 profile.url 重算）。

- [ ] **Step 1: 修改 State 与字段**

将 `DshConnection` 中：
```kotlin
    sealed class State {
        data object Disconnected : State()
        data class Connecting(val baseUrl: String) : State()
        data class Connected(val baseUrl: String) : State()
        data class Error(val message: String) : State()
    }
```
替换为上面 Produces 的版本；删除 `companion object` 里的 `INITIAL_BACKOFF_MS`/`MAX_BACKOFF_MS`（逻辑移入 RetryPolicy）；`sharedUnaryClient`/`sharedStreamClient` 两个 lazy 与字段 `unaryClient`/`streamClient` 删除（改用工厂）。新增：
```kotlin
    private var profileId: String? = null
    private var unaryClient: OkHttpClient = OkHttpClient()
    private var streamClient: OkHttpClient = OkHttpClient()
    private var onAttempt: ((AttemptInfo) -> Unit)? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
```

- [ ] **Step 2: 重写 connect()**

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
                            failPermanently(ConnectionErrorCode.VERSION_MISMATCH, "版本不兼容（远端 ${result.version}）")
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
                        val msg = "连接失败（${result.code.name}），${backoff / 1000} 秒后自动重连"
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
配套新增：
```kotlin
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
        // profile 引用缓存；见 Step 4 的 currentProfile 字段
        currentProfile ?: return false
    ).let { false }.let { _ -> currentProfile?.proxy != null }

    private fun failPermanently(code: ConnectionErrorCode, detail: String) {
        onAttempt?.invoke(AttemptInfo(profileId ?: "", code, null))
        _events.tryEmit(Event.StreamError("$detail（已停止自动重连）"))
        _state.value = State.Error("$detail（已停止自动重连）", code, profileId)
    }
```
（`currentProfileHasProxy` 写法冗长，简化为：新增字段 `private var currentProfile: HostProfile? = null`，connect 时赋值，disconnectInternal 时清空；`currentProfileHasProxy() = currentProfile?.proxy != null`。）

- [ ] **Step 3: 网络切换监听**

在 `DshConnection` 增加（需 import `android.net.ConnectivityManager`、`android.net.Network`）：
```kotlin
    private fun registerNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 新网络可用：若处于重连等待中，立即重置退避重试
                scope.launch { retryNow() }
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
        // 取消当前连接协程的子任务（探测/延迟），由 connect 循环重建
        scope.coroutineContext.cancelChildren()
        delay(300)
        retryNowPending = false
        currentProfile?.let { p ->
            if (_state.value is State.Error) connect(p, onAttempt)
        }
    }
```
`appContext` 由构造注入：`class DshConnection(private val appContext: Context? = null)`。`DshApplication.connection` 创建处改为 `DshConnection(this)`；`DshConnectionService` 里 `DshConnection(this)`。

`disconnectInternal()` 追加：
```kotlin
        unregisterNetworkCallback()
        currentProfile = null
        profileId?.let { OkHttpClientFactory.release(it) }
```

- [ ] **Step 4: 编译验证（无专门单测，逻辑已在 Task 2 覆盖）**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（ConnectScreen/Service 仍用旧 `connect(url)` 的调用点会报错——见下）。

- [ ] **Step 5: 修编译调用点（最小过渡）**

`ConnectScreen.kt` 旧 `connection.connect(u)` 与 `connection.connect(config.serverUrl)` 两处、`DshConnectionService.kt` 的 `connection.connect(config.serverUrl)` 一处：临时改为
```kotlin
connection.connect(
    HostProfile(id = "legacy-0", remark = "旧连接", url = u, autoConnect = true),
)
```
（Task 7/8 会整体替换，此处只保证编译。）

- [ ] **Step 6: 编译通过 + Commit**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

```bash
git add app/src/main/java/com/dsh/mobile/data/DshConnection.kt app/src/main/java/com/dsh/mobile/DshApplication.kt app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt
git commit -m "feat(s1): DshConnection 按主机连接/错误分类/差异化重连/网络切换自愈"
```

---

### Task 6: DshConnectionService 适配 activeProfileId

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt`

**Interfaces:**
- Consumes: Task 3 `SettingsStore.profiles/activeProfileId`、Task 5 `DshConnection.connect(profile)`
- Produces: 服务随活跃主机切换重启 watcher；去重集合随切换清空。

- [ ] **Step 1: 重写 startWatching**

```kotlin
    private fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            val settings = SettingsStore(this@DshConnectionService)
            if (!settings.backgroundNotify.first()) {
                stopSelf()
                return@launch
            }
            // 跟随活跃主机：切换即重启 watcher（单活跃语义）
            settings.activeProfileId
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
                        updateForegroundText("未选择活跃主机")
                        return@collect
                    }
                    val connection = DshConnection(this@DshConnectionService)
                    watcher = connection
                    launch { connection.events.collect { handle(it) } }
                    launch {
                        connection.state.collect { st ->
                            val text = when (st) {
                                is DshConnection.State.Connected -> "已连接 " + st.baseUrl
                                is DshConnection.State.Connecting -> "连接中…"
                                is DshConnection.State.Error ->
                                    if (st.code != null) "连接失败（${st.code.name}），自动重连中" else st.message
                                else -> "后台连接已开启"
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
import 补充：`kotlinx.coroutines.flow.distinctUntilChanged`、`com.dsh.mobile.data.HostProfile`（如未引入）。

- [ ] **Step 2: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt
git commit -m "feat(s1): 后台服务跟随活跃主机切换 watcher"
```

---

### Task 7: ConnectScreen 重构 —— 主机列表 / 一键切换 / 错误横幅

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt`
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt`（整体重写）

**Interfaces:**
- Consumes: Task 3 `SettingsStore`、Task 5 `DshConnection.connect(profile)/State.Error(code)`、Task 1 `HostProfile`
- Produces: `ErrorMessages.reason(code)/advice(code)`（Task 8 复用）；`ConnectScreen(connection, onEditHost: (String?) -> Unit)`（Task 9 接线）。

- [ ] **Step 1: ErrorMessages（spec §6 文案表）**

`app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt`：
```kotlin
package com.dsh.mobile.data

object ErrorMessages {
    fun reason(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "域名无法解析"
        ConnectionErrorCode.PORT_UNREACHABLE -> "端口不可达（连接被拒绝/超时）"
        ConnectionErrorCode.TLS_CERT_FAILED -> "HTTPS 证书校验失败"
        ConnectionErrorCode.AUTH_FAILED -> "前置网关鉴权失败（401/403）"
        ConnectionErrorCode.VERSION_MISMATCH -> "移动端与 PC 端版本不兼容"
        ConnectionErrorCode.PROXY_FAILED -> "代理不可达"
        ConnectionErrorCode.PROTOCOL_ERROR -> "服务响应异常"
        ConnectionErrorCode.UNKNOWN -> "未知错误"
    }

    fun advice(code: ConnectionErrorCode): String = when (code) {
        ConnectionErrorCode.DNS_UNREACHABLE -> "检查地址拼写；局域网场景改用 IP"
        ConnectionErrorCode.PORT_UNREACHABLE -> "确认 PC 端 DSH 已启动、端口正确、防火墙放行"
        ConnectionErrorCode.TLS_CERT_FAILED -> "若为自签名证书，在本主机配置里开启「信任自签名」或导入其 CA"
        ConnectionErrorCode.AUTH_FAILED -> "DSH 本机直连无鉴权；检查自建反代/网关的鉴权配置或凭证"
        ConnectionErrorCode.VERSION_MISMATCH -> "升级 DSH 或本 App"
        ConnectionErrorCode.PROXY_FAILED -> "检查代理地址/端口/账号，或关闭该主机的代理"
        ConnectionErrorCode.PROTOCOL_ERROR -> "确认地址指向 DSH web 服务；导出日志排查"
        ConnectionErrorCode.UNKNOWN -> "导出日志排查"
    }
}
```

- [ ] **Step 2: 重写 ConnectScreen**

完整替换 `ConnectScreen.kt`（保留包名与现有 import 需求；新增 import：`com.dsh.mobile.data.HostProfile`、`com.dsh.mobile.data.ErrorMessages`、`androidx.compose.material3.DropdownMenu` 等）：

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

    // —— 扫码：解析结果 → 新建或更新配置并连接 ——
    val scannerLauncher = rememberLauncherForActivityResult(
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
            Toast.makeText(context, "需要相机权限才能扫码连接", Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    // —— 自动连接 ——
    LaunchedEffect(Unit) {
        val auto = settingsStore.profiles.first().firstOrNull { it.autoConnect }
        if (auto != null) {
            scope.launch { settingsStore.setActiveProfile(auto.id) }
            connectTo(auto)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 错误横幅（spec §6：错误码 → 原因 → 建议；不可恢复错误标注已停止重连）
        val st = connState
        if (st is DshConnection.State.Error && st.code != null) {
            val code = st.code
            val stopped = code == ConnectionErrorCode.AUTH_FAILED ||
                code == ConnectionErrorCode.VERSION_MISMATCH
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        ErrorMessages.reason(code) + if (stopped) "（已停止自动重连）" else "",
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
                            "遥控你电脑上的 DeepSeek Harness 智能体",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { startScan() }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码连接", tint = DshBrand)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 活跃主机卡片
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
                                TextButton(onClick = { onEditHost(p.id) }) { Text("编辑") }
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
                                    "已连接" + (connected.hostVersion?.let { " · 主机版本 $it" } ?: " · 版本未知"),
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
                    "已保存的主机",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (sortedProfiles.isEmpty()) {
                item {
                    Text(
                        "还没有主机配置：扫码或点下方「添加主机」",
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
                                            "使用中",
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
                                            "上次错误：${ErrorMessages.reason(c)}",
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
                    Text("添加主机")
                }
                Spacer(Modifier.height(20.dp))
                val versionName = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                Text(
                    "DSH Remote v$versionName · 非官方客户端 · 数据只存你的手机与你的服务器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
```
注意：`connectTo` 在每次切换前不再手动 disconnect（`DshConnection.connect` 内部已 disconnectInternal）；切换语义由 activeProfileId 驱动服务端 watcher（Task 6）。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`AppNavigation` 中 `ConnectScreen(connection = connection)` 因新参数有默认值无需改动）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/ErrorMessages.kt app/src/main/java/com/dsh/mobile/ui/screens/ConnectScreen.kt
git commit -m "feat(s1): ConnectScreen 重构——主机列表/一键切换/错误横幅"
```

---

### Task 8: HostProfileScreen —— 配置编辑 / 测试连接 / CA 导入 / 代理

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/Diagnostics.kt`
- Create: `app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt`

**Interfaces:**
- Consumes: Task 1 `HostProfile/ProfileCodec`、Task 3 `SettingsStore`、Task 4 `OkHttpClientFactory`、Task 7 `ErrorMessages`
- Produces:
```kotlin
// data/Diagnostics.kt
data class DiagStep(val name: String, val ok: Boolean, val detail: String?, val elapsedMs: Long)
suspend fun runDiagnostics(profile: HostProfile): List<DiagStep>

// ui/screens/HostProfileScreen.kt
@Composable
fun HostProfileScreen(
    profileId: String?,          // null = 新建
    onBack: () -> Unit,
)
```

- [ ] **Step 1: 实现 Diagnostics**

`app/src/main/java/com/dsh/mobile/data/Diagnostics.kt`：
```kotlin
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
    steps += timed("DNS 解析") {
        val addrs = java.net.InetAddress.getAllByName(host)
        if (addrs.isEmpty()) throw IllegalStateException("无解析结果")
        addrs.joinToString(", ") { it.hostAddress }
    }

    // 2. TCP
    steps += timed("TCP 连接") {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 5000)
        }
        "已连接 $host:$port"
    }

    // 3. TLS（仅 https）
    if (https) {
        steps += timed("TLS 握手") {
            val (unary, _) = OkHttpClientFactory.build(profile)
            val sf = unary.sslSocketFactory
            (sf as SSLSocketFactory).createSocket(host, port).use { s ->
                s.startHandshake()
            }
            "证书校验通过"
        }
    }

    // 4. host.describe 版本探测
    steps += timed("版本探测") {
        val (unary, _) = OkHttpClientFactory.build(profile)
        val rpcId = "diag-" + java.util.UUID.randomUUID()
        val body = """{"type":"client-request","rpcId":"$rpcId","method":"host.describe","payload":{}}"""
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(profile.url + "/api/host.describe").post(body).build()
        unary.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            version?.let { "主机版本 $it" } ?: "版本字段缺失（视为未知）"
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

- [ ] **Step 2: 实现 HostProfileScreen**

`app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt`：
```kotlin
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
                    Toast.makeText(context, "CA 证书导入失败：${it.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "地址不能为空", Toast.LENGTH_SHORT).show()
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
                title = { Text(if (original == null) "添加主机" else "编辑主机") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
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
                label = { Text("备注名") },
                placeholder = { Text("如：家里 / 公司 / 服务器") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.100:8787 或你的 cpolar 域名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HTTPS 证书", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "⚠ 仅对本主机生效，跳过证书校验",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Switch(checked = trustSelfSigned, onCheckedChange = { trustSelfSigned = it })
                    }
                    OutlinedButton(onClick = { caPicker.launch(arrayOf("application/x-pem-file", "application/octet-stream")) }) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入 CA 证书")
                    }
                    caCertUri?.let {
                        Text(
                            "已导入：$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("代理（按主机）", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("none" to "无", "http" to "HTTP", "socks5" to "SOCKS5").forEach { (v, label) ->
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
                            label = { Text("代理主机") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPort, onValueChange = { proxyPort = it },
                            label = { Text("端口") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyUser, onValueChange = { proxyUser = it },
                            label = { Text("账号（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = proxyPass, onValueChange = { proxyPass = it },
                            label = { Text("密码（可选）") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启动时自动连接", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
                    Text("诊断中…")
                } else {
                    Text("测试连接")
                }
            }

            diag?.let { steps ->
                Card(Modifier.fillMaxWidth(), shape = DshShape.card) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("诊断结果", style = MaterialTheme.typography.titleSmall)
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
                                    Text("${s.name}（${s.elapsedMs}ms）", style = MaterialTheme.typography.bodySmall)
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
            ) { Text("保存") }

            if (original != null) {
                OutlinedButton(
                    onClick = { delete() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = DshShape.pill,
                ) { Text("删除该主机", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
```
import 补：`kotlinx.coroutines.runBlocking`。

- [ ] **Step 3: 编译验证**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/Diagnostics.kt app/src/main/java/com/dsh/mobile/ui/screens/HostProfileScreen.kt
git commit -m "feat(s1): 主机编辑页（CA 导入/代理/测试连接诊断）"
```

---

### Task 9: 导航接线 + 收尾清理 + 全量构建

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`（移除 `ConnectionConfig` 与旧 flow）

**Interfaces:**
- Consumes: Task 7 `ConnectScreen(connection, onEditHost)`、Task 8 `HostProfileScreen(profileId, onBack)`

- [ ] **Step 1: 导航接线**

`AppNavigation.kt` 中 `Screen` 增加：
```kotlin
    data object HostProfile : Screen("hostProfile/{profileId}") {
        fun createRoute(profileId: String?) = "hostProfile/${profileId ?: "new"}"
    }
```
NavHost 增加（`ConnectScreen` 调用处带回调）：
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

- [ ] **Step 2: 移除旧 ConnectionConfig**

`SettingsStore.kt` 删除：
```kotlin
data class ConnectionConfig(
    /** 服务器基础地址，如 http://192.168.1.100:8787 或 cpolar 域名 */
    val serverUrl: String = "",
    val autoConnect: Boolean = true,
)
```
与
```kotlin
    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            serverUrl = prefs[URL_KEY] ?: "",
            autoConnect = prefs[AUTO_KEY] ?: true,
        )
    }
```
与
```kotlin
    suspend fun saveConnection(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[URL_KEY] = config.serverUrl
            prefs[AUTO_KEY] = config.autoConnect
        }
    }
```
以及 companion 中的 `URL_KEY` / `AUTO_KEY` 定义（迁移函数内以字面量 key 自建，不依赖它们）。

- [ ] **Step 3: 全量编译 + 单元测试**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: 全部单测 PASS（HostProfileTest / ConnectionPolicyTest / SettingsStoreMigrationTest / OkHttpClientFactoryTest）。

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产物 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 4: 真机验收（spec §8 清单）**

安装 app-debug.apk 后逐项执行：
1. 旧版升级迁移：若之前连过地址 → 打开即见「旧连接」配置在列表首位且自动连接。
2. 多主机：添加 ≥3 条配置（含备注），切换即时生效，常驻通知跟随当前主机。
3. 错误场景：错域名（DNS 横幅）、错端口（PORT 横幅）、自签 HTTPS（TLS_CERT_FAILED 横幅 → 编辑页开「信任自签名」→ 重连成功）。
4. 网络切换：WiFi↔蜂窝切换后 ≤3s 静默恢复。
5. 代理：HTTP/SOCKS5 各验证一次（含账号密码）；错误代理 → PROXY_FAILED。
6. 测试连接：编辑页逐项 ✓/✗ 展示。
7. 回归：审批/问答通知、任务完成通知、会话收发全流程正常。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt app/src/main/java/com/dsh/mobile/data/SettingsStore.kt
git commit -m "feat(s1): 导航接入主机编辑页，移除旧 ConnectionConfig"
```

---
