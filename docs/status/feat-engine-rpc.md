# feat/engine-rpc（W1）状态

- 2026-11-03 15:47 CST：**续做已开始**（接手上一位 W1 工作者；其本地未推送的 15 个文件已丢失，按交接说明重做）。
- 已 merge `origin/feat/modules@f183692`（W0 契约第 1 轮修复：messageMeta、CycleModelResult、RunOutcome.cwd、Compaction.error/aborted），merge 提交 `17640cf`。不 rebase、不强推。PR #8 仍未合入 main。
- 依据：`docs/feature-gap.md` §3.2 W1、§3.3。

## 已完成（远端）
- `905ec41` 协议层：RpcCommand/RpcRecords 只追加 21 个命令与新事件；core/protocol 新事件解码、超时表、get_tree 深层 JSON 栈溢出修复、ExtraResponses/CompactionSummary/TurnStats/RunResults 初版；rpc-types.d.ts 字段核对与序列化快照测试。
- `1a8de06`、`17640cf`：合入 feat/modules。

## 剩余（按顺序）
1. 重做丢失的协议层改动：Chat.kt 保持 main 原样；ExtraResponses 直接产出契约类型；ChatReducer 维护 timelineExtras（压缩卡含 error/aborted、bash 卡、带图确认、TurnSummary）；RunResults 产出契约 RunOutcome（含 ABORTED、cwd）。
2. 引擎层：PiEngine 实现 EngineExtras（含 messageMeta：get_entries 全量 + entry_appended 增量；cycleModelResult 直接解析响应）；stats() 的 contextPercent 不回退旧值；发布 timelineExtras/runOutcomes；EngineConfig.timeoutFor 用新超时表；EngineBindings.kt 接入。
3. N1 任务通知（pigo-tasks 通道，后台才发，ABORTED 不发）。
4. 真 pi 1.5.0 集成测试。
5. notices、docs/protocol、assembleDebug、开 PR。
