# Task 1 Report: 测试设施 + HostProfile 数据模型与 JSON Codec

## What I implemented

Followed the brief verbatim (TDD: failing test first → implement → pass):

1. **`gradle/libs.versions.toml`** — added JUnit 4.13.2:
   - `[versions] junit = "4.13.2"`
   - `[libraries] junit = { group = "junit", name = "junit", version.ref = "junit" }`
2. **`app/build.gradle.kts`** — added `testImplementation(libs.junit)` at the end of the
   `dependencies` block.
3. **`app/src/main/java/com/dsh/mobile/data/HostProfile.kt`** (new) — exactly the brief's model:
   - `enum class ConnectionErrorCode` (8 values + Chinese comments)
   - `@Serializable data class ProxyConfig`
   - `@Serializable data class HostProfile`
   - `object ProfileCodec` using `Json { ignoreUnknownKeys = true; encodeDefaults = true }`
     and `ListSerializer(HostProfile.serializer())`; `decode` returns `emptyList()` on failure.
4. **`app/src/test/java/com/dsh/mobile/data/HostProfileTest.kt`** (new) — the brief's 4 tests:
   `codecRoundTrip`, `codecEmptyList`, `codecGarbageReturnsEmpty`, `codecDefaults`.

The codec uses the explicit-serializer member functions of `Json`
(`encodeToString(serializer, value)` / `decodeFromString(serializer, text)`), which require no
extra import beyond `kotlinx.serialization.json.Json` — verified against existing usage in
`HistoryCache.kt`. No extra serialization dependency was needed (project already has
`kotlinx-serialization-json 1.8.1` + the serialization compiler plugin).

## What I tested and results

`.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.HostProfileTest"`

Result: **4 tests, 0 failures, 0 errors** (`tests="4" failures="0" errors="0"` in
`app/build/test-results/testDebugUnitTest/TEST-com.dsh.mobile.data.HostProfileTest.xml`).

## TDD Evidence

### RED (failing first)

Command:
```
.\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.HostProfileTest"
```

Output (compile failure — model not implemented yet):
```
> Task :app:compileDebugUnitTestKotlin FAILED
e: .../HostProfileTest.kt:9:28 Unresolved reference 'HostProfile'.
e: .../HostProfileTest.kt:12:17 Unresolved reference 'ProxyConfig'.
e: .../HostProfileTest.kt:19:20 Unresolved reference 'ProfileCodec'.
...
FAILURE: Build failed with an exception.
BUILD FAILED in 56s
```

### GREEN (after implementing HostProfile.kt)

Command (run through an ASCII junction — see concerns):
```
C:\hremote> .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.HostProfileTest"
```

Output:
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 4s
```

XML:
```
<testsuite name="com.dsh.mobile.data.HostProfileTest" tests="4" skipped="0" failures="0" errors="0" ...>
```

## Files changed

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/dsh/mobile/data/HostProfile.kt` (new)
- `app/src/test/java/com/dsh/mobile/data/HostProfileTest.kt` (new)

## Self-review findings

- Implementation matches the brief's code blocks 1:1 (model, codec, defaults, comments).
- No overbuilding: only the 4 listed files; nothing else in the app was touched.
- Tests verify real behavior through the public `ProfileCodec` seam (round-trip, empty-list,
  garbage-tolerance, default-value preservation) — not implementation internals.
- Note: the task brief file on disk is double-encoded (its Chinese text is mojibake). I
  recovered the intended Chinese comments via a GBK round-trip and context; the ASCII code is
  unaffected.

## Any concerns (important)

1. **Commit was swept by a concurrent agent.** My 4 files were committed by another agent
   (author `Hyna-hla`) into `2f82027` — subject `v1.1.8: DeepLook 工作区显示修复——默认工作区误显示为「标准模式」`
   — which is **already pushed to `origin/main`**. I did **not** create my own
   `feat(s1): HostProfile 数据模型 + JSON Codec + JUnit 测试设施` commit, because:
   - my changes are already present verbatim in the pushed history, and
   - rewriting pushed history / racing a still-active concurrent agent (currently mid-work on
     v1.1.9 + an untracked `TokenUsageWatcher.kt`) would be destructive.
   The controller should decide how to reconcile the commit attribution for Task 1.
2. **Non-ASCII repo path breaks the Gradle unit-test worker.** With the real path
   `E:\AI搓的小东西\harness-remote`, the forked test worker throws
   `ClassNotFoundException: com.dsh.mobile.data.HostProfileTest` even though the class compiles
   and is on the classpath (a known non-ASCII-path issue; the repo already sets
   `android.overridePathCheck=true` for a related reason). Forcing `-Dfile.encoding=UTF-8` on
   the worker did **not** fix it. **Workaround: run Gradle through an ASCII junction**
   (`mklink /J C:\hremote E:\AI搓的小东西\harness-remote`, then run from `C:\hremote`).
   This will affect every later task that runs unit tests (Task 2, 3, 4…).
