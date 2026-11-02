# 提案 B：pi-go —— Kotlin + Compose 原生界面，直连 `pi --mode rpc`

> 状态：评审提案（独立撰写，未参考其他提案）
> 作者：Hark（zr987459 的协作账号）
> 调研基线：
> - PiMet `a80222c2dda4a4c8a936258b96597bc7ea7d4102`（v1.9.0-beta.30，versionCode 122）
> - dsh-Go `main`（2026-11-02 拉取，AGPL-3.0，只读其架构文档与目录结构，未复制代码）
> - pi 引擎 `@earendil-works/pi-coding-agent@1.5.0`（npm 包内 `docs/rpc*.md`、`docs/json.md`、`docs/message-types.md`、`docs/session-format.md`、`dist/modes/rpc/rpc-types.d.ts`）

---

## 0. 一页结论

| 决策 | 选择 | 一句话理由 |
|---|---|---|
| 技术栈 | **Kotlin + Jetpack Compose**（Java 模块原样共存） | PiMet 是 100% Java + View 体系；Kotlin 与 Java 同一 JVM、零桥接，可直接搬 PRoot/终端/桌宠/无障碍代码。Flutter 方案每个复用类都要包一层 MethodChannel，复用率反而最低 |
| 引擎通信 | **pi RPC 模式**（`pi --mode rpc`，stdin/stdout JSONL，经 PRoot 子进程管道） | 官方稳定协议，原生支持插话 `steer` / 排队 `follow_up` / `abort` / 会话切换 / 扩展 UI；不再需要 Next.js 的 pi-web（省 300MB+ 内存和整个 HTTP 层） |
| 引擎包名 | **`@earendil-works/pi-coding-agent`**（钉 1.5.0） | `@mariozechner/pi-coding-agent` 已在 npm 标记 deprecated（最后 0.73.1），提示改用 `@earendil-works/*`；PiMet 也已在用新包名 |
| 运行环境 | 搬 PiMet 的 PRoot 启动 + 国内镜像现装流程，**重写**其中来自 DSH-Folk（GPL-3.0）的部分 | 见 §5.1 许可证发现：PiMet 的 `TarGzipExtractor.java` 与 DSH-Folk 同名文件 447/453 行一致 |
| 许可证 | 不拷贝 dsh-Go 代码 → **pi-go 无需 AGPL**；不用其名称/标识/Deepy/鲸娘 | 只借鉴“原生工具卡片、插话/排队、过程折叠”这类交互设计 |
| MVP 并行 | 5 条工作流：`mvp/scaffold` → `mvp/runtime`、`mvp/engine`、`mvp/chat-ui`、`mvp/sessions-settings` | 模块边界 = 文件边界，scaffold 先合 main 并放好全部接口 |

---

## 1. 调研结论

### 1.1 PiMet（a80222c）是什么

- **语言/框架**：纯 Java（`app/src/main/java/com/xm486/pimet/**` 共约 51.6k 行，无一个 `.kt`），传统 View + XML（`activity_main.xml` 单文件 200KB），**没有 Compose、没有 Kotlin 插件**。
- **构建**：单模块 `:app`，AGP 8.0.0、Gradle 8.1.1、JDK 17（源码级 Java 11），`compileSdk 34 / minSdk 26 / targetSdk 28`，`useLegacyPackaging = true`（让 `lib*.so` 解压到可执行的 `nativeLibraryDir`）。仓库配了阿里云 Maven 镜像。
- **依赖**：okhttp 4.12、material 1.9、appcompat、constraintlayout、documentfile、androidx.webkit、`termux-app:terminal-view/terminal-emulator v0.118.0`、`shizuku api/provider 13.1.5`。
- **包结构**：

| 包 | 主要类 | 作用 |
|---|---|---|
| `proot/` | `ProotManager`(1280 行)、`ProotSession`、`PiWebManager`、`TarGzipExtractor` | rootfs 安装（GitHub 镜像竞速 + 国内源现装）、`buildProotProcess()`、DNS/npmrc/组文件修复、pi + pi-web 安装与守护 |
| `terminal/` | `TerminalHub`、`FloatingTerminal`、`ShellSession`、`Downloader`、`NetworkRouteSelector`、`EnvironmentDoctor`、`AnsiParser` | Termux 引擎真 PTY 会话中心 + 悬浮终端窗；与 pi 的 terminal 工具共享会话 |
| `bridge/` | `AppBridgeManager`(1056 行) | 本机 `127.0.0.1:30143 /api/app-control`，`X-PiMet-Token` 鉴权；tap/swipe/input/screenshot/launch 等手机操作 |
| `automation/` | `PiMetAccessibilityService` | 无障碍读屏与操作 |
| `pet/` | `PetOverlayService`、`PetChatBridge`、`SpritePetView`、`PetRegistry`… | AI 桌宠悬浮窗 |
| `ui/` | `BrowserAgent`、`FloatingApps`(6652 行)、`FileBrowserDialog`… | 浏览器、悬浮应用、文件管理等 |
| 根 | `MainActivity`(7033 行)、`PiWebActivity`、`PiWebApi`、`PiMetConfig`、`PiMetService`、`BackupManager` | 主界面、pi-web WebView、配置写入容器 `~/.pi/agent/{auth,models,settings}.json`、前台保活服务 |
| `assets/extensions/` | `pimet-terminal.ts`、`pimet-android-bridge.ts`、`pimet-browser.ts` | 注入到 `~/.pi/agent/extensions` 的 pi 扩展，经 30143 桥回调 App |

- **关键事实**：
  - pi 安装命令：`npm install -g --ignore-scripts @earendil-works/pi-coding-agent @agegr/pi-web`（npmmirror）。
  - 环境变量 `PI_CODING_AGENT_DIR=/root/.pi/agent`，默认工作区 `/root/pi-cwd`。
  - `buildProotProcess()`：`libproot.so --link2symlink -L --kill-on-exit -0 -r <rootfs> -b /dev -b /dev/urandom:/dev/random -b /proc -b /sys -b /proc/self/fd:/dev/fd -b /storage/emulated/0:/sdcard -b <extFiles>:/root/shared -w <cwd> /usr/bin/env -u LD_LIBRARY_PATH …`，宿主侧 `PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH=files/lib:nativeLibDir`。
  - 国内现装：Ubuntu Base 24.04（阿里云/清华/中科大/华为镜像竞速）→ Node 24（npmmirror 等 5 源）→ apt 换阿里云后台装 git/curl/ca-certificates → npmmirror 装 pi。
  - `PiWebMonitor` 已经按 `@earendil-works/pi-ai` 的 `assistantMessageEvent.type` 词表解析事件——说明 PiMet 团队对 pi 事件模型已有经验。

### 1.2 dsh-Go（AGPL-3.0，只借鉴设计）

- Flutter 3.47 + Riverpod 3 界面；Kotlin 原生层以“app 内 Flutter 插件”形式存在（`runtime/RuntimeInstaller.kt`、`DshProcess.kt`、`DshService.kt`…）；纯 Dart 引擎客户端 `packages/dsh_client`（HTTP/SSE + mux）。
- 运行时：在 x86_64 主机上**预构建** Ubuntu Base + Node + 引擎的 rootfs（`runtime/build-runtime.sh`，钉死每个输入的哈希），打进 APK assets；Termux 官方 apt 仓库取 proot/libtalloc/libandroid-shmem，并在 THIRD_PARTY_NOTICES 里给出 GPL/LGPL 对应源码。
- `targetSdk = 28`（与 Termux 相同，避开 Android 10+ 对私有目录 exec 的限制）。
- 值得借鉴的**交互设计**（不搬代码）：原生工具调用卡片（tool_row）、回复中插话/排队、思考与工具过程折叠成一条“过程”、WebView 兜底模式、横屏抽屉常驻、日志带 `seq` 的“先订阅再取快照”无缝衔接、启动时间线诊断。
- 品牌：ASSETS.md 明确 DSH Go 标识、Deepy 小鲸鱼、鲸娘不在 AGPL 授权内 → pi-go **一律不碰**。

### 1.3 pi 引擎（@earendil-works/pi-coding-agent 1.5.0）

- `engines.node >= 22.19.0`；bin `pi → dist/bundle/cli.js`；许可证 MIT。
- 四种对外接口：交互 TUI、`--print`、`--mode json`（一次性事件流后退出）、`--mode rpc`（长连接 JSONL）、以及 Node 进程内 SDK。
- RPC 命令全集（取自 `rpc-types.d.ts`）：`prompt steer follow_up abort clear_queue new_session get_state get_messages set_model cycle_model get_available_models set_thinking_level cycle_thinking_level get_available_thinking_levels set_steering_mode set_follow_up_mode compact set_auto_compaction set_auto_retry abort_retry bash abort_bash get_session_stats export_html switch_session fork clone get_fork_messages get_entries get_tree get_last_assistant_text set_session_name get_commands extension_ui_response`。
- **RPC 没有“列出会话”命令**：会话是 `<agentDir>/sessions/--<cwd 转义>--/<timestamp>_<id>.jsonl`，由客户端自己扫目录（§4.6）。
- pi **默认不做工具审批**（security.md：“does not ask for approval before every tool call”），审批只能由扩展通过 `extension_ui_request(confirm/select)` 实现。

---

## 2. 技术栈选择

### 2.1 Kotlin + Compose vs Flutter

| 维度 | Kotlin + Compose（选） | Flutter |
|---|---|---|
| 复用 PiMet | Java 类直接放进 Kotlin 模块即可编译，零改动可用：`TerminalHub`/`FloatingTerminal`（Termux View 本身就是 Android View）、`AppBridgeManager`、`PiMetAccessibilityService`、`PetOverlayService`、`Downloader`、`NetworkRouteSelector` | 所有原生能力要经 MethodChannel/EventChannel 包装；终端 View 要用 PlatformView 嵌入（性能与输入法问题多）；悬浮窗本来就是原生，Flutter 只多一层 |
| 复用 dsh-Go UI | 不能（AGPL，且我们不打算变 AGPL） | 同样不能（AGPL）。唯一可合法复用的是 PiPilot（MIT）组件，但它面向 Dart，且把我们拖进 Flutter 工具链 |
| 聊天 UI 能力 | LazyColumn + 增量 recomposition 足够；Markdown 用 Apache-2.0 库 | Flutter 渲染一致性更好，玻璃质感更容易 |
| 构建/CI | 单一 Gradle；现有 PiMet 的 `build.yml` 思路可直接沿用 | 需要 Flutter SDK（国内需 flutter-io.cn 镜像），CI 多一套缓存 |
| 体积 | Compose ≈ +4–6MB | Flutter 引擎 ≈ +8–10MB/ABI |
| 跨平台 | 仅 Android（本项目运行时本来就绑定 Android PRoot） | iOS 不能跑 PRoot，跨平台收益接近 0 |

**取舍**：放弃 Flutter 的跨 ROM 渲染一致性和“现成聊天组件”；换来对 PiMet 现有 Java 资产的直接复用、单一工具链、更小 APK。

### 2.2 版本基线

- Gradle 8.11.1，AGP 8.7.3，Kotlin 2.1.x（`org.jetbrains.kotlin.plugin.compose` 编译器插件），JDK 17，Java/Kotlin target 17。
- `compileSdk 35`、`minSdk 26`、**`targetSdk 28`**（与 PiMet/dsh-Go/Termux 一致；不上 Play，升级 targetSdk 列入风险 §9）。
- ABI：`arm64-v8a`（真机）、`x86_64`（模拟器）。`useLegacyPackaging = true`。
- 依赖（全部宽松许可）：Compose BOM、`androidx.activity:activity-compose`、`navigation-compose`、`lifecycle-runtime-compose`、`datastore-preferences`、`kotlinx-coroutines-android`、`kotlinx-serialization-json`、Markdown：`com.mikepenz:multiplatform-markdown-renderer-m3`（Apache-2.0）、终端沿用 `termux terminal-view/terminal-emulator v0.118.0`（Apache-2.0，二期用）。**不引入 Hilt**：手写 `AppGraph` 单例，减少 kapt/ksp 构建负担。
- Maven 仓库沿用 PiMet 的阿里云镜像 + google/mavenCentral + jitpack（termux 库在 jitpack）。

---

## 3. 模块划分与接口

### 3.1 Gradle 模块与包名

根包：`io.github.zr987459.pigo`（applicationId 同名）。

```
pi-go/
  settings.gradle.kts            include 下列模块
  gradle/libs.versions.toml      版本目录（唯一改版本的地方）
  app/                           :app                  io.github.zr987459.pigo
  core/common/                   :core:common          …pigo.core.common      （JVM 纯 Kotlin）
  core/protocol/                 :core:protocol        …pigo.core.protocol    （JVM 纯 Kotlin，RPC 记录 + 解帧 + Reducer）
  core/runtime/                  :core:runtime         …pigo.core.runtime     （Android：PRoot、安装器、镜像、前台服务）
  core/engine/                   :core:engine          …pigo.core.engine      （Android：PiProcess、RpcClient 实现、重连）
  core/store/                    :core:store           …pigo.core.store       （Android：会话索引、DataStore、auth/models.json 写入）
  feature/onboarding/            :feature:onboarding   …pigo.feature.onboarding
  feature/chat/                  :feature:chat         …pigo.feature.chat
  feature/sessions/              :feature:sessions     …pigo.feature.sessions
  feature/settings/              :feature:settings     …pigo.feature.settings
  docs/                          proposals/、protocol/（录制的 JSONL 夹具说明）
  .github/workflows/             （由 scaffold 负责，本提案不写）
```

二期预留（scaffold 只在 `settings.gradle.kts` 中注释列出，不建目录）：`:feature:terminal`、`:feature:pet`、`:core:bridge`（30143 App 桥 + 无障碍）、`:feature:web`（WebView 兜底）。

依赖方向（只允许向下）：

```
app → feature:* → core:engine, core:store → core:runtime → core:common
                 ↘ core:protocol → core:common
feature:* 之间互不依赖；导航由 :app 组装
```

### 3.2 核心接口（scaffold 落地为空实现/Fake）

```kotlin
// :core:common
sealed interface Outcome<out T> { data class Ok<T>(val v: T): Outcome<T>; data class Err(val code: ErrCode, val msg: String, val cause: Throwable? = null): Outcome<Nothing> }
enum class ErrCode { NETWORK, DISK_FULL, INSTALL_FAILED, ENGINE_START, ENGINE_EXIT, RPC_REJECTED, RPC_TIMEOUT, PROVIDER, CANCELLED, UNKNOWN }
interface AppLog { fun i(tag: String, msg: String); fun w(tag: String, msg: String, t: Throwable? = null); fun tail(n: Int): List<LogLine> }

// :core:runtime
data class RuntimeStatus(val phase: Phase, val rootfsOk: Boolean, val nodeVersion: String?, val piVersion: String?, val error: String?)
enum class Phase { NOT_INSTALLED, INSTALLING, READY, BROKEN }
data class InstallProgress(val step: Step, val percent: Int, val message: String)   // Step = PROBE, ROOTFS, NODE, APT, PI, VERIFY
data class GuestSpec(val argv: List<String>, val cwd: String = "/root/pi-cwd", val env: Map<String, String> = emptyMap())
interface RuntimeManager {
  val status: StateFlow<RuntimeStatus>
  fun install(opts: InstallOptions): Flow<InstallProgress>          // 冷流，取消即中止
  suspend fun upgradePi(version: String): Outcome<String>             // 返回新版本号；失败自动回滚
  fun startGuest(spec: GuestSpec): Process                             // 封装 buildProotProcess
  suspend fun exec(cmd: String, timeoutMs: Long = 60_000): ExecResult // 一次性命令
  fun hostFile(guestPath: String): File                                // /root/x → files/rootfs/root/x
}

// :core:protocol（纯 JVM，可单测）
class JsonlFramer { fun feed(bytes: ByteArray, len: Int): List<String> }   // 只按 0x0A 切分，去掉尾部 \r
sealed interface RpcCommand { val id: String? }                          // 见 §4.3
sealed interface EngineEvent                                              // 见 §4.4，未知 type → Unknown(raw)
data class RpcResponse(val id: String?, val command: String, val success: Boolean, val data: JsonElement?, val error: String?)
object ChatReducer { fun reduce(s: ChatState, e: EngineEvent): ChatState; fun fromMessages(msgs: List<JsonObject>): ChatState }

// :core:engine
sealed interface EngineStatus { object Stopped; object Starting; data class Idle(val state: PiState); data class Running(val state: PiState); data class Crashed(val code: Int?, val stderrTail: List<String>, val retryInMs: Long?) }
interface EngineClient {
  val status: StateFlow<EngineStatus>
  val chat: StateFlow<ChatState>               // 已归约好的界面状态，Activity 重建不丢
  val uiRequests: SharedFlow<ExtUiRequest>     // 扩展对话框
  suspend fun prompt(text: String, images: List<ImageIn> = emptyList()): Outcome<Disposition>
  suspend fun steer(text: String): Outcome<Disposition>
  suspend fun followUp(text: String): Outcome<Disposition>
  suspend fun stop(): Outcome<QueuedTexts>      // clear_queue + abort，返回被撤回的排队文本
  suspend fun newSession(cwd: String? = null): Outcome<Unit>
  suspend fun openSession(guestPath: String): Outcome<Unit>
  suspend fun rename(name: String): Outcome<Unit>
  suspend fun models(): Outcome<List<ModelInfo>>
  suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo>
  suspend fun setThinking(level: String): Outcome<Unit>
  suspend fun answerUi(id: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false)
  suspend fun restart(): Outcome<Unit>
}

// :core:store
data class SessionSummary(val guestPath: String, val id: String, val cwd: String, val name: String?, val preview: String, val createdAt: Instant, val updatedAt: Instant, val sizeBytes: Long)
interface SessionRepository { fun observe(): Flow<List<SessionSummary>>; suspend fun refresh(); suspend fun delete(guestPath: String): Outcome<Unit> }
data class ProviderEntry(val provider: String, val apiKeySet: Boolean, val baseUrl: String?, val api: String?, val models: List<String>)
interface ProviderConfigStore {
  suspend fun list(): List<ProviderEntry>
  suspend fun setApiKey(provider: String, key: String)                      // 合并写 auth.json：{provider:{type:"api_key",key}}
  suspend fun upsertCustom(id: String, baseUrl: String, api: String, apiKey: String, modelIds: List<String>) // 合并写 models.json providers.<id>
  suspend fun setDefault(provider: String, modelId: String, thinking: String?) // 合并写 settings.json defaultProvider/defaultModel/defaultThinkingLevel
}
```

`AppGraph`（`:app`）持有单例：`AppLog`、`RuntimeManager`、`EngineClient`、`SessionRepository`、`ProviderConfigStore`。`EngineClient` 的实例挂在前台服务 `EngineService` 的生命周期上，ViewModel 只订阅 StateFlow。

---

## 4. 原生界面 ↔ 容器内 pi 的通信协议

### 4.1 方案比较

| 方案 | 交互能力 | 资源 | 稳定性/文档 | 结论 |
|---|---|---|---|---|
| **RPC 模式** `pi --mode rpc` | 长连接；`steer`/`follow_up`/`abort`/`clear_queue`、会话切换、模型/思考级别、扩展 UI 子协议、`bash` 直跑 | 1 个 node 进程（~120–200MB） | 官方文档 `rpc.md`/`rpc-commands.md`/`json.md` + 导出 TS 类型 | **选用** |
| JSON 模式 `--mode json` | 一次性：跑完 prompt 即退出；不能插话/排队，中断只能 kill | 每轮冷启动（PRoot 下 node+pi 启动 2–6s） | 事件格式与 RPC 相同 | 不选；可作为“单次任务”后备 |
| pi-web HTTP（@agegr/pi-web 0.14.2） | 功能全，但 API 是 Next.js 内部路由，非对外契约 | Next.js + React SSR，常驻 300MB+；还要 node-pty | 第三方、版本迭代快；PiMet 已为它的依赖冲突打过补丁（`fixPiCodemodeCompat`） | 不选（本项目目标就是去掉它） |
| SDK + 自写 Node 桥（HTTP/WebSocket） | 最灵活：单进程多会话 | 1 个 node 进程 | SDK 面更大、变动更频繁，桥要我们维护 | 不选进 MVP；二期“多会话并发”时再评估 |

选 RPC 的根本理由：**它就是 pi 官方为“其他语言写的自定义界面”设计的接口**（rpc.md 原文 “Use it for language-independent integrations … custom user interfaces”），我们需要的所有交互语义它都已有，且无需额外端口/鉴权（管道天然只对 App 可见，比 PiMet 的 127.0.0.1 HTTP + token 更安全）。

### 4.2 进程模型与启动

```
EngineService(前台服务, specialUse)
  └─ PiProcess
       ProcessBuilder = RuntimeManager.startGuest(
         argv = ["pi", "--mode", "rpc"],          // 打开历史会话时追加 "--session", <guestPath>
         cwd  = 会话 cwd（默认 /root/pi-cwd）,
         env  = { PI_CODING_AGENT_DIR=/root/.pi/agent, NODE_OPTIONS=--max-old-space-size=512 })
       stdout → 读线程(8KB 字节块) → JsonlFramer → Json.parse → Channel<Record>
       stderr → 环形缓冲 500 行（带 seq）→ AppLog，**不当协议解析**
       stdin  ← 单写协程（Mutex），每条记录 = json + "\n"，写完 flush
```

- 同一时刻只有 **1 个** pi 进程（MVP）。切换到另一 cwd 的会话或“在新目录新建会话”时：关闭 stdin → 等 3s → `destroy()` → 用新 cwd 重启。同 cwd 内用 `switch_session` / `new_session`，不重启。
- 就绪判定：进程启动后立刻发 `{"id":"boot","type":"get_state"}`，收到 `success:true` 即 `Idle`；15s 未回 → `Crashed`（附 stderr 尾部）。
- 关闭：关闭 stdin（官方“orderly shutdown”），5s 未退出再 `destroy()`。
- WakeLock 只在 `Running`（`agent_start`…`agent_settled` 之间）持有，空闲释放——比 PiMet 常驻 WakeLock 省电。

### 4.3 消息格式（客户端 → pi）

每条命令都带客户端生成的 `id`（`c-<自增>`），`RpcClient` 维护 `id → CompletableDeferred<RpcResponse>`，按 id 关联而不是按顺序（官方要求）。默认超时：`abort` 20s，其余 15s。

| 界面动作 | 发送的记录 |
|---|---|
| 发送（空闲时） | `{"id":"c-1","type":"prompt","message":"…"}`（可带 `images:[{"type":"image","data":"<b64>","mimeType":"image/png"}]`，二期） |
| 回复中“插话” | `{"id":"c-2","type":"steer","message":"…"}` |
| 回复中“排队” | `{"id":"c-3","type":"follow_up","message":"…"}` |
| 停止 | 先 `{"type":"clear_queue"}`（拿回 `data.steering[]`/`data.followUp[]` 文本放回输入框），再 `{"type":"abort"}` |
| 新会话 | `{"type":"new_session"}`（`data.cancelled` 为 true 表示被扩展拦截） |
| 打开会话 | `{"type":"switch_session","sessionPath":"/root/.pi/agent/sessions/--root-pi-cwd--/…jsonl"}` 然后 `{"type":"get_messages"}` + `{"type":"get_state"}` |
| 重命名 | `{"type":"set_session_name","name":"…"}` |
| 模型列表 / 切换 | `get_available_models` / `{"type":"set_model","provider":"…","modelId":"…"}` |
| 思考级别 | `get_available_thinking_levels` / `{"type":"set_thinking_level","level":"high"}` |
| 统计 | `get_session_stats`（顶栏上下文占用 `contextUsage.percent`） |
| 扩展对话框应答 | `{"type":"extension_ui_response","id":"<请求 id>","value":"…"}` / `"confirmed":true` / `"cancelled":true` |

`prompt` 响应的 `data.disposition`：`started` / `queued` / `handled`。`handled` 表示被扩展命令消费，**不要**等 `agent_settled`。

### 4.4 事件（pi → 客户端）与流式归约

stdout 每行一条，三类：`type:"response"`（命令应答）、会话事件、`type:"extension_ui_request"`。解析用 `kotlinx.serialization` 的 `JsonObject`，`ignoreUnknownKeys = true`，未知 `type` 归为 `EngineEvent.Unknown(raw)` 只记日志——保证引擎小版本升级加字段不崩。

`ChatReducer` 处理表（全部字段名取自 json.md / message-types.md）：

| 事件 | 字段 | 界面效果 |
|---|---|---|
| `agent_start` | — | `running=true`，输入框切换为“插话/排队/停止” |
| `turn_start` / `turn_end` | `turn_end.message`, `toolResults` | 一个“回合”分组边界（折叠单位） |
| `message_start` | `message`（role=user/assistant/…） | user：追加气泡（若是本地乐观气泡则确认）；assistant：新建 `AssistantItem(blocks=[])` |
| `message_update` | `assistantMessageEvent{type, contentIndex, delta…}`, `usage` | 按 `contentIndex` 维护 block：`text_start/delta/end`、`thinking_start/delta/end`、`toolcall_start(id, toolName)/toolcall_delta/toolcall_end(toolCall)`；`*_end` 用权威 `content`/`toolCall` 覆盖拼接结果 |
| `message_end` | `message` | 用权威 message 整体替换；`stopReason` = `error`→错误卡（`errorMessage`），`aborted`→“已停止”标记，`length`→“输出被截断”提示 |
| `tool_execution_start` | `toolCallId, toolName, args` | 工具卡 → `RUNNING`，显示计时 |
| `tool_execution_update` | `toolCallId, partialResult` | 卡片内实时输出（替换为最新 partial） |
| `tool_execution_end` | `toolCallId, result, isError, durationMs` | `OK`/`ERROR`，显示耗时，结果默认折叠 |
| `queue_update` | `steering[]`, `followUp[]`（**完整**当前队列） | 输入框上方的“待插话/待排队”芯片，直接整体替换 |
| `auto_retry_start/end` | `attempt, maxAttempts, delayMs, errorMessage` / `success, finalError` | 顶部黄条“第 n/3 次重试…”，可点“放弃重试”→ `abort_retry` |
| `compaction_start/end` | `reason`, `result`, `aborted`, `errorMessage` | 时间线分隔条“已压缩上下文（150k→32k）” |
| `session_info_changed` | `name` | 更新标题与会话列表 |
| `thinking_level_changed` | `level` | 更新思考级别芯片 |
| `agent_end` | `messages, willRetry` | 不结束 running（后面可能还有重试/排队） |
| `agent_settled` | `aborted` | `running=false`，释放 WakeLock，触发会话列表刷新 |
| `extension_error` | `extensionPath, event, error` | 非阻塞 toast + 日志 |
| `extension_ui_request` | `id, method, title, options/message/placeholder/prefill, timeout` | select/confirm/input/editor → Compose 对话框；notify → Snackbar；setStatus/setWidget → 输入框上方状态条；setTitle/set_editor_text → 标题/输入框 |

性能：读线程把事件投进 `Channel`，归约协程每 **33ms** 批量 reduce 一次后发布 `StateFlow`（delta 再多也最多 30 次/秒重组）；`LazyColumn` 每个 item 以稳定 key（`messageIndex/contentIndex` 或 `toolCallId`）避免整列重组。

### 4.5 工具调用卡片与过程折叠

- 一个 assistant 回合内：`thinking` 块 + 若干 `toolCall` 块 + 对应 `toolResult` 被收成一条“过程”行（折叠时显示“思考 12s · 3 个工具 · 1 失败”），最终 `text` 块单独展示。
- 工具卡按 `toolName` 选渲染器：`bash`（命令 + 等宽输出 + exitCode）、`read`/`write`/`edit`（路径 + 片段/差异）、其它（`args` JSON + 结果文本）。`isError` 红色描边，默认展开出错的那张。
- 历史重建（`get_messages`）：assistant 的 `toolCall` 与后续 `role:"toolResult"` 用 `toolCallId` 合并成同一张卡；`bashExecution`/`custom`/`branchSummary`/`compactionSummary` 渲染为提示行；**未知 role 渲染为可展开的原始 JSON 行**（message-types.md 明确要求容忍未知 role）。

### 4.6 会话管理

- 存储位置：pi 默认 `~/.pi/agent/sessions/--<cwd 转义>--/<timestamp>_<uuid>.jsonl`（转义规则：去掉开头 `/`，`/ \ :` → `-`）。宿主侧路径 = `files/rootfs/root/.pi/agent/sessions/…`。
- **列表**（RPC 无此命令，宿主直接读文件）：`SessionRepository.refresh()` 扫描目录；每个文件只读：首行 header（`type:"session"`, `id`, `timestamp`, `cwd`）、自尾部向前找最后一个 `session_info.name`、第一条 `role:"user"` 消息前 80 字作预览、`lastModified` 作更新时间。用 `(path, size, mtime)` 做缓存键，大文件只读头 64KB + 尾 64KB。
- **打开**：同 cwd → `switch_session`；不同 cwd → 以 `--session <path>` 重启进程（§4.2）。之后 `get_messages` → `ChatReducer.fromMessages()` 重建。
- **新建**：`new_session`；可选“在其他目录新建”→ 以新 `cwd` 重启。
- **删除**：活动会话禁止删除；其余直接删文件（文档：“Sessions can be removed by deleting their .jsonl files”）。
- **重命名**：活动会话用 `set_session_name`；非活动会话先 `switch_session` 再改（MVP 只支持活动会话改名）。

### 4.7 中断、插话、排队

- 空闲：发送键 = `prompt`。
- 运行中：输入框右侧主按钮变为“插话”（`steer`，在当前回合工具执行完、下一次 LLM 调用前送达），长按/副按钮为“排队”（`follow_up`，整个运行结束后送达）；另有独立“停止”。默认模式与 pi 一致：`steeringMode/followUpMode = one-at-a-time`（设置页可改为 `all`）。
- 停止：`clear_queue` → 把返回的文本拼回输入框 → `abort`；`abort` 应答前 UI 显示“正在停止…”。**20s 未应答 → 强杀进程 → 以 `--session <当前文件>` 重启**，最后一条 assistant 标记“已强制停止”。
- 我们不在客户端自建队列，**以 `queue_update` 为唯一真相**（防止与引擎的队列不一致）。

### 4.8 错误与重连

| 情形 | 识别 | 处理 |
|---|---|---|
| 命令被拒 | `response.success=false` | Snackbar 显示 `error`；`prompt` 失败则把文本退回输入框 |
| 解析错误 | `command:"parse"` | 记日志并上报（说明我们发了坏 JSON，属 bug） |
| 模型/网络失败 | `message_end.stopReason=="error"` + `errorMessage`；`auto_retry_end.success=false` | 错误卡：原因 + “重试”（重新 `prompt` 上一条用户文本）+ “去设置模型” |
| 引擎崩溃/被杀 | stdout EOF / `Process.waitFor()` 返回 | `Crashed(code, stderrTail)`；退避 1s→3s→10s 自动重启最多 3 次，重启后 `switch_session(上次文件)` + `get_messages` 重建；仍失败则显示“引擎异常”页（含 stderr 尾部、一键复制诊断、“修复运行环境”） |
| App 被系统杀 | 冷启动 | 恢复“上次打开的会话路径”（DataStore），懒启动引擎；正在跑的那一轮已丢失 → 最后一条 assistant 若 `stopReason=="pending"` 显示“中断” |
| 命令超时 | 15s/20s | `RPC_TIMEOUT`，必要时走强杀重启 |

### 4.9 协议兼容策略

- `core/protocol` 只依赖文档化字段；未知事件/字段忽略。
- 引擎版本**钉死**（`pins.json`：`{"pi":"1.5.0","node":"24.x"}`）。升级前在 CI 跑“录制夹具回放”：真实 pi 1.5.0 产出的 JSONL（`core/protocol/src/test/resources/fixtures/*.jsonl`，覆盖文本流、思考、工具成功/失败、steer、follow_up、abort、重试、压缩）喂给 `ChatReducer`，比对快照。

---

## 5. 运行环境：从 PiMet 迁移

### 5.1 许可证发现（影响“哪些能原样搬”）

1. **PiMet 仓库没有 LICENSE 文件**：默认为作者保留所有权利；用户自己的项目，复用无障碍，但 pi-go 需要自己定许可证（建议 Apache-2.0 或 MIT，由用户决定）。
2. **PiMet 里有来自 DSH-Folk（IPF-Sinon/DSH-Folk，GPL-3.0）的代码**：
   - `proot/TarGzipExtractor.java` 与 DSH-Folk `app/src/main/java/me/bmax/apatch/dsh/TarGzipExtractor.java` 逐行比对 **447/453 行相同**（相似度 0.92，仅包名与少量字段不同）。
   - `ProotManager` 注释写明“参考 DSH-Folk 架构标准 / ensureRuntimeFiles / DSHA ResolverConfig”，`ensureContainerGroups`（`aid_<gid>`、`3003→inet`）与 DSH-Folk `DshRuntimeEnv.kt` 思路一致（语言不同，未见逐行复制，但存在疑似改写）。
   - `build-slim-rootfs.yml` 的 GitHub rootfs 是从 DSH-Folk release 下载后裁剪的二次分发。
   → 若原样搬这些文件，pi-go 就成了 GPL-3.0 衍生作品。**本提案选择：重写 `TarGzipExtractor`（改用 Apache Commons Compress + xz-java，均 Apache-2.0/公有领域），用 Kotlin 从零写 DNS/组文件修复，弃用 DSH-Folk 派生的 rootfs 包**。这样 pi-go 可以自由选许可证。
3. `jniLibs` 中 `libproot.so` 是 Termux proot **v5.1.107.91**（GPL-2.0，二进制内含 STMicroelectronics 版权行），`libtalloc.so`（LGPL-3.0）、`libandroidshmem.so`（BSD-3-Clause）来自 Termux。作为独立可执行程序随 APK 分发属“聚合”，不传染 App 代码，但必须附许可证全文并提供对应源码获取方式。
4. `libproroot*.so`（5 个：bridge/linker/runtime/stub-loader/proroot）**来源与许可不明，且 PiMet Java 代码中没有任何引用** → pi-go 不带。
5. `assets/pets/doro.codex-pet`、`elaina-2` 是动漫角色同人素材，许可不明 → pi-go 不带（二期桌宠用自有或明确授权素材）。

### 5.2 类级迁移清单（MVP 涉及的部分）

| PiMet 类 | 去向 | 方式 |
|---|---|---|
| `ProotManager.buildProotProcess / getRootfsDir / getTmpDir / getNativeLibDir / isNativeProotAvailable` | `core/runtime/ProotLauncher.kt` | **改**：Kotlin 重写为纯函数 `buildArgv(spec)`，去掉对 `PiMetConfig` 的静态依赖；参数集保持一致（这是 proot 的使用方式，不构成表达复制） |
| `ProotManager.ensureRuntimeFiles`（SONAME 复制） | `core/runtime/RuntimeFiles.kt` | **重写**（GPL 疑似来源） |
| `ProotManager.ensureContainerDns / ensureContainerGroups` | `core/runtime/GuestFixups.kt` | **重写**（同上） |
| `ProotManager.ensureNpmConfig` | `core/runtime/GuestFixups.kt` | 改：去掉 `@agegr` scope，保留 `@earendil-works:registry` |
| `ProotManager.buildDomesticRootfs / writeDomesticAptConfig / latestNodeV24 / headOk / downloadFromAny` | `core/runtime/install/DomesticInstaller.kt` | **改**：逻辑迁移为 Kotlin + 协程，镜像表抽成 `mirrors.json`（assets），可远端热更新 |
| `ProotManager.downloadWithResume / testUrlRealThroughput / testAndSortCandidateUrls` | `core/runtime/net/Downloader.kt` | 改：Kotlin 化，保留断点续传与“6s 后 <120KB/s 熔断换线” |
| `TarGzipExtractor` | `core/runtime/install/TarExtract.kt` | **重写**（基于 Commons Compress；保留：绝对符号链接按 rootfs 根解析、硬链接、可执行位、路径穿越防护、总量上限） |
| `terminal/Downloader`、`NetworkRouteSelector` | `core/runtime/net/` | 原样搬（Java）后逐步替换 |
| `PiMetService` | `core/engine/EngineService.kt` | 改：只在运行中持 WakeLock；通知显示“空闲/运行中/n 个排队” |
| `PiWebManager`、`PiWebApi`、`PiWebMonitor`、`PiWebActivity`、`PiWebCompat`、`fixPiCodemodeCompat`、`optimizePiWebOffline` | — | **不搬**（pi-web 整体弃用） |
| `PiMetConfig.syncToContainer`（写 auth/models/settings.json） | `core/store/ProviderConfigStore` | 改：按 pi 1.5.0 文档的 schema 合并写入（§6.2） |
| `TerminalHub`、`FloatingTerminal`、`ShellSession`、`AnsiParser`、`assets/extensions/pimet-terminal.ts` | 二期 `:feature:terminal` | 原样搬（Java + Termux 库） |
| `AppBridgeManager`、`PiMetAccessibilityService`、`DevicePrivilegeManager`、`pimet-android-bridge.ts` | 二期 `:core:bridge` | 原样搬，包名改，token 文件名改 `.pigo_bridge_token` |
| `pet/*` | 二期 `:feature:pet` | 原样搬，素材替换 |
| `BackupManager` | 二期 | 改：仅备份 `~/.pi/agent` |
| `MainActivity`、`FloatingApps`、`BrowserAgent`… | — | 不搬（界面重做） |

### 5.3 首启安装流程（国内优先）

```
Onboarding 页
 0. 预检：ABI（arm64/x86_64）、可用空间 ≥ 2.5GB、网络可用；proot 可执行（nativeLibDir/libproot.so）
 1. 测速选源（并行 HEAD + 2s 吞吐采样）：
      Ubuntu Base 24.04.x：aliyun / tuna / ustc / huaweicloud
      Node 24.x：registry.npmmirror.com/-/binary/node / cdn.npmmirror.com / huaweicloud / tuna / aliyun
 2. 下载并解压 ubuntu-base-24.04.x-base-<arch>.tar.gz → files/rootfs           (进度 5→35%)
 3. 下载 node-v24.x-linux-<arch>.tar.gz → 解压到 rootfs/usr/local              (35→60%)
 4. GuestFixups：resolv.conf（宿主 DNS + 223.5.5.5/119.29.29.29，no-aaaa）、/etc/group、/etc/hosts、.npmrc(npmmirror)
 5. apt 换阿里云源，装 ca-certificates git curl procps（失败不致命，后台重试）      (60→75%)
 6. npm install -g --ignore-scripts @earendil-works/pi-coding-agent@<pins.pi>   (75→95%)
 7. 验证：pi --version == pins.pi；pi --mode rpc 启动后 get_state 成功          (95→100%)
 8. 写 files/rootfs/etc/pi-go-runtime.json {ubuntu, node, pi, installedAt}
```

- 每步幂等可重入：失败只重试当前步，已完成步骤由标记文件跳过；界面提供“换镜像重试”和“查看日志”。
- 可选高级项：“我有代理，走 GitHub/官方源”。
- 二期：pi-go 自有 CI 预构建 rootfs（参考 dsh-Go 的“钉哈希、主机侧构建”思路，自己实现脚本），作为第三条线路，首装可降到 1–2 分钟。

### 5.4 升级策略

- **App 升级不动 rootfs**；App 内置 `pins.json`，启动时比对 `pi-go-runtime.json`。
- **pi 升级**：设置页“检查更新”读 npmmirror `dist-tags.latest`；只有当版本在 App 声明的兼容范围（MVP：`>=1.5.0 <2.0.0`）内才可一键升级。流程：停引擎 → `npm i -g @earendil-works/pi-coding-agent@X` → 验证（版本号 + RPC `get_state`）→ 失败自动 `npm i -g …@旧版本` 回滚。
- **Node 升级**：仅在 pi 的 `engines.node` 要求变化时提示，走与首装相同的下载解压，旧目录保留到验证通过。
- **rootfs 损坏**：设置页“修复运行环境”= 重跑缺失步骤；“重置运行环境”= 删除 rootfs 但保留 `root/.pi/agent`（会话与凭据）——需二次确认。

---

## 6. 第一期 MVP 范围（真机可验收）

验收设备：至少 1 台 arm64 真机（Android 11+，国内网络、不开代理）。

### 6.1 首启安装容器 + pi
- [ ] F1 全新安装后首页进入引导页，显示空间/网络预检结果。
- [ ] F2 无代理国内网络下完成安装，全程只访问国内镜像域名（日志可证），总时长 ≤ 15 分钟（百兆网络）。
- [ ] F3 安装中切后台/锁屏 5 分钟不中断（前台服务通知可见进度）。
- [ ] F4 中途断网后恢复，点“重试”从失败步骤继续，不重新下载已完成部分。
- [ ] F5 完成后 `pi --version` = 1.5.0 显示在“关于运行环境”。

### 6.2 配置模型 / API Key
- [ ] C1 内置供应商下拉（至少：DeepSeek、Moonshot、Qwen（China）、ZAI（China）、OpenRouter、OpenAI、Anthropic），填 Key 后写入 `/root/.pi/agent/auth.json`：`{"<provider>":{"type":"api_key","key":"…"}}`，合并不覆盖已有条目，文件权限 600。
- [ ] C2 “自定义 OpenAI 兼容端点”：填 baseUrl/apiKey/模型 id，写入 `models.json` `providers.<id> = {baseUrl, api:"openai-completions", apiKey, models:[{id}]}`。
- [ ] C3 保存后（重启引擎）调用 `get_available_models` 能列出该供应商模型；选中后 `set_model` 成功，并写 `settings.json` 的 `defaultProvider/defaultModel`。
- [ ] C4 Key 在界面只显示末 4 位；日志与诊断导出中不出现完整 Key（单测断言）。

### 6.3 原生聊天流式对话
- [ ] S1 发送消息后首 token 在模型返回时即刻出现（无整段等待），文本逐字增长。
- [ ] S2 Markdown（标题/列表/代码块/表格）正确渲染；代码块可复制。
- [ ] S3 thinking 块显示为可折叠“思考中…/已思考 Ns”。
- [ ] S4 顶栏显示当前模型、思考级别（可切换）与上下文占用百分比。
- [ ] S5 旋转屏幕/切后台再回来，正在流式的消息继续增长，不丢不重。

### 6.4 工具调用卡片折叠
- [ ] T1 让 pi 执行“列出当前目录并读 README”：出现 `bash`、`read` 工具卡，运行中显示转圈与计时，完成显示耗时。
- [ ] T2 工具失败（如读不存在文件）卡片红色、默认展开、显示错误文本。
- [ ] T3 回合结束后思考+工具自动折叠成一行摘要，可点击展开；最终回答单独显示。
- [ ] T4 打开历史会话时工具卡与实时一致（来自 `get_messages` 重建）。

### 6.5 中断 / 插话 / 排队
- [ ] Q1 回复中输入文字点“插话”，输入框上方出现“待插话”芯片；当前工具执行完后该消息进入对话，芯片消失。
- [ ] Q2 回复中点“排队”，芯片显示“待排队”，本轮结束后自动作为下一轮发出。
- [ ] Q3 有排队时点“停止”：队列文本回到输入框，运行在 20s 内停止，消息标记“已停止”。
- [ ] Q4 强杀场景（模拟 abort 不应答）：20s 后引擎重启并回到同一会话，历史完整。

### 6.6 会话列表
- [ ] L1 侧边抽屉列出所有会话：名称或首条消息预览、工作目录、更新时间，按更新时间倒序。
- [ ] L2 点击切换会话，历史完整显示；新建会话按钮可用。
- [ ] L3 重命名当前会话（`set_session_name`），列表与标题同步。
- [ ] L4 删除非当前会话（二次确认），文件被删除，列表刷新。
- [ ] L5 冷启动后自动回到上次会话。

### 6.7 非功能
- [ ] N1 空闲时不持有 WakeLock（`dumpsys power` 可证）。
- [ ] N2 引擎异常退出后 3 次内自动恢复；超过后显示诊断页，可一键复制（已脱敏）。
- [ ] N3 `:core:protocol` 单测覆盖率 ≥ 80%，夹具回放全部通过。

**MVP 不做**：图片/附件、悬浮终端、桌宠、手机操控、WebView 模式、多会话并发、fork/tree 分支浏览、导出、主题定制、平板双栏（Compose 用 WindowSizeClass 预留，二期实现）。

---

## 7. MVP 并行工作流

规则：每条工作流只改自己的“文件范围”；跨模块接口只能由 `mvp/scaffold` 定义，后续若要改接口，提 PR 单独改 `core/*/api/` 文件并通知各方。全部分支从合入 scaffold 后的 `main` 切出。

### W0 `mvp/scaffold`（最先合 main）
- **文件范围**：根 `settings.gradle.kts`、`build.gradle.kts`、`gradle/**`、`gradlew*`、`gradle.properties`、`.github/workflows/build.yml`、`app/**`（Application、MainActivity、导航骨架、AppGraph、主题）、所有模块的 `build.gradle.kts` 与 `AndroidManifest.xml`、各模块 `api/` 包下的接口与数据类（§3.2 全部）、各接口的 `Fake*` 实现、`LICENSE`（用户选定后）、`THIRD_PARTY_NOTICES.md`（按 §8 落地）、`pins.json`、`jniLibs`（仅 libproot/libprootloader/libprootloader32/libtalloc/libandroidshmem，arm64+x86_64）。
- **依赖**：无。
- **验收**：`./gradlew assembleDebug testDebugUnitTest` 本地与 CI 通过；APK 安装后能在 4 个 feature 的占位页之间导航；`AppGraph` 默认接 Fake 实现，Fake 引擎能播放一段内置夹具（便于 chat-ui 独立开发）；CI 产物为 debug APK。

### W1 `mvp/runtime`
- **文件范围**：`core/runtime/src/main/**`（除 `api/`）、`core/runtime/src/test/**`、`core/runtime/src/main/assets/mirrors.json`、`feature/onboarding/**`。
- **依赖**：W0 的 `RuntimeManager` 接口。
- **验收**：§6.1 F1–F5；`RuntimeManager.exec("node -v")` 返回 v24；`startGuest` 能启动 `pi --version`。

### W2 `mvp/engine`
- **文件范围**：`core/protocol/**`（除 `api/` 已在 W0 定义的数据类）、`core/engine/src/**`、`core/protocol/src/test/resources/fixtures/**`、`docs/protocol/fixtures.md`。
- **依赖**：W0；联调需要 W1（开发期可用桌面 Linux 上的真 pi 1.5.0 跑 `JvmPiProcess` 测试，不依赖手机）。
- **验收**：JVM 单测：Framer（含 U+2028/U+2029 不切分、CRLF、半包）、id 关联、超时、Reducer 夹具快照；集成测（桌面真 pi）：prompt→settled、steer、follow_up、clear_queue+abort、switch_session、进程被杀后自动重启并恢复会话。§6.5 Q4、§6.7 N1–N3。

### W3 `mvp/chat-ui`
- **文件范围**：`feature/chat/**`。
- **依赖**：W0 的 `EngineClient`/`ChatState`（开发期用 Fake 引擎回放夹具）。
- **验收**：§6.3 S1–S5、§6.4 T1–T4、§6.5 Q1–Q3 的界面部分；Compose UI 测试：1000 条 delta 回放下无掉帧报告（Macrobenchmark 或 JankStats 输出 < 5% 慢帧）。

### W4 `mvp/sessions-settings`
- **文件范围**：`core/store/**`、`feature/sessions/**`、`feature/settings/**`。
- **依赖**：W0；与 W2 联调 `openSession/rename/models`。
- **验收**：§6.2 C1–C4、§6.6 L1–L5；`SessionRepository` 单测（header/session_info/预览解析、大文件头尾读取、损坏行容错）；`ProviderConfigStore` 单测（合并写入不丢已有条目、权限 600、脱敏）。

合并顺序：W0 → (W1, W2, W3, W4 并行) → 集成冒烟（真机走通 §6 全表）→ 打 `v0.1.0`。

---

## 8. 许可证与第三方声明（scaffold 落地 `THIRD_PARTY_NOTICES.md` 的草稿）

本提案只新增本文件；下表供 W0 原样落地。

| 组件 | 版本 | 来源 | 许可 | 在 pi-go 中的形态 | 义务 |
|---|---|---|---|---|---|
| PiMet（zr987459/pimet） | a80222c | 用户自有项目 | 无 LICENSE（作者保留权利，作者即本项目所有者） | 复用部分 Java/Kotlin 化代码 | 无（同一权利人） |
| ~~DSH-Folk 派生代码~~ | — | IPF-Sinon/DSH-Folk | GPL-3.0 | **不使用**：TarGzipExtractor 等重写，派生 rootfs 不分发 | — |
| proot | Termux v5.1.107.91（随 PiMet 二进制） | github.com/termux/proot | GPL-2.0 | jniLibs 独立可执行 | 附 COPYING；提供对应源码地址/版本；二期改用自行钉版本的 Termux 官方包 |
| libtalloc | Termux 构建 | samba.org talloc | LGPL-3.0-or-later | jniLibs 动态库 | 附许可；允许替换 |
| libandroid-shmem | Termux 构建 | github.com/termux/libandroid-shmem | BSD-3-Clause | jniLibs | 附版权声明 |
| Termux terminal-view / terminal-emulator | v0.118.0 | termux-app（这两个子库为 Apache-2.0 例外） | Apache-2.0 | 二期依赖 | 附 NOTICE |
| Shizuku API / provider | 13.1.5 | RikkaApps/Shizuku-API | MIT | 二期依赖 | 附许可 |
| OkHttp | 4.12.0 | square | Apache-2.0 | 依赖 | 附 NOTICE |
| Lucide 图标（PiMet `res/drawable/ic_*.xml`） | — | lucide.dev | ISC | 若沿用则附许可 | 附许可 |
| Apache Commons Compress / xz-java | 最新稳定 | Apache / tukaani | Apache-2.0 / 公有领域 | 依赖（替代 TarGzipExtractor） | 附 NOTICE |
| multiplatform-markdown-renderer | 最新稳定 | mikepenz | Apache-2.0 | 依赖 | 附 NOTICE |
| pi coding agent 及 @earendil-works/* | 1.5.0 | earendil-works/pi | MIT | 运行时在设备上通过 npm 安装，不打进 APK | 关于页注明 |
| Ubuntu Base / Node.js | 24.04.x / 24.x | Canonical / nodejs.org | 各自许可 | 运行时下载，不打进 APK | 关于页注明 |
| ~~libproroot*.so~~ | — | 来源不明 | 不明 | **不带** | — |
| ~~doro / elaina 宠物素材~~ | — | 来源不明 | 不明 | **不带** | — |
| dsh-Go | — | ccch1mneyyy/dsh-Go | AGPL-3.0 | **不复制任何代码与素材**，仅参考公开的交互设计 | 不使用其名称、标识、Deepy、鲸娘 |

pi-go 自身许可证：建议 **Apache-2.0**（与主要依赖一致、带专利授权），由仓库所有者在 W0 合并前拍板。

---

## 9. 主要风险与对策

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | `targetSdk 28` 长期受限（新系统可能对低 targetSdk 弹警告/限制安装，Android 14+ 已拦截 targetSdk<23） | 中 | 现阶段 28 仍可装；二期研究 targetSdk 29+ 下“所有可执行文件放 nativeLibraryDir + proot loader 映射 rootfs ELF”的方案，单独分支验证 |
| R2 | PRoot 下 node/pi 冷启动慢、内存高（低端机） | 中 | 单进程；`NODE_OPTIONS=--max-old-space-size=512`；启动后常驻、仅切 cwd 时重启；启动时间线打点 |
| R3 | 国内镜像失效或版本号漂移（Ubuntu 点版本下架） | 高 | `mirrors.json` 可远端更新；版本探测按 `24.04.5→.4→.3` 回退；保留“走官方源”开关；二期自建 rootfs 线路 |
| R4 | pi 小版本改协议字段/事件 | 中 | 钉版本 + 兼容范围 + 夹具回放 CI；未知字段/事件容忍；升级失败回滚 |
| R5 | 系统杀后台导致长任务中断 | 中 | 前台服务 + 引导关闭电池优化；运行中持 WakeLock；恢复时提示“上一轮被系统中断” |
| R6 | 凭据泄露（auth.json 在容器内，pi 的 bash 工具也能读） | 高 | auth.json 600；日志/诊断脱敏单测；在设置页明确提示“模型可读取容器内所有文件”；二期考虑 `key: "!cmd"` 从 App 侧 Keystore 取 Key（pi 支持 `!command` 形式） |
| R7 | 许可证：PiMet 中 GPL-3.0 派生代码被误搬 | 高 | §5.2 明确“重写”清单；W0 加 CI 检查：禁止出现 `me.bmax.apatch`/已知片段哈希；THIRD_PARTY 审查列入发版清单 |
| R8 | proot 二进制来源（随 PiMet 继承的 v5.1.107.91，无对应源码记录） | 中 | 二期改为从 Termux 官方 apt 拉固定版本 .deb 并钉 SHA-256，随 APK 附 COPYING 与源码地址 |
| R9 | 扩展 UI 对话框带 `timeout`，用户没看到就被默认处理 | 低 | 对话框显示倒计时；App 在后台时通过通知提醒 |
| R10 | 项目信任（project trust）在 RPC 模式下的交互 | 低 | MVP 工作区 `/root/pi-cwd` 无 `.pi` 资源；若出现 `extension_ui_request` 确认框按 §4.4 正常展示；必要时启动参数加 `--no-approve` |

---

## 10. 后续路线

- **v0.2**：图片附件（`prompt.images`）、平板/横屏双栏、主题（浅/深/跟随系统 + 强调色）、会话搜索、`get_session_stats` 用量页、`compact` 手动压缩。
- **v0.3（复用 PiMet 能力）**：`:feature:terminal`（TerminalHub/FloatingTerminal 原样搬，与 pi 的 terminal 工具共享会话）、`:core:bridge`（30143 App 桥 + 无障碍手机操控，pi 扩展改名 `pigo-*.ts`）、AI 桌宠（自有素材）。
- **v0.4**：会话树/分叉（`get_tree`/`fork`/`clone`）、导出 HTML、扩展/技能管理页（`get_commands`）、WebView 兜底模式（可选再装 pi-web）。
- **v0.5**：自建 rootfs CI（钉哈希、主机侧构建）、多会话并发（评估 SDK 桥或多 RPC 进程）、远程模式（RPC over TCP/SSH 连电脑上的 pi）、targetSdk 升级。
