# Task 2 Report — ConnectionPolicy（版本宽容策略 / 重连退避 / 错误分类）

## Status: DONE_WITH_CONCERNS

## What I implemented

纯逻辑策略层，两个新文件（无既有文件改动）：

- `app/src/main/java/com/dsh/mobile/data/ConnectionPolicy.kt`
  - `enum class VersionVerdict { OK, UNKNOWN, MISMATCH }`
  - `object VersionPolicy`：`MIN_DSH_VERSION` / `PLACEHOLDER_VERSION` / `evaluate(version, min)`（semver 解析 + 数值比较）
  - `object RetryPolicy`：`FAST_TIER_CAP_MS` / `SLOW_TIER_CAP_MS` / `isRecoverable` / `nextBackoff`
  - `object ErrorClassifier`：`fromException(t, connectPhase, hasProxy)` / `fromHttpStatus(status)`
- `app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt`（brief 逐字转录，11 个测试）

## What I tested and results

- `:app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"` → 11/11 PASS，`BUILD SUCCESSFUL`
- `:app:testDebugUnitTest`（全量回归）→ `BUILD SUCCESSFUL`，无回归

## TDD Evidence

### RED（Step 2：先写失败测试，确认编译失败）

Command（从 junction 运行）:
```
cd C:\hremote
.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"
```

Output（节选）:
```
> Task :app:compileDebugUnitTestKotlin FAILED
e: file:///C:/hremote/app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt:19:22 Unresolved reference 'VersionVerdict'.
e: ... Unresolved reference 'VersionPolicy'.
e: ... Unresolved reference 'RetryPolicy'.
e: ... Unresolved reference 'ErrorClassifier'.
...
BUILD FAILED in 2s
[exit code: 1]
```

### GREEN（Step 4：实现后确认通过）

Command:
```
cd C:\hremote
.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"
```

Output:
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2s
22 actionable tasks: 4 executed, 18 up-to-date
```

## Files changed

- `app/src/main/java/com/dsh/mobile/data/ConnectionPolicy.kt`（新增，84 行）
- `app/src/test/java/com/dsh/mobile/data/ConnectionPolicyTest.kt`（新增，104 行）

Commit: `f49111a feat(s1): 版本宽容策略 + 重连退避 + 错误分类纯逻辑`（2 files changed, 188 insertions）

## Self-review findings

- 完整性：三个 object + enum 全部实现，brief 的 11 个验收用例全部覆盖并通过。
- YAGNI：无多余代码；公共接口与 brief 一致（`parse` / `atLeast` 均为 private）。
- 测试真实性：断言的数值（3s/6s/9s/12s/24s/30s 封顶、DNS/端口/TLS/HTTP 状态分类）与实现语义一一对应；全量单测无回归。
- 输出整洁：提交仅含 brief 指定的两个文件；工作树中 `.superpowers/.../progress.md` 的改动与 `task-2-brief.md` 未跟踪项均为控制器/外部簿记，未 stage、未 commit。

## Concerns（brief 逐字代码中的两处 bug，已最小修正）

1. **`VersionPolicy.evaluate` 的 `v >= m` 无法编译**：`parse` 返回 `Triple<Int, Int, Int>`，而 Kotlin 的 `Triple` 不是 `Comparable`，`>=` 报 `Unresolved reference 'compareTo'`。
   修正：新增 private 扩展 `Triple<Int, Int, Int>.atLeast(other)`，用 `compareValuesBy(...) >= 0` 做字典序比较。语义与 brief 注释「Triple 按数值比较」一致，公共接口不变。

2. **`RetryPolicy.nextBackoff` 的 `ms *= 2` 在 attempt=99 时 Long 溢出**：`3000 * 2^99` 远超 `Long.MAX_VALUE`，回绕为负数后 `coerceAtMost(cap)` 返回负数，导致 `slowTierDoublesToCap` 的 `TLS_CERT_FAILED, 99` 断言（期望 30000）失败。
   修正：改为饱和翻倍 `ms = minOf(ms * 2, cap)`，到达上限后保持封顶值，既符合「doubles to cap」语义又避免溢出。

以上两处修正均为让 brief 的验收测试通过所需的最小改动，不改变公共契约；供 controller 复核确认是否接受。

---

## Round 1 评审修复（Important：版本号超大数字段溢出）

### 问题
`parse()` 用 `m.groupValues[n].toInt()`，当某段版本号超过 `Int.MAX_VALUE`（如 `"99999999999999999999.0.0"`）时抛 `NumberFormatException`；spec 要求不可解析版本返回 `UNKNOWN` 且不阻断（Task 5 中 `evaluate` 在 try/catch 之外调用，会直接崩溃）。

### 改动
- `ConnectionPolicy.kt` 的 `parse()`：三个分量改为 `toIntOrNull() ?: return null`（major/minor/patch 各自判空），超大数字段返回 null → `UNKNOWN`。
- `ConnectionPolicyTest.kt`：新增 `versionOverflowComponentIsUnknown`，断言 `VersionPolicy.evaluate("99999999999999999999.0.0")` 返回 `VersionVerdict.UNKNOWN`。

### TDD 证据
RED（新测试先行）:
```
cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"
...
ConnectionPolicyTest > versionOverflowComponentIsUnknown FAILED
    java.lang.NumberFormatException at ConnectionPolicyTest.kt:43
12 tests completed, 1 failed
BUILD FAILED
```

GREEN（修复后）:
```
cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ConnectionPolicyTest"
BUILD SUCCESSFUL in 3s
```

全量回归:
```
cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest
BUILD SUCCESSFUL in 1s
```

### Commit
`62eb722 fix(s1): VersionPolicy 超大版本号字段解析返回 UNKNOWN 而非崩溃`（只含 ConnectionPolicy.kt 与 ConnectionPolicyTest.kt，2 files changed, 9 insertions, 1 deletion）
