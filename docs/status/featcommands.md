# feat/commands（W4）状态

> **审查第 1 轮修复：修复1 完成（带图对账），修复2 进行中**（2026-11-03 15:5x CST 接手，基于 d2ac502）：先 merge origin/main（f1f0e02），再逐条修 Important 1–4、Nit 1–4，每条单独提交。

**状态：实现与单测完成，已推送，等待 CI**（2026-11-03 15:40 CST）

分支历史：14:55 从 main@f0a4a58 切出放状态文件 → 15:04 merge origin/feat/modules@749d7b2（W0 契约，CI 绿）→ 本次提交实现。W0 合入 main 后 `merge origin/main`（不 rebase、不 force）。

## 已完成
| 功能 | 位置 |
|---|---|
| C3 斜杠候选（按来源分组、前缀/分段/包含/描述排序、按会话缓存、换会话刷新） | feature/chat/logic/SlashCompletion.kt、ChatController；面板 UI feature/commands/ui/CommandPalette.kt |
| C6 `!` / `!!` 走 bash，失败退回输入框 | feature/chat/logic/SlashCompletion.kt（InputRouting）、ChatController |
| C1 带图发送走 EngineExtras.send，失败重试保留图片，「改一改」退回图片，纯文本模型拒绝 | ChatController、logic/ChatRows.kt（LocalSend.images） |
| S7 按会话草稿（切会话换出换入、打字 500 ms 后写盘、重启恢复） | ChatController + feature/commands/data/DraftStore.kt（FileDraftStore） |
| S1 分叉：fork 后原文写回新会话输入框；会话树页（缩进、当前分支、3 叶子、折叠、复制会话） | ChatController.forkFrom；feature/usage/logic/SessionTreeRows.kt、UsageController.kt（SessionTreeController）、ui/SessionTreeScreen.kt |
| C4 技能与模板：列表、查看、启用/停用（settings.json `-路径`）、新建模板 | feature/commands/skills/*、ui/SkillsScreen.kt |
| S3 会话全文搜索 + 增量扫描索引（续读、名字取最后 session_info，修 G8） | feature/commands/data/SessionScanner.kt、PiSessionIndex.kt（实现契约 SessionIndex） |
| S4 置顶/归档/未读/名字锁定、按 cwd 分组 | data/SessionMeta.kt、PiSessionIndex |
| S5 自动命名（首轮完成后 `pi --print --no-session --no-tools`，用户改过名不覆盖） | data/AutoTitle.kt、AutoNamer.kt |
| U1/U2/U3 用量弹层、上下文条（压缩后「待更新」）、手动压缩（可带指示）、美元 + 可选人民币（默认 7.1） | feature/usage/logic/UsageController.kt、UsageFormat.kt、ui/UsageSheet.kt |
| U4 压缩卡展开（摘要、读/改文件） | logic/CompactionSummary.kt、ui/CompactionCard.kt |
| C10 轮末汇总组件 | logic/TurnSummary.kt、ui/TurnSummaryRow.kt |
| C11 edit diff（优先 details.patch，否则按 edits 做行级 diff） | logic/LineDiff.kt、TurnSummary.kt（EditToolDiff）、ui/DiffView.kt |
| 装配 | feature/commands/CommandsGraph.kt（fake/real） |

## 本地验证（aarch64 沙箱，JDK 17.0.21 + Gradle 8.11.1 + SDK 35）
- `:feature:chat:testDebugUnitTest` 78/78（新增 ChatControllerExtrasTest 13 条，原 65 条不变）
- `:feature:commands:testDebugUnitTest` 15/15；`:feature:usage:testDebugUnitTest` 9/9；`:core:contract:test` 23/23
- androidTest（Compose）3 条：本地 aapt2 只有 x86_64，无法打测试 APK、无模拟器；已用临时 testImplementation 编译通过，未运行。
- `assembleDebug`：本地同样受 aapt2 限制，以 CI 为准。

## 遗留
- app 不依赖 `:feature:commands` / `:feature:usage`（build 文件归 scaffold），本分支没加 `app/di/CommandsBindings.kt`；W5 需在 app/build.gradle.kts 加两行依赖后调用 `CommandsGraph.real(...)`。
- 界面入口（顶栏、输入框、抽屉、会话列表、时间线渲染 TimelineExtra）按计划留给 W5。
