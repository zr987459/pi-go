# r3/session 状态

**已开始**（2026-11-04 07:35 CST）：从 r3/core-base@818aa93 拉出 r3/session。

## 计划（DESIGN-r3.md 工作流 ②、§8.1 文件范围、§8.5 验收）
1. 读 DESIGN-r3 ② 段、§8.1/§8.2/§8.5/§7，确定文件范围与验收项。
2. 按范围实现（基于 core/contract 契约与 FakeSessionHost，不改 core/contract 已有文件）。
3. 单测 / 仪器测试覆盖 §8.5 验收项；CI 绿。
4. PR #15 合入 main 后 merge main（不 rebase），开 PR 到 main。

## 进度
- 07:35 建分支，读设计文档中。
