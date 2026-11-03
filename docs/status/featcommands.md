# feat/commands（W4）状态

- 2026-11-03 14:55 CST：**已开始**。分支先从 main@f0a4a58 切出，用来放状态文件；W0（feat/modules）的契约提交出现且 CI 变绿后，用 `git merge origin/feat/modules` 并入（只 merge，不 rebase，不 force）。W0 合入 main 后再 `merge origin/main`。

## 计划（按 docs/feature-gap.md §3.2 W4、§3.3）

允许改：`feature/commands/src/**`、`feature/usage/src/**`、`feature/chat/src/main/**/ChatController.kt`、`feature/chat/src/main/**/logic/**`、`feature/chat/src/test/**`、`app/src/main/java/**/di/CommandsBindings.kt`（新文件）、`docs/notices/feat-commands.md`、`docs/status/featcommands.md`。
不碰：`feature/chat/**/ui/**`、`ChatEntry.kt`、`core/**`（含 contract、store）、`feature/sessions|settings|onboarding/**`、`app/nav`、`AppGraph.kt`、gradle 文件、`.github/`、`THIRD_PARTY_NOTICES.md`。

阶段：
1. 通读：feature-gap、main 上 `feature/chat` 的 Controller 与 logic、`core/contract`、pi 1.5.0 `rpc-types.d.ts`（get_commands / fork / get_tree / compact / get_session_stats / bash）、Pi-Web 对应实现（命令面板、session-list-scanner、session-title、compaction-summary、turn-* 汇总、diff）。
2. `feature/chat` 逻辑层：`/` 候选过滤、`!`/`!!` 走 `bash`、带图发送走 `sendWithImages` 且失败重试保留图片、按会话草稿换入换出。
3. `feature/commands`：命令面板状态（按 source 分组、前缀过滤、按 cwd 失效的缓存）、技能与模板管理（列表、查看、新建模板）、会话数据增强 `SessionIndex`（增量扫描、名字取最后一个 session_info、全文搜索）、置顶/归档/分组数据层、自动命名、草稿存储。
4. `feature/usage`：用量弹层（tokens、美元费用、可选人民币，汇率可配置默认 7.1）、上下文条（percent 为空显示“压缩后待更新”）、压缩操作（可带指示）、压缩卡展开页（U4）、会话树页与“从这里分叉”、轮末汇总组件（C10）、edit diff 组件（C11）。
5. `CommandsBindings.kt`（fake/real 两种装配，real 在缺契约方法时用本模块适配层）。
6. 测试：feature-gap W4 验收里列的单测与 Compose 测试全部写上并在 CI 跑。
7. 开 PR 到 main。

## 进度
- [ ] 1 通读
- [ ] 2–6 实现
- [ ] CI 绿
- [ ] PR
