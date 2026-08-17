# dsh-remote-access

DSH 远程互信认证插件（v2.0.0）。设置页「远程控制」与安卓 App「DSH Remote」配对使用：**只做远程互信**——移动端首次配对确认、已配对设备管理、主机设备信息、只读目录/文件辅助、MCP 枚举、远程通道 Bearer token 鉴权。

> v2.0.0 起移除：微信 iLink 桥（扫码登录、微信遥控、审批回传）与 cpolar 隧道供应（一键安装/注册/authtoken/隧道管理），以及配套的二维码生成路由。公网访问请自行使用任意内网穿透工具（cpolar / cloudflared / ZeroTier / 自建隧道等），把 DSH 端口映射到公网后填入 App——通道鉴权对任意隧道同样生效。

## 安装

bundle 形态：把本包加入 profile 即随 DSH web 启动。

```
npm pack
dsh plugin --profile web add ./dsh-remote-access-2.0.0.tgz
```

无任何运行时依赖（v1.x 的 qrcode 依赖已随微信桥/隧道移除）。

## 功能

### 远程互信认证（核心）

1. **首次配对确认**：手机 App 连接本机后发起配对握手，设置页「远程控制」弹出确认框（允许/拒绝，120 秒超时）。
2. **已配对设备管理**：列表展示、随时撤销；撤销后该设备下次连接需重新确认。
3. **远程通道 token 鉴权**：配对通过后经 `pair/check` 把通道 token 下发到 App（密文落盘），此后 App 的所有 `/api` 请求与 WebSocket（events.mux）都携带 `Authorization: Bearer <token>`，未配对/无 token 的远程请求一律 401。
4. **主机设备信息**：`device/info` 下发主机名、机型、MAC、平台；App 用于设备记录展示与重连 MAC 校验（公网 IP 被回收时不会误连他人主机）。

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
| POST | /api/remote-access/pair/respond | PC 端批准/拒绝（body: deviceId, outcome） |
| GET | /api/remote-access/pair/list | 已配对设备列表 |
| GET | /api/remote-access/pair/check?deviceId= | 回查配对状态；已配对则下发通道 token |
| POST | /api/remote-access/pair/revoke | 撤销设备 |
| GET | /api/remote-access/device/info | 主机设备信息 |
| GET | /api/remote-access/fs/list?path= | 只读目录列举 |
| GET | /api/remote-access/fs/read?path= | 只读文件预览 |
| GET | /api/remote-access/mcp/list | MCP 枚举 |

鉴权规则（/api 全量门禁，含 WebSocket 升级）：

- 本机浏览器放行：loopback 远端且无 `X-Forwarded-For`；
- 局域网直连与公网隧道一律要求 `Authorization: Bearer <token>`；
- 引导通道豁免：`/api/remote-access/pair/*`（配对握手）与 `/api/host.describe`（连接探测）。

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

## 目录

- lib/index.js — 插件宿主：路由注册 + 配对状态机 + 通道鉴权门禁
- lib/client.js — 设置页「远程控制」UI：配对确认对话框 + 已配对设备管理
- cordis.patch.yml — bundle 挂载补丁
- smoke-test.mjs — 离线冒烟测试
