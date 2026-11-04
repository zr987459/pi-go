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

## 进度
- [x] 09:10 已普通 merge main → r3/session（3783a2d，POST /merges，无冲突）。
- [x] 09:15 根因：三个动作是 pi 真有功能的 NA 说明条目（pi-features §4 powershell、§8.1 环境变量、§11 彩蛋；DESIGN-r3 §2 NA 行 → help.* Explain），应保留在动作表。
  UiCoverageTest 按目录顺序逐个点；它们排在 `transcript.jump`（VIEW_LOCAL_ACTIONS，jumpTick++ → CalmMessages `follow = false` 并滚到上一条提示）之后。
  之后追加的「说明」本地块落在视口外，LazyColumn 不组合 → `local:explain:<id>` 找不到。这是真实 UX 缺陷：跳到上方后再执行说明类动作，用户看不到结果。
- [x] 修复（越界最小改动，feature/chat/src/main/**/ui/calm/CalmMessages.kt，属 r3/calm）：本地结果块条数增加时恢复贴底并滚到新块；初值取进入时条数，切形态回来不强制滚动（§5.3）。测试未改。
- [ ] 等 CI（含模拟器 job）全绿 → 更新 PR #20 描述 → 状态写「完成，PR #20」。
