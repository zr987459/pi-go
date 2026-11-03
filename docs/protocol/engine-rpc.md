# pi 1.5.0 RPC 在 pi-go 中的完整映射（W1 feat/engine-rpc）

字段名以 `@earendil-works/pi-coding-agent@1.5.0` 的 `dist/modes/rpc/rpc-types.d.ts` 为准（副本在
`core/protocol/src/test/resources/pi-1.5.0/`，`RpcCommandsSnapshotTest` 做序列化快照与字段核对）。

## 命令 → 接口

| pi 命令 | pi-go 入口 | 说明 |
|---|---|---|
| `prompt{images}` / `steer{images}` / `follow_up{images}` | `EngineExtras.send(text, images, mode)` | `images[]` 为 `{type:"image", data, mimeType}`；乐观气泡按「文本 + 图片签名」与 pi 回显配对；图片经 `TimelineExtra.UserImages` 挂在用户气泡后 |
| `get_commands` | `commands(refresh)` | 按 cwd 缓存；`source`→`CommandSource`，`sourceInfo.path/scope/origin` |
| `bash` / `abort_bash` / `bash_execution_update` | `bash(command, excludeFromContext)`（冷流）/ `abortBash()` | 卡片 key `bash-<命令 id>`，增量按 `id` 归卡；响应以 pi 的 `output` 为准；超时 2 小时 |
| `get_last_assistant_text` | `lastAssistantText()` | |
| `get_fork_messages` / `fork` / `clone` / `get_tree` | `forkMessages()` / `fork(entryId)` / `clone()` / `tree()` | `fork` 只接受**用户消息**的 entryId；fork/clone 成功后重建时间线、刷新状态与 entry；`get_tree` 深树：读线程 64 MB 栈 + 解析失败只丢该记录 + 转换用显式栈 |
| `get_entries{since}` | `entries(since)`；内部维护 `messageMeta` | 见下 |
| `get_session_stats` | `usage()`、`EngineClient.stats()`、`usageState` | `contextUsage.percent == null`（压缩后到下一次回复前）→ `contextPercent == null`，**不回退旧值**（U2） |
| `compact{customInstructions}` + `compaction_start/end` | `compact(instructions)` | 事件与响应归约出同一张 `TimelineExtra.Compaction`（tokensBefore / estimatedTokensAfter / 摘要 / 读改文件；失败 `error`、中止 `aborted`）；超时 10 分钟 |
| `set_auto_compaction` / `set_auto_retry` / `set_steering_mode` / `set_follow_up_mode` | `setAutoCompaction` / `setAutoRetry` / `setSteeringMode` / `setFollowUpMode`、`sessionSettings()` | 1.5.0 的 `get_state` 不给 autoRetry：取最近一次 `set_auto_retry`，否则读 `<cwd>/.pi/settings.json`、`<agentDir>/settings.json` 的 `retry.enabled`，都没有为 null（未知） |
| `cycle_model` / `cycle_thinking_level` | `cycleModelResult()`（`{model, thinkingLevel, isScoped}` 直接解析）/ `cycleThinking()` | `cycleModel()` 由 `cycleModelResult()` 派生，不会切两次 |
| `export_html{outputPath}` | `exportHtml(outputPath)` | 返回容器内路径 |
| `summarization_retry_*` | 状态行 | |
| `entry_appended` | 只作为「需要刷新 messageMeta」的信号（触发一次增量 `get_entries`） | pi 1.5.0 的 user / assistant / toolResult 在 `message_end` 时写盘，**不发** `entry_appended`；只有 custom / context_edit / compaction 等才发 |

## 时间线附加项（`EngineExtras.timelineExtras`）

W0 契约冻结 `Chat.kt`，不新增 `ChatItem` 子类。时间线本身与 main 完全一致（压缩、bash 历史仍是一行提示），
另由 `ChatReducer.timelineExtras` 输出附加项，`anchorKey` 指向它应渲染在其后的 `ChatItem.key`：

- `Compaction`：实时（`compaction_start` 时 running）挂在当时时间线末尾；历史（`compactionSummary` 消息）挂在对应的「已压缩上下文」提示行之后——W5 渲染时用卡片替换该提示行。
- `Bash`：实时挂在发出命令时的末尾；历史（`bashExecution`）挂在「执行了命令」提示行之后。
- `UserImages`：key `img-<用户气泡 key>`，anchor 即该气泡。
- `TurnSummary`（C10）：`agent_settled`（未中止）后挂在本轮末尾；历史重建时每轮有写文件或输出 token 才生成，耗时/速率为 null。
- 附加项 key 用独立序号（`x-…`），不影响 `ChatItem` 的 key 与既有快照。

## messageMeta（S1、U1）

`MessageMetaTracker` 的数据源只有 `get_entries`：打开 / 切换 / 分叉 / 重建 / 清空后**全量**；之后每次 `message_end`（或 `entry_appended`）按 `since=lastId` **增量**，
`since` 找不到（`Entry not found`）时改全量。每份快照带 **covered**：发请求前登记的、已收到 `message_end`（已写盘）的时间线 key；
请求期间又有 `message_end` 就丢弃快照重拉，会话被替换（epoch 变化）就丢弃迟到响应；`get_entries` 失败（超时、>32 MB）清空簿记并发布空表。
从 leafId 沿 parentId 取**当前分支**（链断时只用叶子一侧）；`toolResult` 按 `toolCallId` 对到 `tool-<id>`；user / assistant 各自**从尾部**配对，只用 covered 里的气泡
（压缩后时间线头部对不齐；未写盘的尾部气泡不会配到上一条）。
被 pi 排除的助手消息由 tracker 对齐：条目被 `context_edit`（replacement=null）排除时——自动重试 / 重试中停止 / `abort_retry` 的 `error`，
截断恢复（`_omitRecoveryAttempt`：`stopReason=length` 且输出低于 maxTokens）的 `length`——若气泡的 stopReason 与条目相同且属于 {error, length}，两边一起跳过、不发 meta；
否则（空失败消息已被时间线删掉、重建后不在时间线）只跳条目。这一轮还在进行时，最后一条 error/length 条目可能马上被排除，暂不配对。
其余照常配对：不会重试的错误（401、重试次数用尽）和普通截断留在上下文里，两边都是 error/length 时正常给 meta；气泡是 error 而条目不是时只跳气泡。
全量 `get_entries` 因会话太大（超长 / 解析失败，立即以「会话太大，无法读取」结束）失败时记下当前 epoch：同一会话内不再自动全量重拉，
换会话（epoch 变化）或手动 `entries(null)` 成功后解除。
fork / clone 成功但随后 `get_messages` 失败时，时间线清空并重置簿记（与 openSession 失败一致），不再按旧 entryId 允许「从这里分叉」。
乐观气泡、发送失败、扩展消费（handled）、流式中的助手消息不参与。不按文本匹配。

## 回合结果与通知（N1）

`runOutcomes`：每次 `agent_settled` 发一条（COMPLETED / ERROR / ABORTED），阻塞式扩展对话框发 NEEDS_INPUT；文字经 Redactor 脱敏，预览 ≤200 字，带 `cwd`。
`TaskNotifier` 订阅它：App 在后台（`ForegroundDecider`：已启动的 Activity 计数 > 0 或进程重要性 == IMPORTANCE_FOREGROUND(100) 视为前台；迟注册也判对，只有前台服务时为 125 不算前台）且不是 ABORTED 时，经 `pigo-tasks` 通道发通知（同一会话复用通知 id），
点通知以 `EXTRA_SESSION_FILE` / `EXTRA_CWD` 打开启动 Activity（W5 读取后 `openSession`）。

## 超时表（`RpcTimeouts.forCommand` / `EngineConfig.timeoutFor`）

| 命令 | 超时 |
|---|---|
| `abort` | 20 s（可配） |
| `get_messages` | 30 s（可配） |
| `compact` | 10 min |
| `bash` | 2 h |
| `export_html`、`fork`、`clone`、`get_tree`、`get_entries` | 60 s |
| 其他 | 15 s（可配） |
