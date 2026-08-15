# harness-remote — 第三方 DeepSeek Harness 手机遥控端

> ⚠️ **非官方项目**：本仓库是个人开发的第三方客户端，与 DeepSeek 官方无关，未使用其商标、未经授权背书，仅供个人学习与自用。

把 DeepSeek Harness 装进口袋：手机 App 连接你电脑上正在运行的 DSH 服务，
随时随地给智能体派任务、看进展、批审批。UI 对齐桌面端 DSH 风格，交互参考 Trae 移动端。

## 功能

- **远程操控**：连接 PC 上的 DSH（局域网直连 或 cpolar 内网穿透，任何网络可用）；连接页右上角**扫码自动连接**（扫描 DSH「远程控制」插件生成的二维码，含相机权限申请）
- **通知提醒（前台服务常驻）**：需要**审批 / 问答确认**时高优先级横幅通知（去重、点击直达对应会话）；**任务完成**自动提醒（运行→空闲 8 秒防抖确认）；Android 13+ 首次启动自动申请通知权限，设置页可补授权与开关
- **实时聊天流**：用户/助手消息、流式输出（思考过程不上屏，仅显示"已思考 x 秒"）、Markdown 渲染、工具调用卡片（含参数与结果）
- **审批与问答**：审批横幅（允许一次/拒绝）、问答题卡片（单选/多选）
- **自适应模型**：设置里可开「自适应模型」（默认开）——短问答自动走 Flash，复杂任务（长文本或命中"分析/重构/审查"等关键词）自动切 Pro，发送前完成切换
- **模型与权限**：会话内切换模型 / 思考程度（reasoningEffort）、审查严格度（只读 / 工作区可写 / 完全访问）
- **可拓展主题**：内置 DeepSeek 经典深蓝 / 纯黑（AMOLED）/ 暖白（护眼米白）三套；支持导入**自定义主题包**（zip，含 theme.json + 可选预览图），同名导入即**热替换更新**（无需重启），设置页见缩略图/版本/删除；单文件 theme.json 也可直接导入
- **背景图与面板**：设置 → 外观可选本地背景图 + 四档一键预设（通透玻璃/电影质感/纯净原图/柔和梦境）；图像不透明度/模糊/饱和度/蒙层浓度独立可调，**面板通透**玻璃化（对齐桌面 dsh-beautify 模板）；屏幕亮度 = 夜间模式
- **图片消息**：📎 选择图片（PNG/JPEG/WebP/GIF ≤4MB）随消息发送，新对话与历史会话均支持
- **技能选择**：📚 技能列表（skill.list），点选自动插入"请使用 X 技能"到输入框
- **会话管理**：最近会话列表、历史分页加载、长按归档 / 已归档区恢复、状态指示、停止运行、⚡ 插话发送（打断思考，steer 模式）、新对话工作区选择

## 更新日志

- **v1.0.18**：视觉对齐官方 DeepSeek Harness 移动端——启动图标改为深蓝底白鲸（母鲸/幼鲸负空间，灵感来自官方标识，非官方客户端声明保留）；连接页品牌区与文案对齐官方引导页风格（大标题 + 说明 + 主按钮 + 底部版本声明）
- **v1.0.17**：冷热分离与传输优化——会话历史/会话列表 gzip 磁盘缓存（秒开秒显，网络失败降级显示缓存并提示）；流式/翻页后延迟写盘；HTTP 传输沿用 OkHttp 透明 gzip
- **v1.0.15**：可拓展主题系统——内置三主题注册表化，支持导入 zip 主题包（theme.json + 预览图 + 说明），同 id 导入 = 热替换更新，运行时即时生效；设置页主题管理（缩略图/版本/删除）；Play 上架准备（上传密钥、签名 AAB、隐私政策与上架清单见 docs/）
- **v1.0.14**：通知功能修复——Android 13+ 运行时权限申请（此前无权限通知静默丢失）、审批/问答去重、**任务完成提醒**、点击通知**深链直达会话**；主题改为三色（深蓝/纯黑/暖白）；背景图性能优化（按屏幕采样解码 + 单次缓存，不再每次重组整图重解码）；新增自适应模型（按任务难度自动切 Flash/Pro）；PC 端插件 dsh-remote-access v1.1.2（本地二维码、探测缓存、微信桥优化）
- **v1.0.13**：毛玻璃面板与背景图增强——模糊/饱和度/蒙层/面板通透度、四档一键预设，对齐桌面 dsh-beautify 模板；背景图默认满清晰度显示
- **v1.0.12**：修复会话记录加载慢——首屏历史 10→3 条、chunk 线性合并去重、loadMore 5 条/页
- **v1.0.11**：新对话工作区选择、双主题、TG/X 风格美化、重连指数退避、Markdown 渲染缓存
- **v1.0.10**：性能修复——打开会话不再卡几秒（响应体读取+JSON 解析整体移出主线程）；流式输出改为 50ms 批量更新且长回复只渲染尾部；自动滚动仅在底部时跟随；打开运行中会话不再丢失流式内容
- **v1.0.9**：后台审批提醒——前台服务常驻只读连接，App 在后台时收到审批/确认事件自动推送高优先级横幅通知
- **v1.0.8**：修复 agent preset 列表只显示 cordis；新对话支持图片上传
- **v1.0.7**：关于页版本号改为动态读取
- **v1.0.6**：修复打开历史会话卡死/闪退
- **v1.0.5**：连接页右上角扫码自动连接（相机权限 + zxing）
- **v1.0.4**：技能选择 + 背景图/亮度自定义
- **v1.0.3**：思考过程不上屏，仅显示"已思考 x 秒"；随仓库附 APK

## 安装（APK）

- 仓库内提供 R8 精简版：[release/DSH-Remote-v1.0.15-min.apk](release/DSH-Remote-v1.0.15-min.apk)（已签名，约 4MB，可直接安装）
- **debug 主包（约 60MB，与历次安装包一致）**：因 GitHub 单文件 >50MB 限制不进仓库，本地留档于 `release/DSH-Remote-v1.0.15.apk`（构建产物亦在 `app/build/outputs/apk/debug/`）；分发走 GitHub Release 附件或 Google Play
- 传到手机 → 允许「未知来源」安装 → 填服务器地址 → 连接
- Google Play 上架包（已签名 AAB）：[release/DSH-Remote-v1.0.15.aab](release/DSH-Remote-v1.0.15.aab)；上架清单见 [docs/play-listing.md](docs/play-listing.md)
- 自定义主题包格式与示例：[docs/theme-package-format.md](docs/theme-package-format.md)、[docs/aurora.dshTheme.zip](docs/aurora.dshTheme.zip)

## PC 端使用

    cd "E:\AI搓的小东西\harness-remote"

    # 局域网模式（手机与电脑同一 Wi-Fi）
    .\dsh-remote-start.ps1
    # 控制台会显示局域网地址，如 http://192.168.1.100:8787

    # cpolar 穿透模式（任何网络；先装 cpolar 并登录）
    .\dsh-remote-start.ps1 -Mode cpolar
    # 默认自动探测桌面版 DSH 端口；把 cpolar 输出的 https://xxxx.cpolar.top 填进 App

> 为什么 cpolar 模式用 -host-header=localhost:<port>：DSH 的 /api 有浏览器信任围栏
> （Host 必须是 loopback 或 trusted-host），重写 Host 后所有请求视为本机发出。

## PC 端插件：微信遥控（dsh-remote-access）

除手机 App 外，仓库还附 PC 端 DSH 插件 dsh-remote-access/：在 DSH 设置页「远程控制」扫码绑定
微信 iLink Bot 后，直接在**微信里给自己发消息**即可遥控 DSH —— 消息注入专用会话、回复流式回传、
审批在微信里回复 同意/拒绝 处理、支持图片与语音转文字。二维码由插件**本机生成**（不经过第三方），
cpolar/DSH 端口探测带缓存，通道生成更快。详见
[dsh-remote-access/README.md](dsh-remote-access/README.md) 与
[dsh-remote-access/protocol-spec.md](dsh-remote-access/protocol-spec.md)。

## 协议（逆向自 dsh-client-connection，仅个人学习用途）

- 上行：POST /api/<method>，body = {"type":"client-request","rpcId","method","payload"}
- 下行：{"type":"server-response","rpcId","result":{ok, value|error}}
- 事件流：WebSocket ws://host/api/events.mux（另 events.host），每条消息 = server-request 信封，
  payload 为 mux 帧（session/event、approval/requested、question/requested、session/jobs…）；
  部分服务器（dsh web CLI）用 SSE 承载，App 自动回退
- 应答：POST /api/respond，body = client-response（审批 outcome / 问答 answers）

## 从源码构建

环境：JDK 17 + Android SDK（platform 36）+ Gradle 8.14.3（wrapper 已指向腾讯镜像）。

    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot"
    Set-Location "E:\AI搓的小东西\harness-remote"
    .\gradlew.bat assembleRelease
    # 产物 app/build/outputs/apk/release/app-release-unsigned.apk
    # 用 apksigner（debug.keystore，密码 android）签名后安装

或用 Android Studio 打开本目录直接构建。

## 目录结构

    app/src/main/java/com/dsh/mobile/
    ├── data/            # 协议层：DshProtocol（四象限信封/帧）、DshConnection（RPC+WS/SSE）、SettingsStore
    ├── ui/
    │   ├── components/  # MarkdownText 轻量 Markdown 渲染
    │   ├── navigation/  # 路由：connect → home → session / settings（含通知深链跳转）
    │   ├── screens/     # Connect / Home / Session / Settings
    │   └── theme/       # 三主题（深蓝/纯黑/暖白）+ 面板通透玻璃化
    ├── service/         # 前台服务：审批/问答/完成通知 + 断线重连
    ├── MainActivity.kt
    └── DshApplication.kt
    dsh-remote-start.ps1 # PC 端一键启动脚本（lan / cpolar 两种模式，自动探测桌面版端口）
    dsh-remote-access/   # PC 端微信遥控插件（iLink Bot 桥 + cpolar 隧道，含协议文档）

## 已知限制与路线图

- 会话消息分页加载（首屏 3 条 + 「加载更早」5 条/页），暂未做全量历史
- 图片消息仅支持 DSH 协议核心接受的图片内容块（PNG/JPEG/WebP/GIF）
- 权限审批只支持「允许一次 / 拒绝」两个决策（与 web 端一致）
- 后续：推送通知云端化（App 被杀也能收到，需接三方推送）、远程通道访问鉴权（token）
