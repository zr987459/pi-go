# r3/session 状态

**已开始**（2026-11-04 07:35 CST）：从 r3/core-base@818aa93 拉出 r3/session。

## 计划（DESIGN-r3.md 工作流 ②、§8.1 文件范围、§8.5 验收）
1. 读 DESIGN-r3 ② 段、§8.1/§8.2/§8.5/§7，确定文件范围与验收项。
2. 按范围实现（基于 core/contract 契约与 FakeSessionHost，不改 core/contract 已有文件）。
3. 单测 / 仪器测试覆盖 §8.5 验收项；CI 绿。
4. PR #15 合入 main 后 merge main（不 rebase），开 PR 到 main。

## 进度
- 07:35 建分支，读设计文档中。
- 07:37 `docs/r3/coverage-map.tsv`（229 行：191 动作、17 BASE、16 FORM、5 NA）由 pi-features.md §1–§11 表格行生成，映射依据 DESIGN-r3 §2 各表；`PiActionCatalog` 新增 `ext.commands`、`help.powershell`、`help.env`、`help.easterEgg`（共 79 个）。
- 下一步：`PiFeatureCoverageTest` → `SessionHostImpl` + `PiActionsImpl` → `FormSwitchContractTest` / `SessionHostFormSwitchTest` → `PiSettings` → `SessionCatalog` → §7.3 测试 → 移动 LineDiff/SessionTreeRows/Ansi → 删除 AutoNamer/AutoTitle/commands ui。
- 08:05 core/session 初版（未编译验证）：`SessionHostImpl`、`PiActionsImpl`（全部 79 个动作，`SessionOps` 承接契约里还没有的 abort_retry / 取回队列 / 停引擎 / 标签 / reload）、`QueueRecall`、`AtFileExpansion`（依据 pi-tui 1.5.0 `buildCompletionValue`）、`PiSettingsImpl`（文件锁、只改目标键）+ `PiSettingsSchemaV150`（§8 全部键）、`SessionCatalogImpl`（5 秒撤销、临时会话）。
- 下一步：编译修错 → 写 PiFeatureCoverageTest、FormSwitchContractTest、SessionHostFormSwitchTest、AbortRetryTest、QueueBackToDraftTest、AtFileExpansionSnapshotTest、PiSettingsSnapshotTest、PiSettingsSchemaCoverageTest、LocalBlocksNotPersistedTest。
