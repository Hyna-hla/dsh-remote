# dsh-remote-access v1.1.2 — 微信 iLink 桥 + cpolar 备选

设置页「远程控制」插件：把 **微信 iLink Bot（腾讯官方 ClawBot 通道）** 作为 DSH 远程连接的主要方式，
cpolar 公网隧道降级为「网页版」备选。

## 功能

- **微信遥控（主要方式）**
  - 设置页一键生成微信登录二维码（官方 iLink 协议，ilinkai.weixin.qq.com）
  - 扫码确认后，在微信里给 bot（自己给自己发消息）发文字即可遥控 DSH
  - 消息注入专用 DSH 会话（会话名「微信遥控」），助手回复**流式**回传微信
  - 思考期间显示「对方正在输入中」；不回传思考过程，只回传结果与关键节点（🔧 工具名，同回合去重汇总不刷屏）
  - 工具需要审批时微信收到请求，回复 同意 <id> / 拒绝 <id> 即可
  - 微信发图片自动转给 DSH 看图；语音转文字；长回复自动分片
  - 登录成功主动推送一条欢迎与命令提示；发送失败自动重试一次
  - 凭证持久化：重启 DSH 自动恢复，无需重新扫码；会话过期（errcode -14）自动重新出码
  - 命令：/状态 查看连接信息、/断开 断开、/帮助 命令说明
  - 默认仅绑定扫码的微信号（白名单，防止朋友发消息误触发）
- **网页版隧道（备选）**：cpolar 公网隧道（https，网络层端到端加密）
  - **一键安装 cpolar**：无需手动下载 —— 首次使用点「一键安装」，插件自动从 cpolar 官网拉取并解压到
    `$DSH_HOME/remote-access/cpolar/`（含下载/解压进度），仍兼容旧的 `E:\coplar\cpolar.exe` 与系统 PATH
  - **注册引导 + authtoken**：一键打开 cpolar 注册页 / token 页，粘贴 token 即保存（等价 `cpolar authtoken`），
    自动识别 `~/.cpolar/cpolar.yml` 判断登录态与账号邮箱
  - 二维码由插件**本机生成**（Node qrcode 库，数据不出本机、不依赖第三方 API、秒出图）
  - cpolar 二进制与 DSH 端口探测带 30s/60s 缓存，设置页轮询与「生成地址」更快
  - 地址可直接点击、一键复制
- **移动端 App 配对确认（S3）**：手机 App 首次连接本电脑时，设置页会注入全局全屏确认对话框（允许/拒绝），
  允许后写入 `$DSH_HOME/remote-access/paired.json`；设置页「配对管理」小节可查看已配对设备并逐项撤销。
- **移动端文件只读预览（S6）**：工作区面板浏览 PC 目录时同时列出文件，点文件在手机端只读预览——
  文本等宽展示（>1MB 截断并提示）、二进制识别（base64 大小提示，不解码）；全程只读，不提供写入。
  路由：`GET /api/remote-access/fs/read`（内容）+ `GET /api/remote-access/fs/list`（目录+文件列举）。

## 更新日志

- **v?.?.?**：S6 文件内容只读预览 —— 新增 `GET /api/remote-access/fs/read?path=<abs>`（只读，
  1MB 截断 `truncated: true`；非 UTF-8/含 NUL → `isBinary: true` + base64 `data` 字段；读取优先
  注入的 fs 服务、回退 node:fs，大文件/二进制自动走 node:fs 读头部，不整读大文件）；
  `fs/list` 增强返回 `files[]`（`{name, path, size, hidden}`，保留 `dirs[]` 兼容旧 App）
- **v?.?.?**：S3 安全底座 —— 移动端首次配对确认：新增 `pair/request` / `pair/status` / `pair/respond` /
  `pair/list` / `pair/revoke` 路由，配对记录持久化到 `$DSH_HOME/remote-access/paired.json`（120s 超时自动清除）；
  设置页新增「配对管理」小节（已配对设备列表 + 撤销）；pending 时注入全局全屏确认对话框（允许/拒绝）
- **v1.2.1**：装机自检 —— DSH 启动后若检测到 cpolar 未安装，延迟 10s 后台预下载，
  打开设置页时大概率已就绪；已装旧版（E:\coplar / PATH）则跳过，不重复下载
- **v1.2.0**：cpolar 一键供应 —— 插件内自动下载/解压 cpolar（无需手动安装）、注册引导 + authtoken
  保存、登录态识别（`~/.cpolar/cpolar.yml`）；路由注册改挂 `ctx.effect`（热重载不再残留路由，
  `dev_reload_package` 可直接热更新）
- **v1.1.2**：本地二维码生成（移除 api.qrserver.com 外链，数据不出本机）；cpolar/DSH 端口探测缓存；
  微信桥工具调用同回合去重汇总（不再逐条刷屏）；登录成功主动问候；发送失败自动重试；新增 /帮助 命令
- **v1.1.1**：修复「apiProxy 服务不可用」——DSH 升级后 api-gateway 挂载晚于 webServer，
  apply 时一次性 ctx.get("apiProxy") 会拿到 undefined 并永久缓存；改为每次调用惰性解析，
  并把「服务未挂载」与「sessions 缺少方法」两种失败分开报错
- **v1.1.0**：微信 iLink 桥 + cpolar 备选的首个完整版本

## 技术要点

- 协议客户端自研（lib/ilink.js），Node 20 可用（官方 SDK 要求 Node ≥ 22，故未引用）
- 二维码生成依赖 qrcode npm 包（本插件新增依赖，需安装到 profile）
- 协议依据：protocol-spec.md（从 Tencent/openclaw-weixin 与 corespeed-io/wechatbot 源码逐字段提取，
  真实路径带 /ilink/bot/ 前缀、隐藏头 iLink-App-Id/iLink-App-ClientVersion、
  X-WECHAT-UIN = base64(十进制随机 uint32)、AES-128-ECB 媒体加密等）
- DSH 侧走官方网关：ctx.apiProxy.sessions.create/prompt（与网页前端同一条路径）
- 审批：approval/request 事件 prepend 应答器，仅接管微信绑定会话
- 状态文件：$DSH_HOME/remote-access/wx-state.json（凭证）、wx-config.json（绑定会话/白名单）、paired.json（移动端已配对设备）

## 部署

本仓库即插件包。替换 profile 里的两个副本：

    C:\Users\Administrator\.dsh\profiles\web\vendor\dsh-remote-access\lib\
    C:\Users\Administrator\.dsh\profiles\web\node_modules\dsh-remote-access\lib\

新增依赖需安装到 profile（本插件运行时用 qrcode 生成二维码）：

    cd C:\Users\Administrator\.dsh\profiles\web
    pnpm add qrcode

然后**重启 DSH**（宿主插件代码生效）并刷新页面。

S3 移动端配对确认（`pair/*` 路由 + 全局确认对话框）同样随上述两处 lib 副本替换 + 重启 DSH 生效，
无需新增依赖；配对记录保存在 `$DSH_HOME/remote-access/paired.json`。

## 文件

- lib/ilink.js — 微信 iLink Bot API 客户端（扫码登录、长轮询、发送、typing、AES 媒体上传下载）
- lib/bridge.js — 微信 ↔ DSH 桥（会话注入、流式回传、审批应答、白名单、工具聚合、重试）
- lib/cpolar.js — cpolar 供应模块（一键下载/解压、注册引导、authtoken、隧道进程与状态）
- lib/index.js — 插件宿主入口（HTTP 路由、本地二维码端点、cpolar 接口、探测缓存、移动端配对路由）
- lib/client.js — 设置页 UI（微信卡片 + cpolar 卡片，含安装向导与登录向导 + 配对管理 + 全局确认对话框）
- protocol-spec.md — 协议精确报文规范（源码出处齐全）
