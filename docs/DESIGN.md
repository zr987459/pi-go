# pi-go 设计文档（v0.1）

> 目标：一个原生 Android 的「pi 编程助手」客户端。引擎是 pi coding agent，跑在手机上的 PRoot Ubuntu 容器里；界面全部原生，不再内嵌 Pi-Web（`@agegr/pi-web`）网页。
>
> 本文的事实依据（2026-11-03 调研）：
> - PiMet `zr987459/pimet@a80222c2dda4a4c8a936258b96597bc7ea7d4102`（v1.9.0-beta.30）源码；
> - npm 包 `@earendil-works/pi-coding-agent@1.5.0` 与 `@mariozechner/pi-coding-agent@0.73.1` 的 `dist/` 与 `docs/`（`docs/rpc.md`、`docs/rpc-commands.md`、`docs/json.md`、`docs/session-format.md`、`docs/models.md` 等）；
> - `@agegr/pi-web@0.14.2` 编译产物；
> - dsh-Go（AGPL-3.0）的公开设计文档与目录结构——**只借鉴设计，不拷贝代码**。
>
> 文中标「推断」的地方是读代码得出、文档未明说的结论，实现时要在真机上确认。

---

## 0. 关键结论速览

| 问题 | 结论 |
|---|---|
| 技术栈 | **Kotlin + Jetpack Compose**（单 Activity，多 Gradle 模块）；运行时层允许保留 PiMet 的 Java 源文件，Kotlin 直接调用 |
| 引擎包 | **`@earendil-works/pi-coding-agent`**（当前 1.5.0，要求 Node ≥ 22.19）。`@mariozechner/pi-coding-agent` 已在 npm 上标记 deprecated（最后版本 0.73.1），官方提示改用前者；PiMet 现在装的也是前者 |
| 通信方式 | **`pi --mode rpc`，stdin/stdout JSONL**，App 直接持有容器内 pi 进程的管道；不起 HTTP 服务，不开端口 |
| 运行环境 | 从 PiMet 迁移 PRoot 打包、rootfs 安装（国内镜像现装为主）、tar 解压、前台服务；补上 SHA256 校验和按 PID 停进程 |
| 许可证 | 不拷贝 dsh-Go 代码，pi-go **不需要**采用 AGPL；不使用 dsh-Go 的名称、标识和吉祥物。PiMet 复用代码的来源和其中第三方组件记在 `THIRD_PARTY_NOTICES.md` |

---

## 1. 技术栈选择

### 1.1 PiMet 现状

- 语言：**100% Java**（77 个 `.java`，约 5.2 万行，0 个 `.kt`），Java 11 语法。
- 界面：**Android View 体系**，大部分在代码里动态建 View（`MainActivity.java` 7033 行），不是 Compose；主界面是 WebView 加载 `http://127.0.0.1:30141` 的 pi-web。
- 只有一个 Gradle 模块 `:app`，包名 `com.xm486.pimet`，compileSdk 34 / minSdk 26 / targetSdk 28，AGP 8.0.0，Gradle 8.1.1。
- 依赖：OkHttp 4.12、AndroidX、Material 1.9、Termux `terminal-emulator`/`terminal-view` v0.118.0、Shizuku API 13.1.5。

真正值得复用的部分都在**非界面层**：PRoot 启动封装（`proot/ProotManager.java`）、rootfs 安装与国内镜像（同上）、tar 解压（`proot/TarGzipExtractor.java`）、Termux PTY 终端中心（`terminal/TerminalHub.java`）、宿主控制桥（`bridge/AppBridgeManager.java`）、无障碍自动化（`automation/PiMetAccessibilityService.java`）、前台服务（`PiMetService.java`）、pi 配置读写（`PiMetConfig.java`、`PluginManager.java`）。界面层几乎都绑在 WebView 上，新客户端要重写。

### 1.2 Kotlin + Compose vs Flutter

| 维度 | Kotlin + Compose | Flutter（dsh-Go 的做法） |
|---|---|---|
| 复用 PiMet Java 代码 | **直接调用**，Java/Kotlin 可在同一模块混编；可以逐步把 Java 改成 Kotlin | 必须全部放在平台层，再为每个能力写 MethodChannel/EventChannel 胶水（dsh-Go 有 26 条 MethodChannel + 7 条 EventChannel），等于多一层序列化和一份 Dart 状态镜像 |
| 复用 Termux 终端（View） | `AndroidView` 直接嵌入 `TerminalView` | 需要 PlatformView，滚动/输入法/性能都更难调 |
| 悬浮窗、无障碍、前台服务 | 原生能力，和界面同一语言 | 仍要写 Kotlin，只是界面换成 Dart，团队要维护两门语言 |
| 流式聊天界面 | Compose `LazyColumn` + 状态快照足够；Markdown 渲染有成熟库 | Flutter 表现更好看的上限略高，但不是瓶颈 |
| iOS / 桌面 | 不需要（PRoot 只在 Android） | 跨平台价值用不上 |
| 构建与 CI | 纯 Gradle，一个工具链 | Flutter SDK + Gradle 两套工具链，CI 更慢 |

**结论：Kotlin + Jetpack Compose。** 「最大程度复用 PiMet」意味着运行时层沿用 Java/Kotlin，Flutter 只会让这层多包一层通道而界面层并不能从 PiMet 复用任何东西。Compose 让界面和运行时同语言、同进程、同一套协程，改动最小。

取舍：
- 放弃 Flutter 的跨平台，接受 Compose 在超长列表流式渲染上需要自己做增量优化（见 §3.6）。
- 运行时层第一期**允许直接保留 PiMet 的 Java 文件**（改包名、拆掉对 WebView/pi-web 的依赖），第二期再按需 Kotlin 化，避免一次重写引入回归。

### 1.3 基础版本（脚手架定）

- Gradle 8.9+、AGP 8.7+、Kotlin 2.0.x（Compose 编译器插件 `org.jetbrains.kotlin.plugin.compose`），JDK 17。
- compileSdk 35，minSdk 26，**targetSdk 28**（与 PiMet、dsh-Go 一致：Android 10+ 对 targetSdk ≥ 29 的应用禁止执行 App 数据目录里的文件，PRoot 方案目前依赖它；代价是只能侧载，不能上架 Google Play。第二期评估提升 targetSdk 的方案）。
- ABI：arm64-v8a、x86_64（与 PiMet 的 jniLibs 一致；x86_64 主要给模拟器）。
- 依赖：Compose BOM、Material 3、Navigation Compose、Lifecycle/ViewModel、kotlinx.coroutines、kotlinx.serialization-json、DataStore、AndroidX Security（加密存 Key）、OkHttp（下载）、Termux terminal-emulator/terminal-view（第二期终端用，第一期不引入）。
- 依赖注入：第一期用手写 `AppContainer`（单例服务定位器），不引入 Hilt，降低脚手架复杂度和并行冲突。

---

## 2. 模块划分

applicationId / 根包名：`io.github.zr987459.pigo`（下文简写 `pigo`）。

```
pi-go/
├── app/                      :app            应用入口、导航、AppContainer 装配
├── core/
│   ├── contract/             :core:contract  跨模块接口与数据类型（纯 Kotlin，脚手架所有，冻结）
│   ├── ui/                   :core:ui        主题、通用组件、字符串资源（脚手架所有）
│   ├── runtime/              :core:runtime   PRoot、容器安装、pi 进程、前台服务（Android library，含 jniLibs）
│   └── rpc/                  :core:rpc       pi RPC 协议、会话状态 reducer、会话索引（纯 Kotlin/JVM）
├── feature/
│   ├── chat/                 :feature:chat   聊天界面、工具卡片、插话/排队、中断
│   └── setup/                :feature:setup  首启向导、模型与 API Key 配置、会话列表、设置
├── docs/
└── .github/workflows/
```

依赖方向（只允许向下）：

```
:app ─┬─> :feature:chat ──┐
      ├─> :feature:setup ─┼─> :core:ui ─> :core:contract
      ├─> :core:runtime ──┤               ^
      └─> :core:rpc ──────┴───────────────┘
```

`feature` 模块**只依赖 `:core:contract` 和 `:core:ui`**，不直接依赖 `:core:runtime` / `:core:rpc` 的实现类；实现由 `:app` 的 `AppContainer` 注入。这样四条并行工作流之间只通过冻结的接口相遇，互不改对方文件。

### 2.1 `:core:contract`（纯 Kotlin，kotlinx.serialization + coroutines-core）

脚手架一次性写好，MVP 期间**只增不改**（新增字段带默认值）；任何签名变更要在 PR 里单独说明并通知所有工作流。

包 `pigo.contract`：

```kotlin
// 运行环境
interface PiRuntime {
    val state: StateFlow<RuntimeState>          // NotInstalled / Installing(progress) / Ready / Starting / Running / Stopping / Error(code, message)
    suspend fun install(options: InstallOptions): Result<Unit>   // 幂等；可从断点继续
    suspend fun startAgent(config: AgentLaunchConfig): Result<AgentProcess>
    suspend fun stopAgent()
    suspend fun runInContainer(cmd: String, timeoutMs: Long): CommandResult // 给设置页检查版本等
    fun logs(): Flow<LogLine>
}
interface AgentProcess {                         // 一个 pi --mode rpc 进程
    val stdoutLines: Flow<String>                // 已按 \n 切好的 UTF-8 行
    suspend fun writeLine(json: String)
    val exit: Deferred<Int>
    val stderrTail: StateFlow<List<String>>
}

// 会话与对话
interface PiAgentClient {
    val connection: StateFlow<ConnectionState>   // Disconnected / Connecting / Ready / Restarting(attempt) / Failed(reason)
    val session: StateFlow<SessionUiState>       // 当前会话的完整界面状态（消息、工具调用、队列、是否在生成）
    val uiRequests: Flow<ExtensionUiRequest>     // 需要用户回应的扩展对话框
    suspend fun send(text: String, images: List<ImageAttachment> = emptyList(), mode: SendMode = SendMode.Auto): SendResult
    suspend fun abort()
    suspend fun clearQueue(): QueuedMessages
    suspend fun respondUi(id: String, response: ExtensionUiResponse)
    suspend fun newSession(): Result<Unit>
    suspend fun openSession(ref: SessionRef): Result<Unit>
    suspend fun setModel(provider: String, modelId: String): Result<Unit>
    suspend fun availableModels(): Result<List<ModelInfo>>
}
interface SessionIndex { fun list(): Flow<List<SessionSummary>>; suspend fun refresh(); suspend fun rename(ref: SessionRef, name: String); suspend fun delete(ref: SessionRef) }

// 模型与凭证
interface ProviderConfigStore {
    val providers: StateFlow<List<ProviderConfig>>
    suspend fun save(config: ProviderConfig)          // 写加密存储 + 同步到容器 ~/.pi/agent/{auth,models,settings}.json
    suspend fun test(config: ProviderConfig): ProviderTestResult
    val presets: List<ProviderPreset>                 // DeepSeek / 通义千问 / Kimi / 智谱 / OpenAI / Anthropic / OpenRouter / 自定义 OpenAI 兼容
}
```

数据类（`SessionUiState`、`ChatItem`、`ToolCallUi`、`SendMode`、`ExtensionUiRequest` 等）也在这里，字段见 §3.5。

### 2.2 `:core:ui`

包 `pigo.ui`：`PiGoTheme`（Material 3，浅色/深色/跟随系统，Android 12+ 动态取色开关）、通用组件（`LoadingState`、`ErrorState`、`EmptyState`、`ConfirmDialog`、`MarkdownText` 占位）、公共字符串。各 feature 自己的文案放各自模块的 `res/values*/strings.xml`，避免多人改同一个文件。

### 2.3 `:core:runtime`（Android library）

包 `pigo.runtime`。职责：
- `ProotLauncher`：拼 proot 命令行和环境变量（迁移自 `ProotManager.buildProotProcess`），**纯管道启动**，不分配 PTY。
- `RootfsInstaller`：国内镜像现装 Ubuntu Base 24.04 + Node 24 + `npm i -g @earendil-works/pi-coding-agent`；预构建 rootfs 下载作为可选路线（见 §4）。
- `TarExtractor`：迁移自 `TarGzipExtractor` + `SafeFiles`，并修掉已知的流对齐问题。
- `AgentProcessImpl`：`AgentProcess` 的实现；字节流 → UTF-8 增量解码 → 只按 `\n` 切行；独立线程持续排空 stdout 和 stderr。
- `PiRuntimeImpl`、`RuntimeService`（前台服务，持有 pi 进程，`specialUse` 类型）。
- `jniLibs/`：`libproot.so`、`libprootloader.so`、`libprootloader32.so`、`libtalloc.so`、`libandroidshmem.so`（来自 PiMet，**不带** `libproroot*.so`）。

对外只暴露 `PiRuntime` 实现和 `RuntimeService`。

### 2.4 `:core:rpc`（纯 Kotlin/JVM）

包 `pigo.rpc`。职责：
- `RpcCodec`：命令/响应/事件的 kotlinx.serialization 模型（`ignoreUnknownKeys = true`，未知事件类型保留为 `RpcEvent.Unknown(raw)`）。
- `RpcConnection`：基于 `AgentProcess` 的请求-响应关联（自增 `id` → `CompletableDeferred`）、超时、未关联错误处理。
- `TranscriptReducer`：把事件流折成 `SessionUiState`（纯函数，可单元测试）。
- `PiAgentClientImpl`：实现 `PiAgentClient`，含重连与会话恢复。
- `SessionIndexImpl`：扫描容器内 `~/.pi/agent/sessions/**/*.jsonl` 的 header 与 `session_info`（RPC 没有列会话命令，见 §3.4）。
- `ProviderConfigStoreImpl`：生成 `auth.json` / `models.json` / `settings.json`。

只依赖 `:core:contract`；读写容器文件通过 `PiRuntime`/文件路径参数注入，单元测试可在 JVM 上跑。

### 2.5 `:feature:chat`

包 `pigo.feature.chat`：`ChatScreen`、`ChatViewModel`、`MessageList`、`ToolCallCard`、`ThinkingBlock`、`Composer`（输入框+发送/停止键+队列条）、`ExtensionUiDialogHost`。只读 `PiAgentClient`。

### 2.6 `:feature:setup`

包 `pigo.feature.setup`：`OnboardingScreen`（安装向导）、`ProviderSetupScreen`（模型/API Key）、`SessionListScreen`、`SettingsScreen`。依赖 `PiRuntime`、`ProviderConfigStore`、`SessionIndex`、`PiAgentClient`（只用 `newSession`/`openSession`）。

### 2.7 `:app`

包 `pigo.app`：`PiGoApplication`、`MainActivity`（单 Activity）、`AppNavHost`、`AppContainer`（装配实现）。路由：`onboarding` → `provider-setup` → `chat/{sessionRef?}`；`sessions`、`settings` 从聊天页顶栏进入。

---

## 3. 原生界面与容器内 pi 的通信

### 3.1 方案比较

| 方案 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| **A. `pi --mode rpc`（stdin/stdout JSONL）** | App 启动 proot → `pi --mode rpc`，双向 JSONL；官方文档 `docs/rpc.md`、`docs/rpc-commands.md` | 官方稳定协议；双向（命令 + 事件 + 扩展对话框应答）；支持 prompt/steer/follow_up/abort/clear_queue、切会话、切模型、压缩；**不开端口**，没有本机其他 App 越权调用的问题；进程生命周期由 App 直接掌握 | 没有列会话命令（需扫描文件）；需要自己写行分帧、id 关联、事件 reducer；1.5.0 起 stdout 有背压，必须持续读 |
| B. `pi --mode json` | 单次运行：输出事件 JSONL 后退出 | 简单 | 单向；跑完就退出，不能插话、不能回应扩展对话框；每轮都要冷启动 Node（PRoot 下很慢） |
| C. pi-web HTTP API（POST + SSE） | pi-web 在 Next.js 服务端用 SDK 跑 pi，对外 `POST /api/agent/[id]` + `GET /api/agent/[id]/events`（SSE） | 有现成的列会话、文件、git、终端接口 | 要多装、多跑一个 Next.js 服务（内存、启动时间都大）；**默认无密码**、任何本机 App 能调；接口是 pi-web 的私有 API，字段无文档、随版本变；与「抛弃 pi-web」的目标相反 |
| D. 自写 Node 桥（SDK `createAgentSession` + WebSocket） | 在容器里跑一个自己的 Node 服务 | 能力最全（SDK 有 `SessionManager.list`、`navigateTree`） | 要自己维护一份 Node 代码并跟随 SDK 变化；又引入端口与鉴权问题；第一期不值得 |

**选 A。** 理由：它是 pi 官方为「其他程序嵌入 pi」设计的接口，覆盖 MVP 全部需求（流式、工具事件、中断、插话/排队、会话切换、模型切换）；无网络端口让安全模型最简单；pi 进程挂在 App 的前台服务下，崩溃与重启完全可控。列会话这一个缺口用读会话文件补上，第二期若需要更多能力再考虑 D。

### 3.2 进程与传输

启动命令（容器内，由 `ProotLauncher` 拼装；`env -i` 起干净环境）：

```
<nativeLibDir>/libproot.so --kill-on-exit --link2symlink -L -0 -r <filesDir>/rootfs \
  -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd [-b /storage/emulated/0:/sdcard] \
  -w /root/workspace \
  /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TERM=dumb PATH=/usr/local/bin:/usr/bin:/bin \
     PI_OFFLINE=1 PI_SKIP_VERSION_CHECK=1 PI_TELEMETRY=0 \
     NODE_OPTIONS=--dns-result-order=ipv4first \
  /usr/local/bin/pi --mode rpc --session-dir /root/.pi/agent/sessions [--session <path>] [--model <provider/id>]
```

- `PI_OFFLINE`、`PI_SKIP_VERSION_CHECK`、`PI_TELEMETRY` 是 pi 文档列出的环境变量（`docs/environment-variables.md`），用来避免启动时的网络检查拖慢冷启动。
- **纯管道**，不经过 login shell，不分配 PTY（PTY 会回显并把 `\n` 改成 `\r\n`）。
- stdout：pi 在 rpc 模式下调用 `takeOverStdout()`，stdout 只输出协议记录，其他输出都转到 stderr。
- 分帧：按字节读 → UTF-8 增量解码（多字节字符可能跨两次 read）→ **只按 `\n` 切**，剥掉行尾 `\r`；不能用会把 U+2028/U+2029 当换行的读取器。不设单行长度上限（`get_messages`、带图片的消息可能很长）。
- 背压：1.5.0 每写一条记录都等 stdout 背压，**读线程必须一直读**，把行放进无界 `Channel` 再分发，绝不在读线程里做 UI 或磁盘操作。
- stderr：单独线程排空，保留最近 200 行环形缓冲，用于启动失败提示和诊断导出（写日志前把 `sk-`、`Bearer ` 之类打码）。
- 写命令：每条 `JSON + "\n"`，写完 flush；所有写入经一个串行协程，避免两条命令交错。

### 3.3 消息格式（以 1.5.0 源码为准）

**命令**（stdin，每条都带唯一 `id`，App 用 `c-<自增>`）：

| 用途 | 命令 |
|---|---|
| 就绪探测 / 同步状态 | `{"id":"c-1","type":"get_state"}` → `data: {model?, thinkingLevel, isStreaming, isCompacting, steeringMode, followUpMode, sessionFile?, sessionId, sessionName?, autoCompactionEnabled, messageCount, pendingMessageCount}` |
| 发送 | `{"id","type":"prompt","message","images"?: [{type:"image",data,mimeType}],"streamingBehavior"?: "steer"\|"followUp"}` → `data: {disposition: "started"\|"queued"\|"handled"}` |
| 插话 / 排队 | `{"type":"steer","message"}` / `{"type":"follow_up","message"}` → `data: {disposition}` |
| 中断 | `{"type":"abort"}`（等 agent 空闲才响应；**不清空队列**） |
| 清空队列 | `{"type":"clear_queue"}` → `data: {steering: string[], followUp: string[]}` |
| 会话 | `new_session{parentSession?}`、`switch_session{sessionPath}`、`set_session_name{name}`、`get_messages`、`get_entries{since?}`、`get_session_stats` |
| 模型 | `get_available_models` → `{models}`；`set_model{provider, modelId}`；`set_thinking_level{level}` |
| 其他 | `compact{customInstructions?}`、`set_auto_retry{enabled}`、`abort_retry` |
| 扩展对话框应答 | `{"type":"extension_ui_response","id":"<请求 id>","value"\|"confirmed"\|"cancelled":...}`（没有 response） |

**响应**：`{"id","type":"response","command","success":true,"data"?}` 或 `{..., "success":false,"error":"..."}`。注意：
- 命令是**并发处理**的，响应可能乱序，必须按 `id` 关联；
- JSON 解析失败的响应 `command:"parse"`，**没有 id**，进入「未关联错误」分支（记日志并在开发者模式下提示）；
- `prompt` 的 `success:true` 只表示被接受；之后的模型错误只出现在事件里（`message_end` 的 `stopReason:"error"` + `errorMessage`），不会有第二条 response。

**事件**（stdout，不带 id）：

| 事件 | 客户端用途 |
|---|---|
| `agent_start` / `agent_end{messages, willRetry}` / **`agent_settled{aborted}`** | 生成开始 / 一次运行结束 / **彻底空闲**（重试、压缩、排队消息都处理完）。界面的「生成中」以 `agent_settled` 为准 |
| `turn_start` / `turn_end{message, toolResults}` | 一个 assistant 回合的边界，用于折叠分组 |
| `message_start{message}` / `message_update{assistantMessageEvent, usage}` / `message_end{message}` | 消息流。`message_end` 里的消息是权威版本 |
| `tool_execution_start{toolCallId, toolName, args, parentToolCallId?}` / `_update{partialResult}` / `_end{result, isError, durationMs?}` | 工具卡片 |
| `queue_update{steering[], followUp[]}` | 待发送队列（每次完整推送） |
| `compaction_start{reason}` / `compaction_end{...}`、`auto_retry_start{attempt,maxAttempts,delayMs,errorMessage}` / `auto_retry_end{success,...}` | 状态条：「正在整理上下文」「第 2/3 次重试」 |
| `session_info_changed{name?}`、`thinking_level_changed{level}`、`entry_appended{entry}` | 顶栏标题、会话列表 |
| `extension_ui_request{id, method, ...}` | 扩展对话框（§3.7） |
| `extension_error{extensionPath, event, error}` | 诊断日志 |

**`message_update` 的增量结构**（1.5.0 起去掉了累计快照，只发 delta）：`assistantMessageEvent.type` ∈ `text_start/text_delta/text_end`、`thinking_start/thinking_delta/thinking_end`、`toolcall_start/toolcall_delta/toolcall_end`，都带 `contentIndex`；`*_delta` 带 `delta`，`text_end`/`thinking_end` 带权威 `content`，`toolcall_end` 带 `toolCall{id, name, arguments}`，`toolcall_start` 额外带 `id`、`toolName`。

### 3.4 会话管理

- 存储：pi 自己写 `~/.pi/agent/sessions/--<cwd 编码>--/<时间戳>_<uuid>.jsonl`（v3 格式：首行 header `{"type":"session","version":3,"id","timestamp","cwd","parentSession"?}`，后续 entry 以 `id/parentId` 成树）。App **不另存一份会话内容**，避免两份数据不一致；只在 DataStore 记「最近打开的会话路径」。
- 列表：RPC 没有列会话命令。`SessionIndexImpl` 直接扫描 rootfs 内该目录：读首行 header 取 id/cwd/时间，倒序找最后一条 `session_info` 取名字，找第一条 user 消息作摘要；按文件修改时间排序；结果缓存，文件 mtime 不变不重读。
- 新建：`new_session`；打开：`switch_session{sessionPath}`（容器内路径）后 `get_messages` 重建界面；重命名：当前会话用 `set_session_name`，其他会话第一期不支持（只读）。
- 删除：第一期**移入** `~/.pi/agent/sessions-trash/`，不直接删（可恢复）；删除当前打开的会话前先 `new_session`。
- 工作目录：第一期固定 `/root/workspace`（cwd 决定会话分组和 AGENTS.md 加载）。多项目目录放第二期。

### 3.5 流式输出与界面状态

`TranscriptReducer` 是纯函数 `(SessionUiState, RpcEvent) -> SessionUiState`：
- `message_start(assistant)` 新建一个 `ChatItem.Assistant(blocks = [])`；
- `message_update`：按 `contentIndex` 找到块，`*_delta` 追加到 `StringBuilder` 型缓冲，`*_end` 用权威内容替换；
- `message_end`：整条用权威消息替换（修正任何累计误差），记录 `stopReason`、`errorMessage`、`usage`；
- 工具：`toolcall_*` 建立 `ToolCallUi(id, name, argsText, status = Pending)`；`tool_execution_start` → Running；`_update` → 更新 `partialOutput`（**替换**而非追加，bash 的 partialResult 本身是累计的）；`_end` → Done/Failed + `durationMs`；
- 界面刷新节流：reducer 每条事件都跑，但向 Compose 发布状态最多 30 次/秒（`conflate()` + 33 ms 采样），长文本只重组最后一个块。

`SessionUiState` 关键字段：`items: List<ChatItem>`、`isStreaming`（以 `agent_start`→`agent_settled` 为准）、`queue: QueuedMessages`、`status: StatusBanner?`（重试/压缩/错误）、`model`、`sessionRef`、`sessionName`。

### 3.6 工具调用事件与卡片

- 每个工具调用一张卡片：标题 = 工具名 + 关键参数摘要（`bash` 显示命令首行、`read`/`write`/`edit` 显示路径），状态图标（进行中/成功/失败），耗时。
- 默认折叠，点开看参数 JSON 与输出（输出超过 200 行时只显示首尾，提供「复制全部」）。
- `edit` 工具第一期只显示参数中的旧/新文本；按文件的 diff 视图放第二期。
- 思考块（thinking）：生成中显示最近 3 行，结束后折叠成「已思考 N 秒」。
- 一轮正常结束后，中间过程（思考 + 工具卡片）折叠成一行「已执行 N 个步骤」，最终回答留在外面；被中断或出错的回合**不折叠**，让用户看见停在哪一步。（设计思路参考了 dsh-Go 的公开说明，代码自写。）

### 3.7 中断、插话、排队

pi 的语义（源码 `agent-session.ts` / `agent-loop.ts`）：
- **steer（插话）**：进入 steering 队列，在**当前回合的工具调用都执行完、下一次请求模型之前**注入；不打断正在输出的文字，也不跳过正在执行的工具。
- **followUp（排队）**：只有 agent 本来要停下时才取出，开始下一轮。
- 生成中发 `prompt` 不带 `streamingBehavior` 会被拒（`success:false`）。
- `abort` 不清空队列。

客户端交互：
- 空闲时：发送 = `prompt`。
- 生成中：发送键默认「排队」（`prompt` + `streamingBehavior:"followUp"`）；长按发送键可选「插话」（`streamingBehavior:"steer"`）。默认选排队而不是插话，因为排队不会改变当前这一轮的行为，更不容易出乎用户意料（第二期可在设置里改默认）。
- 输入框为空且正在生成时，发送键变成停止键：先 `clear_queue`（拿回未发送文本放回输入框，不覆盖用户正在写的新草稿），再 `abort`。
- 队列条：根据 `queue_update` 显示待发消息，每条可「撤回」（`clear_queue` 后把其余条目按原顺序重新 `follow_up`；第一期接受这一简化）。
- 乐观显示：用户点发送后立即显示气泡（状态「发送中」），收到对应 `id` 的 response 后转为「已排队/已发送」，失败则标红并给「重试」。

### 3.8 扩展对话框（extension_ui_request）

对话类（`select`、`confirm`、`input`、`editor`）**pi 会阻塞等待应答**，其中 `editor` 没有超时。客户端必须：
- 收到即弹出原生对话框；用户关闭 = `{"cancelled":true}`；
- 未实现的 method 立即回 `cancelled`，绝不让 pi 挂起；
- 即发即忘类（`notify`、`setStatus`、`setWidget`、`setTitle`、`set_editor_text`）：`notify` 显示为 Snackbar，`set_editor_text` 填入输入框，其余第一期忽略。

### 3.9 错误与重连

| 场景 | 识别 | 处理 |
|---|---|---|
| 启动失败（无可用模型、扩展加载报错、参数错误） | 进程在回应首条 `get_state` 前退出，退出码非 0 | 读 stderr 尾部，归类成中文提示（「还没有配置可用的模型」→ 跳转模型配置；其余显示「pi 启动失败」+ 查看日志） |
| 就绪 | 启动后发 `get_state`，收到响应即 Ready；15 秒超时视为失败 | — |
| 模型调用出错 | `message_end.stopReason == "error"`，或 `auto_retry_end.success == false` | 在该条消息下显示中文错误 + 「重试」（重发上一条 user 文本）；401/403 类提示检查 Key |
| 进程意外退出（被系统杀、OOM、proot 崩溃） | `AgentProcess.exit` 完成 | 所有未完成请求标记失败；界面显示「助手已断开，正在重新连接」；退避重启（1s、2s、5s、10s，最多 5 次）后用 `--session <上次 sessionFile>` 启动并 `get_messages` 重建；正在流式的那条消息可能丢失，标注「这条回复未完成」 |
| 命令超时 | 普通命令 30 秒、`abort` 60 秒无响应 | 提示并提供「重启助手」 |
| 停止 | 用户退出或在设置里停止 | 关闭 pi 的 stdin（EOF）让 pi 自行 `shutdown()`；5 秒后仍在就对 proot 发 SIGQUIT（proot 会忽略 TERM），**只杀自己启动的进程树**，不再 `pkill node` |

---

## 4. 运行环境：从 PiMet 迁移

### 4.1 原样搬（改包名、去掉对 PiMetConfig/WebView 的引用）

| PiMet 源 | 去向 | 说明 |
|---|---|---|
| `app/src/main/jniLibs/{arm64-v8a,x86_64}/{libproot,libprootloader,libprootloader32,libtalloc,libandroidshmem}.so` | `core/runtime/src/main/jniLibs/` | 保留 `packaging.jniLibs.useLegacyPackaging = true` 和 `android:extractNativeLibs="true"`。**不带** `libproroot*.so`（代码未使用、来源和许可证不明） |
| `proot/TarGzipExtractor.java`、`util/SafeFiles.java` | `pigo.runtime.archive` | 同时修：未知类型条目要跳过数据体、`strip` 后空名条目同理、PAX 解析 `linkpath` |
| `proot/ProotManager.java` 中 `buildProotProcess`、`ensureRuntimeFiles`、`copyExec`、`ensureContainerDns`、`ensureContainerGroups`、`ensureNpmConfig`、`downloadWithResume`、`testAndSortCandidateUrls`、`buildDomesticRootfs`、`writeDomesticAptConfig`、`latestNodeV24`、`runInContainer` | 拆到 `ProotLauncher`、`RootfsInstaller`、`Downloader`、`MirrorProbe` | 去掉 `PORT` 与 pi-web 相关参数；环境变量注入改为参数 |
| `PiMetService.java` | `RuntimeService` | 改为由服务持有并守护 pi 进程（不再依赖 Activity 存活） |
| `PiMetConfig.java` 中写 `auth.json`/`models.json`/`settings.json` 的逻辑 | `:core:rpc` 的 `ProviderConfigStoreImpl` | Key 改存 AndroidX Security 加密存储，不再明文 SharedPreferences |

### 4.2 改写或丢弃

- `PiWebManager`：丢弃 pi-web 安装、`fixPiCodemodeCompat`、`optimizePiWebOffline`、`stopPiWebSync`（会 `pkill -9 node` 误杀），由 `RootfsInstaller.installPi()` + `PiRuntime.startAgent()` 取代。
- `PiWebActivity`、`PiWebApi`、`MainActivity` 的 WebView 与 `/api/*`、`translation/*`、`ui/PiWebCompat`、`monitor/*`、死代码（`ShellSession`、`DeployScriptGenerator`、`ProotSession` 等）：不迁移。
- 第二期再迁：`terminal/TerminalHub`+`FloatingTerminal`+`assets/extensions/pimet-terminal.ts`（浮动终端）、`bridge/AppBridgeManager`（**所有路由都校验令牌**）、`automation/PiMetAccessibilityService`、`pet/*`（**不带**内置的 Doro、Elaina 素材，存在第三方角色版权风险）。

### 4.3 首启安装流程（国内镜像为主）

1. **空间检查**：可用空间 < 3 GB 时拒绝并提示（PiMet 没做这一步）。
2. **Ubuntu Base 24.04**：镜像按测速排序——阿里云、清华 TUNA、中科大、华为云（路径 `ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-<ver>-base-<arm64|amd64>.tar.gz`），版本 24.04.5 → .4 → .3 依次探测。**下载同目录 `SHA256SUMS` 校验**（PiMet 没有校验）。
3. **Node.js 24**：npmmirror（`registry.npmmirror.com/-/binary/node/`）→ 华为云 → 清华 → 阿里云；**用 `SHASUMS256.txt` 校验**；解压到 `/usr/local`（strip 1）。pi 1.5.0 要求 Node ≥ 22.19。
4. **容器配置**：DNS（宿主 DNS + 223.5.5.5、119.29.29.29）、`/etc/group`、apt 源换阿里云（deb822）、`/root/.npmrc` 指向 npmmirror。
5. **安装 pi**：`npm install -g @earendil-works/pi-coding-agent@<锁定版本> --no-audit --no-fund`（**不加** `--ignore-scripts`，PiMet 加它会让需要 postinstall 的依赖装残）。
6. **验证**：`pi --version` 输出与锁定版本一致；写标记 `/etc/pigo-runtime.json`（记录 ubuntu/node/pi 版本和安装时间）。
7. **后台补装**：`git ca-certificates curl xz-utils procps`（不阻塞进入聊天）。

断点续传：每个文件下载到 `<name>.part`，**换镜像时删掉 `.part` 重新下**（PiMet 会把不同镜像的内容拼进同一文件）；解压到 `staging/` 后原子重命名为 `rootfs/`。

可选路线（第二期）：预构建 rootfs（自己的 Release，带 `manifest.json` 与 SHA256，构建脚本放仓库可复现），用于网络差的用户。

### 4.4 版本升级策略

- App 内锁定一个 pi 版本（`core/runtime/src/main/assets/runtime-lock.json`：`{ "pi": "1.5.0", "node": "24.x", "ubuntu": "24.04" }`）。CI 不自动跟随最新版。
- 启动时比对 `/etc/pigo-runtime.json`：pi 版本低于锁定版本 → 提示「助手需要更新」，用户确认后 `npm install -g @earendil-works/pi-coding-agent@<锁定版本>`；失败则保持旧版可用。
- 升级 pi 前先停进程；会话文件（`~/.pi/agent/sessions`）和配置不受影响。
- Node 大版本或 Ubuntu 升级：第二期做「重建容器、保留 /root」（先把 `/root` 移到 `home.keep/`，装好新 rootfs 后搬回）。
- 协议兼容：`RpcCodec` 忽略未知字段和未知事件；每次升级 pi 锁定版本时，必须跑一遍 `:core:rpc` 的录制回放测试（`src/test/resources/transcripts/*.jsonl`）。

---

## 5. 第一期 MVP 范围

目标：**真机走通**「首启安装容器 + pi → 配置模型/API Key → 原生聊天流式对话 → 工具调用卡片折叠 → 中断/排队 → 会话列表」。

### 5.1 功能清单与验收

| # | 功能 | 验收标准（真机 arm64，Android 10–15 各一台） |
|---|---|---|
| F1 | 首启安装向导 | 全新安装后进入向导，显示分步进度（下载 Ubuntu、下载 Node、安装 pi、检查），每步有百分比和速度；断网后可「重试」从当前步骤继续；空间不足有中文提示；完成后自动进入模型配置 |
| F2 | 安装完整性 | Ubuntu/Node 包 SHA256 校验失败时删除并换镜像重下；安装完成后 `pi --version` 等于锁定版本 |
| F3 | 模型配置 | 预设 DeepSeek、通义千问、Kimi、智谱、OpenAI、Anthropic、OpenRouter、自定义 OpenAI 兼容；可粘贴 Key（自动去空格和 `Bearer ` 前缀）；「测试连接」在 15 秒内给出成功或中文原因；保存后 pi 重启并 `get_available_models` 能列出该模型 |
| F4 | 原生聊天 | 发送后 2 秒内出现「思考中」，文字逐段流式出现；Markdown（标题、列表、代码块、表格）正确显示；代码块可复制 |
| F5 | 工具卡片 | 让 pi 执行 `ls`、读写文件时出现卡片，显示工具名、参数摘要、状态、耗时；点开看到输出；正常结束的回合中间过程自动折叠为一行 |
| F6 | 中断 | 生成中点停止，3 秒内停止输出，回合标为「已停止」，输入框可继续发送 |
| F7 | 排队 / 插话 | 生成中发送的消息出现在队列条，本轮结束后自动开始；长按发送选「插话」时，消息在下一次工具调用后被采纳；队列中的消息可撤回 |
| F8 | 会话列表 | 列出所有会话（名称或首条消息、时间），点击打开并恢复完整历史；可新建；可重命名当前会话；删除会进入回收目录 |
| F9 | 后台与恢复 | 切到后台 5 分钟内生成不中断（前台服务通知可见）；手动杀掉 pi 进程后 30 秒内自动重连并恢复当前会话 |
| F10 | 扩展对话框 | 扩展发出 confirm/select/input 时弹出原生对话框；关闭对话框 pi 不会卡住 |
| F11 | 错误提示 | 无网络、Key 错误、额度不足、模型不存在各给一句中文提示，不直接显示英文异常栈 |
| F12 | 返回键 | 聊天页按返回 → 退到后台（不结束服务）；向导中按返回 → 确认是否中止安装 |

### 5.2 不在第一期

浮动终端、桌宠、手机自动化、网页模式、横屏/平板双栏、主题配色、图片输入、语音、多工作目录、预构建 rootfs、提升 targetSdk。

---

## 6. 并行工作流拆分

规则：
- 所有分支从 `main` 拉出；**`mvp/scaffold` 先单独合进 `main`**，其余 4 条再从合入后的 `main` 拉。
- 每条工作流**只改自己范围内的文件**；跨模块接口只在 `:core:contract`，MVP 期间只增不改，变更需在 PR 中单独说明。
- 字符串放各自模块的 `res/values/strings.xml`（模块内独占），`:core:ui` 的公共字符串由 scaffold 一次放好。
- 每条工作流附带 `docs/workflows/<名>.md`（自己的进度与待办）。
- 合并顺序：scaffold → runtime / rpc（可并行）→ chat / setup（可并行，可先用 Fake 实现开发）。

| 分支 | 负责范围 | 依赖 | 验收 |
|---|---|---|---|
| **mvp/scaffold** | 根 `settings.gradle.kts`、`build.gradle.kts`、`gradle/`、`gradlew*`、`gradle/libs.versions.toml`；`app/`（`MainActivity`、`AppNavHost`、`AppContainer`）；`core/contract/` 全部接口与数据类；`core/ui/`（主题、通用组件）；其他 4 个模块的空壳（`build.gradle.kts`、`AndroidManifest.xml`、占位类：`PiRuntimeImpl`、`PiAgentClientImpl` 等返回 `NotImplementedError`）；`:core:contract` 中的 `Fake*` 实现（`FakePiRuntime`、`FakePiAgentClient` 能回放一段录制的对话） | 无 | CI 绿，产出可安装 debug APK；启动后能在三个占位页面间导航；用 Fake 实现能看到一段假的流式对话；`./gradlew test` 通过 |
| **mvp/runtime** | `core/runtime/**`（含 jniLibs、assets/runtime-lock.json）；`docs/workflows/runtime.md`；`THIRD_PARTY_NOTICES.md` 中运行时部分 | scaffold | F1、F2、F9 中进程守护部分；`PiRuntime` 全部方法真实可用；真机上 `startAgent` 后 `get_state` 有响应；停止后无残留 node/proot 进程 |
| **mvp/rpc** | `core/rpc/**`（含测试与录制回放 `src/test/resources/transcripts/`）；`docs/workflows/rpc.md` | scaffold（运行时可用 Fake，真机联调依赖 runtime） | `TranscriptReducer` 单元测试覆盖：纯文本、思考、多工具、并行工具、中断、排队、错误、重试、压缩；id 关联乱序测试；F3 的配置文件生成与 F8 的会话扫描单测；与 runtime 联调后 F4–F7 协议层可用 |
| **mvp/chat** | `feature/chat/**`；`docs/workflows/chat.md` | scaffold（先接 Fake，联调依赖 rpc） | F4、F5、F6、F7、F10、F11（聊天内部分）；Compose 预览覆盖各状态；1000 条消息的会话滚动不掉帧（Pixel 6 级别真机目测 60fps） |
| **mvp/setup** | `feature/setup/**`；`docs/workflows/setup.md` | scaffold（先接 Fake，联调依赖 runtime、rpc） | F1 的界面、F3、F8、F11（配置部分）、F12；向导可从任意步骤恢复 |

`app/` 的 `AppContainer` 装配真实实现这一行改动，由最后合入的工作流在 PR 里完成（只改一处 `bind`，冲突面极小）。

---

## 7. 后续路线

**第二期（体验补全）**
- 浮动终端：迁移 `TerminalHub` + `FloatingTerminal`（Termux terminal-emulator/view，Apache-2.0）与 `pimet-terminal.ts` 扩展，AI 和用户共享同一终端会话；桥接口改为全路由令牌校验。
- 工具卡片增强：按文件合并 diff、轮末「改动了哪些文件」汇总、图片输入。
- 主题：浅色/深色/跟随系统之外加几套配色，对话字号档位。
- 横屏与平板：按窗口尺寸分级，宽屏下会话列表常驻左栏。
- 多工作目录、项目信任（`-a` / `trust.json`）、`compact` 手动压缩入口、用量统计（`get_session_stats`）。
- 预构建 rootfs（可复现构建 + SHA256）、容器重建保留 `/root`。

**第三期（能力扩展）**
- 手机自动化：迁移无障碍服务，加确认档位、敏感应用黑名单、密码框打码，悬浮条可随时停止。
- AI 桌宠：迁移悬浮窗与动画，聊天后端改走 `PiAgentClient`；素材只用有授权的。
- 网页模式切换：可选安装 pi-web，在原生与网页之间切换并带上当前会话（只作为高级选项）。
- 评估提升 targetSdk（不依赖执行 App 数据目录文件的方案）与上架可能性。
- 评估自写 Node 桥（SDK）以获得列会话、会话树导航等 RPC 缺失的能力。

---

## 8. 许可证

- **dsh-Go（AGPL-3.0）**：只参考其公开的设计思路（工具卡片折叠、插话/排队交互、运行时状态机等），**没有拷贝任何代码**；不使用 dsh-Go 的名称、标识、Deepy/鲸娘形象。因此 pi-go 不受 AGPL 约束。后续工作流同样禁止从 dsh-Go 复制代码或资源。
- **PiMet**：用户本人的项目，可以复用。**PiMet 仓库目前没有 LICENSE 文件**，建议作者为 PiMet 补一个许可证，并为 pi-go 选定许可证（例如 Apache-2.0 或 GPL-3.0）。
- **PiMet 里的第三方组件**：PRoot（GPL-2.0-or-later，可执行文件，分发时须提供对应源码或书面承诺）、talloc（LGPL-3.0-or-later）、libandroid-shmem（Termux，BSD-3-Clause，待核实）、Termux terminal-emulator/terminal-view（Apache-2.0）、Shizuku API（待核实）。详见根目录 `THIRD_PARTY_NOTICES.md`。
- **运行时下载的组件**（不打包进 APK）：Ubuntu Base（各包各自许可证）、Node.js（MIT）、`@earendil-works/pi-coding-agent`（MIT，用户设备上通过 npm 安装）。
