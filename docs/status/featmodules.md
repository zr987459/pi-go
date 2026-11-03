# feat/modules 状态（W0 前置）

**状态：审查第 1 轮修复完成，等 CI**（2026-11-03 15:40 CST）。修复1 已结束。

## 审查第 1 轮修复（契约只追加，不改名、不删）
- [Important] 新增 `MessageMeta`（entryId、role、usage、costUsd、model）与 `EngineExtras.messageMeta: StateFlow<Map<ChatItem.key, MessageMeta>>`。接口给了默认空表以免打断 W1–W4 编译，**W1 必须覆盖**（get_entries + entry_appended 维护）；拿不到 entryId 时界面禁用“从这里分叉”，不按文本匹配。Fake 带夹具，可 `setMessageMeta`。
- [Important] docs/notices/feat-modules.md 补登 Coil 2.7.0（含 Okio 3.9.0 等传递依赖）、androidx.browser 1.8.0、androidx.exifinterface 1.3.7，均 Apache-2.0。
- [Important] FakeEngineExtras 分叉对齐 pi 1.5.0：`forkMessages()` 从 treeFixture 筛全部用户消息（e1、e3、e5）；`fork()` 只接受这些 id，其余 `Err(RPC_REJECTED)`。
- [Nit] `SessionSettings.autoRetry` 注释写明来自 settings.json、取不到为 null；Fake 初值改 null。
- [Nit] 新增 `CycleModelResult{model, thinkingLevel, isScoped}` 与 `cycleModelResult()`（默认实现包装旧 `cycleModel()`，W1 应覆盖）；旧方法保留不改签名。
- [Nit] `RunOutcome.cwd: String? = null`（追加在末尾）。
- [Nit] `TimelineExtra.Compaction` 追加 `error: String? = null`、`aborted: Boolean = false`。
- [Nit] `SessionExporter.export` 注释：RPC `export_html` 只导当前会话，非当前会话用 `pi --export <file> [output]`（已在 1.5.0 `dist/cli/args.js` 核对）。
- [Nit] FakeWorkspaceFs.importStream 根目录不再拼出 `//name`。
- [Nit] feature-gap.md 第 6、45 行改为已拍板结论。
- ExtrasFakesTest 新增 5 条（分叉、messageMeta、cycleModelResult 含接口默认实现、追加字段默认值、根目录导入）。

## 本地测试（aarch64 沙箱，JDK 17、Gradle 8.11.1、AGP 8.7.3）
- `:core:contract:test`：28/28 通过（原 23 + 新增 5）。
- `testDebugUnitTest`：core/protocol 72、core/runtime 62、core/engine 57（9 跳过）、feature/chat 65、feature/settings 14、feature/sessions 9、feature/onboarding 5 全过；core/store 48 条中 1 条失败：`KeystoreSecretStoreJvmTest.cryptoRunsOnInjectedIoDispatcher`，线程名带协程调试后缀（`fake-io @coroutine#1` ≠ `fake-io`），是本机环境问题，本分支未改 core/store。
- `:app:testDebugUnitTest` 与 `assembleDebug`：本机跑不了。AAPT2 只有 x86-64 版本，在 aarch64 上报 `Syntax error: "(" unexpected`。这两项以 CI 为准。

## 首轮交付（W0）

- 新模块骨架：core/catalog、core/workspace、feature/models、feature/files、feature/commands、feature/usage。
- settings.gradle.kts include；libs.versions.toml 加 coil-compose 2.7.0、androidx.browser 1.8.0、androidx.exifinterface 1.3.7。
- core/contract 只新增：EngineExtras.kt、Catalog.kt、Workspace.kt、fake/**（10 个 Fake），ExtrasFakesTest 10 条。
- docs/notices/README.md、feat-modules.md；REVIEW.md（决策 2）、DESIGN §3.10（决策 1）、feature-gap.md（决策 1–3，TimelineExtra 代替新增 ChatItem 子类）。

### 首轮验收
- CI（push）run 41593237794：assembleDebug success（含 6 个新模块）。
- core/contract 单测（本地 JDK 17 + Gradle 8.11.1 独立工程）：23 条全过（新增 10 + 原有 13）。
- 全仓 testDebugUnitTest 本地未跑：沙箱 Android SDK 已丢失；CI 只跑 assembleDebug。

### 给 W1–W4
- 契约已冻结，按 EngineExtras / Catalog / Workspace 实现；先用 contract/fake 里的 Fake 开发。
- 时间线新内容走 EngineExtras.timelineExtras，不要给 ChatItem 加子类。
- 审查第 1 轮追加：分叉用 `messageMeta[item.key].entryId`（只对 `forkable` 的用户消息）；单条用量也从 messageMeta 取；`cycleModelResult()` 优先于 `cycleModel()`。
