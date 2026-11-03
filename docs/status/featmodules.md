# feat/modules 状态（W0 前置）

**状态：审查第 1 轮修复 1 进行中**（2026-11-03 15:15 CST 接手）

> 修复1 进行中：PR #8 审查第 1 轮 Important 3 条 + Nit 8 条。

（上一状态：完成，PR #8 已开到 main，2026-11-03 15:08 CST）

## 已完成
- 新模块骨架：core/catalog、core/workspace、feature/models、feature/files、feature/commands、feature/usage。
- settings.gradle.kts include；libs.versions.toml 加 coil-compose 2.7.0、androidx.browser 1.8.0、androidx.exifinterface 1.3.7。
- core/contract 只新增：EngineExtras.kt、Catalog.kt、Workspace.kt、fake/**（10 个 Fake），ExtrasFakesTest 10 条。
- docs/notices/README.md、feat-modules.md；REVIEW.md（决策 2）、DESIGN §3.10（决策 1）、feature-gap.md（决策 1–3，TimelineExtra 代替新增 ChatItem 子类）。

## 验收
- CI（push）run 41593237794：assembleDebug success（含 6 个新模块）。
- core/contract 单测（本地 JDK 17 + Gradle 8.11.1 独立工程）：23 条全过（新增 10 + 原有 13）。
- 全仓 testDebugUnitTest 本地未跑：沙箱 Android SDK 已丢失；CI 只跑 assembleDebug。

## 给 W1–W4
- 契约已冻结，按 EngineExtras / Catalog / Workspace 实现；先用 contract/fake 里的 Fake 开发。
- 时间线新内容走 EngineExtras.timelineExtras，不要给 ChatItem 加子类。
