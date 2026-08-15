# S2 统一待办审批中心设计文档

- 日期：2026-08-16
- 项目：DSH Remote（harness-remote）能力补全计划 · 子项目 S2
- 状态：待评审

## 1. 背景与目标

当前审批/问答只在会话页内以本地横幅处理：状态是 `remember` 级，跨会话不可见、不可聚合、App 重启即丢（审批在 PC 端有持久日志但 App 不重建）。S2 建一个**统一待办中心**：集中收纳待审批、待问答、待人工介入（智能体报错），支持批量操作、优先级排序、点击直达会话位置，并收编会话内横幅为轻提示。

目标（对应需求清单 二-1）：

1. 独立入口（首页铃铛 + 待办数徽章）收纳全部待办，三档分类：审批 / 问答 / 异常。
2. 审批支持单项 允许一次/拒绝 与分区级 全部允许/全部拒绝；问答支持内联作答与 全部跳过。
3. 按优先级排序：审批 → 问答 → 异常；审批/问答按等待时长最早优先，异常按最新优先。
4. 点击任意项跳转对应会话并定位到相关位置（异常/历史审批带 seq 定位，降级为停在顶部）。
5. App 重启后从最近 5 个活跃会话的历史事件恢复未决审批与报错。

## 2. 范围

**In scope**：PendingItem 模型与纯函数、ApprovalCenter 状态层（实时事件 + 历史恢复）、PendingScreen（三档/批量/跳转）、SessionScreen 横幅收编为轻提示、导航接线（Screen.Pending + Session focusSeq）、共享组件抽取（QuestionCard、sessionTitleOf）、单测与真机验收。

**Out of scope（后续子项目）**：文件工作区（S6）、通知渠道精细化（S4）、思考链/工具可视化（S5）、高危操作二次确认（S3）；PC 端协议扩展（本子项目为 App 端纯改动）。

## 3. 现状与协议事实（实现基线）

- `DshConnection.events`（SharedFlow）已有：`ApprovalRequested(sessionId, approvalId, toolName, callId?, reason?)`、`ApprovalResolved(sessionId, approvalId, outcome)`、`QuestionRequested(sessionId, questions)`、`QuestionResolved(sessionId, questionRpcId, outcome)`、`SessionEvent(sessionId, event)`。应答 API：`answerApproval(sessionId, approvalId, outcome)`、`answerQuestions(sessionId, answers)`（`emptyList()` = 跳过）。会话列表 `listSessions()`、历史 `history(sessionId, beforeSeq?, maxMessages?)` → `HistoryValue(events: List<HistoryEntry>, hasMore, projections)`，`HistoryEntry(event: SessionEventWire, view?)`，`SessionEventWire(type, seq, time, data: JsonElement, …)`。
- **协议事实（已对 PC 端源码核实）**：
  - 会话持久日志含 `approval/asked`（data = `{id, toolName, callId?, reason?}`）与 `approval/decided`（data = `{id, outcome}`），两者按 seq 成对出现；`agent/error` 亦入日志（data 含错误信息，宽松解析 message/name 字段）。
  - **问答事件不持久化**：会话日志中无 question 事件类型。历史恢复只能覆盖审批与异常；问答是"活期"数据，App 重启后不恢复（如实边界）。
- SessionScreen 现有：会话内全功能审批横幅（拒绝/允许一次）与内联 `QuestionCard`（私有组件，1667 行起），状态为 `remember` 局部变量。
- HomeScreen：会话标题提取 `chatGptTitleOf(s)`（读 `projections.values.title`，2320 行，私有）；顶栏含设置入口（DeepLook 分组样式）。
- AppNavigation：`Screen.Session("session/{sessionId}")`、`Screen.Pro` 等；`DshApplication` 持有 `connection`。

## 4. 核心决策（已与用户确认）

| 决策 | 结论 |
| :-- | :-- |
| 入口形态 | 首页顶部铃铛 + 待办数徽章 → 独立待办页（不新增底部 Tab） |
| 与会话内横幅关系 | **收编**：会话内改轻提示条（「本会话有 N 条待办，去待办中心」+ 入口按钮）；实际处理统一在中心 |
| 「待人工介入」定义 | 审批 + 问答 + **智能体报错**（agent/error）；报错不可批量，点项跳会话 |
| 历史恢复 | 连接成功后扫描**最近 5 个**活跃会话（updatedAt 倒序）的历史重建待办；实时事件覆盖同 key 历史项 |
| 方案 | A：集中式状态层 + 独立页，App 端纯改动（B 服务端聚合弃：需改 PC 插件且问答无持久化服务端也聚不全） |

## 5. 详细设计

### 5.1 数据模型与纯函数（新增 `data/PendingItem.kt`）

```kotlin
sealed class PendingItem {
    abstract val sessionId: String
    abstract val arrivedAt: Long      // 实时=接收时刻；历史=事件 time
    abstract val fromHistory: Boolean

    data class Approval(
        override val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
        val callId: String?,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()

    data class Question(
        override val sessionId: String,
        val questions: List<QuestionItem>,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,   // 恒为 false（问答无持久化）
    ) : PendingItem()

    data class Error(
        override val sessionId: String,
        val message: String,
        val seq: Long,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()
}

/** 从历史事件序列重建待办（纯函数，可单测）：
 *  按 seq 升序扫描：approval/asked → 加入（id 为键）；approval/decided → 移除同 id；
 *  agent/error → 每会话保留 seq 最大的一条（覆盖旧值）。
 *  容错：asked 重复出现 → 忽略后者；decided 无对应 asked → 忽略；data 解析失败 → 跳过该事件。 */
fun scanHistoryEvents(entries: List<HistoryEntry>): List<PendingItem>

/** 优先级排序：Approval → Question → Error；Approval/Question 按 arrivedAt 升序（最早在前），Error 按 arrivedAt 降序（最新在前）。返回新列表。 */
fun sortPendingItems(items: List<PendingItem>): List<PendingItem>
```

- `QuestionItem`/`HistoryEntry`/`SessionEventWire` 复用 `DshProtocol.kt` 现有定义。
- 异常消息提取（宽松）：`data.jsonObject["message"]` 优先，其次 `["error"]?.jsonPrimitive?.contentOrNull` 与 `["name"]`，全缺省显示「智能体执行出错」。

### 5.2 状态层（新增 `data/ApprovalCenter.kt`）

```kotlin
class ApprovalCenter(
    // 主构造：全部依赖注入，纯逻辑可单测（事件流/应答/状态/会话源均为接口形态）
    private val events: Flow<DshConnection.Event>,
    private val answerApprovalFn: suspend (sessionId: String, approvalId: String, outcome: String) -> Unit,
    private val answerQuestionsFn: suspend (sessionId: String, answers: List<DshConnection.QuestionAnswer>) -> Unit,
    private val state: Flow<DshConnection.State>,
    private val listSessionsFn: suspend () -> List<SessionSummary>,
    private val historyFn: suspend (sessionId: String) -> HistoryValue,
    private val scope: CoroutineScope,
) {
    constructor(connection: DshConnection, scope: CoroutineScope) : this(
        events = connection.events,
        answerApprovalFn = connection::answerApproval,
        answerQuestionsFn = connection::answerQuestions,
        state = connection.state,
        listSessionsFn = connection::listSessions,
        historyFn = { connection.history(it) },
        scope = scope,
    )

    val items: StateFlow<List<PendingItem>>   // 唯一事实源，已排序
    val pendingCount: StateFlow<Int>          // items.size（供徽章）

    /** 单项应答/跳过：委托应答函数；成功后由 Resolved 事件驱动移除（乐观更新：调用前先本地移除，失败回滚并抛 ApiException 供 UI toast） */
    suspend fun allow(sessionId: String, approvalId: String)
    suspend fun reject(sessionId: String, approvalId: String)
    suspend fun answerQuestions(sessionId: String, answers: List<DshConnection.QuestionAnswer>)
    suspend fun skipQuestions(sessionId: String)            // answerQuestions(emptyList())

    /** 批量：顺序执行，收集每项失败为 List<String> 返回（空 = 全部成功） */
    suspend fun allowAllApprovals(): List<String>
    suspend fun rejectAllApprovals(): List<String>
    suspend fun skipAllQuestions(): List<String>
}
```

内部机制：

- **实时事件**（`connection.events` 收集）：
  - `ApprovalRequested` → upsert `Approval`（键 `(sessionId, approvalId)`，fromHistory=false）
  - `ApprovalResolved` → 移除同键
  - `QuestionRequested` → 替换该会话的 `Question`（每会话一条）
  - `QuestionResolved` → 移除该会话 Question
  - `SessionEvent` 且 `event.type == "agent/error"` → 替换该会话 `Error`（seq 更大才替换）
- **历史恢复**：收集 `connection.state`，每次进入 `State.Connected` 执行一次：`listSessions()` 按 `updatedAt` 倒序取 5 个 → 逐个 `history(sessionId)` → `scanHistoryEvents`；合并规则：历史项仅当同键实时项不存在时加入（实时覆盖历史）；恢复过程中任何一步失败 → 静默降级为仅活期待办（记日志，不阻断连接）。恢复期间已到达的实时事件照常处理（先实时后合并）。
- 键定义：Approval = `(sessionId, approvalId)`；Question = `(sessionId, "q")`；Error = `(sessionId, "e")`。
- 乐观更新：批量/单项应答前先移除目标项（UI 即时反馈），若 `connection.answer*` 抛异常则回滚该项并计入失败列表。

### 5.3 待办页 UI（新增 `ui/screens/PendingScreen.kt`）

- 结构：顶栏（返回 + 标题「待办中心」+ 右上「全部清空」？——**不做**，无对应批量语义，避免误操作）；LazyColumn 三分区：
  - **审批**（分区头 + 「全部允许」「全部拒绝」按钮）：每项卡片 = 会话标题（见 5.6）、工具名徽章、「原因：…」、到达时间；单项按钮 允许一次 / 拒绝。
  - **问答**（分区头 + 「全部跳过」）：每项卡片内联 `QuestionCard`（共享组件，见 5.6），提交即答、可跳过。
  - **异常**（分区头，无批量）：每项卡片 = 会话标题、错误摘要（2 行截断）、时间；点卡片跳会话。
- 空态：全清时显示「暂无待办」+ 副文案。
- 批量执行中：按钮转圈禁用；完成后失败列表以 snackbar 汇总（「3 条处理失败：<首条原因>…」）。
- 点任意项/「去会话」：`navController.navigate(Screen.Session.createRoute(sessionId, focusSeq))`。focusSeq 规则：`Error` 项带其 seq；`Approval`/`Question` 项不带（历史扫描不保留 Approval 的 seq，见 §9）。
- 标题获取：进入页面时 `connection.listSessions()` 建 `sessionId → title` 映射（复用 5.6 的 sessionTitleOf），会话列表失败则显示 `sessionId.take(8)`。

### 5.4 会话页收编（SessionScreen 修改）

- 删除现有审批横幅与会话内 QuestionCard 的处理逻辑（QuestionCard 组件本体移到共享处）。
- 新增轻提示条（顶部、悬浮于消息列表上方）：收集 `ApprovalCenter.items` 中本会话数量 N>0 时显示「本会话有 N 条待办（审批/问答）· 去处理」，点击跳 Pending 页。异常不在提示条内（消息流中已展示）。
- 原 `approval`/`questions` 局部状态与相关 LaunchedEffect 依赖移除；自动滚动逻辑不受影响。

### 5.5 导航与接线

- `AppNavigation`：新增 `Screen.Pending("pending")`；`Screen.Session` 改为 `"session/{sessionId}?focusSeq={focusSeq}"`（`focusSeq` NavType.StringType、defaultValue ""；`createRoute(sessionId, focusSeq: Long? = null)`）。
- `SessionScreen` 增加 `focusSeq: Long?` 参数：历史加载完成后，若 focusSeq 非空 → 在已加载事件中找该 seq 对应的 item index → 滚动定位 + 1.5s 高亮；未找到 → 停在顶部并 snackbar「目标位置不在已加载窗口」。
- `HomeScreen`：顶栏（设置入口同排）加铃铛 IconButton + `pendingCount` 徽章；`onPending` 回调由 AppNavigation 注入。`MainActivity`/`DshApplication`：创建 `ApprovalCenter(app.connection, appScope)`（进程级单例，挂 DshApplication），经 AppNavigation 传给 HomeScreen/SessionScreen/PendingScreen。

### 5.6 共享抽取

- `ui/components/QuestionCard.kt`（新增）：从 SessionScreen 迁移现有 QuestionCard（多选/单选/提交回调），签名 `QuestionCard(questions: List<QuestionItem>, onSubmit: (List<DshConnection.QuestionAnswer>) -> Unit, onSkip: () -> Unit)`。
- `data/DshProtocol.kt`（或新 util）：新增 `fun sessionTitleOf(session: SessionSummary): String?`（读 `projections.values.title`）；HomeScreen 的 `chatGptTitleOf` 改为委托它（删除私有实现）。

## 6. 事件类型与 JSON 形状

| 事件 | data 形状 | 用途 |
| :-- | :-- | :-- |
| `approval/asked` | `{id, toolName, callId?, reason?}` | 历史恢复：加入待办 |
| `approval/decided` | `{id, outcome}` | 历史恢复：移除待办 |
| `agent/error` | 对象（宽松取 message/error/name） | 实时与历史：异常档 |
| mux `approval/requested` / `approval/resolved` | 现有 MuxFrame 字段 | 实时增删（沿用 Task 之前机制） |
| mux `question/requested` / `question/resolved` | 现有 MuxFrame 字段 | 实时增删 |

## 7. 错误处理与降级

- 历史恢复任一步失败 → 静默跳过（保留活期待办），不弹错误。
- 批量操作逐项容错：单项失败回滚该项 + 汇总 snackbar，不影响其余项。
- 待办项指向的会话已归档/删除 → 应答失败计入失败列表，项保留由用户手动忽略（后续会话清理事件自然移除）。
- PendingScreen 在连接断开时显示「未连接」空态，重连后由恢复逻辑重建。

## 8. 测试与验收

1. JUnit（纯函数与状态层语义）：
   - `scanHistoryEvents`：asked/decided 成对 → 空；asked 无 decided → 保留；重复 asked 忽略；乱序 decided 容错；agent/error 每会话取 seq 最大；data 解析失败跳过。
   - `sortPendingItems`：三档顺序与档内排序规则。
   - `ApprovalCenter`（用假 connection 事件流注入）：实时增删/替换语义、批量顺序执行与失败汇总、乐观更新回滚、历史合并实时优先。
2. 编译 + 全量单测 + `assembleDebug`。
3. 真机验收清单：
   - 审批到达 → 首页徽章 +1、待办页现项；单项允许/拒绝即时消失且 PC 端生效。
   - 批量全部允许/拒绝；问答内联作答与全部跳过。
   - agent/error → 异常档出现；点击跳转会话并定位（异常带 seq）。
   - 重启 App → 最近 5 会话的未决审批/报错恢复（问答不恢复为预期）。
   - 会话页轻提示条替代旧横幅，数量实时更新，点击进入待办页。
   - 后台通知（S1 服务）与审批联动回归：中心处理后通知不重复。
   - 无待办时徽章隐藏、空态显示。

## 9. 风险与开放问题

| 项 | 说明 | 处置 |
| :-- | :-- | :-- |
| 问答无持久化 | 协议事实，恢复只覆盖审批/异常 | 已确认边界，文档与 UI 文案如实 |
| Approval 历史项无 seq 保留 | 跳转定位仅对 Error（有 seq）生效；Approval 历史项跳会话顶部 | 可接受；后续若需精确定位再扩展 scanHistoryEvents 返回 seq |
| 最近 5 会话窗口 | 更早会话的未决审批不恢复 | 已确认决策；窗口值 `RECOVERY_SESSION_LIMIT = 5` 常量可调 |
| 批量操作与流式 Resolved 竞态 | 本地移除后 PC 端 Resolved 到达 → 移除不存在的键 | 移除幂等（不报错） |
| 历史恢复与实时事件交错 | 恢复慢 + 新事件到 → 覆盖顺序 | 合并规则「实时优先」+ 恢复在 IO 协程，UI 不阻塞 |
| SessionScreen 大改动的回归面 | 移除横幅逻辑触及滚动/状态 | 单测覆盖有限，真机清单逐项回归 |

## 10. 影响文件清单

| 文件 | 动作 |
| :-- | :-- |
| `data/PendingItem.kt` | 新增（模型 + scanHistoryEvents + sortPendingItems） |
| `data/ApprovalCenter.kt` | 新增（状态层） |
| `data/DshProtocol.kt` | 改（sessionTitleOf 共享函数） |
| `ui/components/QuestionCard.kt` | 新增（自 SessionScreen 迁移） |
| `ui/screens/PendingScreen.kt` | 新增（三档/批量/跳转） |
| `ui/screens/SessionScreen.kt` | 改（收编横幅 + focusSeq 定位） |
| `ui/screens/HomeScreen.kt` | 改（铃铛入口 + chatGptTitleOf 委托） |
| `ui/navigation/AppNavigation.kt` | 改（Screen.Pending、Session focusSeq、ApprovalCenter 注入） |
| `DshApplication.kt` | 改（ApprovalCenter 进程级单例） |
| `app/src/test/java/com/dsh/mobile/data/PendingItemTest.kt` 等 | 新增测试 |
