# feat/engine-rpc（W1）状态

> **修复1 进行中**（2026-11-03 16:48 CST）：PR #13 审查第 1 轮，Important 4 条 + Nit 4 条；先 merge origin/main（cfa3725）。


- 2026-11-03 15:47 CST：**续做已开始**（接手上一位 W1 工作者；其本地未推送的 15 个文件已丢失，按交接说明重做）。
- 已 merge `origin/feat/modules@f183692`（W0 契约第 1 轮修复：messageMeta、CycleModelResult、RunOutcome.cwd、Compaction.error/aborted），merge 提交 `17640cf`。不 rebase、不强推。PR #8 仍未合入 main。
- 依据：`docs/feature-gap.md` §3.2 W1、§3.3。

## 已完成（远端）
- `905ec41` 协议层：RpcCommand/RpcRecords 只追加 21 个命令与新事件；core/protocol 新事件解码、超时表、get_tree 深层 JSON 栈溢出修复、ExtraResponses/CompactionSummary/TurnStats/RunResults 初版；rpc-types.d.ts 字段核对与序列化快照测试。
- `1a8de06`、`17640cf`：合入 feat/modules。

- 16:05 协议层重做（本地 `:core:protocol:test` 99/99、`:core:contract:test` 28/28 通过）：`Chat.kt` 恢复 main 原样（不加 ChatItem 子类）；ChatReducer 以 `timelineExtras` 输出压缩卡（含 error/aborted/manual）、bash 卡（按 id 归卡）、用户图片、轮末汇总（实时 + 历史重建），时间线 key 与 main 快照不变；`RunResults` 直接产出契约 `RunOutcome`（含 ABORTED、cwd、脱敏、≤200 字）；新增 `ContractMapping`（记录→契约类型，含 cycleModelResult 解析）与 `MessageMetaTracker`（get_entries + entry_appended，当前分支，从尾部配对，toolResult 按 toolCallId）。

- 16:25 引擎层：`PiEngine` 同时实现 `EngineExtras`（全部方法；messageMeta 由 get_entries + entry_appended 维护；cycleModelResult 直接解析；bash 冷流按 id 归卡；compact 定稿压缩卡并刷新用量）；`stats()` 的 contextPercent 不再回退旧值（U2）；发布 timelineExtras / runOutcomes / usageState / extensionSurface；`EngineConfig.timeoutFor` 用新超时表。`:core:engine:testDebugUnitTest` 原有用例全过。

- 16:40 N1：`TaskNotifier`（JVM 逻辑：后台才发、ABORTED 不发、同会话复用 id）+ `AndroidTaskNotifications`（`pigo-tasks` 通道，点通知带 sessionFile/cwd 打开 App）+ `ActivityVisibility`；`EngineBindings` 接入通知并提供 `extras(engine)`。新增 `PiEngineExtrasTest`（7）、`TaskNotifierTest`（1）；`:core:engine:testDebugUnitTest` 56 过 + 9 个真 pi 用例跳过（未配置）。

- 16:55 真 pi 1.5.0 集成：本地（linux aarch64、Node v22.23.3、`npm i @earendil-works/pi-coding-agent@1.5.0`）`RealPiExtrasIntegrationTest` 7/7 通过（get_commands 列出 prompts/x.md、fork 后 sessionFile 改变且 text 正确、get_tree ≥2 层、compact 成功、bash echo hi、export_html 文件存在、1×1 PNG 以 image_url 到达 mock），原有 `RealPiIntegrationTest` 8/8 通过。`MockOpenAiServer` 记录 image_url。轮末再全量 get_entries 一次（messageMeta 防漏）。`docs/notices/feat-engine-rpc.md`、`docs/protocol/engine-rpc.md` 已写。

- 17:00 全量本地：`:core:contract:test` 28/28、`:core:protocol:test` 99/99、`:core:engine:testDebugUnitTest` 72 过 1 跳过（RecordFixturesTest 录制开关）0 失败（含真 pi 15 个）。`assembleDebug` 本地 linux aarch64 在 `:app:processDebugResources`（aapt2 只有 x86_64）失败，属环境问题，以 CI 为准。

- 17:10 已开 PR #13 到 main；`1e043c8` 的 push run 41603561762、PR run 41603630161 均为 success。

## 状态：**已完成，待审查**（遗留见 PR #13 描述）
