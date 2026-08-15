# harness-remote — 第三方 DeepSeek Harness 手机遥控端

> ⚠️ **非官方项目**：本仓库是个人开发的第三方客户端，与 DeepSeek 官方无关，未使用其商标、未经授权背书，仅供个人学习与自用。

把 DeepSeek Harness 装进口袋：手机 App 连接你电脑上正在运行的 DSH 服务，
随时随地给智能体派任务、看进展、批审批。UI 复刻 DSH 深色风格，交互参考 Trae 移动端。

## 功能

- **远程操控**：连接 PC 上的 DSH（局域网直连 或 cpolar 内网穿透，任何网络可用）；连接页右上角**扫码自动连接**（扫描 DSH「远程控制」插件生成的二维码，含相机权限申请）
- **首页新对话**：输入任务一键创建会话（可选 agent preset）
- **实时聊天流**：用户/助手消息、流式输出（思考过程不上屏，仅显示"已思考 x 秒"）、Markdown 渲染、工具调用卡片（含参数与结果）
- **审批与问答**：审批横幅（允许一次/拒绝）、问答题卡片（单选/多选）
- **后台审批提醒**：App 在后台时，桌面端请求审批/确认会自动推送高优先级横幅通知（前台服务常驻连接，点通知回到 App；设置里可关）
- **模型与权限**：会话内切换模型 / 思考程度（reasoningEffort）、审查严格度（只读 / 工作区可写 / 完全访问）
- **图片消息**：📎 选择图片（PNG/JPEG/WebP/GIF ≤4MB）随消息发送，新对话与历史会话均支持
- **技能选择**：📚 技能列表（skill.list），点选自动插入"请使用 X 技能"到输入框
- **外观自定义**：设置里可选本地背景图（透明度可调）+ 屏幕亮度（夜间模式）
- **会话管理**：最近会话列表、历史分页加载、长按归档 / 已归档区恢复、状态指示、停止运行、⚡ 插话发送（打断思考，steer 模式）
- **DSH 风格深色主题**：AMOLED 友好色板（#0D1B2A 底 / 品牌蓝强调）

## 更新日志

- **v1.0.9**：后台审批提醒——前台服务常驻只读连接，App 在后台时收到审批/确认事件自动推送高优先级横幅通知（设置页可开关）
- **v1.0.8**：修复 agent preset 列表只显示 cordis（解析 `presets` 字段）；新对话支持图片上传
- **v1.0.7**：关于页版本号改为动态读取（修复一直显示 1.0.4 的问题）
- **v1.0.6**：修复打开历史会话卡死/闪退（解码移到后台线程 + 首屏分页减小 + 实时事件防护）
- **v1.0.5**：连接页右上角扫码自动连接（相机权限 + zxing）
- **v1.0.4**：技能选择 + 背景图/亮度自定义
- **v1.0.3**：思考过程不上屏，仅显示"已思考 x 秒"；随仓库附 APK

## 安装（APK）

- 最新构建产物：[release/DSH-Remote-v1.0.9.apk](release/DSH-Remote-v1.0.9.apk)（debug 签名，Android 10+）
- 传到手机 → 允许「未知来源」安装 → 填服务器地址 → 连接

## PC 端使用

```powershell
cd "E:\AI搓的小东西\DSH-Mobile"

# 局域网模式（手机与电脑同一 Wi-Fi）
.\dsh-remote-start.ps1
# 控制台会显示局域网地址，如 http://192.168.1.100:8787

# cpolar 穿透模式（任何网络；先装 cpolar 并登录）
.\dsh-remote-start.ps1 -Mode cpolar
# 默认自动探测桌面版 DSH 端口；把 cpolar 输出的 https://xxxx.cpolar.top 填进 App
```

> 为什么 cpolar 模式用 `-host-header=localhost:<port>`：DSH 的 `/api` 有浏览器信任围栏
> （Host 必须是 loopback 或 trusted-host），重写 Host 后所有请求视为本机发出。

## 协议（逆向自 dsh-client-connection，仅个人学习用途）

- 上行：`POST /api/<method>`，body = `{"type":"client-request","rpcId","method","payload"}`
- 下行：`{"type":"server-response","rpcId","result":{ok, value|error}}`
- 事件流：WebSocket `ws://host/api/events.mux`（另 `events.host`），每条消息 = server-request 信封，
  payload 为 mux 帧（session/event、approval/requested、question/requested、session/jobs…）；
  部分服务器（dsh web CLI）用 SSE 承载，App 自动回退
- 应答：`POST /api/respond`，body = client-response（审批 outcome / 问答 answers）

## 从源码构建

环境：JDK 17 + Android SDK（platform 36）+ Gradle 8.14.3。

```powershell
$env:JAVA_HOME = "C:\Users\Administrator\.dsh-tools\jdk\jdk-17.0.20+8"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
C:\Users\Administrator\.dsh-tools\gradle-dist\gradle-8.14.3\bin\gradle.bat -p . assembleDebug
```

或用 Android Studio 打开本目录直接构建（Gradle wrapper 的 distributionUrl 已指向腾讯镜像）。

## 目录结构

```
app/src/main/java/com/dsh/mobile/
├── data/            # 协议层：DshProtocol（四象限信封/帧）、DshConnection（RPC+WS/SSE）、SettingsStore
├── ui/
│   ├── components/  # MarkdownText 轻量 Markdown 渲染
│   ├── navigation/  # 路由：connect → home → session / settings
│   ├── screens/     # Connect / Home / Session / Settings
│   └── theme/       # DSH 深色主题
├── service/         # 前台服务骨架（后续接推送）
├── MainActivity.kt
└── DshApplication.kt
dsh-remote-start.ps1 # PC 端一键启动脚本（lan / cpolar 两种模式，自动探测桌面版端口）
```

## 已知限制与路线图

- 会话消息分页加载（每页 10 条 + 「加载更早」，首屏更快），暂未做全量历史
- 图片消息仅支持 DSH 协议核心接受的图片内容块（PNG/JPEG/WebP/GIF）
- 权限审批只支持「允许一次 / 拒绝」两个决策（与 web 端一致）
- 后续：推送通知云端化（App 被杀也能收到，需接三方推送）、工作区管理
