# feat/engine-rpc（W1）状态

- 2026-11-03 14:52 CST：**已开始**。
- 依据：`feat/gap` 分支 `docs/feature-gap.md` §3.2 W1、§3.3。
- 分支来源：为满足“2 分钟内推状态文件”，先从 `main@f0a4a58` 建分支只放本文件；`origin/feat/modules` 出现契约（`contract/EngineExtras.kt` 等）且 CI 绿后，`git merge origin/feat/modules`（不 rebase、不强推）再开工；W0 合入 main 后 `git merge origin/main`。

## 计划
1. 通读：feature-gap.md、DESIGN.md、REVIEW.md、现有 `core/protocol`/`core/engine`/`rpc/*`、pi 1.5.0 `dist/modes/rpc/rpc-types.d.ts`、Pi-Web 对应实现、dsh-Go 对应实现。
2. 协议层：在 `rpc/RpcCommand.kt`、`rpc/RpcRecords.kt` 只追加 images、get_commands、bash/abort_bash/bash_execution_update、get_last_assistant_text、get_fork_messages/fork/clone/get_tree、get_entries、get_session_stats（完整）、compact 与 compaction 事件、set_auto_compaction/set_auto_retry/set_steering_mode/set_follow_up_mode、export_html。
3. 引擎层：`EngineExtras` 真实实现（`core/engine`），ChatItem 新子类（压缩卡、bash 卡，只追加），contextUsage.percent==null → contextPercent==null，C10 派生数据（本轮改动文件、耗时、速率）。
4. N1：任务通知通道 + `RunOutcome`（完成/出错/需要回答，aborted 不发）。
5. `EngineBindings.kt` 接入；契约缺方法时在 `core/engine` 内加适配层，不改 W0 契约。
6. 测试：JVM 快照单测；桌面真 pi 1.5.0 集成测试（realpi/）；通知 Robolectric。
7. `docs/notices/feat-engine-rpc.md`、`docs/protocol/**`；开 PR 到 main。

## 阶段
- [ ] 阅读与计划
- [ ] 等待 W0 契约
- [ ] 协议层
- [ ] 引擎层
- [ ] 通知
- [ ] 测试与 CI
- [ ] PR
