# pi RPC 回放夹具

`core/protocol/src/test/resources/fixtures/` 下的 `.jsonl` 是 `:core:protocol` 单测（`ChatReducerFixtureTest`）回放的 pi stdout 记录；
对应的期望结果（归约后的 `ChatState` 文本快照）在 `core/protocol/src/test/resources/snapshots/`。

## 格式

- 每行一条 pi 写到 stdout 的记录，**原样**保留（只按 LF 分行，`U+2028`/`U+2029` 不转义、不切分）。
- 以 `//> ` 开头的行是客户端（pi-go）当时写到 pi stdin 的命令，只用于说明交互顺序，回放时跳过。
- 以 `//` 开头的其它行是注释。
- 回放时 `response` 记录不参与归约（由 `RpcChannel` 按 id 关联），只有会话事件进入 `ChatReducer`；
  每条记录推进 100 ms 的假时钟，使思考时长、工具耗时可复现。

## 来源

### `recorded/`：桌面真 pi 1.5.0 录制

| 文件 | 场景 | 覆盖的验收点 |
|---|---|---|
| `text-stream.jsonl` | 一轮纯文本流式回答（含 `U+2028`） | 文本流、S1 逐字增长 |
| `thinking.jsonl` | `reasoning_content` → thinking 块，再正文 | 思考块、S3 |
| `tool-success.jsonl` | `bash` 成功；随后 `get_messages` | 工具成功、T4 重建与实时一致 |
| `tool-failure.jsonl` | `read` 不存在的文件 | 工具失败、T2 |
| `follow-up.jsonl` | 生成中 `prompt + streamingBehavior:"followUp"` | followUp、Q1、`agent_end` 不结束本轮 |
| `steer.jsonl` | 工具执行中 `prompt + streamingBehavior:"steer"` | steer、Q2 |
| `clear-queue-abort.jsonl` | 有排队消息时 `clear_queue` → `abort` | 停止、Q3（撤回的消息不再发出） |
| `retry.jsonl` | 模型服务先回 503，pi 自动重试成功 | `auto_retry_start/end`、重试黄条 |
| `compaction.jsonl` | 一轮对话后手动 `compact` | `compaction_start/end`；会话太短，pi 1.5.0 返回 `Nothing to compact (session too small)` → 「压缩上下文失败」错误卡、状态行清空（成功压缩由 `ChatReducerTest` 手写事件覆盖） |
| `provider-error.jsonl` | 模型服务回 401 | `stopReason:"error"`、「Key 错误」中文提示 |
| `protocol-errors.jsonl` | 非 JSON 命令、`set_model` 失败、模型/思考级别/统计查询 | 不带 id 的 `parse` 错误、`success:false`、各响应字段 |

录制环境：`@earendil-works/pi-coding-agent@1.5.0`（`npm i -g`），Node v22.23.3（pi 要求 `>=22.19.0`；手机上为 Node 24.22.2），
Debian arm64 桌面，2026-11-03。模型请求全部发往测试内置的本机假 OpenAI 兼容服务
（`core/engine/src/test/.../realpi/MockOpenAiServer.kt`，只监听 127.0.0.1，按最后一条 user 消息里的 `#text`/`#tool-ok` 等标记决定回复），
因此录制不需要任何真实 Key 或外网。

录制后做了两处替换，使夹具与机器无关：主机临时目录 → 容器内路径（`/root/.pi/agent`、`/root/pi-cwd`，会话目录名相应为 `--root-pi-cwd--`），
假服务端口 → `PORT`。除此之外不做任何修改；system 消息（pi 自带的系统提示词）也原样保留，它出现在 `message_start/end` 与 `agent_end.messages` 中，
归约器按设计忽略 `role:"system"`。

重新录制：

```bash
npm i -g @earendil-works/pi-coding-agent@1.5.0
PIGO_PI_BIN=$(which pi) \
PIGO_RECORD_FIXTURES=$PWD/core/protocol/src/test/resources/fixtures/recorded \
  ./gradlew :core:engine:testDebugUnitTest --tests '*RecordFixturesTest*'
# 夹具变了以后，快照要重新生成并逐行人工检查：
UPDATE_SNAPSHOTS=1 ./gradlew :core:protocol:test --tests '*ChatReducerFixtureTest*'
```

pi 升级（§4.6 兼容范围 `>=1.5.0 <2.0.0`）时应重新录制并比对快照差异，作为协议变化的回归信号。

### `handwritten/`：手写

真 pi 难以稳定触发、但客户端必须容忍的情况，按 pi 1.5.0 `docs/json.md`、`docs/message-types.md`、`docs/rpc-commands.md` 的字段手写：

| 文件 | 场景 |
|---|---|
| `unknown-event-role.jsonl` | 未知事件类型、未知 `role`（渲染为可展开的原始 JSON）、未知内容块、`custom`（`display:false` 不显示）、`entry_appended`、`bash_execution_update`、`extension_error`、不带 `name` 的 `session_info_changed`；以及一份含 `toolResult`/`bashExecution`/`branchSummary`/`compactionSummary`/未知 role/坏元素的 `get_messages` 结果 |
| `edge-cases.jsonl` | 没有 `message_start` 的 `message_update`；`text_end` / `thinking_end` / `message_end` 的权威内容覆盖拼接结果；`toolcall_delta` 拼接；`toolResult` 消息不覆盖 `tool_execution_end` 的结果；缺 `tool_execution_start` 的工具；压缩取消/失败；重试最终失败；本轮结束时仍在运行的工具 |
| `abort-during-toolcall.jsonl` | 工具调用参数流式输出中途停止（`stopReason:"aborted"` 的 `message_end` 带半截 `toolCall`）；`stopReason:"toolUse"` 之后、工具开始执行之前被中止。两种情况 pi 都不会执行工具，卡片落为「未执行」（ERROR），不会一直转圈 |

## 桌面真 pi 集成测试

`core/engine/src/test/.../realpi/RealPiIntegrationTest.kt` 用同一个假模型服务驱动真 pi，覆盖
prompt→`agent_settled`、`handled` 不等待、followUp/steer、停止、`switch_session`、进程被杀后自动重启并恢复会话。
默认跳过（CI 不跑、不依赖网络），本机运行：

```bash
PIGO_PI_BIN=$(which pi) ./gradlew :core:engine:testDebugUnitTest --tests '*RealPiIntegrationTest*'
```
