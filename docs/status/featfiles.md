# feat/files（W3）状态

- 状态：**已开始**（2026-11-03 14:51 CST）
- 当前阶段：通读 docs/feature-gap.md（feat/gap）§3.2 W3 与 §3.3 归属表、Pi-Web、pi 1.5.0 rpc-types.d.ts、dsh-Go 的对应部分。
- 分支说明：本分支暂从 main 切出，仅放状态文件；W0（feat/modules）契约提交出现且 CI 绿后 `git merge origin/feat/modules` 再开工（不 rebase、不强推）。

## 计划
1. 读文档与上游，确认 W3 的功能清单、允许改范围、验收项。
2. 等 W0 契约（contract/EngineExtras.kt 等）就绪后合入，按归属表在本工作流模块内实现；契约缺方法时在本模块加适配层。
3. 补单测，跑本地测试，CI 绿后开 PR 到 main，移植来源记入 docs/notices/feat-files.md。
