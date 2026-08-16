# S3 安全底座设计文档

- 日期：2026-08-16
- 项目：DSH Remote（harness-remote）能力补全计划 · 子项目 S3
- 状态：待评审

## 1. 背景与目标

DSH 拥有本地文件读写、命令执行权限，远程操控的安全优先级高于普通聊天 App。S3 补齐安全底线四项能力：凭证加密存储、App 生物锁、高危操作二次确认、PC 端首次配对确认。

目标（对应需求清单 三-1/2/3）：

1. **凭证安全存储**：代理密码等敏感字段加密落盘（AndroidKeyStore AES-GCM），不明文存 DataStore；旧明文无感迁移。
2. **App 生物锁**：可选的指纹/面容/系统密码锁，防止手机遗失后他人直接操控电脑。
3. **高危操作二次确认**：高危审批（命令执行/删除/安装）必须有二次确认，批量放行不得覆盖高危项。
4. **PC 端首次配对确认**：手机首次连接时 PC 端弹确认对话框，需在电脑端同意后才正式连通，防扫码被劫持/误扫静默接管。

## 2. 范围

**In scope**：SecretCipher（Keystore 加密）、SettingsStore 凭证加密与迁移、生物锁开关与前台解锁覆盖层、RiskClassifier 分级 + 待办中心二次确认、PC 插件配对路由与确认对话框、App 配对握手与 HostProfile.paired 字段、设置页安全区、单测与真机验收。

**Out of scope（后续子项目或明确不做）**：逐请求鉴权/传输加密（配对是一次性握手，不改变既有连接强度）；密钥轮换与双因子；S4 通知渠道精细化；S5/S6/S7 其余能力。

## 3. 现状与事实

- 凭证现状：`HostProfile.proxy.password` 明文存 DataStore（S1 已标 TODO-S3）；协议无 Token/鉴权头（AUTH_FAILED 仅来自用户自建反代）。
- 敏感字段盘点：仅代理密码一项（`ProxyConfig.password`）；无其它明文凭据。
- 审批数据：`ApprovalRequested(sessionId, approvalId, toolName, reason?, callId?)`；待办中心（S2）已集中审批/问答/异常。
- PC 插件 `dsh-remote-access`：`webServer.register` 路由（wx/cpolar/qr/fs.list）；`client.js` 是设置页组件，可注入全局 DOM；`approval/request` prepend 应答器仅接管微信绑定会话——配对不同走审批通道，避免与待办中心互相干扰。
- Android 事实：minSdk 29 → AndroidKeyStore `KeyGenParameterSpec`/`AES/GCM/NoPadding` 可用；`BiometricPrompt` + `BiometricManager.canAuthenticate(BIOMETRIC_WEAK)` 可用；Jetpack security-crypto 已弃用（手写 Keystore 方案）。

## 4. 核心决策（用户已批准）

| 决策 | 结论 |
| :-- | :-- |
| 加密方案 | AndroidKeyStore AES-256-GCM 手写 `SecretCipher`（不用弃用的 security-crypto）；密文 = Base64(iv‖ct) |
| 凭证迁移 | 解密失败且非空 → 视为旧明文，读后随下次写入重加密（无感、一次性） |
| 生物锁 | 设置开关（默认关）；无生物硬件时禁用；`BIOMETRIC_WEAK \| DEVICE_CREDENTIAL` 兜底；**60s 宽限**（后台切回不重复锁）；全屏锁覆盖层，失败/取消保持锁态 |
| 高危分级 | `RiskClassifier` 按 toolName/reason 关键词：HIGH（bash/pwsh/terminal/shell/cmd、delete/remove/rm、install/winget/choco/apt/pip、git push/force…）/ MEDIUM（write/edit/move/create…）/ LOW（其余） |
| 高危确认 | 待办中心 HIGH 卡红色徽章 + 「允许一次」二次确认对话框；**批量「全部允许」跳过 HIGH**（须单项确认），「全部拒绝」含全部 |
| 配对通道 | PC 插件 `client.js` 全局对话框（不走审批通道，避免与待办中心互相干扰）；App 连接后未配对 → 发请求 → 轮询状态 ≤120s |
| 兼容降级 | 插件未更新（pair 路由 404 或异常）→ 跳过配对并提示，向后兼容 |

## 5. 详细设计

### 5.1 SecretCipher（新增 `data/SecretCipher.kt`）

```kotlin
/** Keystore AES-GCM 加密盒。接口化以便 SettingsStore 单测注入假件。 */
interface SecretBox {
    fun encrypt(plain: String): String   // Base64(iv ‖ ciphertext)
    fun decrypt(enc: String): String?    // 失败返回 null
}

object SecretCipher : SecretBox {
    const val KEY_ALIAS = "dsh_secret_key"
    fun init(context: Context)   // 幂等：KeyStore 已有 alias 则跳过
    override fun encrypt(plain: String): String
    override fun decrypt(enc: String): String?
    fun hasKey(): Boolean
}
```
- 实现要点：`KeyGenParameterSpec.Builder(KEY_ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`、`KeyProperties.KEY_ALGORITHM_AES`、`BLOCK_MODE_GCM`、`ENCRYPTION_PADDING_NONE`、`setKeySize(256)`；`Cipher.getInstance("AES/GCM/NoPadding")`；IV 12 字节前置；`Base64.NO_WRAP`；解密异常（BadPaddingException 等）→ null。init 在 `DshApplication.onCreate` 调用一次。
- 不可 JVM 单测（需 AndroidKeyStore）→ 单测走注入的假 `SecretBox`（见 5.2），真实现走真机验收。

### 5.2 SettingsStore 凭证加密与迁移

- `SettingsStore` 构造增加注入：`class SettingsStore(context, private val secretBox: SecretBox = SecretCipher)`。
- 持久化边界：`upsertProfile` 写入前 `profile.copy(proxy = proxy?.copy(password = secretBox.encrypt(password)))`；`profiles` flow 读取时 `password = secretBox.decrypt(enc) ?: enc`（解密失败 → 按旧明文读）。
- 惰性重加密：解密失败且密码非空（= 旧明文）时，不做即时写回（避免读路径副作用），下次 `upsertProfile` 自然覆盖为密文。
- 迁移测试（假 SecretBox）：保存加密 → 读回解密；假件解密返回 null → 读回明文；往返不破坏其它字段。

### 5.3 App 生物锁

- `SettingsStore` 新字段：`biometricLockEnabled: Flow<Boolean>`（默认 false）、`setBiometricLock(enabled)`、`biometricAvailable(): Boolean`（BiometricManager.canAuthenticate(BIOMETRIC_WEAK) == BIOMETRIC_SUCCESS）。
- `MainActivity`：onStart 时若 `biometricLockEnabled && now - lastUnlockedAt > 60_000` → 置 locked 态。`DshTheme` 内容之上渲染 `BiometricLockOverlay`（全屏遮罩，锁图标 + 「验证身份以继续」）：composition 时触发 BiometricPrompt；成功 → `lastUnlockedAt = now` + 隐藏；失败/取消/不可用 → 保持锁态（不可绕过；设置里可关闭生物锁作为逃生，但关闭也要求先通过验证——**逃生设计**：设置页生物锁开关在锁态下访问需先解锁；为控制范围，锁态下允许从系统通知栏/设置页关闭生物锁前仍须一次系统凭据验证）。
- 首次进程启动即锁（`lastUnlockedAt` 初始 0）；进程存活期间 60s 宽限内前台切换不重复锁。
- 纯逻辑（宽限判断 `shouldLock(lastUnlockedAt, now, enabled)`）提为顶层纯函数，可单测。

### 5.4 RiskClassifier 与高危二次确认

- 新增 `data/RiskClassifier.kt`（纯函数，可单测）：
```kotlin
enum class RiskLevel { LOW, MEDIUM, HIGH }

object RiskClassifier {
    fun level(toolName: String, reason: String?): RiskLevel
    // HIGH 关键词（大小写不敏感，子串匹配 toolName+reason）：
    //   bash, pwsh, powershell, terminal, shell, cmd, delete, remove, rm, unlink,
    //   install, winget, choco, scoop, apt, pip, npm, git push, force, format, drop
    // MEDIUM 关键词：write, edit, move, rename, create, mkdir, upload, replace, truncate
    // 其余 LOW
}
```
- 待办中心（PendingScreen）审批卡：`RiskLevel.HIGH` → 红色「高危」徽章；MEDIUM → 黄色「中危」；LOW 无徽章。
- 单项「允许一次」：HIGH → 弹二次确认对话框（标题「高危操作二次确认」，正文「工具 X · 原因：… · 允许该操作执行一次？」红色主按钮「仍要允许」/ 取消）；MEDIUM/LOW → 直接允许。
- 批量「全部允许」：仅作用于 LOW/MEDIUM（HIGH 留在列表），完成后 snackbar 注明「N 项高危已跳过，需逐项确认」。「全部拒绝」覆盖全部（拒绝本身安全）。
- `ApprovalCenter.allowAllApprovals()` 改为接受 `RiskClassifier` 过滤（或由调用方过滤）——设计：调用方（PendingScreen）传入过滤后的快照？为保持状态层纯净，`allowAllApprovals` 签名不变，PendingScreen 自行按 `items` 过滤 HIGH 后仅对非 HIGH 调用 `allow(...)`（复用单项逻辑）；snackbar 汇总含跳过数。状态层不加风险逻辑（单一职责）。

### 5.5 PC 端首次配对

**插件（dsh-remote-access）改动：**

- 状态：`paired.json`（`$DSH_HOME/remote-access/paired.json`：`{ devices: [{deviceId, name, at}] }`）+ 内存 `pendingPair { deviceId, name, at, timer }`（120s 超时自动清）。
- 路由（沿用 `reg`/`json`/`readBody` 惯例）：
  - `POST /api/remote-access/pair/request` body `{deviceId, deviceName}`：已配对 → `{ok:true, state:"paired"}`；pending 中 → `{ok:true, state:"pending"}`；否则建 pending → `{ok:true, state:"pending"}`。
  - `GET /api/remote-access/pair/status` → `{ok:true, state: "none"|"pending"|"approved"|"denied", deviceId?}`。
  - `POST /api/remote-access/pair/respond` body `{deviceId, outcome: "approve"|"deny"}`：approve → 写入 paired.json；返回 `{ok:true}`。
  - `GET /api/remote-access/pair/list` → 已配对设备列表；`POST /api/remote-access/pair/revoke` body `{deviceId}` → 从 paired.json 移除（配对管理）。
- client.js（设置页「远程控制」卡片内）：
  - 全局轮询 `pair/status`（页面可见时 2s）：pending 且未显示对话框 → 注入 fixed 全屏遮罩对话框「🔐 手机设备「X」请求首次连接\n允许该设备远程控制这台电脑？」+ 「允许 / 拒绝」→ 调 `pair/respond`；对话框带设备名。
  - 新增「配对管理」小节：列已配对设备 + 撤销按钮（调 `pair/revoke`）。

**App 端改动：**

- `HostProfile` 增加 `paired: Boolean = false` 字段（模型 + Codec 往返默认值测试更新）。
- 新增 `data/PairingManager.kt`（注入式，同 ApprovalCenter 风格）：
```kotlin
class PairingManager(
    private val rpc: PairingRpc,          // 接口：request/status
    private val onDenied: suspend () -> Unit,   // 断开连接
    private val scope: CoroutineScope,
) {
    data class Result(val state: String)  // paired / pending / approved / denied / skip
    suspend fun ensurePaired(profile: HostProfile, deviceId: String, deviceName: String): Result
}
interface PairingRpc {
    suspend fun request(deviceId: String, deviceName: String): String   // state
    suspend fun status(): String                                         // state
}
```
- 流程：连接成功（复用 S2 的 State.Connected 收集，仅当 `profile.paired == false`）→ `request`：`paired` → 标记 + 结束；`pending` → 轮询 `status`（2s×60）：`approved` → `settingsStore.upsertProfile(profile.copy(paired = true))` + 结束；`denied` → `connection.disconnect()` + 事件提示；超时 → 断开 + 提示；`request` 抛异常/404（插件未更新）→ 视为 `skip`（标记 paired = true 并 toast「插件未开启配对确认，已跳过」）。
- deviceId：DataStore 持久 UUID（`device_id` key，首启生成）；deviceName：`Build.MODEL`（用户可改 → 设置页安全区输入框，存 DataStore）。
- 轮询状态机的纯逻辑（request/status 输入序列 → 动作序列）提为可单测函数。

### 5.6 设置页安全区（SettingsScreen）

- 「安全」分组：生物锁开关（不可用时禁用 + 说明）、设备名称（配对显示名，默认 Build.MODEL）、「已配对主机」只读提示（当前活跃主机 paired 状态）。

## 6. 兼容与降级

- 旧版本 App 数据：代理密码旧明文 → 解密失败回退明文 + 下次写加密（§5.2）。
- 旧版本插件：pair 路由不存在 → App `skip` 降级（可连，提示一次）；插件新路由对旧 App 无影响（App 不调即闲置）。
- 模型变化：`HostProfile.paired` 默认 false → 旧配置反序列化自动补默认（ProfileCodec ignoreUnknownKeys + 默认值），无迁移。
- 生物锁开启不改变任何网络行为。

## 7. 测试与验收

1. JUnit（纯逻辑，假件注入）：
   - `SecretBox` 假件下 SettingsStore 加密写/解密读/旧明文回退（§5.2）。
   - `RiskClassifier.level`：HIGH/MEDIUM/LOW 关键词矩阵 + 大小写不敏感 + reason 命中。
   - `shouldLock` 宽限判断（enabled/宽限内/超宽限/首次）。
   - `PairingManager` 状态机：paired 直达 / pending→approved / pending→denied 断开 / 轮询超时断开 / request 404 → skip。
   - HostProfile 往返含 paired 默认值。
2. 编译 + 全量单测 + assembleDebug。
3. 真机验收清单：
   - 生物锁：启用后锁态出现、指纹/密码通过、60s 宽限、无硬件时开关禁用、失败保持锁态。
   - 高危确认：bash 审批显示红徽章 + 二次确认才放行；批量全部允许跳过 HIGH 并提示；MEDIUM/LOW 行为。
   - 配对：首次连接 PC 弹全局对话框（允许/拒绝）；允许后 App 标记 paired 不再弹；拒绝/超时 App 断开并提示；插件未更新时降级可连；paired.json 持久化 + 撤销后重新要求配对。
   - 凭证：代理密码落盘为密文（可抽查 DataStore 文件），旧明文升级后读回正常。
   - 回归：审批中心/连接/后台通知不受影响。

## 8. 风险与开放问题

| 项 | 说明 | 处置 |
| :-- | :-- | :-- |
| SecretCipher 不可 JVM 单测 | AndroidKeyStore 需真机 | 接口化 + 假件单测覆盖集成路径；真实现真机验收（加解密 + 重启持久） |
| 配对是软边界 | 一次性握手非逐请求鉴权 | 如实声明；后续如需强鉴权走 S6/S7 时评估 Token |
| 生物锁逃生 | 锁态下如何关闭开关 | 锁态设置页进入需先系统凭据验证（BiometricPrompt DEVICE_CREDENTIAL 兜底） |
| 插件部署耦合 | 配对功能依赖插件更新 | 双端独立发布，App 侧降级路径兜底；README 更新部署说明 |
| 轮询功耗 | 配对期间 2s 轮询 ≤120s | 仅配对窗口内，超时即停；不做常驻轮询 |

## 9. 影响文件清单

**App 端：**
- `data/SecretCipher.kt` 新增；`data/SettingsStore.kt` 改（SecretBox 注入 + biometric/deviceId 字段）
- `data/RiskClassifier.kt` 新增；`data/PairingManager.kt` 新增
- `data/HostProfile.kt` 改（paired 字段）
- `ui/screens/PendingScreen.kt` 改（风险徽章 + 二次确认 + 批量跳过 HIGH）
- `ui/screens/SettingsScreen.kt` 改（安全区）
- `MainActivity.kt` 改（生物锁覆盖层）；`DshApplication.kt` 改（SecretCipher.init）
- 测试若干

**PC 插件（dsh-remote-access）：**
- `lib/index.js` 改（pair 路由 + paired.json）
- `lib/client.js` 改（全局配对对话框 + 配对管理）

**文档：** `README.md` 更新日志 + 插件部署说明
