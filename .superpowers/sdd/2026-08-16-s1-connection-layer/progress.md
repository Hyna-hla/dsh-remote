# SDD ledger — plan: docs/superpowers/plans/2026-08-16-s1-connection-layer.md

## Preflight

- 工作区方式：用户明确同意直接在 main 上实现（本仓库惯例即单分支直提；当前工作树有他人未提交的 Claude/DeepLook 改动，实现者必须只用 `git add <精确文件>` 提交自己任务的文件）。
- 工具替代：环境无 bash，skill 的 sdd-workspace/task-brief/review-package 以 pwsh 等价实现（extract-brief.ps1 + 手工 git log/diff 打包），产物路径约定不变。
- 模型参数：本环境 subagent 工具无 model 参数，无法按 Model Selection 显式指定模型；以任务 prompt 中角色描述代替。Ruling: 记录此限制，实现/审查提示词按模板完整展开。
- Ruling: 主分支直接提交 — 用户已确认（ask_user_question 选择"直接在 main 上做"）— 若错：S1 提交与用户提交混排，但每个 commit 可独立 revert。

## 计划冲突扫描（任务对 / 共享接口）

| 对 | 共享物 | 结论 |
| T1→T2/T3/T4 | ConnectionErrorCode/HostProfile/ProxyConfig/ProfileCodec | 一致（签名见 T1 Produces） |
| T2→T5 | VersionPolicy/RetryPolicy/ErrorClassifier | 一致 |
| T3→T6/T7/T8 | profiles/activeProfileId/upsertProfile/deleteProfile/setActiveProfile/markAttempt | 一致 |
| T4→T5/T8 | OkHttpClientFactory.build/release + 顶层 buildProxy/trustAllSslContext/parseCaCertificate | 一致 |
| T5→T6/T7 | connect(profile,onAttempt)/State.Error(code,profileId)/State.Connected(hostVersion)/AttemptInfo | 一致 |
| T7→T8/T9 | ErrorMessages / ConnectScreen(connection,onEditHost) | 一致 |
| T9 | 移除 ConnectionConfig——MainActivity 不引用，T6/T7 已切换 | 一致 |

各任务自洽性（测试 vs 代码、文件创建 vs 引用）发现 5 处，全部先裁：

- Ruling T3: 计划测试代码 `mutablePreferencesOf<String>()` 不合法（该函数非泛型）→ 实现者改用 `mutablePreferencesOf()`。若错：无。
- Ruling T4: Step 3 中 mergedX509 骨架与 CompositeTrustManager 修正版并存 → 以 CompositeTrustManager 修正版为准（含 lastComposite/compositeX509 字段）。若错：证书链校验实现不完整，真机证书场景需返工。
- Ruling T5: `currentProfileHasProxy()` 占位写法 → 用 `currentProfile: HostProfile?` 字段简化版。若错：无。
- Ruling T8: `original` 永不为 null（新建时也构造空 profile）→ 标题/删除按钮判断改用 `isNew = profileId == null`。若错：新建页显示「编辑主机」标题与多余的删除按钮，纯 UI 瑕疵。
- Ruling T6: 服务端 onAttempt 仅在 errorCode != null 时 markAttempt → 成功尝试不刷新 lastUsedAt（deferred minor，最终评审裁决）。若错：连接历史排序在纯后台连接场景下不更新。

## 任务进度

## 任务进度

Task 1: 实现完成（DONE_WITH_CONCERNS）——代码被并发 agent 扫入 2f82027（v1.1.8）并推送 origin/main，归属错乱；4 文件内容完好（HostProfile.kt 54 行 / HostProfileTest.kt 45 行 / 两处构建文件）。测试 4/4 PASS。评审补做中（路径限定包 review-task-1.md）。
- 环境事实：并发 agent Hyna-hla 持续提交推送（v1.1.4 DeepLook 主题也扫走了本会话早先未提交的 DeepLook 改动），5 分钟内三版；用户已决定：等其干完再继续 S1。
- 环境事实：非 ASCII 仓库路径破坏 Gradle 测试 worker（ClassNotFoundException），后续任务统一经 ASCII junction C:\hremote 跑 Gradle（mklink /J C:\hremote <repo>）。
- 状态：PAUSED —— 待 (a) Task 1 评审闭环 (b) 用户确认并发 agent 收工，再启 Task 2。

Task 2-9: pending（未派发）

Task 1: minor (deferred): runCatching 捕获 Throwable 过宽（brief 规定代码，非实现者偏差）
Task 1: minor (deferred): codecDefaults 只断言 3 个默认字段，建议全结构断言
Task 1: minor (deferred): 错误 JSON 形状输入（[1,2,3] / {}）无测试覆盖
Task 1: complete (commits 440e339..bbede81, review clean, 3 deferred minors；代码实际位于并发 agent 的 2f82027，归属已注明)
- 并发 agent 仍在活动（工作树 versionCode 37 / versionName 1.2.0，继续等待用户确认收工）
