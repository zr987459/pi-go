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
- [x] 09:19 CI@9f6bc22（含 413cee8）：powershell、easterEgg 已过，仍剩 `help.env` 失败（偶发，时序相关）。
  第二个根因：CalmMessages 原来写的是「滚动进行中就 follow = atBottom」。程序 scrollToItem 到底的那一帧 layoutInfo 还没更新，atBottom 读成 false，贴底被关掉；之后流式内容在上方长高，就把新出现的结果块挤出视口。
  修法：follow 只在两种情况下改变：用户拖动中跟手（DragInteraction），或一次滚动结束时停在底部才贴底。程序滚到底不会再误关贴底。
- [x] 09:28 CI@b230ae9（原测试）：模拟器 job 仍红，只剩 `help.env`（powershell、easterEgg 已过）。help.env 的根因**未查明**。
- [x] 09:39 分支头 329cda5（r3-session-fix2 的审查第 2 轮提交，包含本线两次修复）CI 全绿，含模拟器 job：
  https://github.com/zr987459/pi-go/actions/runs/41754395039/job/126327027473 。
  **注意**：329cda5 同时改了 `UiCoverageTest.checkExplain`。改法是：先等结果块进入 localBlocks，再在 `calm:messages` 上 performScrollToNode 滚到块，最后 assertIsDisplayed，并附失败原因。
  所以这次变绿不只来自本线修复。这个测试改动是否算削弱，交审查者判断（本线没有改测试）。
- 状态：**模拟器 CI 已绿（329cda5），PR #20；help.env 在原测试下偶发失败的根因待查。**
  下一步：如果审查者认定测试被削弱，就恢复原 checkExplain，再在 CalmMessages 里查 help.env。线索：help.env 的说明文字最长，且紧跟在 powershell 后面。
