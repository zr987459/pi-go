# feat/modules 状态（W0 前置）

**状态：代码已推送，等待 CI**（2026-11-03 15:00 CST）

## 已完成
- 新模块骨架：core/catalog、core/workspace、feature/models、feature/files、feature/commands、feature/usage（build.gradle.kts + Placeholder）。
- settings.gradle.kts include；libs.versions.toml 加 coil-compose 2.7.0、androidx.browser 1.8.0、androidx.exifinterface 1.3.7。
- core/contract 只新增：EngineExtras.kt、Catalog.kt、Workspace.kt；fake/FakeEngineExtras.kt、FakeCatalog.kt、FakeWorkspace.kt、FakeSessionData.kt；测试 ExtrasFakesTest（10 条，每个 Fake 一条）。
- docs/notices/README.md 与 feat-modules.md。
- REVIEW.md（决策 2）、DESIGN §3.10（决策 1）、feature-gap.md（三项决策、TimelineExtra 代替新增 ChatItem 子类）。

## 说明
- 本地沙箱的 JDK/SDK 已丢失（沙箱重置），本轮以 CI 作为编译与单测依据。
