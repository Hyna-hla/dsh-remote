# dsh-remote — 第三方 DeepSeek Harness 手机遥控端

> ⚠️ **非官方项目**：本仓库是个人开发的社区第三方客户端，基于 DeepSeek Harness 构建，并非 DeepSeek 官方产品，未使用其商标、未经授权背书。项目遵循 [MIT License](LICENSE) 且完全开源免费；如有人向您以任何形式出售本软件，请拒绝交易。

> ✍️ **开发人员**：残星会·虚质空间分部 达妮娅同学（B站：**最喜欢达妮娅了**）。
> APK 签名证书 DN = `CN=残星会虚质空间分部达妮娅同学, OU=残星会, O=虚质空间分部`。

把 DeepSeek Harness 装进口袋：手机 App 连接你电脑上正在运行的 DSH 服务，
随时随地给智能体派任务、看进展、批审批。UI 对齐桌面端 DSH 风格，交互参考 Trae 移动端。

## 功能

- **远程操控**：连接 PC 上的 DSH（局域网直连 或 自备内网穿透，任何网络可用）；连接页右上角**扫码连接**（扫描任意 URL 二维码自动填入服务器地址，含相机权限申请）
- **通知提醒（前台服务常驻）**：需要**审批 / 问答确认**时高优先级横幅通知（去重、点击直达对应会话）；**任务完成**自动提醒（运行→空闲 8 秒防抖确认）；Android 13+ 首次启动自动申请通知权限，设置页可补授权与开关
- **实时聊天流**：用户/助手消息、流式输出（思考过程不上屏，仅显示"已思考 x 秒"）、Markdown 渲染、工具调用卡片（含参数与结果）
- **审批与问答**：审批横幅（允许一次/拒绝）、问答题卡片（单选/多选）
- **自适应模型**：设置里可开「自适应模型」（默认开）——短问答自动走 Flash，复杂任务（长文本或命中"分析/重构/审查"等关键词）自动切 Pro，发送前完成切换
- **模型与权限**：会话内切换模型 / 思考程度（reasoningEffort）、审查严格度（只读 / 工作区可写 / 完全访问）
- **可拓展主题**：内置 5 套有真实观感差异的主题——**深蓝（鲸鱼娘装扮，默认）** / 纯黑（AMOLED）/ 暖白 / **Codex CLI 终端风**（全局等宽 + 方角 + `❯` 提示符）/ **夜之城 2077**（NC 黄霓虹青 + 切角 + 稀有度会话色）；支持导入**自定义主题包**（zip，含 theme.json + 可选预览图），同名导入即**热替换更新**；单文件 theme.json 也可直接导入；另支持**界面字体/代码字体**切换（对齐 dsh-font 概念）
- **背景图与面板**：设置 → 外观可选本地背景图 + 四档一键预设（通透玻璃/电影质感/纯净原图/柔和梦境）；图像不透明度/模糊/饱和度/蒙层浓度独立可调，**面板通透**玻璃化（对齐桌面 dsh-beautify 模板）；屏幕亮度 = 夜间模式
- **图片消息**：📎 选择图片（PNG/JPEG/WebP/GIF ≤4MB）随消息发送，新对话与历史会话均支持
- **技能选择**：📚 技能列表（skill.list），点选自动插入"请使用 X 技能"到输入框
- **应用内更新**：启动自动检查 GitHub Releases（每天最多一次，不打扰）；有新版弹窗询问，下载走多镜像加速（ghfast.top / gh-proxy.com / ghproxy.net / 直连兜底，失败自动切换、全程透明展示进度与速度），**优先下载 debug 主包**（功能最全；无主包时回退 -min 精简包），下载完一键安装
- **保险库解锁（dsh-encrypt 联动）**：设置页「保险库」卡片查看锁定状态、手机端输入密码解锁 PC 端凭证保险库——与 web 端走同一路由（/api/credentials.unlock），密码本地 SHA3-256 后仅上传摘要，解锁后 PC 端同步生效；支持记住密码（AndroidKeyStore 加密存储摘要）、防爆破冷却展示
- **会话管理**：最近会话列表、历史分页加载、长按归档 / 已归档区恢复、状态指示、停止运行、⚡ 插话发送（打断思考，steer 模式）、新对话工作区选择

## 更新日志

> 完整更新历史见 [CHANGELOG.md](CHANGELOG.md)。

- **插件 v2.4.1 / App v1.8.1**（最新）：配对码安全修复 + 四项健壮性改进 + 节点小宝教程，详见 [CHANGELOG.md](CHANGELOG.md) 顶部条目。

## 安装（APK）

- **推荐：应用内更新**——App 启动自动检查 GitHub Releases（每天最多一次），有新版弹窗询问后直接下载安装（多镜像加速、断源自动切换），**优先下载 debug 主包**（约 68MB，功能最全），无主包时回退 -min 精简包
- **手动安装**：从 [Releases](https://github.com/Hyna-hla/dsh-remote/releases) 下载 `DSH-Remote-vX.Y.Z.apk`（debug 主包）；仓库 `release/` 目录另存各版本 R8 精简版 `-min.apk`（约 4.6MB，已签名）
- 传到手机 → 允许「未知来源」安装 → 填服务器地址 → 连接
- ⚠️ v1.2.0 起更换签名证书，旧版（upload.jks 签名）需卸载重装；debug 包与 release 签名包互不兼容，升级请走同一通道
- 自定义主题包格式与示例：[docs/theme-package-format.md](docs/theme-package-format.md)、[docs/aurora.dshTheme.zip](docs/aurora.dshTheme.zip)

## PC 端使用

    # 局域网模式（手机与电脑同一 Wi-Fi）：DSH 启动后，把电脑局域网地址填进 App，形如
    http://192.168.1.100:8787

    # 公网模式：任选内网穿透工具（cpolar / cloudflared / ZeroTier / 自建隧道等），
    # 把 DSH 服务端口映射成公网域名后填进 App。首次连接需在 PC 端 DSH「设置 → 远程控制」
    # 确认配对，之后所有请求自动携带通道令牌，任意网络可用。
    # 无公网 IP 想走国产内网穿透的：用【节点小宝】把 8787 映射成公网地址，配好公网域名
    # 白名单 + 配对码即可，逐步教程见 docs/节点小宝内网穿透教程.md

## PC 端插件：远程互信认证（dsh-remote-access）

除手机 App 外，仓库还附 PC 端 DSH 插件 [dsh-remote-access/](dsh-remote-access/)：**只做远程互信**——
推荐在 DSH 设置页「远程控制」点「生成配对码」，手机 App 连接后输入 6 位码即完成配对（也可沿用旧式
PC 弹窗确认）；配对后远程通道 token 下发到 App，此后所有 /api 与实时通道请求都要求该 token，拿到地址
的陌生人无法遥控你的 DSH；同时提供主机设备信息、只读目录/文件浏览、MCP 枚举等辅助路由。
**手机经公网隧道连接时**，还需把隧道公网域名加进「远程控制 → 公网域名白名单」（写入 DSH 核心
`client-connection.trustedHosts`，重启 DSH 生效）——否则核心 Host 围栏会拒绝 403。
详见 [dsh-remote-access/README.md](dsh-remote-access/README.md)。

> v2.0.0 起不再内置微信遥控与 cpolar 隧道（仓库内的隧道辅助脚本已一并移除）。公网访问用
> 任意自备内网穿透工具即可，通道鉴权对任何隧道同样生效。

## 协议（逆向自 dsh-client-connection，仅个人学习用途）

- 上行：POST /api/<method>，body = {"type":"client-request","rpcId","method","payload"}
- 下行：{"type":"server-response","rpcId","result":{ok, value|error}}
- 事件流：WebSocket ws://host/api/events.mux（另 events.host），每条消息 = server-request 信封，
  payload 为 mux 帧（session/event、approval/requested、question/requested、session/jobs…）；
  部分服务器（dsh web CLI）用 SSE 承载，App 自动回退
- 应答：POST /api/respond，body = client-response（审批 outcome / 问答 answers）

## 从源码构建

环境：JDK 17 + Android SDK（platform 36）+ Gradle 8.14.3（wrapper 已指向腾讯镜像）。

    $env:JAVA_HOME = "<你的 JDK 17 路径>"
    Set-Location <本仓库路径>
    .\gradlew.bat assembleDebug      # debug 主包（应用内更新分发的就是这个）
    .\gradlew.bat assembleRelease    # min 精简签名包

`local.properties` 写 SDK 路径时**用正斜杠**（`sdk.dir=D:/android-sdk`）——反斜杠会被
properties 转义吞掉导致 "文件名、目录名或卷标语法不正确" 启动失败。

发布签名：`local.properties` 指向发布密钥库 `dsh-remote-daniya.jks`
（alias `daniya`；证书 DN = `CN=残星会虚质空间分部达妮娅同学, OU=残星会, O=虚质空间分部`），
构建产物自动签名。旧版 upload.jks 仅保留用于历史版本校验与 Play 上架迁移参考。

或用 Android Studio 打开本目录直接构建。

## 目录结构

    app/src/main/java/com/dsh/mobile/
    ├── data/            # 协议层：DshProtocol（四象限信封/帧）、DshConnection（RPC+WS/SSE）、SettingsStore
    ├── ui/
    │   ├── components/  # MarkdownText 轻量 Markdown 渲染
    │   ├── navigation/  # 路由：connect → home → session / settings（含通知深链跳转）
    │   ├── screens/     # Connect / Home / Session / Settings
    │   └── theme/       # 多主题注册表（深蓝/纯黑/暖白/Codex CLI/夜之城 2077 + 导入主题包）+ 面板通透玻璃化
    ├── service/         # 前台服务：审批/问答/完成通知 + 断线重连
    ├── MainActivity.kt
    └── DshApplication.kt
    dsh-remote-access/   # PC 端 DSH 插件（远程互信认证：配对/设备/fs/mcp 路由 + 通道 token 鉴权；v2.0.0 已移除微信桥与 cpolar）

## 已知限制与路线图

- 会话首屏取最近 3 条 + 「加载更早」15 条/页 + 「加载全部历史」（循环翻页到底）；超大会话全量加载耗时取决于网络
- 图片消息仅支持 DSH 协议核心接受的图片内容块（PNG/JPEG/WebP/GIF），长图不缩放仅采样显示
- 权限审批只支持「允许一次 / 拒绝」两个决策（与 web 端一致）；若服务端审批策略为 never 则不会出现审批请求
- 后续：推送通知云端化（App 被杀也能收到，需接三方推送）

## 皮肤素材署名与许可

鲸鱼娘皮肤素材来自 [dsh-deep-whale](https://github.com/Small-tailqwq/dsh-deep-whale)（深海女仆工坊 maid-atelier）：

- 鲸鱼娘角色原作：**上善**（[Pixiv](https://www.pixiv.net/users/62155430) · [Bilibili](https://b23.tv/8h5L4xz)）
- 女仆鲸鱼娘二次设计：**ZipZipPipe**（[Pixiv](https://www.pixiv.net/users/18604994) · [Bilibili](https://b23.tv/Pnw6nG8)）
- 完整署名链见 [docs/maid-atelier-NOTICE.md](docs/maid-atelier-NOTICE.md)
- 素材以 **CC BY-NC-SA 4.0** 发布（署名-非商业性使用-相同方式共享），本 App 仅个人自用，不用于商业用途
- 蓝鲸配色参考 [dsh-blue-whale](https://github.com/starslittle/dsh-blue-whale)；夜之城主题对齐 [dsh-theme-cyberpunk2077](https://github.com/nicepkg/dsh-theme-cyberpunk2077)；字体方案对齐 [dsh-font](https://github.com/nicepkg/dsh-font)（App 仅引用系统字体族名，不打包字体文件）
