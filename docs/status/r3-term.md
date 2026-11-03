# r3/term 状态（工作流 ⑤ 终端形态）

**已开始** 2026-11-04 07:35 CST，基于 r3/core-base@818aa93。

## 计划
1. 读 DESIGN-r3.md 的 §3（pi TUI 还原）、§7、§8.1/8.2/8.5 和派发表 ⑤ 段，以及 pi-features.md。
2. 在 §8.1 规定的终端形态文件范围内，基于 FakeSessionHost 实现：消息流渲染、编辑器/草稿、斜杠命令（PiActionCatalog）、状态栏/页脚、键位、一键切到 UI 形态（共用会话和引擎，保留草稿）。
3. 补单测和截图快照，作为验收证据。
4. PR #15 合入后，把 main merge 进本分支（不 rebase），再开 PR 到 main，盯 CI 变绿。

## 进度
- [ ] 读设计
