# r3/core 审查第 1 轮修复（PR #16）

- 2026-11-04 08:00 CST：已开始。
- 计划：
  1. PiEngine 桥接命令按类型给超时（tree-nav/reload/switch 用长超时，COMPACT_MS 生效）；超时后 get_state/get_messages 对账刷新；加测试。
  2. RootfsWorkspace.gitStatus 恢复真实行为（或明确"不可用"），旧 Git 页不误报干净。
  3. EngineBindings.kt 两行越界：PR 描述注明"调度者 2026-11-04 授权"，不扩大。
  4. 新增 docs/notices/r3-core.md（FileSearch / branchName / ProjectTrustFile 来源，MIT）。
  5. Nit：PigoBridge 按请求 id 关联回复；切会话取消尽快报错；fork 退路与 TreeNavResult 带回 editorText；PiEngineTest:827 时序稳定化。
- 进度：见下方更新。

## 进度 08:06 CST
- 已改（第一次推送，待 CI）：
  - PiEngine 桥接：bridgeCall 不再走 callCmd 的 15 s；prompt 与 waiter 同超时（label=bridgeReplyTimeoutMs，tree-nav/reload/switch=bridgeSlowTimeoutMs，--summarize=bridgeSummarizeTimeoutMs=COMPACT_MS）；prompt 被拒立即失败；超时对账（tree-nav：get_messages 重建 + get_tree 取叶子，返回 unconfirmed；switch：get_state 已在目标即成功；reload：清命令缓存）；迟到回复在 bridgeLateWindowMs 内再重建。
  - 每种桥接命令一把 Mutex；label 回复按 entryId 关联；switch 的 cancelled 回复立即报 CANCELLED。
  - TreeNavResult 加 editorText / unconfirmed；pigo-bridge.ts 回传用户消息原文；fork 退路带回 text，非用户消息先说明不发 fork。
  - RootfsWorkspace.gitStatus → Err(GIT_REMOVED)。
  - docs/notices/r3-core.md。
  - PiEngineTest.promptTimeout… 改闸门（CountDownLatch），去掉 300 ms 时间差。
  - 新测试：PiEngineBridgeTest 6 条（slowSummarize…、treeNavTimeoutReconciles…、switchTimeoutReconciles…、switchCancelledByExtensionFailsFast、rejectedPromptFailsFast、concurrentLabelsGetTheirOwnReplies）；RealPi 断言 editorText。
- 下一步：看 CI（Android debug build、真 pi 桥接集成测试），修红；更新 PR #16 描述（范围外改动：EngineBindings.kt:76-77 调度者 2026-11-04 授权）。

## 完成，PR #16（08:16 CST）
- 修复提交 c71f4fd：CI 全绿。Android debug build：run 41744276606（pull_request）、41744271242（push），87 个测试类 679 个用例，失败 0，跳过 29。真 pi 桥接集成测试：run 41744276227、41744271268，5/5 通过，无跳过，treeNav 用例新增的 editorText 断言也通过。
- PR #16 描述已更新：「范围外最小改动」注明调度者 2026-11-04 授权；新增「审查第 1 轮修复」表，逐条列出处理与证据。
- 未完成（与修复前相同）：TurnStats 收缩（等 r3/app 删除 feature/usage）；`include(":feature:usage")` 残留按 §8.3-5 单独提小 PR；FakePiBridge 没有填 editorText（可选，由 r3/session 按需补）。
