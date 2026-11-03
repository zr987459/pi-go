> **修复2 已完成**（2026-11-03 18:00 CST）：PR #13 审查第 2 轮 Important 2 条、Nit（Oversized、文档）已修，SessionTreeRows 写入遗留；已 merge origin/main（f9097eb）。明细与测试结果见 PR 描述「审查第 2 轮修复」。

# feat/engine-rpc（W1）状态

> **修复1 已完成**（2026-11-03 17:10 CST）：PR #13 审查第 1 轮 Important 4 条、Nit 4 条已修，明细与测试结果见下文与 PR 描述。


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

## 审查第 1 轮修复（修复1，2026-11-03）

基线：先 `merge origin/main`（cfa3725，含 PR#11 UI 改版、PR#10 W4 命令面板）→ 3bffa68，无冲突；W4 改过的 `ChatController` 原样保留，`feature/chat` 单测 92/92 通过。未改 app、build.gradle、.github、契约。

| 条目 | 修法（文件:行号，按最终提交） |
|---|---|
| Important#1 messageMeta 按 pi 1.5.0 真实序列 | `MessageMetaTracker.kt`：数据源只用 `get_entries`（全量 `reset` / `since=lastId` 增量 `merge` :54），不再靠 `entry_appended` 拼接；每份快照带 covered（请求前已收到 message_end 的 key），`compute` :93 只用 covered 里的气泡从末尾配对，尾部未写盘的新气泡不参与；`context_edit(replacement=null)` 的目标从投影排除 :95，时间线上已重试的失败助手消息不配对 `retriedFailures` :128；链断只用叶子一侧。`PiEngine.kt`：`onMessageEnded` :350（user/assistant/toolResult 的 message_end 与 entry_appended 都触发增量拉取），`refreshEntriesOnce` :1134 由单协程串行处理，请求期间又有 message_end 则丢弃快照重拉。 |
| Important#2 会话替换/失败/乱序 | `PiEngine.kt` `resetMeta` :361：在**同一次归约**里作废旧簿记（epoch++）再换时间线（:647 rebuild、:1027 切会话、`clearedAll`、boot）；fork 后 reloadAfterSwitch 走 rebuild 同样作废；响应 epoch 不符即丢弃（不会用旧会话的 entry 配对、不会覆盖更新状态）；get_entries 失败（超时 / >32 MB）清空并发布空表（界面按契约禁用分叉），since 找不到时改全量；长会话走 since 增量。 |
| Important#3 通知前台判定 | `AndroidTaskNotifications.kt` `ForegroundDecider` :24：Activity 计数 > 0 **或** `ActivityManager.getMyMemoryState` 重要性 = FOREGROUND(100)；迟注册（AppGraph by lazy）不再恒判后台，后台只剩前台服务时为 125 不误判。未用 ProcessLifecycleOwner：core/engine 未声明 lifecycle-process，按 §3.3 不改 build.gradle.kts；未改 app。测试：JVM `TaskNotifierTest`（迟注册、started 不发、stopped 后发一条、aborted 不发）；仪器 `core/engine/src/androidTest/.../AndroidTaskNotificationsTest.kt`（pigo-tasks 通道已建、Android 13 未授权不崩）。Robolectric 需维护者在 core/engine 加 `testImplementation("org.robolectric:robolectric")` 后才能补。 |
| Important#4 get_tree 深度 | `ExtraResponses.kt` `buildForest` :147：显式栈后序构建，去掉 500 层上限与拉平，保留真实父子结构；新增 `EngineRpcReducerTest.deepTreeKeepsBranchStructureBeyond500`（第 600 层两分支逐层断言）。 |
| Nit 解析失败空等 | `RpcChannel.kt` `failMalformedResponse` :172：行首抽 id，立即以 RPC_REJECTED「会话太大，无法读取」结束；Oversized 帧拿不到行内容，仍按超时处理。 |
| Nit stderr 栈 | `RpcChannel.kt:79` stderr 线程用默认栈，只给 stdout 读线程 64 MB。 |
| Nit TurnStats | `ChatReducer.kt:91,475` + `TurnStats.kt`：传 cwd；以 agent_start 时的最后一条为本轮起点，steer 前写的文件不漏。 |
| Nit bash 丢块 | `PiEngine.kt:1300` 每个 bash 一条无界 Channel，响应后先归约完已入队增量再关闭。 |

### 本地测试（沙箱 JDK17 + Android SDK + 真 pi 1.5.0）
- `:core:protocol:test` 105/105 通过（新增：MessageMetaTracker 未写盘尾部气泡 / context_edit+重试 / 链断 / since 增量，深树结构，解析失败立即结束请求，TurnFiles）。
- `:core:engine:testDebugUnitTest` 75 个，0 失败，1 跳过（RecordFixturesTest，需录制）；含 `PIGO_PI_BIN=pi 1.5.0` 的真 pi 集成 RealPiIntegrationTest 8/8、RealPiExtrasIntegrationTest 8/8（新增 `messageMetaNeverPairsNewBubbleWithPreviousEntryMidTurn`：第二轮全程监听，新气泡从未配到第一条 entry，轮末配到内容为「第二句」的 user entry）。
- 脚本 pi 测试 `messageMetaFollowsReal150SequenceWithAutoRetry` 按真实序列：普通消息不发 entry_appended、message_end 后才写盘、一次自动重试（失败助手 + context_edit + 重试成功），全程断言无错配。
- `:feature:chat:testDebugUnitTest` 92/92。
- 未运行：androidTest（本机 AAPT2 不可用，未编译）；`:app` 单测。CI 只跑 assembleDebug。
