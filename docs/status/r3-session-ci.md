# r3/session 模拟器 CI 修复（PR #20）状态

- 2026-11-04 09:06 CST：**已开始**。

## 问题
r3/session@80a2ef2「仪器测试（模拟器）」：feature/chat `UiCoverageTest.everyActionHasUiEntry` 失败：
UI 形态缺少入口或结果标记：[help.powershell（Explain）, help.env（Explain）, help.easterEgg（Explain）]。

## 计划
1. 普通 merge 最新 main 进 r3/session（不 rebase、不 force）。
2. 查这三个动作的来源（pi-features.md 是否真有、PiActionCatalog 怎么登记、UiCoverageTest 如何判定 Explain 入口/结果标记）。
3. 真有 → 在 feature/chat UI 入口表补入口/结果标记（越界最小改动，PR 描述说明）；不是 pi 功能 → 从动作表移除。不削弱测试。
4. CI 全绿（含模拟器 job），更新 PR 描述。
