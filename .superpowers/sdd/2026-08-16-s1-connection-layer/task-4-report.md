# Task 4 Report — OkHttpClientFactory（按主机证书与代理）

## What I implemented

- `app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt` (NEW)
  - 顶层纯函数：`buildProxy(cfg)`、`trustAllSslContext()`、`parseCaCertificate(bytes)`。
  - `object OkHttpClientFactory`：`build(profile)`（按 `profile.id` 缓存 unary/stream 双客户端，共享连接池）、`release(profileId)`。
  - 按控制器裁定，实现 **CompositeTrustManager 版本**：
    - `CompositeTrustManager` 内部类（系统 primary + 导入 CA extra 合并校验）。
    - `@Volatile private var lastComposite` + `compositeX509()`。
    - `mergedCaContext()` 读取 PEM/DER 文件 → 合成 `CompositeTrustManager`。
    - **未实现** 早期骨架的 `mergedX509()`（按裁定删除）。
  - `newClient(profile, stream)`：stream/unary 超时差异化、trustSelfSigned → trust-all + hostnameVerifier 放行、caCertUri → CA 合成、`buildProxy` 代理、`proxyAuthenticator` 凭证。

- `app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt` (NEW)
  - 3 个测试：`proxyMapping`、`trustAllSslContextBuilds`、`parseCaCertificateHandlesGarbage`（与 brief 逐字一致）。

## What I tested and results

- 命令：`.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"`（从 junction `C:\hremote` 运行，规避非 ASCII 路径问题）。
- 结果：`BUILD SUCCESSFUL`；测试报告 XML：`tests="3" skipped="0" failures="0" errors="0"`（proxyMapping / parseCaCertificateHandlesGarbage / trustAllSslContextBuilds 全 PASS）。

## TDD Evidence

### RED（实现前，仅测试文件存在）
```
> Task :app:compileDebugUnitTestKotlin FAILED
e: .../OkHttpClientFactoryTest.kt:14:20 Unresolved reference 'buildProxy'.
e: .../OkHttpClientFactoryTest.kt:15:20 Unresolved reference 'buildProxy'.
e: .../OkHttpClientFactoryTest.kt:16:20 Unresolved reference 'buildProxy'.
e: .../OkHttpClientFactoryTest.kt:17:46 Unresolved reference 'type'.
e: .../OkHttpClientFactoryTest.kt:18:21 Unresolved reference 'buildProxy'.
e: .../OkHttpClientFactoryTest.kt:19:48 Unresolved reference 'type'.
e: .../OkHttpClientFactoryTest.kt:24:19 Unresolved reference 'trustAllSslContext'.
e: .../OkHttpClientFactoryTest.kt:31:20 Unresolved reference 'parseCaCertificate'.
e: .../OkHttpClientFactoryTest.kt:32:20 Unresolved reference 'parseCaCertificate'.
e: .../OkHttpClientFactoryTest.kt:33:20 Unresolved reference 'parseCaCertificate'.
BUILD FAILED in 1s
```

### GREEN（实现后）
```
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
```
测试 XML：`<testsuite name="com.dsh.mobile.data.OkHttpClientFactoryTest" tests="3" skipped="0" failures="0" errors="0" ...>`

## Files changed

- `app/src/main/java/com/dsh/mobile/data/OkHttpClientFactory.kt`（新增）
- `app/src/test/java/com/dsh/mobile/data/OkHttpClientFactoryTest.kt`（新增）

Commit: `3c694c6 feat(s1): 按主机 OkHttp 客户端工厂（自签名信任/CA 合成/代理）`
（`git show --stat` 确认仅含上述 2 个文件，183 insertions。）

## Self-review findings

1. 完整性：brief 要求的所有接口（build/release/buildProxy/trustAllSslContext/parseCaCertificate）均已实现；CompositeTrustManager 版本按控制器裁定落地，未实现早期 `mergedX509()`。✓
2. YAGNI：无超出 brief 的额外代码。✓
3. 编译/测试：仅针对 brief 的 3 个测试，全部通过，无 warning/error。✓
4. 干净提交：`git status` 干净，commit 仅含本任务两个文件（显式 `git add` 路径，未用 `-A`/`.`）。✓
5. 一处必要的最小修正（偏离 brief 逐字文本）：`proxyAuthenticator` 的 lambda 参数从 `_: Route` 改为 `_: Route?`。原因：OkHttp 4.12.0 的 `Authenticator.authenticate(route: Route?, response: Response)` 首参为可空 `Route?`；brief 的 `Route`（非空）无法通过编译。此为 API 签名适配，语义不变。

## Concerns

1. **`mergedCaContext` 的 `ctx.init(null, null, null)` 与导入 CA 实际生效存在语义偏差（潜在功能 bug，非本任务测试覆盖范围）。**
   按 JSSE 语义，`SSLContext.init(km=null, tm=null, random=null)` 的 `tm=null` 会回退到**系统默认信任管理器**，因此 `ctx.socketFactory` 只信任系统 CA；而 `CompositeTrustManager`（含导入 CA）仅通过 `builder.sslSocketFactory(ctx.socketFactory, compositeX509()!!)` 作为 OkHttp 的 `CertificateChainCleaner` 传入，**不参与握手阶段的信任校验**。结果是「导入私有 CA」场景下，握手仍可能抛出 `SSLHandshakeException: unable to find valid certification path`。对比之下 `trustSelfSigned` 路径是正确的：`trustAllSslContext()` 把 trust-all 管理器**放进 SSLContext**（`ctx.init(null, arrayOf(trustAll), ...)`），握手真正放行。
   若要按 brief 意图「系统链 + 导入 CA 合成」生效，`mergedCaContext` 应改为 `ctx.init(null, arrayOf<TrustManager>(lastComposite), null)` 后再返回 ctx。**我未擅自修改**——严格按控制器裁定的「最终设计」逐字转录，此问题交由控制器/后续 Task 8（用 `build` 做 TLS 诊断）裁决是否修正。

2. 观测到仓库存在**并行提交**：工作期间出现了 `b90bbf6 v1.2.1: 修复 Pro 计费漏洞 + ChatGPT 空态文案`（非本任务、非 S1 计划）。经 `git show --stat` 确认我的提交 `3c694c6` 仅含本任务 2 个文件，未受污染。

---

## Fix (控制器复核后修复 Concern 1)

按控制器要求修复「导入私有 CA 不参与握手信任」缺陷：

1. **`mergedCaContext` 重构为纯函数**：`internal fun mergedCaContext(caBytes: ByteArray): SSLContext?`
   - URI 文件读取移出，改为接收字节数组。
   - `val composite = CompositeTrustManager(systemTmf, importedTmf)` → `ctx.init(null, arrayOf<TrustManager>(composite), SecureRandom())` → `lastComposite = composite` → 返回 ctx。
   - **删除** `ctx.init(null, null, null)`（原来回退系统默认信任，导致导入 CA 不进握手信任链）。
2. **`newClient` CA 分支**：`val bytes = runCatching { java.io.File(ca).readBytes() }.getOrNull()` → `mergedCaContext(bytes)`；null 则保持默认链，非 null 则 `builder.sslSocketFactory(ctx.socketFactory, compositeX509()!!)`。
   - 说明：控制器原文写 `val bytes = java.io.File(ca).readBytes()`；我额外用 `runCatching { ... }.getOrNull()` 包裹文件读取，以保留原 brief「CA 文件读取失败回退系统默认」语义（文件缺失/不可读时不再抛 IOException 崩溃，而是回落默认链）。若控制器不认可此兜底，可去掉 runCatching。
3. **补测试** `mergedCaContextGarbageReturnsNull`：`OkHttpClientFactory.mergedCaContext("not a pem".toByteArray())` 与 `byteArrayOf()` 均返回 null（注意：`mergedCaContext` 是 object 成员，测试里以 `OkHttpClientFactory.mergedCaContext(...)` 限定调用）。

### 验证结果

- 定向：`.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.OkHttpClientFactoryTest"` → BUILD SUCCESSFUL，XML `tests="4" failures="0" errors="0"`。
- 全量单测：`.\gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL，EXITCODE=0；聚合 4 个测试类 / 24 tests / 0 failures / 0 errors。

### 修复提交

- `089f389 fix(s1): mergedCaContext 真正合并系统链与导入 CA（CompositeTrustManager 进入 SSLContext）`
- 仅含 `OkHttpClientFactory.kt`（16 行变更）+ `OkHttpClientFactoryTest.kt`（+6 行）。
