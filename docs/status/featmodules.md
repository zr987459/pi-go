# feat/modules 状态（W0 前置）

**状态：已开始**（2026-11-03 14:50 CST）

## 计划
1. 新模块骨架：core/catalog、core/workspace、feature/models、feature/files、feature/commands、feature/usage（build.gradle.kts + Placeholder）。
2. settings.gradle.kts include；libs.versions.toml 加 Coil、androidx.browser、exifinterface。
3. core/contract 只新增：EngineExtras.kt、Catalog.kt、Workspace.kt、fake/**（每个 Fake 一条单测）。
4. docs/notices/ 目录与说明。
5. REVIEW.md 按决策 2 修改；DESIGN §3.10 按决策 1 增补。
6. 本地 assembleDebug testDebugUnitTest → 推送 → CI 绿 → PR 到 main。
