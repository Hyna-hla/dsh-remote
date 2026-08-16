# S4 会话与输入体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **流程简化（用户决定）：** 本阶段不做代码审计/评审（无 task review、无 final review、无 fix loop）。每个任务 = 实现 + 测试 + 全流程跑通（`gradlew :app:testDebugUnitTest` 全绿 + `gradlew :app:assembleDebug` 成功）。实现者自测即门禁，逐任务独立 commit。

**Goal:** DSH Remote 会话与输入体验四项能力补全（范围 = 侦察报告 `recon-report.md` 的 T1-T6 全做）：通知渠道精细化、语音输入、会话搜索统一、本地置顶、快捷指令栏、打断（steer）动效反馈。

**Architecture:** 纯逻辑（置顶排序/搜索过滤/快捷指令编解码/通知分流判断）抽为顶层纯函数并假件单测；UI 增量改造（HomeScreen 四分支列表与长按、SessionScreen 输入栏、SettingsScreen 提醒卡片）；通知走既有 3 渠道 + 分渠道开关；语音用系统 `RecognizerIntent`（零新 Gradle 依赖）。**不扩展 PC 端协议**（置顶仅本地持久化，不做服务端删除）。

**Tech Stack:** Kotlin 2.1.21 / Compose BOM 2025.06 / kotlinx-coroutines-test 1.10.2 / JUnit 4.13.2 / Android 系统语音（`android.speech` RecognizerIntent，零新依赖）。

**Spec / 现状依据:** `.superpowers/sdd/2026-08-16-s4-session-input/recon-report.md`（只读侦察报告，含全部 file:line 索引；计划行号如与代码漂移，以报告检索词为准）

## Global Constraints

- **测试命令统一在 `C:\hremote`（ASCII junction → 仓库）下执行**（非 ASCII 路径破坏 Gradle 测试 worker，S1 已证实）。
- **不做评审**：实现者自测（单测 + assembleDebug）即完成门禁；无需 reviewer、无 fix loop。每个任务单独 `git add <本任务文件>` + 单独 commit，禁止 `git add -A` / `git commit -a`。
- **并行改动保护**：工作树中 `app/build.gradle.kts`（versionCode 41 / 1.3.1）与 `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`（超长消息折叠 +31 行）是他方未提交改动，**S4 全程不得提交、不得覆盖、不得回滚**。开工前统一 `git stash push -m "s4-keep-parallel" -- app/build.gradle.kts app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`，收尾后 `git stash pop` 原样恢复。S4 任务若需改这两个文件（SessionScreen.kt 被 T2/T5/T6 触及），基于 stash 后的 HEAD 版本修改，pop 时如冲突以并行改动语义优先（超长折叠逻辑不得丢）。
- 置顶仅本地（SettingsStore key），不依赖 PC 端协议；**不做批量删除**（需服务端协议，明确出范围）。
- 每任务尽可能把可测逻辑抽成顶层/internal 纯函数 + JVM 单测；纯 UI 无逻辑处不强求单测（项目无 Robolectric/Compose UI 测试设施），以 assembleDebug 通过为准。

---

### Task 1: 通知渠道精细化（T1）

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/DshApplication.kt`（渠道定义区 :39-72）
- Modify: `app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt`（`postAlert`/`handle` :150-238）
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`（新增 key）
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SettingsScreen.kt`（提醒卡片 :450-496）
- Test: `app/src/test/java/com/dsh/mobile/data/NotificationPolicyTest.kt`（新增）

**Interfaces:**
- SettingsStore 新增（companion 区 :46-68 之后，模式照抄 `BACKGROUND_NOTIFY_KEY`）：
```kotlin
private val NOTIFY_APPROVALS_KEY = booleanPreferencesKey("notify_approvals")
private val NOTIFY_COMPLETION_KEY = booleanPreferencesKey("notify_completion")
suspend fun setNotifyApprovals(on: Boolean)
suspend fun setNotifyCompletion(on: Boolean)
val notifyApprovals: Flow<Boolean>   // 默认 true
val notifyCompletion: Flow<Boolean>  // 默认 true
```
- 顶层纯函数（供单测，放 `data/NotificationPolicy.kt` 同包 internal）：
```kotlin
/** 审批/问答横幅是否放行：总开关 && 分渠道开关 && 不在前台 */
fun shouldNotifyApproval(backgroundNotify: Boolean, notifyApprovals: Boolean, appInForeground: Boolean): Boolean
/** 完成提醒是否放行：总开关 && 分渠道开关 && 不在前台 */
fun shouldNotifyCompletion(backgroundNotify: Boolean, notifyCompletion: Boolean, appInForeground: Boolean): Boolean
```

- [ ] **Step 1: 写失败测试** `NotificationPolicyTest.kt`：`shouldNotifyApproval`/`shouldNotifyCompletion` 全组合（总开关关→false；分渠道关→false；前台→false；全开→true）。
- [ ] **Step 2: 实现纯函数 + SettingsStore key**（含 setter/flow，默认 true）。
- [ ] **Step 3: DshConnectionService 接线**：`handle()` 审批/问答前与 `updateActivity()` 完成提醒前，分别读 `settings.notifyApprovals.first()` / `settings.notifyCompletion.first()` 与 `DshApplication.isAppInForeground`，组合 `shouldNotifyApproval/Completion` 判断是否 `postAlert`；保持既有 `backgroundNotify` 总开关与去重逻辑不变。
- [ ] **Step 4: SettingsScreen 提醒卡片**：在现有「提醒」卡片（:450-496，总开关 `backgroundNotify` 之下）新增两行 Switch：「审批与确认提醒」（`notifyApprovals`）与「任务完成提醒」（`notifyCompletion`），样式对齐现有 Switch 行；卡片底部新增「通知渠道设置」TextButton → `Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)`。
- [ ] **Step 5: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿，含新测试）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/dsh/mobile/DshApplication.kt app/src/main/java/com/dsh/mobile/service/DshConnectionService.kt app/src/main/java/com/dsh/mobile/data/SettingsStore.kt app/src/main/java/com/dsh/mobile/data/NotificationPolicy.kt app/src/main/java/com/dsh/mobile/ui/screens/SettingsScreen.kt app/src/test/java/com/dsh/mobile/data/NotificationPolicyTest.kt
git commit -m "feat(s4): 通知渠道精细化——分渠道开关 + 跳系统渠道设置"
```

---

### Task 2: 语音输入（T2，RecognizerIntent 首版）

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`（:5-12 权限区加 `RECORD_AUDIO`）
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`（输入栏 :1062-1212、状态区 :643-646、权限请求参考 SettingsScreen :485-493 的 POST_NOTIFICATIONS 模式）
- Test: `app/src/test/java/com/dsh/mobile/data/VoiceIntentTest.kt`（新增，测语音文本回填前的最小处理：trim/空串过滤）

**Interfaces:**
- 输入栏 Row 内、附件按钮（:1099）与正文之间插入麦克风 `IconButton`（`Icons.Rounded.Mic`，material-icons-extended 已有），样式对齐 AttachFile 按钮。
- 权限：Manifest 声明 `android.permission.RECORD_AUDIO`；首次点击麦克风时若未授权 → `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` 申请（参考 SettingsScreen 通知权限模式 :485-493）；拒绝时 Toast/提示「需要麦克风权限」。
- 语音：已授权 → `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())` 启动 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`（`EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`）；结果 `EXTRA_RESULTS` 取第一条非空文本 → 追加到 `input`（末尾补空格，与既有输入拼接）。
- 无语音服务（`RecognizerIntent` 抛 `ActivityNotFoundException`）→ Toast「此设备不支持语音输入」。

- [ ] **Step 1: 写失败测试** `VoiceIntentTest.kt`：顶层纯函数 `normalizeVoiceResult(raw: String?): String?`——null/空白 → null；首条文本 → trim；空串 → null。
- [ ] **Step 2: 实现纯函数**（放 SessionScreen.kt 同文件顶层 internal 或 data 包，供单测）。
- [ ] **Step 3: Manifest + SessionScreen 输入栏接线**：麦克风按钮（插入位置：附件与正文之间）、权限申请、RecognizerIntent launcher、结果回填、ActivityNotFoundException 兜底提示。
- [ ] **Step 4: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿，含新测试）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 5: Commit**（SessionScreen.kt 此时是 stash 后 HEAD 版本；只 add 本任务文件，绝不含并行改动）
```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt app/src/test/java/com/dsh/mobile/data/VoiceIntentTest.kt
git commit -m "feat(s4): 语音输入——RecognizerIntent 麦克风按钮 + 文本回填"
```

---

### Task 3: 置顶（本地 pin，T4）

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`（新增 key）
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt`（列表刷新 :172-190、SessionCard 长按 :854-860/:1290-1293、排序 :178-179、各分支列表渲染）
- Test: `app/src/test/java/com/dsh/mobile/data/SessionPinTest.kt`（新增）

**Interfaces:**
- SettingsStore 新增：
```kotlin
private val PINNED_SESSION_IDS_KEY = stringPreferencesKey("pinned_session_ids")
val pinnedSessionIds: Flow<Set<String>>
suspend fun setPinned(id: String, pinned: Boolean)   // JSON 数组或逗号分隔存储
```
- 顶层纯函数（`data/SessionPin.kt` 同包 internal）：
```kotlin
/** 置顶优先排序：pinned 的会话按 updatedAt 倒序在前，其余按 updatedAt 倒序在后 */
fun sortSessionsWithPinned(sessions: List<SessionSummary>, pinnedIds: Set<String>): List<SessionSummary>
fun togglePinned(pinnedIds: Set<String>, id: String): Set<String>
```

- [ ] **Step 1: 写失败测试** `SessionPinTest.kt`：`sortSessionsWithPinned`（pinned 在前/组内倒序/空 pinned 与现状等价）、`togglePinned`（加入/移除/幂等）。
- [ ] **Step 2: 实现纯函数 + SettingsStore key**。
- [ ] **Step 3: HomeScreen 接线**：`refreshSessions` 处（:172-190）把 `sortSessionsByUpdatedAt` 换成 `sortSessionsWithPinned(sessions, pinnedIds)`（pinnedIds 从 SettingsStore flow 收集）；SessionCard 长按菜单（:858 现仅归档）扩为 `AlertDialog` 或 DropdownMenu：「置顶/取消置顶」「归档」两项（归档逻辑保留原样）；列表项顶部加 pin 标记（小图钉图标，`Icons.Rounded.PushPin`，放 preview 行或卡片右上角）。
- [ ] **Step 4: 其余分支**：ChatGPT/Claude 抽屉行与 DeepLook 会话 tab 行按同样长按菜单接入置顶（复用同一 settings 调用；若该分支无长按则新增 combinedClickable/clickable）。置顶排序只依赖 `sessions` 列表排序处统一替换（各分支共用 `sessions` 状态 :79，排序在刷新处替换即可全局生效）。
- [ ] **Step 5: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/dsh/mobile/data/SettingsStore.kt app/src/main/java/com/dsh/mobile/data/SessionPin.kt app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt app/src/test/java/com/dsh/mobile/data/SessionPinTest.kt
git commit -m "feat(s4): 会话置顶——本地 pin 排序 + 长按菜单 + 图钉标记"
```

---

### Task 4: 会话搜索统一（T3）

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt`（ChatGPT 搜索 :2394-2467 抽公共；STANDARD/Claude/DeepLook 补搜索框）
- Test: `app/src/test/java/com/dsh/mobile/data/SessionSearchTest.kt`（新增）

**Interfaces:**
- 顶层纯函数（`data/SessionSearch.kt` 同包 internal）：
```kotlin
/** 标题 + 预览双字段过滤，忽略大小写；query 空白时原样返回 */
fun filterSessions(sessions: List<SessionSummary>, query: String): List<SessionSummary>
```
- 复用 `sessionTitleOf`（DshProtocol.kt:155-158，`internal` 同包可调）；预览字段取 `projections.values.preview`（SessionCard 同款提取 :1259-1260）。

- [ ] **Step 1: 写失败测试** `SessionSearchTest.kt`：标题命中/预览命中/忽略大小写/空白 query 全量/无命中空列表。
- [ ] **Step 2: 实现纯函数**。
- [ ] **Step 3: HomeScreen 四分支接入**：ChatGPT 分支把内联 `filtered = sessions.filter { ... }`（:2398-2401）替换为 `filterSessions(sessions, searchQuery)`；STANDARD 分支顶栏 TopAppBar actions 加搜索图标（`Icons.Rounded.Search`）→ 展开搜索框（复用 ChatGPT 搜索框样式）；Claude 抽屉与 DeepLook 会话 tab 补同样搜索框。搜索框统一过滤 `sessions`（置顶排序在前一任务已落地，过滤在排序后应用：`filterSessions(sortSessionsWithPinned(sessions, pinnedIds), query)`）。
- [ ] **Step 4: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/dsh/mobile/data/SessionSearch.kt app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt app/src/test/java/com/dsh/mobile/data/SessionSearchTest.kt
git commit -m "feat(s4): 会话搜索统一——标题+预览过滤，四布局分支接入"
```

---

### Task 5: 快捷指令栏（T5）

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`（输入栏上方 chip 条，插入点 :1024-1064 之间）
- Modify: `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`（`quick_prompts` key）
- Test: `app/src/test/java/com/dsh/mobile/data/QuickPromptsTest.kt`（新增）

**Interfaces:**
- SettingsStore 新增：
```kotlin
private val QUICK_PROMPTS_KEY = stringPreferencesKey("quick_prompts")
val quickPrompts: Flow<List<String>>   // 默认内置 4 条：["帮我总结当前会话","列出待办事项","检查代码问题","优化这段代码"]；JSON 数组存储
suspend fun setQuickPrompts(items: List<String>)
```
- 顶层纯函数（`data/QuickPrompts.kt` 同包 internal）：
```kotlin
fun encodeQuickPrompts(items: List<String>): String     // JSON 数组；空列表 → "[]"
fun decodeQuickPrompts(raw: String?): List<String>      // 非法/null → 空列表（不抛）
fun defaultQuickPrompts(): List<String>
```

- [ ] **Step 1: 写失败测试** `QuickPromptsTest.kt`：encode/decode 往返、非法输入容错、默认列表。
- [ ] **Step 2: 实现纯函数 + SettingsStore key**（默认值注入 `defaultQuickPrompts()`）。
- [ ] **Step 3: SessionScreen 快捷指令条**：在 `pendingImages` 区（:1024-1041）与输入 Surface（:1064）之间插入横向 `LazyRow`/`Row` chip 条：`quickPrompts` 每条一个 `AssistChip`（复用项目既有 AssistChip 样式），点击 → `input += prompt`（末尾补空格，复用技能选择 :1258 的拼接模式）；条尾加「编辑」小按钮（`Icons.Rounded.Edit`）→ `AlertDialog` 内文本编辑（每行一条，保存走 `setQuickPrompts`）。
- [ ] **Step 4: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/dsh/mobile/data/SettingsStore.kt app/src/main/java/com/dsh/mobile/data/QuickPrompts.kt app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt app/src/test/java/com/dsh/mobile/data/QuickPromptsTest.kt
git commit -m "feat(s4): 快捷指令栏——输入栏上方 chip 条 + 可编辑"
```

---

### Task 6: 打断（steer）动效反馈（T6）

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`（发送瞬间 :799-824 / 输入栏 :1062-1212 / ChatItem 模型 :79-109）
- Test: `app/src/test/java/com/dsh/mobile/data/SteerFeedbackTest.kt`（新增）

**Interfaces:**
- 纯逻辑（顶层 internal，`data/SteerFeedback.kt`）：
```kotlin
/** 插话发送瞬间的反馈状态机：触发 → 显示 N 秒「⚡ 已插话」banner → 自动消失 */
data class SteerFlash(val visible: Boolean, val at: Long = 0L)
fun steerFlashOn(sendingSteer: Boolean, prev: SteerFlash, now: Long, durationMs: Long = 2000L): SteerFlash
```
- 消息级「插话」标记（可选，做乐观标记）：`ChatItem` 增加 `steerSent: Boolean = false`，`send()` 时若 `steerMode` 则对本次发送置 true。

- [ ] **Step 1: 写失败测试** `SteerFeedbackTest.kt`：`steerFlashOn`——触发瞬间 visible=true；duration 内保持；超时 → false；非 steer 发送不触发。
- [ ] **Step 2: 实现纯函数**。
- [ ] **Step 3: SessionScreen 接线**：`send()`（:799-824）里当 `steerMode` 为 true 时触发 flash（`mutableStateOf(SteerFlash(false))`，发送时置 visible=true，`LaunchedEffect` 2s 后置 false）；输入栏上方（快捷指令条之下）显示 `AssistChip`/`Surface` banner「⚡ 已插话」（tint DshBrand，对齐 steer 图标选中色 :1142）；`ChatItem` 增加 `steerSent` 并在发送后置 true，助手/用户消息卡片加「插话」小徽章（纯乐观，不做服务端确认——recon §7.5 已注明无回执事件）。
- [ ] **Step 4: 校验**（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest`（全绿）→ `.\gradlew.bat :app:assembleDebug`（成功）。
- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/dsh/mobile/data/SteerFeedback.kt app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt app/src/test/java/com/dsh/mobile/data/SteerFeedbackTest.kt
git commit -m "feat(s4): 打断动效反馈——发送瞬间已插话横幅 + 消息级标记"
```

---

## 收尾（S4 完成门禁）

- [ ] 全量回归（`cd C:\hremote`）：`.\gradlew.bat :app:testDebugUnitTest` 全绿 + `.\gradlew.bat :app:assembleDebug` 成功 + `git status` 干净（仅遗留 .superpowers/sdd 记录文件）。
- [ ] `git stash pop` 恢复并行改动（build.gradle.kts 版本 41/1.3.1 + SessionScreen.kt 超长消息折叠）；确认折叠逻辑完整保留、无冲突丢失。
- [ ] README.md 更新日志追加「S4 会话与输入体验」条目（v?.?.? 占位，样式照抄 S3 条目）。
- [ ] 提交 README 更新（独立 commit）。
- [ ] S4 进度记录写入 `.superpowers/sdd/2026-08-16-s4-session-input/progress.md`。

## Self-Review 记录（计划作者自查）

- **Spec 覆盖**：T1 通知→Task 1；T2 语音→Task 2；T4 置顶→Task 3（执行顺序提前，先定长按菜单结构供 T3/T4 复用）；T3 搜索→Task 4；T5 快捷指令→Task 5；T6 steer 反馈→Task 6。侦察报告 6 项全覆盖，无缺口。
- **占位符扫描**：无 TBD/TODO；批量删除/服务端置顶明确出范围（recon §7.4 已注明需 PC 协议，本阶段不做）。
- **类型一致性**：`sortSessionsWithPinned`/`togglePinned`（Task 3 定义，Task 4 复用）；`filterSessions`（Task 4 定义）；`quickPrompts`（Task 5 定义）；`steerFlashOn`（Task 6 定义）。SettingsStore 新 key 全部独立命名（notify_approvals/notify_completion/pinned_session_ids/quick_prompts），互不冲突。
- **执行注意**：Task 2/5/6 同改 SessionScreen.kt，任务间通过 stash 后 HEAD 基线隔离，串行执行；并行改动 pop 冲突时以「超长折叠逻辑不得丢」为优先。测试统一 `C:\hremote`。所有 commit 精确 add，禁止 `git add -A`。
