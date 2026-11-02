# 提案 C：pi-go 原生 Android 客户端设计

> 状态：提案（多方案评审之一，独立撰写）
> 作者：提案 C
> 日期：2026-11-03
> 依据：zr987459/pimet@a80222c（v1.9.0-beta.30）源码；`@earendil-works/pi-coding-agent@1.5.0` npm 包内 `docs/rpc.md`、`docs/rpc-commands.md`、`docs/json.md`、`docs/rpc-extension-ui.md`、`docs/message-types.md`、`docs/session-format.md`、`docs/providers.md`、`docs/models.md`、`docs/cli.md` 与 `dist/modes/rpc/rpc-types.d.ts`；ccch1mneyyy/dsh-Go 仓库结构与 README（只看设计，不看/不拷代码）。

---

## 0. 结论先行

| 决定 | 选择 | 一句话理由 |
|---|---|---|
| 技术栈 | **Kotlin + Jetpack Compose**（单 Activity，多 Gradle 模块） | PiMet 全部是 Java + Android View，Kotlin 与 Java 零成本互调，PRoot/下载/终端/桌宠/无障碍代码可以整文件搬；Flutter 需要把这些全部包进 Platform Channel，复用率最低。 |
| 通信协议 | **pi 官方 RPC 模式**：`pi --mode rpc`，经 PRoot 子进程的 stdin/stdout 走 JSONL | 官方、稳定、带类型定义；原生支持流式、工具事件、`steer`/`follow_up` 排队插话、`abort`、`clear_queue`、会话切换；不需要端口、鉴权和额外 npm 包。 |
| 引擎包名 | **`@earendil-works/pi-coding-agent`**，固定版本（当前 1.5.0） | `@mariozechner/pi-coding-agent` 已停在 0.73.1，项目已迁到 earendil-works/pi；PiMet 设备版本也已安装新包名。 |
| 许可证 | **不拷 dsh-Go 任何代码 → 不需要 AGPL**；pi-go 自身许可证由仓库主人决定（建议 GPL-3.0-or-later 或 Apache-2.0，见 §9） | 只借鉴 dsh-Go 的交互设计。 |
| 不做 | 不再内嵌 `@agegr/pi-web`，不用 WebView 承载聊天 | 用户明确要求原生界面。 |

---

## 1. 现状调研

### 1.1 PiMet（a80222c，v1.9.0-beta.30）

- **语言/框架**：**纯 Java 11 + 传统 Android View**（AppCompat、Material Components 1.9、ConstraintLayout），**没有 Kotlin、没有 Compose**。单模块 `:app`，Groovy `build.gradle`。
- **SDK**：`minSdk 26`，`targetSdk 28`（刻意停在 28，以便执行应用私有目录里的二进制，与 Termux 同理），`compileSdk 34`；`jniLibs.useLegacyPackaging = true`（proot 以 `libproot.so` 形式放在 nativeLibDir，可执行）。
- **依赖**：OkHttp 4.12、androidx.webkit、Termux `terminal-view` / `terminal-emulator` v0.118.0、Shizuku api/provider 13.1.5、documentfile。
- **包结构**（`com.xm486.pimet`）：

| 包 | 主要类 | 与 pi-go 的关系 |
|---|---|---|
| `proot/` | `ProotManager`(1280 行)、`ProotSession`、`PiWebManager`、`TarGzipExtractor` | **核心复用**：rootfs 安装、镜像竞速、国内现装、`buildProotProcess()` |
| `terminal/` | `TerminalHub`、`FloatingTerminal`、`ShellSession`、`Downloader`、`NetworkRouteSelector`、`EnvironmentDoctor`、`DeployScriptGenerator`、`AnsiParser` | 下载/选路/诊断直接复用；浮动终端二期复用 |
| `bridge/` | `AppBridgeManager`（127.0.0.1:30143，`POST /api/app-control`，Token 头 `X-PiMet-Token`） | 二期复用（终端/手机控制给 pi 扩展调用） |
| `automation/` | `PiMetAccessibilityService`(1337 行) | 三期复用 |
| `pet/` | `PetOverlayService`(1953 行) 等 | 三期复用 |
| `assets/extensions/` | `pimet-terminal.ts`、`pimet-android-bridge.ts`、`pimet-browser.ts`（pi 扩展，`import type { ExtensionAPI } from "@earendil-works/pi-coding-agent"`） | 二期改名复用 |
| `PiWebActivity/PiWebApi/ui.PiWebCompat/monitor.*/translation.*` | Pi-Web 网页外壳、各种端口监控、网页翻译 | **丢弃** |

- **运行环境关键事实**（来自源码）：
  - rootfs 来源：`https://github.com/zr987459/pimet/releases/download/runtime-latest/rootfs.tar.gz`（x86_64 另有包），经 `gh-proxy.com / ghfast.top / ghproxy.net / github.com` 真吞吐竞速；若最快源 < 1 MB/s 自动走 **国内现装**：从阿里云/清华/中科大/华为云拉 Ubuntu Base 24.04.x，从 npmmirror/华为云/清华/阿里云拉 Node v24，在手机上组装。
  - 容器内配置：DNS、国内 apt 源（`99pimet` apt 配置）、`.npmrc`（registry=npmmirror 及各类二进制镜像）、groups。
  - pi 安装：`npm install -g --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent @agegr/pi-web`。
  - PRoot 参数：`--link2symlink -L --kill-on-exit -0 -r <rootfs>`，绑定 `/dev /proc /sys`、`/storage/emulated/0:/sdcard`、`getExternalFilesDir:/root/shared`；`/usr/bin/env -u LD_LIBRARY_PATH` 防止宿主库路径泄进容器。
- **许可证**：PiMet 仓库**没有 LICENSE 文件**（GitHub API `license: null`）。仓库主人复用自己的代码没有问题，但 pi-go 发布时需要为这些代码选定许可证（§9）。

### 1.2 dsh-Go（AGPL-3.0，只借鉴设计）

Flutter（`app/lib/*`）+ Kotlin 平台层（`app/android/.../kotlin/...` 下 phone/a11y/pet/notify/islands 等插件），引擎是 DeepSeek Harness（不是 pi），通过 Dart 客户端访问其 Web API。值得借鉴的**交互设计**：
1. 工具调用做成原生卡片，执行完自动折叠成一行；
2. 回复中途可插话，排队消息可「立即发送」；
3. 思考/工具过程折叠；
4. 横屏/平板双栏；
5. 手机控制以“工具”形式暴露给 Agent。

**红线**：不拷贝任何文件或代码片段，不使用 dsh-Go/DSH 名称、图标、Deepy、鲸娘。评审时把“是否出现 dsh 代码”作为合并检查项。

### 1.3 pi RPC 能力（来自 1.5.0 文档与 `rpc-types.d.ts`）

- 启动：`pi --mode rpc [--session <path|id>] [--no-session] [--session-dir <dir>] [--provider ..] [--model ..] [-e <ext>]`。
- stdin 命令（全部 `type` 字段）：`prompt`、`steer`、`follow_up`、`abort`、`clear_queue`、`new_session`、`get_state`、`get_messages`、`set_model`、`cycle_model`、`get_available_models`、`set_thinking_level`、`cycle_thinking_level`、`get_available_thinking_levels`、`set_steering_mode`、`set_follow_up_mode`、`compact`、`set_auto_compaction`、`set_auto_retry`、`abort_retry`、`bash`、`abort_bash`、`get_session_stats`、`export_html`、`switch_session`、`fork`、`clone`、`get_fork_messages`、`get_entries`、`get_tree`、`get_last_assistant_text`、`set_session_name`、`get_commands`。
- stdout：`{"type":"response","command":..,"success":..,"id"?,"data"?,"error"?}`、会话事件（`agent_start/agent_end/agent_settled`、`turn_start/turn_end`、`message_start/message_update/message_end`、`tool_execution_start/update/end`、`queue_update`、`compaction_*`、`auto_retry_*`、`session_info_changed`、`thinking_level_changed`、`extension_error`、`bash_execution_update`）、`extension_ui_request`。
- **没有“列出会话”命令**；会话文件在 `~/.pi/agent/sessions/--<path>--/<timestamp>_<session-id>.jsonl`，首行为 `{"type":"session","version":3,"id","timestamp","cwd","parentSession"?}`，名称在 `session_info` 条目里。删除会话 = 删除文件（文档明确）。

---

## 2. 技术栈选择

### 2.1 候选对比

| 维度 | Kotlin + Compose | Flutter + Kotlin 平台层 |
|---|---|---|
| 复用 PiMet（Java/View） | Java 类原样放进 Kotlin 工程即可编译；View（Termux `TerminalView`、桌宠悬浮窗）用 `AndroidView` 嵌入；Service/无障碍不变 | 所有能力都要包成 MethodChannel/EventChannel；终端要 PlatformView（Hybrid Composition 性能差、输入法问题多）；聊天客户端要用 Dart 重写 |
| 进程/流 | 子进程 stdin/stdout 直接在 Kotlin 协程里读写，`Flow` 推给 UI，零序列化跨界 | 流式 token 每条都要穿越平台通道（高频小消息），或把进程管理也搬到 Dart（Dart 无法直接用 PiMet 的 ProcessBuilder 封装） |
| 悬浮窗 / 无障碍 / 前台服务 | 原生一等公民 | 仍需 Kotlin 写，等于两套 UI 技术 |
| 包体/内存 | 无额外引擎（+~0 MB） | Flutter 引擎 +8~12 MB，常驻内存更高；手机还要跑 Node + pi |
| 横屏/平板 | `WindowSizeClass` + `ListDetailPaneScaffold` | 自己写断点 |
| 团队与 AI 辅助编码 | 与 PiMet 同语言族，可逐步把 Java 改写为 Kotlin | 新增 Dart 技术栈 |
| 跨平台（iOS/鸿蒙） | 不支持 | 有潜力，但 PRoot/Node 在 iOS 本来就跑不了，跨平台价值很小 |

### 2.2 决定

**Kotlin 2.x + Jetpack Compose（Material 3）**，单 Activity + Compose Navigation，手写轻量 DI（`AppGraph`），不引入 Hilt（减少编译时间与 KSP 复杂度）。
- 复用的 PiMet Java 文件**先原样搬**（只改包名），在 Kotlin 侧包一层接口；后续按需渐进改写。
- 依赖控制：`kotlinx-coroutines`、`kotlinx-serialization-json`、Compose BOM、`androidx.navigation:navigation-compose`、`androidx.lifecycle`、OkHttp（PiMet 下载器在用）、Termux terminal 库（二期）。Markdown 渲染库在 mvp/chat 选型（要求 Apache-2.0/MIT）。
- `minSdk 26`、`targetSdk 28` 沿用 PiMet（理由同 Termux：在私有目录执行容器内二进制）；代价是不能上架 Google Play，侧载分发（§10 风险）。`compileSdk 35`。
- 只打 `arm64-v8a` 与 `x86_64`（与 PiMet jniLibs 一致）。

---

## 3. 模块划分与接口

### 3.1 Gradle 模块

```
pi-go/
├── settings.gradle.kts
├── gradle/libs.versions.toml            # 版本目录
├── build-logic/                         # convention plugins（android-library / compose / jvm-library）
├── app/                                 # :app  com.xm486.pigo  —— Activity、导航、主题、AppGraph、PiGoService
├── core/
│   ├── common/                          # :core:common  纯 Kotlin：Result、日志脱敏、Dispatchers 抽象
│   ├── rpc/                             # :core:rpc     纯 Kotlin/JVM：协议类型、JSONL 分帧、RpcClient、事件→状态 reducer
│   ├── runtime/                         # :core:runtime Android：PRoot 启动、rootfs 安装、镜像选路、容器配置、诊断（含搬来的 Java）
│   ├── agent/                           # :core:agent   Android：AgentHost（拉起/守护 pi 进程，连接 RpcClient）、PiInstaller、扩展部署
│   └── data/                            # :core:data    Android：SessionIndex、SecretStore、ModelConfig、设置
├── feature/
│   ├── onboarding/                      # :feature:onboarding  首启安装向导 + 模型/API Key 引导
│   ├── chat/                            # :feature:chat        聊天、流式渲染、工具卡片、输入栏、队列
│   ├── sessions/                        # :feature:sessions    会话列表
│   ├── settings/                        # :feature:settings    模型/Provider/Key、运行时、关于
│   ├── terminal/                        # :feature:terminal    （占位，二期）浮动终端
│   └── device/                          # :feature:device      （占位，三期）桌宠 / 无障碍自动化
└── docs/
```

依赖方向（只允许向下）：`app → feature:* → core:agent/data/runtime → core:rpc → core:common`。`feature:*` 之间互不依赖，跨页跳转走 `app` 里的导航回调。

包名：`com.xm486.pigo.<module>`，如 `com.xm486.pigo.core.rpc`。`applicationId = com.xm486.pigo`（与 PiMet 不同，两者可共存；注意两个 App 的容器互不共享）。

### 3.2 核心接口（scaffold 阶段即落地，带 Fake 实现）

```kotlin
// :core:rpc —— 纯 JVM，可单测
interface PiRpcClient : Closeable {
    val events: SharedFlow<RpcEvent>                       // 所有非 response 记录
    suspend fun request(cmd: RpcCommand, timeoutMs: Long = 30_000): RpcResponse   // 自动分配 id 并按 id 关联
    fun replyExtensionUi(resp: ExtensionUiResponse)        // 不产生 response
}
fun PiRpcClient(input: InputStream, output: OutputStream, scope: CoroutineScope): PiRpcClient

class ChatReducer { fun reduce(state: ChatState, ev: RpcEvent): ChatState }   // 事件 → 界面状态，纯函数

// :core:runtime
interface RuntimeManager {
    val state: StateFlow<RuntimeState>                     // NotInstalled / Installing(step,pct,msg) / Ready / Broken(reason)
    suspend fun install(listener: InstallListener)         // 可断点续装
    fun processBuilder(workDir: String, argv: List<String>, env: Map<String, String>): ProcessBuilder
    suspend fun exec(cmd: String, timeoutMs: Long, onLine: (String) -> Unit = {}): Int
    suspend fun doctor(): DoctorReport
    fun hostPath(guestPath: String): File                  // /root/... → files/rootfs/root/...
}

// :core:agent
interface PiInstaller {
    suspend fun installedVersion(): String?
    suspend fun install(version: String, registry: String, listener: InstallListener)
    suspend fun upgrade(target: String, listener: InstallListener): UpgradeResult   // 分阶段 + 冒烟 + 切换 + 可回滚
    suspend fun rollback(): Boolean
}
interface AgentHost {
    val status: StateFlow<HostStatus>                      // Stopped / Starting / Running(pid) / Crashed(err, retryInMs)
    val client: StateFlow<PiRpcClient?>
    suspend fun start(opts: StartOptions)                  // cwd、sessionPath?、provider/model?、env
    suspend fun restart(reason: String)                    // 例如换了 API Key
    suspend fun stop()                                     // 关 stdin → 等退出 → 超时 destroy
}

// :core:data
interface SessionIndex { suspend fun list(cwd: String? = null): List<SessionSummary>; suspend fun delete(path: String) }
interface SecretStore  { fun put(key: String, value: String); fun get(key: String): String?; fun remove(key: String) }
interface ModelConfig  {
    suspend fun providers(): List<ProviderPreset>          // 内置预设 + 自定义兼容端点
    suspend fun saveProvider(cfg: ProviderConfig)          // Key 进 SecretStore；兼容端点写 models.json
    fun buildEnv(): Map<String, String>                    // 启动 pi 时注入的环境变量
}

// :feature:chat 只依赖这个仓库接口
interface ChatRepository {
    val state: StateFlow<ChatState>
    suspend fun send(text: String, images: List<ImageAttachment> = emptyList(), mode: SendMode = SendMode.Auto)
    suspend fun abort()
    suspend fun recallQueue(): QueueSnapshot               // clear_queue，返回文本放回输入框
    suspend fun sendQueuedNow(index: Int)                  // 见 §4.6
    suspend fun newSession(); suspend fun switchSession(path: String); suspend fun rename(name: String)
    suspend fun setModel(provider: String, modelId: String); suspend fun setThinking(level: String)
}
```

`AppGraph`（`:app`）按工作流拆成多个文件各自注册，避免并行开发冲突：`di/RuntimeBindings.kt`、`di/AgentBindings.kt`、`di/DataBindings.kt`、`di/ChatBindings.kt`；scaffold 先全部绑 Fake。

---

## 4. 原生界面 ↔ 容器内 pi 的通信

### 4.1 方案比较

| 方案 | 形态 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **RPC 模式** `pi --mode rpc` | 长驻子进程，stdin 命令 / stdout 事件，JSONL | 官方协议、有 `rpc-types.d.ts`；流式 delta、工具生命周期、`steer`/`follow_up`/`abort`/`clear_queue`、会话切换/分叉、扩展 UI 对话框齐全；无端口、无鉴权面；进程随 App 生死 | 一个进程一个活动会话；要自己处理分帧、背压、崩溃重启 | **采用** |
| JSON 模式 `pi --mode json "…"` | 一次性进程，只出事件 | 简单 | 单向、跑完即退出；不能插话/排队/中断（只能 kill），每轮都冷启动 Node（PRoot 下数秒） | 否 |
| pi-web HTTP API（`@agegr/pi-web`） | Next.js 服务，127.0.0.1:30141 | PiMet 已有 `PiWebApi` 客户端 | 第三方、非官方，接口随 pi-web 改版漂移；额外 npm 包与常驻 Next.js 内存；本身就是要抛弃的网页层 | 否 |
| SDK + 自写 Node sidecar（WebSocket） | 容器里跑我们自己的 TS 服务，进程内调用 SDK | API 最全，可多会话、多客户端 | 要维护一份 TS 服务及其版本兼容；多一层故障点 | 二期备选（远程/多端时再议） |

### 4.2 进程模型

```
PiGoService (前台服务, WakeLock)
 └─ AgentHost
     └─ ProcessBuilder(proot … /usr/bin/env -u LD_LIBRARY_PATH  pi --mode rpc --session-dir /root/.pi/agent/sessions [--session <file>] -e /root/.pi-go/ext/*.ts)
          stdin  ← RpcWriter（单写协程，Channel 串行化）
          stdout → RpcReader（专用 IO 线程，按字节 LF 分帧）→ PiRpcClient.events / 按 id 完成请求
          stderr → 环形日志（2 MB，脱敏后可在设置页导出），不当协议解析
```

- 工作目录：默认容器内 `/root/workspace`；用户可选目录（可选 `/sdcard/...`，即手机共享存储），换目录 = 用新 `cwd` 重启 pi。
- 一期**单进程单活动会话**：切换会话用 `switch_session`（运行中先提示中断）。二期再做“每个运行中的会话一个进程”的池（上限 2~3，受内存约束）。
- 环境变量：沿用 PiMet `buildProotProcess` 的 guest 环境（HOME/PATH/LANG/TERM/TMPDIR），再合并 `ModelConfig.buildEnv()`（API Key），**Key 不落盘**。

### 4.3 分帧与序列化（按官方要求）

- **只按 `\n`（0x0A）切分**，去掉行尾可选 `\r`；用字节缓冲 + UTF-8 解码，不用会把 U+2028/U+2029 当换行的读取器（官方文档专门警告）。
- **持续读 stdout**，否则 pi 会因背压卡住；读线程只做“解析 + 投递到 `MutableSharedFlow(extraBufferCapacity = 4096)`”，渲染节流在 UI 层做。
- 写入：一条命令一个 JSON 对象 + `\n`，`flush()`；所有命令带自增字符串 `id`（`"c-<n>"`），response 按 `id` 关联，不按顺序。
- 解析容错：`kotlinx.serialization` 的 `JsonObject` 先按 `type` 分发，已知字段解析成数据类，**未知 `type`/字段保留原始 `JsonElement`**（`RpcEvent.Unknown(raw)`），升级 pi 时不崩。
- 解析失败的 response（`"command":"parse"`）记录为协议错误，不影响连接。

### 4.4 消息格式（与官方一致，节选）

```jsonc
// 发送
{"id":"c-1","type":"prompt","message":"帮我看下这个仓库","images":[{"type":"image","data":"<base64>","mimeType":"image/png"}]}
{"id":"c-2","type":"prompt","message":"先别改测试","streamingBehavior":"steer"}     // 运行中必须带 streamingBehavior
{"id":"c-3","type":"follow_up","message":"做完再写个总结"}
{"id":"c-4","type":"clear_queue"}
{"id":"c-5","type":"abort"}
{"id":"c-6","type":"get_state"}
{"id":"c-7","type":"switch_session","sessionPath":"/root/.pi/agent/sessions/--root-workspace--/xxx.jsonl"}
{"id":"c-8","type":"set_model","provider":"deepseek","modelId":"<id>"}

// 接收
{"id":"c-1","type":"response","command":"prompt","success":true,"data":{"disposition":"started"}}   // started | queued | handled
{"type":"message_update","usage":{…},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"你好"}}
{"type":"tool_execution_start","toolCallId":"call_1","toolName":"bash","args":{"command":"ls -la"}}
{"type":"tool_execution_end","toolCallId":"call_1","toolName":"bash","result":{"content":[{"type":"text","text":"…"}],"details":{}},"isError":false,"durationMs":812}
{"type":"queue_update","steering":["先别改测试"],"followUp":[]}
{"type":"agent_settled","aborted":false}
```

### 4.5 流式输出与工具调用 → 界面状态（ChatReducer）

| 事件 | 界面动作 |
|---|---|
| `agent_start` | 进入“运行中”，输入栏切换为“插话/排队”模式，显示停止按钮 |
| `message_start`(assistant, `stopReason:"pending"`) | 新建助手气泡（占位） |
| `message_update.text_start/text_delta/text_end` | 按 `contentIndex` 追加文本；`text_end.content` 覆盖为权威内容 |
| `thinking_start/delta/end` | “思考中”折叠块，默认收起，展示字数/耗时 |
| `toolcall_start`(`id`,`toolName`) / `toolcall_delta` / `toolcall_end`(`toolCall`) | 生成工具卡片（参数流式显示），`toolcall_end` 后定稿参数 |
| `tool_execution_start/update/end`（`toolCallId` 关联） | 卡片状态：排队 → 运行中（`partialResult` 实时输出）→ 成功/失败（`isError`、`durationMs`）；完成后**自动折叠成一行摘要**（如 `bash · ls -la · 0.8s ✓`），点开看完整输出 |
| `message_end` | 用 `message_end.message` 整体替换局部重建的消息（官方要求）；展示 `usage`、`stopReason`（`error` 显示 `errorMessage`，`aborted` 显示“已中断”） |
| `turn_end` | 一轮结束（工具结果已归位） |
| `agent_end` | 低层 run 结束，**不代表空闲**（可能自动重试/压缩/处理队列） |
| `agent_settled` | 真正空闲：恢复普通输入栏；`aborted:true` 标“已中断” |
| `queue_update` | 输入栏上方显示排队气泡（完整队列，直接替换） |
| `compaction_start/end`、`auto_retry_start/end` | 系统提示条（“上下文压缩中…”“第 2/3 次重试…”） |
| `session_info_changed` / `thinking_level_changed` | 更新标题 / 思考档位 |
| `extension_ui_request` | `select/confirm/input/editor` → 原生对话框，回 `extension_ui_response`（用请求 `id`）；`notify` → Snackbar；`setStatus/setTitle/setWidget` 一期忽略或简单展示 |
| `extension_error` | 开发者日志 + 轻提示 |

工具卡片渲染器按 `toolName` 注册（`bash`、`read`、`edit`、`write`、`grep`、`find`、`ls` 等内置工具有专用样式，如 `edit` 显示 diff、`bash` 显示命令+输出），未知工具（扩展/MCP）用通用 JSON 卡片。实际可用工具以 pi 返回为准，不在客户端写死列表。

渲染节流：delta 先进 reducer，UI 以 ~16 ms 合帧；长消息分段 `LazyColumn` item，避免每个 token 重组整条消息。

### 4.6 中断 / 插话 / 排队

| 用户操作 | 发送 |
|---|---|
| 空闲时发送 | `prompt` |
| 运行中点“插话”（默认） | `prompt` + `streamingBehavior:"steer"`（当前轮工具执行完、下次调模型前送达） |
| 运行中点“排队” | `follow_up`（agent 完全结束后送达） |
| 停止 | 官方推荐的 Esc 行为：先 `clear_queue`（返回的 `steering`/`followUp` 文本放回输入框），再 `abort`；`abort` 的 response 在会话空闲后才返回 |
| 撤回排队 | `clear_queue`，把返回文本放回输入框 |
| 排队消息“立即发送” | `clear_queue` 取回整个队列 → 被点的那条用 `steer` 重发 → 其余按原类型重发（保持顺序）。二期视 pi 是否提供单条出队命令再简化 |

`set_steering_mode` / `set_follow_up_mode`（`all` / `one-at-a-time`）放在设置里，默认保持 pi 默认值。

### 4.7 会话管理

- **列表**：pi 没有列表命令 → `SessionIndex` 直接读宿主侧 `files/rootfs/root/.pi/agent/sessions/**/*.jsonl`：首行 header（`id`、`timestamp`、`cwd`、`parentSession`），倒序找最后一个 `session_info.name`，找第一条 user 消息做预览，文件 mtime 作为最近活跃；结果按文件 `mtime+size` 做缓存（JSON 文件，不引入数据库）。
- **新建**：`new_session`（检查 `data.cancelled`）。
- **切换/续聊**：`switch_session {sessionPath}` → `get_messages` 重建界面 → `get_state` 同步模型/思考档位/队列。
- **重命名**：`set_session_name`（仅当前会话；列表里改别的会话 = 先切换再改）。
- **删除**：删文件（官方文档说明的方式）；当前会话不可删。
- 二期：`fork`/`clone`/`get_tree` 做分支视图，`export_html` 导出分享。

### 4.8 错误与重连

| 场景 | 处理 |
|---|---|
| 命令失败 `success:false` | 对应 UI 动作报错（如 `set_model` 找不到模型），不影响连接 |
| 运行中模型/网络错误 | 走消息流：`message_end.stopReason="error"` + `errorMessage`，卡片内显示“重试”（重发上一条 prompt） |
| `prompt` 无响应 | `request()` 超时 30 s → `get_state` 探活；探活也超时 → 视为进程挂死，重启 |
| 进程意外退出 | `AgentHost` 捕获退出码 + stderr 尾部；自动用 `--session <最近的 sessionFile>` 重启（指数退避 1/2/4/8 s，最多 5 次），然后 `get_messages` 重建；连续失败进入 `Crashed`，提供“查看日志 / 运行环境诊断 / 重装 pi” |
| Activity 重建/切后台 | 状态全部在 Service 作用域的 `ChatRepository`（`StateFlow`）里，UI 重新订阅即可，无需重新拉取 |
| App 进程被杀 | 下次启动按“进程意外退出”路径恢复（会话文件已由 pi 持久化） |
| 关闭 | 关 stdin（官方的有序关闭方式）→ 等 5 s → `destroy()`；PRoot `--kill-on-exit` 清理子进程 |

---

## 5. 运行环境从 PiMet 迁移

### 5.1 类迁移表

| PiMet 类 | 处理 | 去向 |
|---|---|---|
| `proot/TarGzipExtractor`、`terminal/Downloader`、`terminal/NetworkRouteSelector`、`util/SafeFiles` | **原样搬**（仅改包名） | `:core:runtime` `com.xm486.pigo.core.runtime.legacy` |
| `proot/ProotManager` | **拆分改造**：`RootfsInstaller`（`installRootfs`/`buildDomesticRootfs`/断点续传/镜像竞速 `testAndSortCandidateUrls`）、`ProotLauncher`（`buildProotProcess`、`ensureRuntimeFiles` 复制 libtalloc/libandroid-shmem）、`ContainerConfigurator`（`ensureContainerDns`、`ensureNpmConfig`、`writeDomesticAptConfig`、`ensureContainerGroups`）；删除 Pi-Web 端口探活/离线优化/`PORT` 环境变量；去掉对 `PiMetConfig` 的静态依赖，改为构造参数 | `:core:runtime` |
| `proot/ProotSession`、`terminal/ShellSession` | 改造为 `exec()` 的实现 | `:core:runtime` |
| `terminal/EnvironmentDoctor` | 改：检查项改为 rootfs / node ≥ 22.19 / `pi --version` / RPC 握手 / DNS / 磁盘 | `:core:runtime` + `:core:agent` |
| `proot/PiWebManager` | **改写**为 `PiInstaller`：保留 npm 镜像与重试参数、`--ignore-scripts`；**去掉 `@agegr/pi-web`** 和守护 Web 服务逻辑 | `:core:agent` |
| `PiMetService` | 改写为 `PiGoService`（前台服务 + WakeLock + 通知“pi 正在工作 / 已完成”） | `:app` |
| `PiMetConfig` | 拆：镜像/运行时设置 → `:core:runtime`；模型与 Key → `:core:data` | — |
| jniLibs（`libproot.so`、`libprootloader*.so`、`libtalloc.so`、`libandroidshmem.so`、`libproroot*.so`） | 原样搬；**先核实 `libproroot*` 来源与许可证**，未核实前不打包（PiMet 的 `buildProotProcess` 只用到 proot/loader/talloc/shmem） | `:core:runtime/src/main/jniLibs` |
| `terminal/TerminalHub`、`FloatingTerminal`、`assets/extensions/pimet-terminal.ts`、`bridge/AppBridgeManager` | 二期搬（改名 `pigo-terminal.ts`，Token 文件改 `.pigo_bridge_token`，端口另选避免与 PiMet 冲突） | `:feature:terminal` |
| `automation/*`、`pet/*`、`assets/extensions/pimet-android-bridge.ts` | 三期搬 | `:feature:device` |
| `PiWebActivity`、`PiWebApi`、`ui/PiWebCompat`、`monitor/*`、`translation/*`、`DeployScriptGenerator`、`UserScriptManager`、`WebPinchZoom` 等 | **不搬** | — |

### 5.2 首启安装流程（国内优先）

每步写完成标记（`files/.pigo-install/<step>.done`），失败可从断点续装。

1. **预检**：ABI（arm64-v8a/x86_64）、可用空间 ≥ 2 GB、网络可用、通知权限（前台服务进度）。
2. **选路**：沿用 PiMet 竞速——GitHub 代理（gh-proxy / ghfast / ghproxy / 直连）真吞吐测速；**最快 < 1 MB/s 时自动走国内现装**。
3. **rootfs**：
   - 路线 A（快网）：下载预制精简 rootfs（Ubuntu 24.04 + Node 24 + git + bash），断点续传，`TarGzipExtractor` 解压。一期可读 PiMet 的 `runtime-latest` 发布物；后续在 pi-go 自己的 Release 发布同款底包（避免跨仓库耦合）。
   - 路线 B（国内现装）：阿里云/清华/中科大/华为云 Ubuntu Base 24.04.x + npmmirror/华为云/清华/阿里云 Node v24（PiMet 已实现）。
4. **容器配置**：DNS、国内 apt 源、`.npmrc`（`registry=https://registry.npmmirror.com/`）、groups、`/root/workspace`。
5. **安装 pi**：`npm install -g --ignore-scripts --no-audit --no-fund @earendil-works/pi-coding-agent@<锁定版本>`，`npm_config_fetch_retries=5`、`fetch_timeout=300000`；安装到 `/opt/pi-go/pi/<版本>`（`--prefix`），`/usr/local/bin/pi` 软链到 `current`，便于回滚。
6. **验证**：`node -v` ≥ 22.19（pi `engines` 要求），`pi --version` 等于锁定版本，**RPC 握手**：启动 `pi --mode rpc --no-session`，发 `get_state`，20 s 内拿到 `success:true`。
7. **进入模型配置**（§6 MVP-2）。

### 5.3 升级策略

- **App 内置“已验证 pi 版本”**（`pi-versions.json`：`recommended`、`minSupported`、`blocked[]`），随 App 发版更新；客户端协议解析对 `minSupported..recommended` 有契约测试。
- 设置页“检查 pi 更新”：从 npmmirror 查 `dist-tags.latest`；高于 `recommended` 时标“实验版”。
- 升级 = 装到新 prefix → 冒烟（`--version` + RPC 握手 + 一次 `get_available_models`）→ 切软链 → 重启 AgentHost；任一步失败自动回滚到旧软链。保留最近 2 个版本。
- rootfs 升级：只在大版本（Node 大版本、Ubuntu 大版本）时提示重建；用户工作区 `/root/workspace` 与 `~/.pi/agent`（会话/配置）在重建前打包备份并还原（复用 PiMet `BackupManager` 思路，二期）。

---

## 6. 第一期 MVP 范围（可验收清单）

目标设备：Android 10~15 的 arm64 真机各至少 1 台（含一台国产 ROM：小米/华为/OPPO 之一）+ x86_64 模拟器。

**MVP-1 首启安装**
- [ ] 全新安装后进入向导，显示分步进度（预检/选路/下载/解压/配置/装 pi/验证），可后台继续（前台服务通知显示进度）。
- [ ] 关闭代理的国内网络下能完整装完（走国内现装或代理镜像），记录总耗时。
- [ ] 中途杀 App / 断网后再打开，从断点继续，不重复下载已完成部分。
- [ ] 完成后诊断页显示：Node 版本、pi 版本、RPC 握手成功、剩余空间。

**MVP-2 模型 / API Key**
- [ ] 预设 Provider：DeepSeek、Moonshot、MiniMax（中国）、智谱 ZAI（中国）、Qwen、OpenAI、Anthropic、Gemini、OpenRouter（环境变量名以 pi `providers.md` 为准，如 `DEEPSEEK_API_KEY`、`MOONSHOT_API_KEY`、`MINIMAX_CN_API_KEY`、`ZAI_CODING_CN_API_KEY`）。
- [ ] 自定义 OpenAI 兼容端点：填 baseUrl / 模型 id / Key，写入 `~/.pi/agent/models.json`，`apiKey` 写成 `$PIGO_KEY_<name>` 环境变量引用（**明文 Key 不落容器磁盘**）。
- [ ] Key 用 Android Keystore（AES-GCM）加密存在 App 私有目录；界面只显示后 4 位；日志/崩溃报告自动脱敏。
- [ ] 保存后自动重启 pi，`get_available_models` 列出可用模型，`set_model` 选中并持久化默认。
- [ ] 思考档位选择（`get_available_thinking_levels` / `set_thinking_level`）。

**MVP-3 原生聊天流式对话**
- [ ] 发送文字，逐字流式显示；Markdown（标题/列表/表格/行内代码/代码块）正确渲染，代码块可复制。
- [ ] 思考内容折叠块，默认收起。
- [ ] 显示每条助手消息的 token 用量；`stopReason=error` 时显示错误与“重试”。
- [ ] 切后台 5 分钟再回来，流仍在继续或已完成且完整。
- [ ] 支持从相册附加图片（`images`，base64），模型不支持时给出提示。

**MVP-4 工具调用卡片**
- [ ] `bash`/`read`/`edit`/`write` 等调用显示为卡片：工具名、关键参数、运行状态、耗时。
- [ ] 运行中实时显示 `partialResult`；完成后自动折叠成一行，点击展开；失败卡片红色且默认展开。
- [ ] `edit` 卡片显示 diff；超长输出截断并可“查看全部”。

**MVP-5 中断 / 插话 / 排队**
- [ ] 运行中输入框可发送“插话”（steer）与“排队”（follow_up），排队区实时反映 `queue_update`。
- [ ] 排队消息可撤回（回到输入框）、可“立即发送”。
- [ ] 停止按钮：1 秒内界面进入“停止中”，`agent_settled` 后显示“已中断”，队列文本回到输入框。
- [ ] 扩展弹出的 `confirm`/`select`/`input` 能以原生对话框交互。

**MVP-6 会话列表**
- [ ] 列表显示名称（无名称时用首条消息预览）、工作目录、最近时间，按时间倒序。
- [ ] 新建、切换（加载历史消息，含工具卡片）、重命名（当前会话）、删除（二次确认）。
- [ ] 进程崩溃（手动 kill node）后 10 秒内自动恢复到原会话，界面提示“已重新连接”。

**MVP-7 体验底线**
- [ ] 深色/浅色跟随系统；手机竖屏 + 平板/横屏双栏（左会话列表，右聊天）。
- [ ] 冷启动到可输入 ≤ 3 s（容器已就绪时，pi 进程启动在后台进行，未就绪时输入框显示“引擎启动中”）。

不在 MVP：浮动终端、桌宠、无障碍自动化、会话分叉树、多会话并行、远程连接电脑上的 pi、插件市场。

---

## 7. 并行工作流

规则：每条工作流只改自己列出的目录；跨模块只通过 scaffold 定义的接口；`:app/di/*Bindings.kt` 每个文件归属一个工作流；共享文件（`libs.versions.toml`、`settings.gradle.kts`）只有 scaffold 能改，其他工作流需要新依赖时在 PR 描述中提出，由 scaffold 维护者合入。

### W1 `mvp/scaffold`（先合 main，其他分支基于它）

- **文件范围**：根目录 Gradle 文件、`gradle/`、`build-logic/`、`app/`（除 `di/*Bindings.kt` 的具体实现）、所有模块的 `build.gradle.kts` 与空包、各接口文件（§3.2）、Fake 实现、`README.md`、`LICENSE` 与 `THIRD_PARTY_NOTICES.md`（按 §9 定稿后）、CI 配置（是否加、怎么加由仓库主人决定）。
- **内容**：多模块骨架；Compose 主题、导航（onboarding / chat / sessions / settings 四个路由）；`PiGoService` 空壳；`AppGraph` + 四个 Bindings 文件绑定 Fake；`:core:rpc` 的数据类型（命令/事件/response 的 Kotlin 定义，与 `rpc-types.d.ts` 对齐）；`:feature:terminal`、`:feature:device` 占位模块。
- **依赖**：无。
- **验收**：`./gradlew assembleDebug lint test` 通过；装到真机能看到四个页面（Fake 数据可跑通“假对话流式”）；依赖图满足 §3.1 方向（用 `./gradlew :app:dependencies` 人工核对）。

### W2 `mvp/runtime`

- **文件范围**：`core/runtime/**`、`feature/onboarding/**`、`app/.../di/RuntimeBindings.kt`。
- **内容**：§5.1 中 runtime 相关迁移；`RuntimeManager` 实现；安装向导 UI；诊断页数据（UI 放 onboarding，设置页通过路由打开）。
- **依赖**：W1。
- **验收**：MVP-1 全部条目；`RuntimeManager.processBuilder()` 能跑 `node -v`。

### W3 `mvp/rpc`

- **文件范围**：`core/rpc/**`、`core/agent/**`、`app/.../di/AgentBindings.kt`、`app/.../PiGoService.kt`（W1 的空壳由 W3 接管实现）。
- **内容**：JSONL 分帧、`PiRpcClient`、`ChatReducer`、`PiInstaller`（含升级/回滚）、`AgentHost`（启动、守护、崩溃重启）、扩展部署目录、`ChatRepository` 实现。
- **依赖**：W1；联调依赖 W2 的 `RuntimeManager`（开发期用 JVM 测试 + 宿主机真实 `pi --mode rpc` 录制的 JSONL 夹具）。
- **验收**：`:core:rpc` 单测覆盖：U+2028 不误切、CRLF、乱序 response、未知事件、背压（10 万条 delta 不丢）、夹具回放结果与 `message_end` 一致；真机上：MVP-5 的协议部分、MVP-6 的崩溃恢复。

### W4 `mvp/chat`

- **文件范围**：`feature/chat/**`、`app/.../di/ChatBindings.kt`。
- **内容**：消息列表、流式渲染与节流、Markdown、思考折叠、工具卡片渲染器注册表、输入栏（插话/排队/停止/附图）、排队区、扩展 UI 对话框、平板双栏中的聊天面板。
- **依赖**：W1（用 Fake `ChatRepository` + W3 提供的夹具回放开发）；联调依赖 W3。
- **验收**：MVP-3、MVP-4、MVP-5 的界面部分、MVP-7。

### W5 `mvp/sessions-settings`

- **文件范围**：`core/data/**`、`feature/sessions/**`、`feature/settings/**`、`app/.../di/DataBindings.kt`。
- **内容**：`SessionIndex`（读 jsonl）、`SecretStore`（Keystore）、`ModelConfig`（预设 Provider、`models.json` 写入、env 生成）、会话列表页、设置页（模型/Key/思考档位/队列模式/运行时诊断入口/pi 版本与升级入口）。
- **依赖**：W1；联调依赖 W3（`set_model`、重启 AgentHost）。
- **验收**：MVP-2、MVP-6 的列表与管理部分；Key 不出现在任何日志与容器文件中（`grep -r` 容器 rootfs 验证）。

合入顺序：W1 → (W2 ∥ W3 ∥ W4 ∥ W5 并行) → 联调分支 `mvp/integration` 只改 Bindings 切换为真实实现 → main。

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **pi 版本快速演进**（包名已从 `@mariozechner` 迁到 `@earendil-works`，1.3→1.5 两周三版） | 字段变化导致解析失败 | 锁定版本 + 升级冒烟；协议解析保留未知字段；按版本录制夹具做契约测试；协议类型以 `rpc-types.d.ts` 为单一来源，升级时 diff |
| **Android 12+ 幻影进程限制**（后台子进程过多/占 CPU 被系统杀） | pi/node 在后台被杀 | 前台服务 + WakeLock；子进程数量少（单 pi 进程）；崩溃自动以 `--session` 恢复；诊断页给出关闭限制的说明（开发者选项 / adb `settings put global settings_enable_monitor_phantom_procs false`） |
| 国产 ROM 杀后台 | 长任务中断 | 引导加入电池优化白名单/自启动（沿用 PiMet 已有权限申请）；任务完成通知 |
| `targetSdk 28` | 无法上架 Google Play，部分新系统行为（如 Android 14 前台服务类型）需额外适配 | 侧载分发；声明 `foregroundServiceType="dataSync"`/`specialUse` 并按版本处理；中期评估“二进制放 nativeLibDir + proot loader”路线下升 targetSdk 的可行性 |
| PRoot 性能（npm 安装慢、文件 I/O 慢） | 首装 10~30 分钟、工具调用慢 | `--ignore-scripts`、预制 rootfs、国内镜像；沿用 PiMet 已验证的 `-u LD_LIBRARY_PATH` 优化；首装期间允许后台 |
| 内存（Node + pi 常驻约 150~300 MB） | 低端机 OOM | 单进程；空闲 N 分钟后可选择停 pi（下次发送前冷启动，会话可恢复） |
| stdout 背压 / 大输出 | UI 卡顿或 pi 阻塞 | 专用读线程 + 大缓冲；UI 合帧；工具输出在卡片内截断显示 |
| API Key 泄露 | 安全事故 | Keystore 加密、环境变量注入、`models.json` 用 `$VAR` 引用、日志脱敏、导出日志前二次脱敏 |
| 网络：国外模型直连不可用 | 体验差 | 预设国内 Provider；支持自定义兼容端点 / `baseUrl` 代理 |
| 许可证：proot（GPL-2.0）二进制随 APK 分发；`libproroot*` 来源不明；PiMet 内置桌宠素材（doro、elaina）涉及第三方角色版权 | 合规风险 | proot 作为独立可执行文件分发并在 NOTICE 中给出源码地址；`libproroot*` 核实前不打包；桌宠素材不随 pi-go 打包，三期改为用户自行导入 |
| dsh-Go AGPL 污染 | 被迫 AGPL | 仅参考 README/截图层面的交互设计；评审检查清单加“无 dsh-Go 代码/名称/素材” |
| RPC 单会话限制 | 不能多会话并行 | 一期接受；二期进程池或 SDK sidecar |

---

## 9. 许可证与第三方声明（scaffold 落地用的草案）

本提案阶段只新增本文件，不添加代码，因此暂不需要 LICENSE/THIRD_PARTY_NOTICES；**由 W1 `mvp/scaffold` 在搬入第一行 PiMet 代码前创建**。

- **pi-go 许可证**：未拷贝 dsh-Go 代码，不受 AGPL 约束。建议仓库主人在 GPL-3.0-or-later（与其 aifiles 等项目一致、对 proot 生态友好）与 Apache-2.0（更宽松）之间选择；PiMet 目前无 LICENSE，复用前由同一作者在 pi-go 中以所选许可证重新授权即可。
- **THIRD_PARTY_NOTICES.md 草案**（scaffold 时逐项核实版本与原文）：

| 组件 | 来源 | 许可证 | 用途 / 备注 |
|---|---|---|---|
| PiMet 代码（rootfs 安装、PRoot 启动、下载、选路、诊断，二期终端/桥接，三期无障碍/桌宠） | github.com/zr987459/pimet（同一作者） | 作者自有，随 pi-go 许可证重新授权 | 注明来源提交 a80222c |
| PRoot（`libproot.so`、`libprootloader*.so`） | termux/proot 派生构建 | GPL-2.0 | 独立可执行文件；NOTICE 给出对应源码地址 |
| talloc（`libtalloc.so`） | Samba talloc | LGPL-3.0 | 动态链接 |
| libandroid-shmem（`libandroidshmem.so`） | termux/libandroid-shmem | 需核实（scaffold 时以上游 LICENSE 为准） | |
| `libproroot*.so` 系列 | **来源待查** | **待核实** | 核实前不打包 |
| Termux terminal-view / terminal-emulator v0.118.0（二期） | termux/termux-app | Apache-2.0 | 浮动终端 |
| Shizuku api/provider 13.1.5（三期） | RikkaApps/Shizuku-API | Apache-2.0 | 高权限操作 |
| OkHttp 4.12 | square/okhttp | Apache-2.0 | 下载 |
| Ubuntu Base 24.04 rootfs | Canonical | 各软件包各自许可证（下载时获取，不随 APK 分发） | 运行时下载 |
| Node.js 24 | nodejs.org | MIT + 内含依赖许可证 | 运行时下载 |
| pi coding agent | `@earendil-works/pi-coding-agent` | MIT | 运行时 npm 安装，不随 APK 分发 |
| PiMet 桌宠素材 doro / elaina | 第三方角色 | 版权不明 | **不搬** |

---

## 10. 后续路线

- **二期（可编程手机工作站）**：浮动终端（Termux 渲染，与 pi 扩展 `pigo-terminal.ts` 共享会话，经 127.0.0.1 桥 + Token）；工作区文件浏览与代码查看；会话分叉树（`get_tree`/`fork`/`clone`）；多会话并行（进程池）；`export_html` 分享；手动 `compact` 与上下文用量条；rootfs 备份/还原；pi-go 自有 runtime Release。
- **三期（手机智能体）**：无障碍自动化（`pigo-android-bridge.ts` 把手机操作作为 pi 工具，危险操作走 `extension_ui_request` 确认）；可选桌宠（用户导入素材，状态随 `agent_start`/`agent_settled`/扩展确认联动）；通知栏实时进度。
- **四期（远程）**：连接电脑上的 pi（SSH 隧道下跑 `pi --mode rpc`，复用同一 `PiRpcClient`，只换传输层），或评估 SDK sidecar + WebSocket 支持多端。
