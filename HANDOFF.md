# dsh-remote 接力开发文档

> 更新于 v1.7.0 发布前（插件 v2.0.0 纯互信化重写）。给接力的人(或下一个会话的我):项目全貌、已完成、未完成、怎么做。

## 项目现状速览

- **仓库**:<https://github.com/Hyna-hla/dsh-remote(原名> harness-remote,旧地址自动重定向)
- **构建环境(本机 Legion)**:JDK 17 + Android SDK 在 `D:\android-env\`(jdk17 / android-sdk),工程在 `D:\harness-remote`
- 构建:`cd /d/harness-remote && JAVA_HOME=D:\android-env\jdk17 ./gradlew.bat assembleDebug --no-daemon`
- **构建环境(本机 Develop,当前会话实测)**:JDK 24 GraalVM 在 `D:\x64\GraalVM\graalvm-jdk-24.0.1+9.1`;Android SDK 在 `D:\SDK`(platforms/android-36 + build-tools 36.x,licenses 已接受);工程在 `D:\Developments\repo\dsh-remote`;构建:`$env:JAVA_HOME='D:\x64\GraalVM\graalvm-jdk-24.0.1+9.1'; .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`(GRADLE_USER_HOME 默认 `~/.gradle`,已有大部分依赖缓存;缺件需外网补齐——dl.google.com/maven central 可直连);`local.properties` 已生成(`sdk.dir=D:/SDK`,gitignored);Gradle 8.14.3 + AGP 8.12.1 + Kotlin 2.1.21 在 JDK 24 上可跑
- 发布流程:改 `app/build.gradle.kts` 版本号 → 构建 → `git push`(直连超时就带 token 走 gh-proxy,见下) → GitHub API 建 Release → uploads.github.com 传 `DSH-Remote-vX.Y.Z.apk` → 拷贝到 `release/`(已 gitignore,>50MB 不进 git)→ Downloads 留一份
- git 推送:全局有 gh-proxy insteadOf 改写,坏的时候 `git config --global --unset url.https://gh-proxy.com/https://github.com/.insteadOf` 推完恢复;或直接用 `https://x-access-token:TOKEN@gh-proxy.com/https://github.com/Hyna-hla/dsh-remote.git`(TOKEN 从 `git credential fill` 取)
- **用户 PC 侧**:DSH web 官方 npm 版跑在 127.0.0.1:3080(`~/.dsh/dsh-web.ps1` 启动);插件 dsh-remote-access 装在 `~/.dsh/profiles/web/`(vendor + node_modules 两份,改代码要同步两份);公网隧道自备(cpolar 模式走 dsh-remote-start.ps1 或 dsh-tunnel-watchdog.ps1 看门狗,cloudflared 走 dsh-cloudflared.ps1,均与插件解耦)

## 已完成(v1.3.1 → v1.3.4)

1. v1.3.2:保险库解锁(dsh-encrypt 联动,`/api/credentials.*`,SHA3-256 纯 Kotlin)、插件 inject 兼容修复、ps1 BOM/防火墙/探测修复、LICENSE(MIT+社区声明)
2. v1.3.3:代码块语言角标+复制、会话首屏骨架屏、发送/审批触感、待办横幅动效、假 Pro 三档划线价修复、隧道看门狗
3. v1.3.4:**远程通道 token 鉴权**(插件 `/api` 全量 Bearer 门禁,含 WS;本机浏览器放行=loopback 且无 XFF;pair/* 与 host.describe 豁免;token 在 `~/.dsh/remote-access/channel-token`,经 pair/check 下发;App 端 ChannelTokenRegistry 动态拦截器热生效)、仓库更名 dsh-remote、应用内更新改 debug 主包优先、README 全面校对
4. v1.7.0:**插件 v2.0.0 纯互信化重写**——删除 lib/ilink.js(微信 iLink 协议客户端)、lib/bridge.js(微信↔DSH 桥)、lib/cpolar.js(cpolar 供应)、protocol-spec.md(微信协议文档)与 qrcode 依赖;保留 pair/* 配对、device/info、fs/list、fs/read、mcp/list、通道 token 门禁(契约形状不变,App 零适配);lib/client.js 设置页仅剩配对管理;smoke-test.mjs 重写为离线冒烟(node --test,零依赖)。App v1.7.0 仅文案对齐(设置页 cpolar 指引改自备穿透、主机地址 placeholder),功能代码不动
5. v1.8.0:**配对码快速配对 + self-approve 漏洞修复**——插件 v2.1.0 新增 pair/code/generate(仅本机)+ pair/code/verify(引导豁免,6 位码/10 分钟/5 次试错/一次性/常量时间比较/通过即配对并直接发 token);豁免面从 pair/* 前缀收紧为白名单(request/status/check/code-verify/host.describe),respond/list/revoke/generate 不再豁免(v1.x 可远程自我批准配对,已修)。App:PairingCoordinator 改为「决策交 UI」(awaitingDecision 状态流),ConnectScreen 配对对话框(配对码优先,「等待 PC 确认」旧式兜底);HttpPairingRpc.codeVerify。插件冒烟 9 例全绿(含配对码全流程与安全回归)

### 重要踩坑记录(别再踩)

- **cordis `ctx.effect(fn)`:fn 立即执行,返回值才是清理器**。写成立即执行体会导致"恢复逻辑"在挂载瞬间运行(鉴权代理表当场被撤,形同虚设)
- npm 版 DSH 的 `Inject.resolve` 只认数组/服务名为键的对象,没有 `{required,optional}` 语义
- `.ps1` 必须 UTF-8 带 BOM,否则 Win PowerShell 5.1 按 GBK 解析炸语法
- `local.properties` 的 sdk.dir 用正斜杠(`D:/...`),反斜杠被 properties 转义吞
- Kotlin Long 字面量:`0x8...uL` 高位常量超有符号范围要 uL
- pnpm `file:` 依赖改 vendor 后 node_modules 不会自动更新,要手动同步或 --force
- client-connection 的 `/api` 路由在插件挂载**之后**惰性注册 → 路由包裹必须用 Proxy set 陷阱兜晚注册
- cpolar 隧道请求带 `x-forwarded-for`/`x-real-ip`/`x-original-host`(实测),据此区分隧道与真本机
- **DSH 沙箱下 `node --test` 直接跑会 spawn EPERM**(测试 runner 默认子进程起测试文件,沙箱禁命名管道)→ 用 `node --test --test-isolation=none smoke-test.mjs` 同进程跑
- 插件零运行时依赖后,同步 `~/.dsh/profiles/web` 只需覆盖 lib/ 与 package.json,不再有 node_modules 变更

## 剩余工作明细

### A. seq 完整水位 — ✅ v1.4.0 已完成

**目标**:打开缓存过的会话只拉增量,不重新分页。

**现状**(`SessionScreen.kt` 的 `SessionChatState`):

- `load()`:先读 HistoryCache(gzip 磁盘)得 `cachedItems`,再 `connection.history(sessionId, maxMessages=3)` 拿最近 3 条,`mergeCachedWithNet` 按 seq 拼接
- `loadMore()` 每页 15 条往老翻;`loadAll()` 循环翻到底
- `HistoryCache`(`data/HistoryCache.kt`):gzip JSON,条目带 seq

**方案**(按此实施):

1. HistoryCache 元数据加 `completeThroughOldestSeq: Long?`(loadAll 到 hasMore=false 时记录当时的 oldestSeq)与 `completeUpToSeq: Long`(缓存最新 seq)
2. 打开会话:缓存有 completeThrough 标记时,跳过「加载更早」按钮(hasMore 仅当 net 首屏 seq 老于缓存缺口);`loadAll` 发现缓存已完整覆盖到某 seq,只从缺口往下翻,不整页重来
3. 合并逻辑复用 `mergeCachedWithNet`(seq 去重已支持)
4. 验证:大会话(200+ 条)二次打开应秒开;新增消息只影响尾部
**注意**:先读 `DshConnection.history()` 确认 RPC 是否支持 after/before 游标;若服务端支持 `afterSeq` 类参数,可直接做真增量(只拉 > maxSeq),优先走这条路

### B. Cloudflare Tunnel 方案 — 部分完成(脚本见 v1.3.5;v2.0.0 起与插件解耦)

- `dsh-cloudflared.ps1`:自动下载 cloudflared(走 gh-proxy 镜像)、quick tunnel 免登录、`--http-host-header` 重写绕信任围栏
- 剩余:账号版固定域名(named tunnel + DNS 路由)需要用户自己 `cloudflared tunnel login`;cloudflared 的 XFF 会触发 token 鉴权(App 已支持,无需改)
- 对比:cpolar 免费版断线换 URL;cloudflare quick tunnel 同样换 URL 但不限带宽、不断流
- 注意:v2.0.0 起 cpolar 供应与微信桥已从插件移除,隧道全部走仓库根脚本(lan/cpolar/cloudflared),插件只负责互信认证

### C. 更多 UI 打磨候选(未做)

- 空状态按主题配 mini 插画(鲸鱼娘/夜之城全息/终端 ASCII)
- strings.xml 抽取(大量中文硬编码在 Composable 里,渐进做)
- 大字号无障碍复查(快捷指令 chip、模型胶囊有截断风险)
- 更新弹窗内嵌渲染 Release body(UpdateChecker 已拿 body 字段,缺 Markdown 渲染)

### D. 桌面小部件 — v1.3.5 已做(连接状态 4x1,RemoteViews 实现,无新依赖)

## 发版检查单

1. `app/build.gradle.kts` versionCode+1 / versionName+1
2. README 更新日志加条目
3. 构建 debug APK → 本地冒烟(装机连隧道)
4. commit + push(gh-proxy 兜底)
5. GitHub API:POST /releases(tag=vX.Y.Z)→ upload asset `DSH-Remote-vX.Y.Z.apk`
6. `release/` 目录归档 + Downloads 副本 + 删旧包
7. PC 端插件有改动时:先 `node --check lib/index.js lib/client.js` + `node --test --test-isolation=none smoke-test.mjs` 全绿,再同步 `~/.dsh/profiles/web` 的 vendor 与 node_modules 两份,重启 DSH,验证 `/api/remote-access/pair/status`
