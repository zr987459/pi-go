# Pi-Web 学习笔记：pi-go 可以借鉴什么（调研文档）

> **许可证（一句话）**：Pi-Web 是独立上游项目 [`agegr/pi-web`](https://github.com/agegr/pi-web)（npm `@agegr/pi-web`，MIT，Copyright (c) 2026 agegr），PiMet 只在运行时用 npm 装它，仓库里没有 Pi-Web 源码；PiMet 自己的对接层（`PiWebManager/PiWebApi/PiWebMonitor/PiWebActivity/PiWebCompat`）是作者原创，不含 DSH-Folk 来源代码。按所有者最新指示（自用、不商用），本文的移植清单按「能拷就拷」给出，移植时只在 `THIRD_PARTY_NOTICES.md` 记一行来源。
>
> 证据：PiMet `a80222c` 的 `app/src/main/java/com/xm486/pimet/proot/PiWebManager.java:113`（`npm install -g … @earendil-works/pi-coding-agent @agegr/pi-web`，不锁版本）与 `:206`（`exec node …/@agegr/pi-web/bin/pi-web.js --no-open -p <port> -H 127.0.0.1`）；npm `@agegr/pi-web@0.14.2` 的 `license: "MIT"`、`gitHead: 025e24e74fdbc8f46c4e0cf31c6d6b3fa6cf29d5`；上游该提交（2026-11-03 “Release v0.14.2”）根目录 `LICENSE` 为 MIT；上游源码中搜索 `dsh` / `DSH-Folk` / `bmax` / `apatch` 无结果；PiMet 五个 PiWeb 相关 Java 文件中也无。
>
> 本文所有 Pi-Web 行号均指 `agegr/pi-web@025e24e`（= npm 0.14.2，依赖 `@earendil-works/pi-coding-agent` 1.5.0，与 pi-go 锁定版本一致）。PiMet 行号指 `zr987459/pimet@a80222c`。pi-go 行号指本文写作时的 `main@54884b8`、`mvp/engine@a4f023a`、`mvp/chat-ui@730fc49`（PR #4）、`mvp/runtime@5e88e55`（PR #3）。

移植时 `THIRD_PARTY_NOTICES.md` 的一行建议写法：

```
- agegr/pi-web @ 025e24e（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 移植/改写：<pi-go 文件> ← <pi-web 文件:行号>
```

---

## 0. 结论速览

- Pi-Web **不走 `pi --mode rpc`**：它在 Next.js 服务端进程内用 SDK `createAgentSessionFromServices()` 跑 pi（`lib/rpc-manager.ts:3337`），再自己包一层「仿 RPC」命令（`AgentSessionWrapper.send()`，`lib/rpc-manager.ts:1128`），事件经 SSE 推给浏览器。所以它的**命令语义、事件名、字段**与 pi-go 走的 RPC 基本同源（都是 `AgentSession` 事件），交互规则可以直接照搬，但**进程模型和重连逻辑不能照搬**（pi-go 是单个常驻子进程 + 管道）。
- pi-go 现有设计和三个分支的实现在协议正确性上已经比 Pi-Web 更严格（按 id 关联、`agent_settled`、回收站、Key 不落盘）。Pi-Web 真正值得学的是**交互细节**：停止时撤销扩展对话框、阶段提示行、重试横条的清除时机、出错回合不折叠、后台完成通知、自定义端点的模型发现与连接测试。
- **前 5 个最重要的缺口**（详见 §2）：
  1. 停止时不撤销正在等待的扩展对话框 → 可能卡 20 秒后被强杀重启，或留下「僵尸对话框」（engine + chat-ui）。
  2. 自定义 OpenAI 兼容端点没有「获取模型列表」，新手要手填模型 id（sessions-settings）。
  3. 没有「测试连接」，Key/模型错了要发第一条消息才知道（sessions-settings + integration）。
  4. 自动重试横条要等重试那一轮整段回复完才消失（engine，一行修复）。
  5. 没有阶段提示行：发出后到首字之间、长时间跑 bash 时，界面只有「生成中」，看起来像卡住（chat-ui）。

---

## 1. Pi-Web 如何与 pi 对接（含 PiMet 外壳）

### 1.1 PiMet 一侧：怎么把 Pi-Web 跑起来

| 环节 | 做法 | 源码 |
|---|---|---|
| 安装 | `npm install -g --ignore-scripts … @earendil-works/pi-coding-agent @agegr/pi-web`，**两者都不锁版本** | `PiWebManager.java:113` |
| 启动 | 在 PRoot 里 `bash -c "export PORT=…; export PI_CODING_AGENT_DIR=/root/.pi/agent; exec pi-web --no-open -p <port> -H 127.0.0.1"`，stdout/stderr 追加到 `/root/pi-web.log` | `PiWebManager.java:180-215` |
| 停止 | 容器内 `kill -9 $(pidof node)`、`pkill -9 -f node`、`fuser -k <port>/tcp`，再轮询端口释放 | `PiWebManager.java:286-330` |
| 健康检查 | 探测端口与 HTTP 就绪 | `ProotManager.java:159`、`:216` |
| 界面 | 全屏 WebView 加载 `http://127.0.0.1:30141`，注入翻译与主题 JS 桥 | `PiWebActivity.java:111-190`、`:361` |
| 原生调用 | `PiWebApi` 直接 HTTP 调 Pi-Web 的 `/api/*`（模型、技能、插件、MCP 等，见 `docs/piweb-api.md`） | `PiWebApi.java:33-255` |
| 状态监控（桌宠/悬浮） | `GET /api/sessions` 找最近会话 → 连 `GET /api/agent/{id}/events` SSE，把事件映射成 `THINKING/RESPONDING/TOOL_RUNNING/WAITING/ERROR/IDLE` 和中文事件标签 | `PiWebMonitor.java:32-60`、`:345-420`（映射）、`:480-495`（标签） |

pi-go 已经决定不走这条路（DESIGN §1.3、§3.1）。PiMet 这一层唯一值得搬的是 `PiWebMonitor` 的**事件 → 阶段/中文标签映射**（给通知栏文案、二期桌宠用，见 §3 移植清单 #17）。

### 1.2 Pi-Web 一侧：逐项拆解

**启动 / 进程模型**
- 浏览：`GET /api/sessions` 只读扫描 `~/.pi/agent/sessions/`，不创建会话（`AGENTS.md` “Architecture”）。
- 发送：`POST /api/agent/[id]` → `startRpcSession()`（`lib/rpc-manager.ts:3069`）在服务端进程内创建 `AgentSession`（`:3337`），之后所有命令走 `AgentSessionWrapper.send(command)`（`:1128`）。
- 一个服务进程可同时挂多个会话；每个会话有**空闲回收**：默认 10 分钟无活动就关掉 `AgentSession`（`lib/rpc-manager.ts:200-222`、`resetIdleTimer` `:1012-1030`），停止（abort）后强制启用回收。

**协议**
- 浏览器 → 服务端：HTTP POST 命令（`lib/agent-client.ts`），命令名与 pi RPC 相同：`prompt`（带 `streamingBehavior`）、`steer`、`follow_up`、`abort`、`clear_queue`、`get_state`……
- 服务端 → 浏览器：SSE `data: {...}`（`app/api/agent/[id]/events/route.ts:12-39`），内容是 `session.subscribe()` 的原始事件；连接时先发 `connected`，再**重放仍在运行的工具调用和所有待答的扩展对话框**（`lib/rpc-manager.ts:639-647`、`:1071-1073`）。
- 断线重连：`AgentEventConnection` 负责就绪握手、心跳判死与被动重连（`lib/agent-event-connection.ts:46-63`、`:226-275`）。

**流式输出渲染**
- 流式中的那条助手消息单独放在 `StreamingStore`，只有订阅它的组件重渲染（`lib/streaming-message.ts:130-185`）；`message_end` 时并入消息列表。
- 流式 Markdown 按块切分，只重新解析最后一块（`lib/markdown-blocks.ts`）；代码高亮在空闲片里做（`lib/highlight-queue.ts`）。
- 乐观气泡与服务端 user 消息按 `userMessageKey`（文本 + 图片签名）匹配（`lib/prompt-recovery.ts:39-47`，使用处 `hooks/useAgentSession.ts:1919-1926`、`:2103`）。

**工具调用显示**
- 卡片：`ToolCallBlock`（`components/MessageView.tsx:1187-1350`），失败红框（`:1235-1247`），展开状态按 `toolCallId` 记住（`lib/tool-call-expansion.ts:15-25`）。
- 运行中：只给 `bash`/`powershell`/codemode 保留实时输出（`hooks/useAgentSession.ts:1965-1980`），其他工具只显示「正在运行 X」。
- 阶段提示行取最新工具 `partialResult` 的**最后一行非空输出**（≤500 字）（`lib/tool-execution-progress.ts:1-29`、`lib/chat-phase-label.ts:27-43`）。
- 嵌套调用（`parentToolCallId`，codemode 脚本发起的）不作为顶层卡片（`lib/agent-event-wire.ts:59-65`）。
- 回合折叠：一轮的最终 assistant 消息拆成「过程」与「回答」（`lib/turn-views.ts:31-56`、`lib/message-display.ts:126-139`）；**只有存在真正的回答（文字/图片）时才折叠过程**，出错或被截断时过程保持展开（`lib/message-display.ts:145-155`，上游 issue #906）。
- 轮末显示本轮写过的文件（只认 `write/edit/apply_patch` 成功结果）（`lib/turn-written-files.ts:82-127`），以及本轮耗时（`lib/turn-duration.ts:27-64`）、输出速率（`lib/stream-token-rate.ts:1-39`）。

**思考内容**
- `ThinkingBlock`（`components/MessageView.tsx:1037`），超长思考分片（`lib/message-display.ts:11-29`），默认展开与否是一个持久偏好（`lib/thinking-expansion-preference.ts`）。

**停止 / 中断 / 排队 / 插话**
- 停止 = `abort`，服务端在 abort 前**先撤销所有等待中的扩展对话框**（`extensionUiAbortController.abort()`），撤回被压缩挡住的排队消息放回输入框（`lib/rpc-manager.ts:1323-1345`）；前端把撤回文本还原到输入框（`hooks/useAgentSession.ts:2277`）。
- `clear_queue` 只做整体清空，并写明原因：「pi 没有单条出队，清空再重排会和 agent 循环抢消息」（`lib/rpc-manager.ts:1578-1590`）。这与 pi-go 不做「立即发送」的决定一致。
- 排队区显示 steer / follow-up 两种芯片（`components/ChatInput.tsx:556-580`、`:1801-1875`）；手机上把插话/排队合成一个按钮（`:383` 注释），Enter 默认插话、Alt+Enter 排队（`:1343-1360`、`:1553`）。

**会话**
- 列表：增量扫描，记住每个文件读到的偏移，文件只增长时从断点续读，靠 inode + 头部/尾部采样哈希判断是否被改写（`lib/session-list-scanner.ts:41-60`、`:241-264`、`:318-460`）；**整文件扫描**，所以中途改过的名字也能找到。
- 命名：`PATCH /api/sessions/[id]`（`app/api/sessions/[id]/route.ts:181`）；另有「自动命名」按钮，用当前模型对裁剪后的对话生成标题（`lib/session-title.ts:13-48` 提示词、`:193-280` 裁剪与解析，入口 `components/AppShell.tsx:1124-1150`）。
- 删除：`DELETE /api/sessions/[id]`（`app/api/sessions/[id]/route.ts:213`），连同子代理会话一起删（`lib/session-delete.ts`）；**直接删除，没有回收站**（pi-go 的回收站更安全）。
- 长会话按页加载：`/api/sessions/[id]/context?tail=50&before=…`（`AGENTS.md` File Map、`lib/chat-lazy-load.ts`）。

**错误与重连**
- 模型错误：`stopReason=="error"` 显示 `errorMessage`，空时「Unknown provider error」（`lib/message-display.ts:94-100`）；`stopReason=="length"` 显示「输出额度耗尽」提示（`:107-112`）。
- 自动重试：`auto_retry_start` 显示横条；**`agent_start` 时就清掉横条**，因为成功的 `auto_retry_end` 要等重试那一轮第一条完整回复后才来，可能要几分钟（`hooks/useAgentSession.ts:1778-1789`）。
- 压缩中：提示行优先显示「正在压缩（n 条排队）」，避免看起来像卡住（`lib/chat-phase-label.ts:19-30`）。
- 上下文占用：压缩后 `contextUsage.percent` 为 `null` 时照实置空（`hooks/useAgentSession.ts:771`）。
- 后台通知：页面不可见时，回合结束发「任务完成」通知，扩展对话框等待时发「需要你回答」通知（同一请求只通知一次）（`components/AppShell.tsx:1069-1092`、`:1109-1122`，`lib/browser-notifications.ts:30-47`）。

**模型与 Key**
- Key 写 `auth.json`（锁 + 原子写 + 权限）（`lib/provider-credential-store.ts:1-114`）；`models.json` 读写（`lib/models-config-store.ts`）。
- **模型发现**：对已配置的 Provider 请求上游 `/models`，兼容 OpenAI / Anthropic / Gemini 三种列表格式与鉴权头，解析出 id、名称、上下文窗口、是否推理（`lib/model-discovery.ts:1-135`、`app/api/models-config/discover/route.ts:13-99`）。
- **连接测试**：用临时 `models.json` 起一个 `ModelRuntime`，对选定模型发一条「Reply with OK only.」（`maxTokens 16`、20 s 超时、不重试），返回成功/失败原因、HTTP 状态和耗时（`app/api/models-config/test/route.ts:11`、`:28-121`）。
- 保存前校验 pi 会拒绝的 Provider 条目（`lib/model-provider-check.ts`）。

**扩展 UI / 确认对话框**
- 阻塞类（select/confirm/input/editor，以及未关闭的 custom）按到达顺序排队、按 id 去重，重连时只保留服务端仍持有的请求，停止时逐个移除（`lib/extension-ui-queue.ts:22-70`）。
- 服务端为每个对话框挂 abort 信号，停止即结束等待（`lib/rpc-manager.ts:2160-2275`）。
- 原则：扩展在 CLI 下怎么表现，Web 里就怎么表现，不给扩展 API 加私有字段（`AGENTS.md` “Extension compatibility”）。

---

## 2. 对照 pi-go：Pi-Web 做得好而 pi-go 缺/弱的点

按用户可感知影响排序。「位置」列的 pi-go 行号以上文所列提交为准。

| # | 影响 | 缺口 | Pi-Web 做法 | pi-go 现状 |
|---|---|---|---|---|
| G1 | **高** | 停止时不撤销等待中的扩展对话框 | Stop 先 abort 所有对话框等待（`rpc-manager.ts:1323-1345`） | `PiEngine.stop()`（`mvp/engine` `PiEngine.kt:548-581`）与 `ChatController.stop()`（PR #4 `ChatController.kt:401-437`）都只做 `clear_queue → abort`。pi 1.5.0 的 RPC 对话框只在扩展传了 `signal` 或 `timeout` 时才会自己结束，且**结束时不发任何关闭事件**（pi `dist/modes/rpc/rpc-mode.js:46-77`）。后果：扩展没传 signal → `abort` 等到 20 s 超时 → 强杀重启（用户看到「已强制停止」）；传了 signal → pi 已放弃等待，App 里对话框还开着，点了也没用 |
| G2 | **高** | 自定义端点没有「获取模型列表」 | `model-discovery.ts` + discover 路由 | 设置页让用户手填「模型 id（多个用逗号分开）」（`main` `SettingsScreen.kt:437`、`:505`）。新手基本不知道百炼/智谱的模型 id 怎么写 |
| G3 | **高** | 没有「测试连接」 | test 路由：一次 16 token 请求，20 s，返回原因与状态码 | DESIGN §2.2 的 `ProviderConfigStore` 无测试方法，设置页无按钮；Key 错只能发消息后在错误卡里看到（§5.3 S6） |
| G4 | 中高 | 重试横条清除太晚 | `agent_start` 即清（`useAgentSession.ts:1778-1789`） | `ChatReducer.reduce` 对 `AgentStart` 只置 `running=true`（`mvp/engine` `ChatReducer.kt:44`），横条要到 `auto_retry_end` 才清（`:59-62`），重试成功那一轮整段流式期间一直显示「第 n/3 次重试」 |
| G5 | 中高 | 没有阶段提示行 | 「等待模型…」「正在运行 bash：<最后一行>」「正在压缩（n 条排队）」（`chat-phase-label.ts:27-43`、`tool-execution-progress.ts:7-29`） | 顶栏只有「· 生成中」（PR #4 `TopBar.kt:69`），状态行只在压缩/停止时出现（`ChatReducer.kt:63`、`PiEngine.kt:568`）。发送后到首个 token 之间、bash 长时间无新卡片时，看起来像卡死 |
| G6 | 中 | 出错/截断的回合仍被折叠 | 只有真回答时才折叠（`message-display.ts:145-155`，#906） | `ChatRows.emitBody` 只要有过程就折叠（PR #4 `ChatRows.kt:227-262`），`final == null` 时（出错、`length`、中断）最后一次工具调用前写的文字被藏进摘要。DESIGN v0.1（提案 A §3.6）原本要求「被中断或出错的回合不折叠」，定稿 §5.4 T3 没写明 |
| G7 | 中 | 后台完成/需要回答的通知不完整 | 不可见时发「任务完成」「需要你回答」，同一请求只通知一次 | 扩展对话框有通知（PR #4 `AndroidExtUiNotifier.kt`），但回合结束/出错没有提醒；前台服务通知是 LOW 级（`mvp/engine` `EngineService.kt:60-78`）。手机用户切出去等长任务是常态 |
| G8 | 中 | 会话名可能读错 | 整文件增量扫描 | 只读头尾各 64 KB（`main` `SessionFileParser.kt:25`、`:49-63`）。会话在 64 KB 之后改名、之后又聊了超过 64 KB，列表显示的是预览而不是名字 |
| G9 | 低 | 压缩后上下文占用显示旧值 | `percent` 为 null 时置空 | `stats()` 用 `s.contextPercent ?: it.contextPercent`（`PiEngine.kt:667`），压缩后仍显示压缩前的 90%+ |
| G10 | 低 | 嵌套工具调用当顶层卡片 | 过滤 `parentToolCallId`（`agent-event-wire.ts:59-65`） | `onToolStart` 无过滤（`ChatReducer.kt:204-227`）。只有用户开启 codemode 才会出现（pi 默认关闭） |
| G11 | 低 | 草稿只有一份 | 按会话分别保存（`draft-store.ts:16-49`） | `ChatController` 切会话时「草稿本身保留」，不随会话走 |
| G12 | 低（v0.2） | 轮末「改动了哪些文件」、本轮耗时、输出速率 | `turn-written-files.ts`、`turn-duration.ts`、`stream-token-rate.ts` | 无（DESIGN §7.2 v0.2 已列「轮末改动汇总」类需求） |
| G13 | 低（v0.2） | 自动命名会话 | `session-title.ts` | 只用首条消息预览 |
| G14 | 低（v0.2） | 空闲回收省内存 | 10 分钟空闲关会话（`rpc-manager.ts:200-222`） | pi 进程常驻（DESIGN §3.2），低端机 node 常驻 150–300 MB |

pi-go 已经做得比 Pi-Web 好、**不要倒退**的地方：按 id 关联响应、以 `agent_settled` 判空闲、删除进回收站可恢复、Key 只在 Keystore + 进程环境、停止时撤回文本不覆盖草稿、只杀自己的进程树（对比 PiMet `PiWebManager.java:291-293` 的 `pkill -9 -f node`）、锁定 pi 版本（PiMet 安装不锁版本）。

---

## 3. 建议落点、修改建议与验收

### 3.1 逐项落点

| # | 工作流 | 何时补 | 具体修改 | 验收 |
|---|---|---|---|---|
| G1 | **engine** + **chat-ui** | 两个分支的**当前审查轮** | engine：`PiEngine` 维护「已下发、未应答的阻塞类请求 id」集合（`onUiRequest` 加入、`answerUi` 移除、换代清空）；`stop()` 第一步对集合中每个 id 发 `extension_ui_response{cancelled:true}`，再 `clear_queue → abort`。chat-ui：`stop()` 先把 `dialogs` 里阻塞类全部本地关闭并取消通知（应答由引擎统一发，避免重复；若引擎未改则由 chat-ui 发） | engine：`ScriptedPi` 脚本「工具执行中发 `confirm` 且不响应 abort，直到收到 cancelled」→ 调 `stop()`，3 s 内拿到 abort 响应、不触发强制重启、cancelled 只发一次。chat-ui：Fake 引擎发 confirm 后点停止，对话框消失、`answerUi(cancelled)` 至多一次 |
| G2 | **sessions-settings**（已合入，开后续小 PR，只改 `core/store/src/**`、`feature/settings/src/**`） | integration 之前 | 新增 `core/store/.../ModelDiscovery.kt`：移植 `parseDiscoveredModels` / `buildModelsListUrl` / 按 api 选鉴权头；宿主侧用 OkHttp 直接 GET（Key 在宿主，不经容器）。设置页「自定义端点」加「获取模型列表」，结果多选回填模型 id 输入框 | 单测：OpenAI `{data:[{id}]}`、Anthropic、Gemini `models/xxx` 前缀、去重排序；真机：百炼 baseUrl + 真 Key 列出模型 |
| G3 | **sessions-settings**（自定义端点）+ **integration**（内置 Provider） | 自定义端点随 G2 一起；内置 Provider 在 integration | 自定义 `openai-completions` 端点：宿主侧 OkHttp `POST {baseUrl}/chat/completions`，`max_tokens 16`、20 s、不重试，状态码按 DESIGN §3.9 映射中文。内置 Provider：`RuntimeManager.exec(["pi","--print","--no-session","--model","<provider>/<id>","Reply with OK only."], env=Key 环境变量, 20 s)`；需要 runtime 已就绪，所以放 integration | 错 Key → 20 s 内显示「Key 错误」并带 HTTP 401；对 Key → 「连接正常，耗时 xx ms」；全程日志无完整 Key（沿用 C5 的脱敏断言） |
| G4 | **engine** | **当前审查轮**（一行） | `ChatReducer.reduce`：`AgentStart -> state.copy(running = true, retry = null)` | 在 `recorded/retry.jsonl` 回放测试里断言：重试轮的 `agent_start` 之后 `retry == null`，失败时仍由 `auto_retry_end{success:false}` 出错误提示 |
| G5 | **chat-ui** | **当前审查轮** | 在 chat-ui 内从 `ChatState` 推导阶段（不改契约）：`running` 且最后一条是 user/无内容 assistant → 「等待模型响应…」；有 RUNNING 工具 → 「正在运行 <名称>」+ `partialResult.content[].text` 最后一行非空（≤80 字，移植 `getToolExecutionProgress`）；`statusLine == 正在压缩` 且队列非空 → 「正在压缩上下文（n 条排队）」。显示在输入框上方状态区 | Compose 单测：三种阶段文案；回放 `tool-success.jsonl` 时 bash 运行期间显示最后一行输出 |
| G6 | **chat-ui** | **当前审查轮** | `ChatRows.emitBody`：`final == null`（出错、`length`、`aborted`、只有思考）时摘要默认展开（或直接不折叠）；仍保留「失败工具卡永远可见」 | `ChatRowsTest`：provider-error / 截断 / 中断三种回合，过程行默认可见；正常回合仍折叠（T3 不回归） |
| G7 | **engine**（通知通道与时机）→ **integration**（点击跳转、权限申请） | engine 当前轮可先做通道；跳转在 integration | `EngineService` 加一个 DEFAULT 级「任务通知」通道；`onRunSettled` 时若 App 不在前台：正常 → 「pi 已完成：<会话名>」，`stopReason==error` → 「pi 出错：<中文原因>」，`aborted` 不通知；文案可直接用 PiMet `PiWebMonitor.eventLabel` 的中文（去掉 emoji） | 真机：切到别的 App 跑一个 30 s 任务，结束时收到通知，点击回到该会话；在前台时不弹 |
| G8 | **sessions-settings** 后续小 PR | integration 之前或 v0.2 | `SessionFileParser` 改为流式整文件扫描，缓存 `(path, inode, size, 已解析偏移, name, preview)`，文件只增长时从偏移续读（移植 `session-list-scanner.ts` 的 ResumePoint 思路） | 单测：200 KB 会话、在 100 KB 处改名 → 列表显示新名；追加 10 KB 后只读新增部分 |
| G9 | **engine** | 当前审查轮 | `stats()`：`contextUsage` 存在而 `percent == null` 时置空，不回退旧值 | `compaction.jsonl` 回放 + 一条 `get_session_stats{contextUsage:{percent:null}}` 响应后 `contextPercent == null` |
| G10 | **engine** | 当前轮或 v0.2 | `tool_execution_*` 带非空 `parentToolCallId` 时不建顶层卡（可记到父卡的子列表，MVP 直接忽略） | 手写夹具：嵌套调用不产生新 `ChatItem.Tool` |
| G11 | **chat-ui** | v0.2 | 草稿按 `sessionFile` 存 `Map`，切会话时换出/换入；新会话用 `new:<cwd>` 键 | UI 测试：A 会话写草稿 → 切到 B → 切回 A 草稿仍在 |
| G12–G14 | chat-ui / sessions-settings / engine | v0.2 | 见移植清单 #12–#14、#19 | — |

### 3.2 哪些在当前 PR 审查轮补，哪些留给 integration

- **当前审查轮可补**：
  - `mvp/engine`：G1（引擎侧）、G4、G9、G10，以及 G7 的通知通道（不含跳转）。
  - PR #4 `mvp/chat-ui`：G1（界面侧）、G5、G6。
  - PR #3 `mvp/runtime`：本次调研**没有**发现需要 runtime 补的缺口（Pi-Web 的进程与安装做法都比 pi-go 粗糙）。
- **sessions-settings 后续小 PR**（该分支已合入，按 DESIGN §6 只改自己的 `src/**`）：G2、G3（自定义端点部分）、G8。
- **mvp/integration**：G3（内置 Provider 用 `pi --print` 测试，依赖 runtime + Key 环境变量）、G7（通知点击跳转与 Android 13 通知权限申请）、真机验证 G1 与 G5。
- **v0.2**：G11–G14。

---

## 4. 检查清单

### 4.1 给 engine 审查员

- [ ] `stop()` 在 `clear_queue` 之前，对所有已下发、未应答的阻塞类扩展请求发了 `cancelled`，且每个 id 只发一次；引擎换代后不再对旧 id 应答。
- [ ] `agent_start` 清掉 `retry` 横条；`auto_retry_end{success:false}` 仍产生错误提示。
- [ ] `get_session_stats` 的 `contextUsage.percent == null` 时界面占用置空，而不是保留旧值。
- [ ] 带 `parentToolCallId` 的 `tool_execution_*` 不生成顶层工具卡（或有明确说明为何保留）。
- [ ] 回合结束通知只在 App 不在前台时发；`aborted` 不发；通知文本不含 Key（走 `Redactor`）。
- [ ] 没有引入 Pi-Web 式的 HTTP 端口或 `pkill`；仍是单进程管道。
- [ ] 若移植了 Pi-Web 代码，`THIRD_PARTY_NOTICES.md` 已由 scaffold 维护者补一行来源（本分支不改该文件，在 PR 描述里提出）。

### 4.2 给 integration 工作者

- [ ] 真机：装一个会在工具执行中弹 `confirm` 的测试扩展，生成中点停止 → 3 s 内停下、对话框消失、没有「已强制停止」。
- [ ] 真机：发送后到首字之间显示「等待模型响应…」；跑 `sleep 5; ls` 类 bash 时提示行显示工具名和最后一行输出。
- [ ] 真机：断网让模型请求失败触发自动重试，重试那一轮开始流式时黄条已消失。
- [ ] 真机：provider 返回错误的回合，过程默认展开，最后一次工具调用前写的文字可见。
- [ ] 真机：App 在后台时任务完成/出错收到通知，点击回到对应会话；Android 13+ 首次申请通知权限。
- [ ] 设置页：自定义端点「获取模型列表」能列出百炼模型；「测试连接」对错 Key 给出「Key 错误」、对正确 Key 给出耗时；内置 DeepSeek 的测试走 `pi --print` 并通过。
- [ ] 大会话（>200 KB、中途改名）在列表里显示正确名称。
- [ ] 所有移植自 Pi-Web 的文件都在 `THIRD_PARTY_NOTICES.md` 有一行来源。

---

## 5. 「能拷就拷」移植清单

源 → 目标。Pi-Web 是 TypeScript/React，「移植」= 按原逻辑改写为 Kotlin；纯逻辑函数可以逐行对照翻译。

| # | 源文件:行号 | 内容 | pi-go 目标文件 | 工作流 |
|---|---|---|---|---|
| 1 | `lib/extension-ui-queue.ts:22-70` | 阻塞判断、按 id 去重入队、保留/移除 | `feature/chat/.../logic/ExtUi.kt`（PR #4 已有类似实现，补 `retain`/`remove` 语义） | chat-ui |
| 2 | `lib/rpc-manager.ts:1323-1345` | Stop 先撤销扩展对话框，再 abort，撤回文本还原 | `core/engine/.../PiEngine.kt` `stop()`、`onUiRequest()`、`answerUi()` | engine |
| 3 | `lib/chat-phase-label.ts:11-43` | 阶段 → 提示文案（压缩优先、多工具合并显示） | `feature/chat/.../logic/PhaseLabel.kt`（新） | chat-ui |
| 4 | `lib/tool-execution-progress.ts:1-29` | `partialResult` 取最后一行非空、截断 | `feature/chat/.../logic/PhaseLabel.kt` | chat-ui |
| 5 | `hooks/useAgentSession.ts:1778-1789`、`:1946-2016` | `agent_start` 清重试；工具开始/更新/结束时的阶段迁移 | `core/protocol/.../ChatReducer.kt`（清重试）；`PhaseLabel.kt`（阶段推导） | engine / chat-ui |
| 6 | `lib/message-display.ts:94-155` | 错误文本兜底、截断判断、`hasAssistantAnswer`、`splitFinalAssistantBlocks`、`collapsesProcessDetails` | `feature/chat/.../logic/ChatRows.kt` | chat-ui |
| 7 | `lib/model-discovery.ts:1-135` | 上游模型列表解析与 URL 构造 | `core/store/.../ModelDiscovery.kt`（新） | sessions-settings |
| 8 | `app/api/models-config/discover/route.ts:13-99` | 按 api 加鉴权头、错误分类 | `core/store/.../ModelDiscovery.kt` | sessions-settings |
| 9 | `app/api/models-config/test/route.ts:11`、`:28-121` | 连接测试：提示词、`maxTokens 16`、20 s、结果结构 | `core/store/.../ProviderTester.kt`（新，自定义端点）；内置 Provider 版放 integration | sessions-settings / integration |
| 10 | `lib/session-list-scanner.ts:41-60`、`:241-264`、`:318-460` | 增量扫描、ResumePoint、`session_info`/首条消息累计 | `core/store/.../SessionFileParser.kt`、`FileSessionRepository.kt` | sessions-settings |
| 11 | `lib/browser-notifications.ts:30-47`；`components/AppShell.tsx:1069-1092`、`:1109-1122` | 何时通知、同一请求只通知一次、完成/需要回答两类 | `core/engine/.../EngineService.kt`（完成通知）；`feature/chat/.../ui/AndroidExtUiNotifier.kt`（去重） | engine / chat-ui |
| 12 | `lib/turn-duration.ts:27-64`、`lib/stream-token-rate.ts:1-39` | 本轮耗时、输出速率 | `feature/chat/.../logic/TurnStats.kt`（新） | chat-ui（v0.2） |
| 13 | `lib/turn-written-files.ts:82-127` | 本轮写过的文件（只认成功的 write/edit） | `feature/chat/.../logic/TurnWrittenFiles.kt`（新） | chat-ui（v0.2） |
| 14 | `lib/session-title.ts:13-48`、`:193-280` | 自动命名提示词、对话裁剪、标题清洗 | `core/store/.../SessionTitle.kt`（新，经 `pi --print` 调用） | sessions-settings（v0.2） |
| 15 | `lib/compaction-summary.ts:1-34` | 压缩摘要里的读/改文件清单 | `core/protocol/.../ChatReducer.kt` 的 `compactionSummary` 提示行 | engine（v0.2） |
| 16 | `lib/agent-event-wire.ts:59-65` | 识别嵌套工具调用 | `core/protocol/.../ChatReducer.kt` `onToolStart/onToolEnd` | engine |
| 17 | PiMet `monitor/PiWebMonitor.java:345-420`、`:480-495` | 事件 → 阶段（思考/回复/执行工具/重试/出错/完成）与中文标签 | `core/engine/.../EngineService.kt` 通知文案；二期 `:feature:pet` | engine / v0.3 |
| 18 | `lib/draft-store.ts:16-110` | 按会话保存草稿、提交失败时合并回草稿 | `feature/chat/.../ChatController.kt` | chat-ui（v0.2） |
| 19 | `lib/rpc-manager.ts:200-222`、`:1012-1030` | 空闲超时回收 | `core/engine/.../PiEngine.kt`（可选「空闲 N 分钟停 pi」） | engine（v0.2） |

**不建议搬**：Pi-Web 的 HTTP/SSE 服务层、Next.js 鉴权、文件浏览/终端/MCP/技能/插件管理页（超出 MVP，且 pi-go 不开端口）；PiMet 的 `PiWebManager` 启停方式（`pkill -9 -f node` 会误杀）和不锁版本的安装命令。
