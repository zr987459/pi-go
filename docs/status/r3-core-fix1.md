# r3/core 审查第 1 轮修复（PR #16）

- 2026-11-04 08:00 CST：已开始。
- 计划：
  1. PiEngine 桥接命令按类型给超时（tree-nav/reload/switch 用长超时，COMPACT_MS 生效）；超时后 get_state/get_messages 对账刷新；加测试。
  2. RootfsWorkspace.gitStatus 恢复真实行为（或明确"不可用"），旧 Git 页不误报干净。
  3. EngineBindings.kt 两行越界：PR 描述注明"调度者 2026-11-04 授权"，不扩大。
  4. 新增 docs/notices/r3-core.md（FileSearch / branchName / ProjectTrustFile 来源，MIT）。
  5. Nit：PigoBridge 按请求 id 关联回复；切会话取消尽快报错；fork 退路与 TreeNavResult 带回 editorText；PiEngineTest:827 时序稳定化。
- 进度：见下方更新。
