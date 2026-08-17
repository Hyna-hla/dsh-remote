# dsh-remote-access

DSH 远程互信认证插件（v2.4.1）。设置页「远程控制」与安卓 App「DSH Remote」配对使用：**只做远程互信**——配对码快速配对、首次配对确认（旧式兜底）、已配对设备管理、公网域名信任白名单（v2.4.1）、主机设备信息、只读目录/文件辅助、MCP 枚举、远程通道 Bearer token 鉴权。

> v2.0.0 起移除：微信 iLink 桥（扫码登录、微信遥控、审批回传）与 cpolar 隧道供应（一键安装/注册/authtoken/隧道管理），以及配套的二维码生成路由。公网访问请自行使用任意内网穿透工具（cpolar / cloudflared / ZeroTier / 自建隧道等），把 DSH 端口映射到公网后填入 App——通道鉴权对任意隧道同样生效。

## 安装

bundle 形态：把本包加入 profile 即随 DSH web 启动。

```
# 方式一：从 npm 安装（发布到 npm 后）
dsh plugin --profile web add dsh-remote-access

# 方式二：本地打包 tgz 安装
npm pack
dsh plugin --profile web add ./dsh-remote-access-2.4.1.tgz
```

无任何运行时依赖（v1.x 的 qrcode 依赖已随微信桥/隧道移除）；运行时 peer 依赖（`@deepseek-ai/cordis` / `@deepseek-ai/dsh` / `@deepseek-ai/dsh-tools`）由 DSH 宿主提供。

## 功能

### 远程互信认证（核心）

1. **配对码快速配对（推荐，v2.1.0）**：设置页点「生成配对码」得到 6 位随机码（10 分钟有效、最多 5 次试错、验证通过立即作废、常量时间比较），手机 App 连接本机后输入该码即完成配对并直接拿到通道 token——不需要有人守在电脑前点确认。
2. **首次配对确认（旧式兜底）**：手机 App 也可选择「等待 PC 确认」，设置页弹出确认框（允许/拒绝，120 秒超时），流程与 v1.x 一致。
3. **已配对设备管理**：列表展示、随时撤销；撤销后该设备下次连接需重新配对。
4. **远程通道 token 鉴权**：配对通过后把通道 token 下发到 App（密文落盘），此后 App 的所有 `/api` 请求与 WebSocket（events.mux）都携带 `Authorization: Bearer <token>`，未配对/无 token 的远程请求一律 401。
5. **主机设备信息**：`device/info` 下发主机名、机型、MAC、平台；App 用于设备记录展示与重连 MAC 校验（公网 IP 被回收时不会误连他人主机）。
6. **公网域名信任白名单（v2.4.1）**：设置页可直接增删 DSH 核心 Host 围栏的白名单。手机经公网隧道访问时 Host 是公网域名，默认被核心 403 拒绝；把隧道域名加进白名单后立即生效（无需重启）。实现对齐 DSH ≥0.1.0-rc.7：写入 profile 的 `cordis.patch.yml`，给 `connection`（client-connection）行渲染 `trustedHosts: !!js '[...ctx.webRuntime.trustedHosts, "域名"]'`——把用户条目拼回运行时派生的 LAN 地址与 `--trusted-host` 值（v2.3.0 顶掉 web-runtime 配置的做法会让 `--trusted-host` 静默失效，本版已废弃并自动迁移清理旧条目）。条目为纯「域名」或「域名:端口」（IDN 用 punycode），按 WHATWG 规范化 + 字符集白名单校验（与核心同口径）。

### 只读辅助路由（App 直连）

- `GET /api/remote-access/fs/list?path=<绝对路径>`：目录列举（子目录 + 文件，各最多 200 项）。
- `GET /api/remote-access/fs/read?path=<绝对路径>`：文件只读预览（1MB 截断、二进制识别，二进制返回 base64）。
- `GET /api/remote-access/mcp/list`：MCP 服务与工具枚举（按 `mcp__<server>__<tool>` 聚合）。
- `GET /api/remote-access/device/info`：主机名/机型/MAC/平台。

## 路由总览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/remote-access/pair/request | 发起配对（body: deviceId, deviceName）→ pending / paired |
| GET | /api/remote-access/pair/status | 轮询握手结果（pending / approved / denied / none） |
| POST | /api/remote-access/pair/code/generate | 生成 6 位配对码（10 分钟有效；仅本机可达，不豁免） |
| GET | /api/remote-access/pair/code/current | 读取当前有效配对码（v2.4.1 自动生成，无需手动点生成；仅本机可达，不豁免） |
| POST | /api/remote-access/pair/code/verify | 校验配对码（body: code, deviceId, deviceName）；通过即配对并下发 token |
| GET/POST | /api/remote-access/trusted-hosts | 公网域名白名单增删查（body: action=add/remove, host；仅本机可达，不豁免） |
| POST | /api/remote-access/pair/respond | PC 端批准/拒绝（body: deviceId, outcome；仅本机可达，不豁免） |
| GET | /api/remote-access/pair/list | 已配对设备列表（仅本机可达，不豁免） |
| GET | /api/remote-access/pair/check?deviceId= | 回查配对状态；已配对则下发通道 token |
| POST | /api/remote-access/pair/revoke | 撤销设备（仅本机可达，不豁免） |
| GET | /api/remote-access/device/info | 主机设备信息 |
| POST | /api/remote-access/token/rotate | 轮换通道 token（M4：旧 token 即时失效，返回新 token） |
| POST | /api/remote-access/token/revoke | 吊销 token + 清空配对表（M4：所有设备强制重新配对） |
| GET | /api/remote-access/token/audit | 审计日志（M4：最近 50 条，rotate/revoke） |
| GET | /api/remote-access/fs/list?path= | 只读目录列举 |
| GET | /api/remote-access/fs/read?path= | 只读文件预览（1MB 截断 + 二进制识别） |
| GET | /api/remote-access/fs/stat?path= | 文件/目录元信息（M1：size/mtime/isFile/isDir） |
| POST | /api/remote-access/fs/write | 上传文件（M1：json `{path, content=base64, overwrite?}`；沙箱限 home；返回 path/size） |
| POST | /api/remote-access/fs/mkdir | 建目录（M1：json `{path, recursive?}`） |
| POST | /api/remote-access/fs/delete | 删除（M1：软删进回收站 `trash/`, 返回 trashId） |
| GET | /api/remote-access/mcp/list | MCP 枚举 |

鉴权规则（/api 全量门禁，含 WebSocket 升级）：

- 本机浏览器放行：loopback 远端且无 `X-Forwarded-For`；
- 局域网直连与公网隧道一律要求 `Authorization: Bearer <token>`；
- 引导通道豁免（仅手机侧引导端点白名单）：`pair/request`、`pair/status`、`pair/check`、`pair/code/verify`、`/api/host.describe`；
- v2.1.0 起 `pair/respond`、`pair/list`、`pair/revoke`、`pair/code/generate` **不再豁免**——修复 v1.x 的 self-approve 漏洞（拿到隧道地址的人此前可远程自我批准配对）。

## 双向文件传输与 Token 运维（M1 / M4）

- **双向文件（M1）**：`fs/write`（base64 json）、`fs/mkdir`、`fs/delete`、`fs/stat` 提供手机↔PC 的文件上传/管理；`fs/read` 反向读取预览。**写/删/建目录是破坏性操作，一律沙箱到 `$DSH_HOME` 内**（`resolve`+`relative` 归一化防 `../` 穿越），并额外校验 `overwrite`。均复用通道 Bearer 鉴权，远程未持 token 一律 401。
- **Token 运维（M4）**：`token/rotate`（轮换，旧 token 即时失效）、`token/revoke`（吊销 + 清空配对表，强制所有设备重新配对）、`token/audit`（最近 50 条审计）。均**非豁免**——仅本机设置页或已配对设备（带当前 Bearer）可达。

## 状态文件

位于 `$DSH_HOME/remote-access/`：

- `paired.json`：已配对设备（deviceId/name/at）
- `channel-token`：远程通道 token（首次使用自动生成，48 位 hex）

## 开发

```
node --check lib/index.js lib/client.js   # 语法
node --test smoke-test.mjs                # 离线冒烟：配对全流程 + 门禁 + fs/mcp/device
```

冒烟测试用最小 fake webServer 驱动 `apply()`，经真实 HTTP 服务器跑完整链路，无任何外部依赖。

## 配对码流程（序列图）

```
手机 App                           PC 插件 (dsh-remote-access)           DSH 核心
   │  连接新主机 /pair/request           │                                   │
   │────────────────────────────────→    │  ensureActiveCode() 保底有码       │
   │                                   │──(设置页 /pair/code/current 显示码)──│
   │                                   │                                   │
   │  POST /pair/code/verify {code,deviceId,deviceName}                     │
   │────────────────────────────────→    │  timingSafeEqual + 5 次限定       │
   │                                   │  通过 → 写 paired.json + 发 token  │
   │←────────── {ok, token} ─────────────│                                   │
   │  此后所有 /api 与 events.mux 带 Authorization: Bearer <token>          │
```

### 版本兼容矩阵

| App (DSH Remote) | 插件 (dsh-remote-access) | 说明 |
|---|---|---|
| ≥ v1.3.4 | ≥ v2.1.0 | 配对码快速配对可用；`pair/code/generate\|current\|verify` |
| ≥ v1.3.4 | ≥ v2.4.1 | **推荐**：PC 自动生成配对码 + 公网域名白名单 + 通道 token 门禁 |
| ≥ v1.3.4 | ≤ v2.0.x | 仅旧式「等待 PC 确认」路径（无配对码） |
| < v1.3.4 | 任意 | 无 Bearer token 支持，连新插件会在配对外请求收到 401 |

> ⚠️ App 与插件需**同时升级**；升级插件后需在 PC 端重启 DSH 生效。

## 目录

- lib/index.js — 插件宿主：路由注册 + 配对码/握手状态机 + 通道鉴权门禁
- lib/client.js — 设置页「远程控制」UI：配对码卡片（生成/倒计时）+ 配对确认对话框 + 已配对设备管理
- cordis.patch.yml — bundle 挂载补丁
- smoke-test.mjs — 离线冒烟测试（含配对码全流程与 self-approve 安全回归）
