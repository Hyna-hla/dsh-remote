# Task 3 Report: SettingsStore — profiles 存储、活跃主机、旧数据迁移

## What I implemented

扩展 `SettingsStore.kt`，新增多主机配置存储与旧版单地址数据的一次性迁移（保持 `ConnectionConfig`/`connectionConfig`/`saveConnection` 原样，留给 Task 9 移除）：

- companion object 新增 `PROFILE_LIST_KEY = stringPreferencesKey("connection_profiles")` 与 `ACTIVE_PROFILE_KEY = stringPreferencesKey("active_profile_id")`。
- 新增 `profiles: Flow<List<HostProfile>>`、`activeProfileId: Flow<String?>`（DataStore 流映射）。
- 新增 `upsertProfile` / `deleteProfile` / `setActiveProfile` / `markAttempt`（经 `writeProfiles` 写入，写入前先 `applyLegacyMigration` 顺带清旧 key）。
- 新增顶层 `internal fun applyLegacyMigration(prefs: MutablePreferences): Boolean`：`server_url`/`auto_connect` → 生成一个 `remark="旧连接"` 的 `HostProfile` 并设为活跃，随后移除旧 key；幂等，旧 key 不存在时返回 false；已有 profiles 时不覆盖。
- 新增 `import kotlinx.coroutines.flow.first`。

## What I tested and results

- `SettingsStoreMigrationTest`（3 用例）：`legacyCreatesProfileAndActive`、`noLegacyNoChange`、`existingProfilesKeepAndCleanLegacy` —— 全部 PASS。
- 全量 `:app:testDebugUnitTest`：19/19 PASS（ConnectionPolicyTest 12 + HostProfileTest 4 + SettingsStoreMigrationTest 3），无回归。

## TDD Evidence

RED（编译失败，applyLegacyMigration 不存在）：

```
> Task :app:compileDebugUnitTestKotlin FAILED
e: file:///C:/hremote/app/src/test/java/com/dsh/mobile/data/SettingsStoreMigrationTest.kt:18:23 Unresolved reference 'applyLegacyMigration'.
...
BUILD FAILED in 1s
```

GREEN：

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
```

测试报告：`TEST-com.dsh.mobile.data.SettingsStoreMigrationTest.xml` → `tests="3" skipped="0" failures="0" errors="0"`。

## Files changed

- `app/src/main/java/com/dsh/mobile/data/SettingsStore.kt`（+69 行）
- `app/src/test/java/com/dsh/mobile/data/SettingsStoreMigrationTest.kt`（新建，63 行）

Commit: `a78432a feat(s1): SettingsStore 多主机 profiles 存储 + 旧配置迁移`

## Self-review findings

- 完整性：brief 的 Produces 接口（profiles/activeProfileId/upsertProfile/deleteProfile/setActiveProfile/markAttempt/applyLegacyMigration）全部落地；`ConnectionConfig` 系列未删未改。
- 质量：无多余代码；`MutablePreferences` 由现有 `androidx.datastore.preferences.core.*` 通配导入覆盖，未重复 import。
- 测试真实：迁移逻辑用纯函数 + `mutablePreferencesOf` 断言，输出干净（XML 0 failure / 0 error）。
- 编码处理：task-3-brief.md 本身含乱码（计划源文件 `docs/superpowers/plans/2026-08-16-s1-connection-layer.md` 已在生成时被错误编码损坏），代码块里的中文串如 `"鏃ц繛鎺?"`/`"瀹堕噷"` 无法从文件恢复。我依据现有 `HostProfileTest.kt` 中已确认的正确中文（`remark = "家里"`）与语义还原为：迁移 profile 的 `remark = "旧连接"`、测试既有 profile 的 `remark = "家里"`。乱码注释也一并还原为正确中文。
- 额外必要的编译修正（超出 controller 已裁的 `mutablePreferencesOf<String>`）：测试 helper 签名引用 `MutablePreferences` 类型，brief 的 import 清单缺失该项，已补 `import androidx.datastore.preferences.core.MutablePreferences`，否则同样无法编译。

## Any concerns

- `markAttempt` 的 `hostVersion: String?` 参数按 brief 原文未被使用（仅写 `lastUsedAt`/`lastErrorCode`），与计划一致；该行为已在 progress.md 的 Ruling T6 标注为 deferred minor（成功尝试不刷新 lastUsedAt），留待最终评审裁决，非本任务引入。
- 计划源文件与 brief 存在非 ASCII 乱码，建议后续任务统一从一处修正编码，避免实现者各自还原中文造成字面不一致。

---

## Fix round 1/5（Spec ❌ → 修复 Important）

评审指出：`writeProfiles` 在 edit 内先 `applyLegacyMigration`，随后用 edit 外 `profiles.first()` 读到的旧列表覆盖 `connection_profiles`，迁移场景下迁移生成的 profile 被覆盖丢弃、`active_profile_id` 指向未持久化 id。

**改了什么：**

- 删除 `writeProfiles`；`upsertProfile` / `deleteProfile` / `markAttempt` 各自在 `context.dataStore.edit { prefs -> }` 内部完成：`applyLegacyMigration(prefs)` → `ProfileCodec.decode(prefs[PROFILE_LIST_KEY] ?: "")` 重读 current → 计算新列表写回。
- `deleteProfile` 的活跃判断移进同一 edit：`if (prefs[ACTIVE_PROFILE_KEY] == id) prefs.remove(ACTIVE_PROFILE_KEY)`，不再二次 edit 调 `setActiveProfile`。
- 移除不再使用的 `import kotlinx.coroutines.flow.first`。
- 测试新增用例 `migrationSurvivesSubsequentUpsert`：用纯 `mutablePreferencesOf` 模拟「迁移 → edit 内重读 → 写回」，断言迁移 profile 与新 profile 同时存活（共 2 个）。

**覆盖测试命令与输出：**

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.SettingsStoreMigrationTest"
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
```
`TEST-com.dsh.mobile.data.SettingsStoreMigrationTest.xml` → `tests="4" failures="0" errors="0"`。

全量回归：
```
.\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL in 1s
```
ConnectionPolicyTest 12 + HostProfileTest 4 + SettingsStoreMigrationTest 4 = 20/20 PASS，无回归。

Commit: `472e080 fix(s1): SettingsStore 写回在 edit 内重读 profiles，修复迁移被覆盖丢弃`（仅含 SettingsStore.kt 与 SettingsStoreMigrationTest.kt）。
