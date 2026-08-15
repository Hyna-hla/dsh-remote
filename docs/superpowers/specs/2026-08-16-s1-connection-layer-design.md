# S1 连接层重构设计文档

- 日期：2026-08-16
- 项目：DSH Remote（harness-remote）能力补全计划 · 子项目 S1
- 状态：待评审

## 1. 背景与目标

DSH Remote 目前只支持「单地址 + 自动重连」：一个 `serverUrl` 存 DataStore，扫码/手输都覆盖同一条配置；连接失败只显示笼统文案；证书/代理不可配。S1 把连接层升级为「多主机配置 + 可诊断 + 可信任」的地基，服务后续全部子项目。

目标（对应需求清单 一-1/2/3 与 五-3）：

1. **多实例连接管理 + 连接历史**：保存多台 DSH 主机配置（备注名），一键切换，保留扫码/手输两种添加方式，展示最近使用与上次错误。
2. **连接诊断与自动重连**：失败给出具体错误原因（端口不可达 / Token 无效 / 版本不兼容 / 证书校验失败等）；网络切换（WiFi↔蜂窝）自动静默重连；按错误类型差异化退避。
3. **HTTPS 与证书兼容**：按主机配置「信任自签名证书」开关与「导入自定义 CA」，适配内网穿透 HTTPS 场景。
4. **代理设置**：按主机支持 HTTP / SOCKS5 代理（含账号密码），覆盖企业网/校园网场景。

## 2. 范围

**In scope**：连接配置数据模型与持久化、按主机 OkHttp 客户端构建、错误分类与重连策略、ConnectScreen UI 重构（配置列表/切换/编辑/测试连接）、网络切换监听、旧配置迁移。

**Out of scope（后续子项目，不在 S1 实现）**：

- 多主机同时在线（已决策：单活跃主机，一键切换）
- 凭证 Keystore 存储与生物锁（S3 安全底座）
- PC 端首次配对确认（S3）
- 审批中心 / 文件 / 思考链 / MCP 等（S2/S5/S6）
- 通知渠道精细化（S4）

## 3. 现状（实现基线）

- `SettingsStore`：`ConnectionConfig(serverUrl, autoConnect)` 单条，DataStore keys `server_url` / `auto_connect`。
- `DshConnection`：`connect(url)` → 规范化 → `host.describe` 探测 → 指数退避 3→6→12→24→30s 自动重连；WS 优先 SSE 兜底事件流；共享两个 OkHttpClient（unary / stream），均无证书/代理定制；错误只有字符串 `State.Error(message)`。
- `ConnectScreen`：单输入框 + 扫码 + 自动连接已存地址；连接成功即启动 `DshConnectionService`。
- `DshConnectionService`：前台服务自建第二个 `DshConnection` 读同一 `serverUrl`，监听审批/问答/完成事件发通知，常驻通知同步连接状态文案。
- `host.describe` 返回 `{ version, cwd, provider?, model?, attachedSessions, canOpenPath }`（来自 dsh-host-apiproxy schema），版本兼容检测可用 `version` 字段。

## 4. 核心决策（已与用户确认）

| 决策 | 结论 |
| :-- | :-- |
| 多主机连接策略 | **单活跃主机**：同时只连一台，切换=断开当前→连接目标，后台提醒跟随当前主机 |
| 证书信任粒度 | **按主机配置**：每台独立「信任自签名」开关 + 独立 CA 导入 |
| 错误分类 | 新增 `ConnectionErrorCode` 枚举，UI 按错误码展示原因+排查建议 |
| 重连策略 | 保留指数退避；网络切换即时重连；`AUTH_FAILED`/`VERSION_MISMATCH` 停止自动重连 |
| 连接历史 | 复用配置列表（`lastUsedAt` + `lastErrorCode` 排序），不建独立历史表 |

## 5. 详细设计

### 5.1 数据模型

```kotlin
// data/HostProfile.kt（新增）
data class ProxyConfig(
    val type: String = "none",   // "none" | "http" | "socks5"
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
)

data class HostProfile(
    val id: String,                // UUID，新配置生成
    val remark: String,            // 备注名；未填时默认 host:port
    val url: String,               // 规范化后的地址（含协议、无尾斜杠）
    val trustSelfSigned: Boolean = false,
    val caCertUri: String? = null, // App 私有目录内 CA 文件（可空）
    val proxy: ProxyConfig? = null,
    val autoConnect: Boolean = false,
    val lastUsedAt: Long = 0,      // 最近一次连接尝试时间戳
    val lastErrorCode: String? = null, // 上次错误码（成功置 null）
)

enum class ConnectionErrorCode {
    DNS_UNREACHABLE,   // 域名解析失败
    PORT_UNREACHABLE,  // TCP 拒绝/连接超时
    TLS_CERT_FAILED,   // 证书校验失败（自签名/过期/不匹配）
    AUTH_FAILED,       // HTTP 401/403
    VERSION_MISMATCH,  // host.describe 版本超出兼容区间
    PROXY_FAILED,      // 配置了代理但代理不可达
    PROTOCOL_ERROR,    // 非 HTTP 200 / 信封解析失败 / RPC ok=false
    UNKNOWN,           // 兜底
}
```

### 5.2 存储与迁移（SettingsStore）

- DataStore 新增 `connection_profiles`（JSON 数组字符串）、`active_profile_id`（字符串）。
- `connectionConfig` Flow 改为 `profiles: Flow<List<HostProfile>>` + `activeProfileId: Flow<String?>`；旧 `connectionConfig` 保留过渡读取。
- **迁移**：首次读取时若存在旧 `server_url` 且 `connection_profiles` 为空 → 生成一条 `HostProfile`（remark = "旧连接"，url = 旧值，autoConnect = 旧值）写入数组，并置为 active；随后删除旧 key。
- 密码字段（代理密码）明文存 DataStore（Keystore 迁移属 S3，spec 注明 TODO-S3）。

### 5.3 按主机客户端工厂（新增 data/OkHttpClientFactory.kt）

- `build(profile): Pair<OkHttpClient, OkHttpClient>`（unary / stream 两份，超时配置沿用现有值）。
- 证书策略：
  - `trustSelfSigned=true`：自定义 `X509TrustManager`（全接受）+ `HostnameVerifier`（全通过）。
  - `caCertUri != null`：读取 PEM/DER → 与系统默认信任链合并（`KeyStore` + `X509TrustManagerFactory`）。
  - 默认：系统链。
- 代理：`ProxyConfig` → `Proxy(HTTP|SOCKS)` + `ProxyAuthenticator`（仅账号密码非空时挂）。
- 客户端实例按 profile 缓存（LRU，切换主机后旧实例释放），避免每连接新建线程池。

### 5.4 DshConnection 扩展

- `connect(profile: HostProfile)` 替代 `connect(url)`；保留内部 `normalizeBaseUrl`。
- `State.Error(message, code: ConnectionErrorCode?, profileId: String?)`；连接尝试结果通过回调 `onAttempt: (profileId: String, errorCode: ConnectionErrorCode?, hostVersion: String?) -> Unit` 通知上层（用于写回 lastUsedAt/lastErrorCode，不引入反向依赖）。
- **错误分类**：`call()` 与探测链路统一 `catch` → 按异常类型映射：
  - `UnknownHostException` → DNS_UNREACHABLE
  - `ConnectException` / 连接超时 `SocketTimeoutException` → PORT_UNREACHABLE（若配置了代理 → PROXY_FAILED）
  - `SSLHandshakeException` / `SSLPeerUnverifiedException` / 含 "CertPath" 的 SSL 异常 → TLS_CERT_FAILED
  - HTTP 401/403 → AUTH_FAILED；HTTP 5xx / 解析失败 / RPC ok=false → PROTOCOL_ERROR
- **版本兼容**：探测成功后解析 `host.describe.version`（semver 容错解析），与 App 内置 `MIN_DSH_VERSION` 比较；低于最低版本 → VERSION_MISMATCH，不进入 Connected。`MIN_DSH_VERSION` 默认取宽松值 `0.1.0`（当前协议族均可连），实现时若协议考古发现实际下界更高则收紧；版本号不可解析时宽容放行（不阻断，仅记录）。
- **重连策略**：
  - 可恢复错误（DNS/PORT/TLS/PROXY/PROTOCOL）：指数退避 3→6→12→24→30s 封顶（沿用现状）；PORT/DNS 走快速档 3→6→9s 封顶。
  - 不可恢复错误（AUTH_FAILED / VERSION_MISMATCH）：停止自动重连，UI 持久横幅提示（改配置 / 升级）。
- **网络切换重连**：`ConnectivityManager.registerDefaultNetworkCallback`：`onAvailable`（新网络）→ 立即重置退避并重试；`onLost` → 标记断网状态（不误报错误码）。回调在 `connect()` 时注册、`disconnect()` 时注销，仅当前活跃连接持有。

### 5.5 ConnectScreen UI 重构

结构（自上而下）：

1. **当前活跃主机卡片**：备注名 + 地址 + 状态灯（已连接/连接中/错误码文案/已停止重连），点击进入编辑页。
2. **连接配置列表**：所有已保存主机（按 lastUsedAt 倒序）。每行：备注名、地址、上次错误码徽标（若有）、「使用中」标记。点击 = 一键切换（确认弹窗若当前已连接且目标不同）。长按菜单：编辑 / 重命名 / 删除 / 设为自动连接。
3. **添加**：右上扫码（解析结果自动填地址并新建配置，若地址已存在则更新该配置）+ 底部「添加主机」按钮（手输地址 + 可选备注）。
4. **测试连接**（编辑页内）：逐项执行 DNS 解析 → TCP 连接 → TLS 握手 → host.describe 版本探测，每项显示 ✓/✗ 与耗时，失败项给出错误码原因。
5. **配置编辑页**（新增 HostProfileScreen）：备注、地址、信任自签名开关（⚠ 警示说明）、导入 CA 证书（系统文件选择器，复制到 App 私有目录）、代理（类型/主机/端口/账号/密码）、自动连接开关、保存/删除。
6. **错误横幅**：连接失败时在连接页顶部显示「错误码 → 中文原因 → 排查建议」（见 §6 文案表）；AUTH_FAILED/VERSION_MISMATCH 显示「已停止自动重连」+ 对应操作按钮。

### 5.6 DshConnectionService 适配

- 服务改为读 `activeProfileId` 对应配置启动 watcher（`active` 为空或该配置无 → stopSelf）。
- 常驻通知文案沿用状态流（`State.Error` 增加错误码展示）。
- 配置切换时（activeProfileId 变化）：重启 watcher（断开旧连接、连接新配置）；去重集合（approval/question）与完成防抖按主机隔离清空。
- 服务与 App 主连接各自持有 `DshConnection`，均经同一 `OkHttpClientFactory`（按 profile 缓存，共享连接池）。

### 5.7 二维码与协议不变

- 扫码内容格式不变（`dsh-remote-start.ps1` / 远程控制插件生成的地址即当前格式）。
- RPC 信封、端点、事件类型全部不变（S1 不涉及协议扩展）。

## 6. 错误码与文案表

| 错误码 | 中文原因 | 排查建议 |
| :-- | :-- | :-- |
| DNS_UNREACHABLE | 域名无法解析 | 检查地址拼写；局域网场景改用 IP |
| PORT_UNREACHABLE | 端口不可达（连接被拒绝/超时） | 确认 PC 端 DSH 已启动、端口正确、防火墙放行 |
| TLS_CERT_FAILED | HTTPS 证书校验失败 | 若为自签名证书，在本主机配置里开启「信任自签名」或导入其 CA |
| AUTH_FAILED | 凭证无效（401/403） | 重新获取 Token 或检查服务器鉴权配置 |
| VERSION_MISMATCH | 移动端与 PC 端版本不兼容 | 升级 DSH 或本 App；具体版本号显示在横幅 |
| PROXY_FAILED | 代理不可达 | 检查代理地址/端口/账号，或关闭该主机的代理 |
| PROTOCOL_ERROR | 服务响应异常 | 确认地址指向 DSH web 服务；导出日志排查 |
| UNKNOWN | 未知错误 | 导出日志排查 |

## 7. 迁移与兼容

- 旧单地址数据自动迁移（§5.2），升级后用户无感。
- S1 完成时所有读取点（ConnectScreen / DshConnectionService / MainActivity）已切换为新模型，旧 `server_url`/`auto_connect` key 与 `ConnectionConfig` 类随 S1 一并移除（不留过渡读取）。
- 对旧版 DSH 主机（host.describe 无版本字段或版本不可解析）保持可连（宽容版本检查）。

## 8. 测试与验收

1. 编译：`:app:compileDebugKotlin`、`:app:assembleDebug` 通过。
2. 真机验收清单：
   - 多主机：保存 ≥3 条配置（含备注），切换即时生效，后台通知跟随当前主机。
   - 历史：列表按最近使用排序，上次错误码徽标正确。
   - 错误场景复现：错域名（DNS）、错端口（PORT）、自签 HTTPS（TLS_CERT_FAILED→开启信任后连通）、版本不兼容（VERSION_MISMATCH，用旧版 DSH 或 mock 验证）。
   - 网络切换：WiFi↔蜂窝切换后 ≤3s 自动恢复连接（静默）。
   - 代理：HTTP/SOCKS5 各验证一次（含账号密码）。
   - 迁移：装旧版连过一次再升级 → 旧地址出现在配置列表首位且自动连接。
   - 二维码扫码 → 新配置自动创建。
3. 回归：审批/问答通知、任务完成通知、会话操作全流程不受影响。

## 9. 风险与开放问题

| 项 | 说明 | 处置 |
| :-- | :-- | :-- |
| MIN_DSH_VERSION 取值 | 协议兼容下界需按 App 实际依赖确定 | 实现时从 dsh-client-connection 兼容性确认，先取宽松值 |
| 代理密码明文 | DataStore 明文存储 | TODO-S3 迁 Keystore |
| 信任自签名的安全暴露 | 跳过校验仅限该主机 | UI 常驻 ⚠ 警示徽标 |
| 网络回调的机型差异 | 部分 ROM 回调延迟 | 保留退避兜底（不依赖回调才能恢复） |
| 服务/主连接双实例 | 两份 DshConnection 共享客户端工厂 | 连接池复用，切换时统一经由 activeProfileId 驱动 |

## 10. 影响文件清单

| 文件 | 动作 |
| :-- | :-- |
| `data/HostProfile.kt` | 新增（模型 + ConnectionErrorCode） |
| `data/OkHttpClientFactory.kt` | 新增（证书/代理客户端工厂） |
| `data/SettingsStore.kt` | 改（profiles 存储 + 迁移） |
| `data/DshConnection.kt` | 改（connect(profile)、错误分类、重连策略、网络监听） |
| `ui/screens/ConnectScreen.kt` | 改（主机卡片/列表/切换/横幅） |
| `ui/screens/HostProfileScreen.kt` | 新增（配置编辑/测试连接/CA 导入） |
| `service/DshConnectionService.kt` | 改（activeProfileId 驱动 watcher） |
| `MainActivity.kt` | 改（导航接入编辑页） |
