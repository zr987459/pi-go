# 第三轮重新设计 · 方案 C：「一个会话，两种画法，一张动作表」

> 作者：方案 C（独立撰写，未参考其他 r3 方案）
> 日期：2026-11-04
> 依据：`docs/pi-features.md`（pi 1.5.0 全功能清单，下文简称「清单」，按节号 §1–§13 和行名引用），以及 `main` 上现有代码（2026-11-04 拉取）。
> 用户原话：
> 1. 「根据 pi 自己重新设计一个界面，把一些无用的功能删了，先只保留配置模型，做成那种极简好看的风格」
> 2. 「pi 该有的功能不能少，可以分成终端和 UI 界面两种形态」
> 3. 「再把功能加到 UI 界面上，两种模式无缝切换」

---

## 0. 方案一句话

**只有一个 pi 进程、一个会话状态和一张「pi 动作表」（`PiActions`）。终端形态和 UI 形态只是两种画法，读的是同一份状态，调的是同一张动作表。**

- **同一个会话。** `PiEngine` 加 `ChatController` 加草稿，由新的 `SessionHost` 持有，与 App 进程同寿。两种形态的 Composable 都不持有业务状态。
- **两种画法。**
  - `TermSurface`：用 Compose 画出 pi TUI，包括等宽字体、pi 的 `dark.json`/`light.json` token、页眉、消息区、带边框的编辑器、补全浮层和页脚。
  - `CalmSurface`：极简原生聊天界面。
- **一张动作表。** 清单里每一项用户可触发的 pi 功能，都登记成 `PiActions` 里的一条 `PiAction`。每条带 id、中文名、斜杠别名、执行函数，以及两种形态各自的入口描述。终端形态从 `/` 补全和按键条进入，UI 形态从「⌘ 命令中心」以及少量常驻控件进入。
  - **好处：「UI 形态覆盖清单全部功能」可以用单元测试机器核对**，不靠人工对表（见 §7.3）。
- **设置页只剩「模型」。** pi 自己的 `/settings`（显示、队列、压缩、工具……）是 pi 的一个功能，不是 App 设置。所以它作为一个动作出现在命令中心和 `/settings`，不出现在设置首页。这样同时满足「设置只保留配置模型」和「pi 的功能一项不少」。

---

## 1. 功能保留 / 删除 / 改造清单（逐模块）

判断标准：
- pi 1.5.0 原生具备的保留，以清单为准。
- pi 没有、但安卓运行 pi 必需的（安装运行环境、前台服务、凭据加密）保留并标「平台必需」。
- 其他 pi 没有的一律删除。

### 1.1 core

| 模块 / 文件 | 结论 | 理由 |
|---|---|---|
| `core/protocol`（RpcChannel、JsonlFramer、ChatReducer、ContractMapping、RecordCodec、Responses、ExtraResponses、MessageMetaTracker、Diagnostics、CompactionSummary、RunResults、Support） | **保留** | 33 个 RPC 命令和全部事件的唯一通道（清单 §2）。两种形态共用 |
| `core/protocol/TurnStats.kt` | **改造** | 只留给页脚和 `/session` 用的累计统计；不再产出「每轮统计卡」 |
| `core/engine`（PiEngine、EngineConfig、EnginePorts、AndroidPorts、EngineService） | **保留** | 引擎本体与前台服务（平台必需）。另加启动参数 `-e /root/.pi-go/pigo-bridge.ts`（§5.4） |
| `core/engine/TaskNotifier.kt`、`AndroidTaskNotifications.kt` | **保留，默认关闭** | pi 有「等待用户 / 完成」的终端进度提示（OSC 9;4），安卓上对应通知。前台服务通知必须保留 |
| `core/runtime`（全部） | **保留** | PRoot 和 pi 安装（平台必需）。`MirrorProbe`/`DomesticInstaller` 只在安装页用 |
| `core/store`：`FileSessionRepository` | **改造** | 删回收站，改成「删除后 5 秒内可撤销」（pi 没有回收站，清单 §12） |
| `core/store`：`PiProviderConfigStore`、`ProviderSpecs`、`ProviderPresets`、`BuiltinCatalog`、`ModelDiscovery`、`ProviderTester`、`AuthJsonLock`、`KeystoreSecretStore`/`KeystoreCipher`、`KeyRedaction`、`HostProxy`、`GuestPaths`、`JsonFiles`、`ResumePolicy`、`SessionFileParser` | **保留** | 模型配置（用户唯一保留的设置）与会话读取 |
| `core/store/ModelPrefsStore.kt` | **改造** | 删人民币汇率字段；保留最近使用和默认模型（对应 pi 的 `defaultProvider`/`defaultModel`） |
| `core/catalog`（AuthService、OAuth*、Pkce、Real*、CatalogRepository、ModelIndex、GuestScripts） | **保留** | 42 个服务商、OAuth、自定义服务商 |
| `core/catalog/RealAppSettingsStore.kt` | **改造** | 删 `CostDisplay`（汇率）；网络代理移到 pi 的 `httpProxy`（由 `PiSettings` 读写） |
| `core/workspace/Attachments.kt`、`ImagePrep.kt`、`AndroidImageCodec.kt`、`FileTypes.kt`、`FileNames.kt`、`GuestPaths.kt` | **保留** | 图片和附件（pi `prompt.images[]`、`images.autoResize`） |
| `core/workspace/SessionExport.kt` | **保留** | `/export`（HTML 与 JSONL）是 pi 原生 |
| `core/workspace/RootfsWorkspace.kt` | **改造** | 只留 `@文件` 模糊搜索、读取工作目录 git 分支名（页脚用）和读 `full_output_path`；删目录浏览 API 的界面调用方 |
| `core/workspace/GitParse.kt` | **删除**（只保留 `branchName()` 一个函数，挪进 `RootfsWorkspace`） | pi 只在页脚显示分支名，没有 Git 页 |
| `core/ui`（DshPalette、DshTokens、Components 中的玻璃面板与渐变背景、AppIcons 的 dsh 字形） | **删除并替换** | 换成两套新主题：`PiTermTheme`（pi token）和 `CalmTheme`（极简）。`AppIcons` 改用 Material Symbols Rounded 子集 |
| `core/contract` | **保留并小幅追加** | 新增 `SessionHost`、`PiActions`、`PiSettings`、`UiForm` 接口（§5）。现有签名不改 |

### 1.2 feature

| 模块 / 文件 | 结论 | 理由 |
|---|---|---|
| `feature/chat` 逻辑（ChatController、ChatRows、ComposerLogic、Outbox、ExtUi、SlashCompletion、PhaseLabel、ErrorText、MarkdownSegments） | **保留，提为共享层** | 流式、工具、排队、引导、中止、扩展对话框都在这里（清单 §2、§10）。两种形态共用 |
| `feature/chat/ui/*`（ChatScreen、TopBar、Composer、Messages、ChatWiring、ExtDialogs、MarkdownText） | **重写** | 拆成 `TermSurface`（新模块 `feature/term`）和 `CalmSurface`（本模块 `ui/calm/`）。旧 dsh 风格界面删除 |
| `feature/chat/ui/SuggestionText.kt`、空会话的四个建议胶囊 | **删除** | pi 没有。空会话改成 pi 页眉的信息（目录、模型、已加载资源） |
| `feature/commands`：`DraftStore`、`JsonFileStore`、`PiSessionIndex`、`SessionScanner`、`SessionTranscriptReader`、`SessionMeta`、`SkillTemplateManager`、`SkillsController`、`CommandPalette`（逻辑） | **保留** | 草稿保存（切换形态和被系统杀进程都要用）、`/resume` 列表、技能与模板（pi 原生） |
| `feature/commands`：`AutoNamer.kt`、`AutoTitle.kt` | **删除** | pi 没有自动起名，只有 `/name`。未命名会话显示第一条用户消息（与 pi 选择器一致） |
| `feature/commands/ui/SkillsScreen.kt` | **改造** | 并入动作「技能」：列表、启用开关、查看。两种形态各画一次 |
| `feature/files`：`AttachmentController`、`AttachmentSheet`（逻辑）、`Pickers`、`FileShare`、`PigoFilesProvider`、`Share`、`ShareCache`、`ShareUriPolicy` | **保留** | 图片和附件、`/export` 后分享、系统分享进来（平台入口） |
| `feature/files/output/OutputViewer.kt`、`Ansi.kt` | **保留并移到共享层** | bash 输出的 ANSI 渲染，以及截断后查看 `full_output_path`（pi 工具截断机制，清单 §4） |
| `feature/files/export/ExportSheet.kt` | **改造** | 变成动作 `export` 的 UI 形态面板（HTML / JSONL），直接调 rpc `export_html` |
| `feature/files/ui/FilesBrowserScreen.kt`、`FilePreviewScreen.kt`、`Highlight.kt`、`GitScreens.kt`、`FilesController.kt`、`FilesHost.kt` | **删除** | pi 没有文件浏览器和 Git 页（清单 §12）。文件入口改成 `@` 选择器 |
| `feature/models`：`ModelPicker`、`PickerLogic`、`ProviderScreens`、`CustomAndSettingsScreens`（服务商部分） | **保留并重画** | 用户唯一保留的设置 |
| `feature/models`：`NetworkAndCostScreen` | **删除** | 汇率不是 pi 的。代理是 pi 的 `httpProxy`，进 `/settings › 网络` |
| `feature/sessions`（SessionsScreen、DrawerGrouping、SessionFormat） | **改造** | 变成 `/resume` 会话选择器的 UI 形态，支持排序（最近 / 名称）、只看已命名、显示路径、重命名、删除、搜索，与 pi 选择器选项一一对应 |
| `feature/sessions/DrawerStyle.kt`、预览 | **删除** | dsh 视觉 |
| `feature/settings`：`SettingsScreen`、`SessionDefaults`、`DiscoveryUi`、`KeyText`、`LatestOnly` | **改造** | 设置页只剩「模型」。`SessionDefaults` 的排队方式、自动压缩、自动重试变成 pi `/settings` 的对应键 |
| `feature/settings/DshComponents.kt` | **删除** | dsh 视觉 |
| `feature/settings/RomGuide.kt`、`BackgroundGuide.kt` | **保留并移位** | 平台必需。只在「引擎被系统杀掉」的提示里出现，不放在设置页 |
| `feature/usage`：`UsageSheet`、`UsageController`（汇率部分）、`TurnSummary`、`TurnSummaryRow`、`UsageFormat`（¥） | **删除** | 用量页、每轮统计卡、汇率都不是 pi 的。统计改由页脚和 `/session` 展示（pi 原生） |
| `feature/usage`：`CompactionSummary`、`CompactionCard`、`LineDiff`、`DiffView`、`SessionTreeRows`、`SessionTreeScreen` | **保留并迁移** | 压缩摘要、`edit` 工具 diff、`/tree`（pi 原生）。逻辑进共享层，界面两种形态各画一次 |
| `feature/onboarding` | **保留并重画** | 平台必需（装 PRoot 和 pi，对应清单 §11「首次设置」） |
| 吉祥物 | **删除** | 清单 §12 说 main 上没找到资源；r3/prune 再全仓 `grep -ri "mascot\|鲸\|deepy"` 一次，清掉残留和 NOTICE 相关段 |

### 1.3 app

| 文件 | 结论 |
|---|---|
| `AppGraph`、`di/*Bindings` | **保留**。新增 `SessionHost` 单例；`ExtrasBindings` 已是真实实现（PR#13） |
| `di/WorkspaceBindings.kt` | 只装配附件、导出和 `@` 搜索 |
| `nav/PiGoNavHost.kt`、`PiGoDrawer.kt`、`WiredPages.kt` | **重写**。导航只剩四个目的地：安装 → 会话（形态由 `UiForm` 决定）→ 模型设置 → 关于。旧的设置首页、文件、用量、技能等页面删除，并入动作表 |

---

## 2. 对照清单逐项覆盖（`docs/pi-features.md` §1–§13）

### 2.0 读表说明

- **入口类型**：
  - 「⌘」：UI 形态的命令中心（顶栏 ⌘ 键，或在输入框敲 `/`），可搜索，按组列出全部动作。
  - 「常驻」：UI 形态始终可见的控件。
  - 「/x」：终端形态的斜杠命令，也会出现在 `/` 补全里。
  - 「键条」：终端形态编辑器下方的触屏按键条。
- **动作 id**：`PiActions` 里的 id，§7.3 的覆盖测试按 id 核对。
- 「同上」只用于同一节里紧挨着的上一行。
- 凡标「两者只做说明」的项，命令中心里都有一条「说明」条目，点开后解释为什么手机上不适用。所以覆盖测试仍要求它有入口，并不是漏掉。

### 2.1 清单 §1 CLI 与运行模式

| 清单条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 交互 TUI | —（形态本身） | `TermSurface` 全屏 | `CalmSurface` |
| print `-p` / `--mode json` | `help.modes` | `/help` 中说明 | ⌘「运行模式说明」。两者只做说明：手机上不需要跑一次就退出，`!` 和 rpc 已覆盖 |
| `--mode rpc` | —（底座） | 底座 | 底座 |
| `pi install/remove/update/list/config` | `packages` | `/packages` 列表（安装 / 移除 / 更新 / 配置），也可以直接 `!pi install …` | ⌘「扩展与包」页：列表、安装（输入 npm/git/本地路径）、移除、更新 |
| `pi auth print-*` | `help.auth` | `!pi auth …` | ⌘「凭据打印说明」。只做说明，不放按钮，避免把密钥显示到屏幕上 |
| `pi mcp …` | `mcp` | `/mcp` | ⌘「MCP 服务器」 |
| `--provider/--model` | `model.select` | 页脚模型段、`/model` | 常驻：顶栏模型胶囊 |
| `--api-key` | `help.apikey` | 说明 | ⌘ 说明。不做，理由同清单：密钥会进进程参数。凭据统一写 `auth.json` |
| `--system-prompt` / `--append-system-prompt` | `context.system` | `/settings › 系统提示`，编辑 `SYSTEM.md`、`APPEND_SYSTEM.md` | ⌘「系统提示」编辑页 |
| `-c/-r/--session/--session-id` | `session.resume` | `/resume` | 常驻：左上 ☰ 打开会话列表 |
| `--fork` | `session.fork` | `/fork` | 消息长按 › 从这里分叉；⌘「分叉」 |
| `--session-dir`、`--no-session`、`-n` | `settings.sessionDir`、`session.newEphemeral`、`session.name` | `/settings`、`/new --ephemeral`、`/name` | ⌘ 对应三条；常驻：点标题改名；「＋」长按 › 临时会话 |
| `--models` | `model.scoped` | `/scoped-models` | 模型面板「常用」页签（星标、拖动排序） |
| `-nt/-nbt/-t/-xt` | `settings.tools` | `/settings › 工具` | ⌘「工具开关」 |
| `--thinking` | `thinking.select` | 编辑器边框色、`/thinking` | 常驻：输入框左下思考胶囊 |
| `-e/-ne` | `packages`（扩展页签） | `/settings › 扩展` | ⌘「扩展与包 › 扩展」 |
| `--no-mcp` | `mcp`（总开关） | `/mcp` | ⌘「MCP 服务器」顶部总开关 |
| `--skill/-ns` | `skills` | `/skill:名` 补全；页眉列出技能 | ⌘「技能」页；`/` 面板技能组 |
| `--prompt-template/-np` | `templates` | `/模板名` 补全 | `/` 面板模板组；⌘「提示模板」页 |
| `--theme/--use-theme/--no-themes` | `appearance` | `/settings › 主题`（dark / light / system，以及已装主题） | ⌘「外观」：浅 / 深 / 跟随系统。终端形态主题单独选 |
| `-nc` | `settings.contextFiles` | `/settings` | ⌘「上下文文件」顶部开关 |
| `--export` | `session.export` | `/export [path]` | 会话菜单 › 导出；⌘ |
| `--list-models` | `model.select` | `/model` | 模型面板 |
| `--verbose` | `session.info` | 页眉（`quietStartup=false`） | ⌘「会话信息 › 已加载资源」 |
| `--tui-mode` | `help.tuiMode` | 只有全屏 | ⌘ 说明（手机上只有全屏） |
| `-a/-na` | `trust` | `/trust`；首次打开项目时弹信任选择器 | 首次打开带 `.pi/` 的目录时弹窗；⌘「项目信任」 |
| `--offline` | `settings.network` | `/settings › 网络 › 离线` | ⌘「pi 设置 › 网络」 |
| `-h/-v` | `help`、`about` | `/help`、页眉版本 | ⌘「帮助」「关于」 |

### 2.2 清单 §2.1 RPC 命令（33 个）

| 命令 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| `prompt` | `send` | 编辑器回车，或键条 ⏎ | 常驻：发送键 |
| `steer` | `send`（生成中） | 生成中回车 | 生成中发送键文字变「引导」 |
| `follow_up` | `send.followUp` | Alt+Enter；键条 ⇣ | 发送键长按 › 排队 |
| `abort` | `abort` | Esc；键条 Esc | 常驻：停止键 |
| `clear_queue` | `queue.dequeue` | Alt+↑；键条 ↑队 | 队列条「撤回」 |
| `new_session` | `session.new` | `/new` | 常驻：顶栏 ＋ |
| `get_state` | —（状态源） | 页脚 | 顶栏状态点、会话信息 |
| `get_messages` | —（恢复会话） | 重建消息区 | 同左 |
| `set_model` | `model.select` | `/model`、Ctrl+L、点页脚 | 模型胶囊 → 模型面板 |
| `cycle_model` | `model.cycle` | Ctrl+P；页脚模型段左右滑 | 模型胶囊左右滑；⌘「下一个模型」 |
| `get_available_models` | —（数据） | 选择器 | 同左 |
| `set_thinking_level` | `thinking.select` | `/thinking` | 思考胶囊点开 |
| `cycle_thinking_level` | `thinking.cycle` | Shift+Tab；点编辑器边框 | 思考胶囊单击循环、长按展开 |
| `get_available_thinking_levels` | —（数据） | 选择器 | 同左 |
| `set_steering_mode` / `set_follow_up_mode` | `settings.queue` | `/settings` | ⌘「pi 设置 › 队列」 |
| `compact` | `session.compact` | `/compact [说明]` | 顶栏上下文环 → 「立即压缩」（可填说明）；⌘ |
| `set_auto_compaction` | `settings.compaction` | `/settings` | ⌘「pi 设置 › 压缩」 |
| `set_auto_retry` / `abort_retry` | `settings.retry`、`retry.abort` | `/settings`；状态行 Esc 取消重试 | ⌘「pi 设置 › 重试」；重试横条的「取消」 |
| `bash` / `abort_bash` | `bash`、`bash.abort` | `!cmd` / `!!cmd`，Esc 中止 | 输入 `!` 进入命令模式（输入框变等宽，按钮变「运行」）；「＋ › 运行命令」；运行中停止键中止 |
| `get_session_stats` | `session.info` | `/session` | 上下文环点开 → 会话信息页 |
| `export_html` | `session.export` | `/export` | 会话菜单 › 导出 |
| `switch_session` | `session.resume` | `/resume` | 会话列表点选 |
| `fork` / `get_fork_messages` | `session.fork` | `/fork` 选择器 | 消息长按；⌘「分叉」打开用户消息列表 |
| `clone` | `session.clone` | `/clone` | 会话菜单 › 复制会话 |
| `get_entries` | —（增量同步） | 底座 | 底座 |
| `get_tree` | `session.tree` | `/tree` | 会话菜单 › 分支树 |
| `get_last_assistant_text` | `copy.last` | `/copy`；Ctrl+X | 助手消息长按 › 复制；⌘「复制最后回复」 |
| `set_session_name` | `session.name` | `/name` | 点标题改名 |
| `get_commands` | —（补全数据） | `/` 补全 | `/` 面板与 ⌘ 的「扩展命令」组 |
| 桥接补齐：会话列表 / 原地切分支 / 标签 / 热重载 / 登录 / 导入 | `session.resume` / `session.tree` / `tree.label` / `reload` / `auth.login` / `session.import` | 见各命令 | 见各命令。实现见 §5.4 |

### 2.3 清单 §2.2 RPC 事件

两种形态都只消费 `ChatController.state`，事件的归约只做一次（`ChatReducer`）。

| 事件 | 终端形态 | UI 形态 |
|---|---|---|
| `agent_start/end`、`agent_settled` | 状态行 loader 与计时出现或消失 | 发送键和停止键互换；顶栏状态点 |
| `turn_start/end` | 不显示，只计入页脚统计 | 不显示，只计入会话信息 |
| `message_start/update/end` | 流式 Markdown 块（等宽） | 流式正文（无气泡） |
| thinking 增量 | `thinkingText` 色思考块，Ctrl+T 或点击折叠 | 「思考了 N 秒」折叠行 |
| toolcall 增量、`tool_execution_*` | 三态底色工具块（pending/success/error） | 过程行：状态点、动词、目标、时长，可展开 |
| `queue_update` | 编辑器上方队列列表 | 输入框上方队列条 |
| `entry_appended` | `customMessage` 色块 | 灰色系统行 |
| `session_info_changed` | 页脚会话名 | 顶栏标题 |
| `thinking_level_changed` | 编辑器边框色 | 思考胶囊 |
| `compaction_start/end` | 「压缩中…」加摘要块 | 压缩卡（可展开摘要：目标、约束、进度……） |
| `auto_retry_*`、`summarization_retry_*` | 状态行倒计时「重试 2/3」 | 重试横条加取消 |
| `bash_execution_update` | `!` 输出实时滚动（ANSI） | 等宽输出块 |
| `extension_error` | 红色提示行 | 吐司加 ⌘「诊断」 |
| `extension_ui_request`（select/confirm/input/editor/notify/setStatus/setWidget/setTitle/set_editor_text） | pi 风格弹层；setStatus 显示在页脚；setWidget 显示在编辑器上方 | 底部面板；状态显示在顶栏副标题；widget 显示在输入框上方 |

### 2.4 清单 §3 斜杠命令

UI 形态里，下面每一条都是 ⌘ 命令中心的一项（按组：会话 / 模型 / pi 设置 / 扩展与资源 / 帮助）。在输入框敲 `/` 时，同一份列表以补全面板的形式出现。终端形态是 pi 原样的斜杠命令。

| 命令 | 动作 id | 终端形态 | UI 形态（除 ⌘ 外的快捷入口） |
|---|---|---|---|
| `/settings` | `settings` | pi 风格 SettingsList 弹层（按清单 §8 的分组） | ⌘「pi 设置」，按同样分组列出的原生列表。**不放进设置页**，见 §6 |
| `/model` | `model.select` | 可搜索列表弹层 | 模型胶囊 |
| `/thinking` | `thinking.select` | 选择器 | 思考胶囊 |
| `/scoped-models` | `model.scoped` | 多选列表；触屏拖动排序；Ctrl+S 映射为「保存」 | 模型面板「常用」页签 |
| `/login` | `auth.login` | 服务商选择器 → 登录对话框（显示 URL 和设备码，可粘贴回调） | 设置 › 模型 › 服务商 › 登录 |
| `/logout` | `auth.logout` | 选择器 | 服务商页「退出登录」 |
| `/llama` | `ext.llama` | 由扩展 UI 协议画 | ⌘「llama.cpp」（同一协议画成底部面板） |
| `/new` | `session.new` | 命令 | ＋ |
| `/resume` | `session.resume` | 会话选择器（搜索；键条映射 Ctrl+P 显示路径、Ctrl+S 排序、Ctrl+N 只看已命名、Ctrl+R 重命名、Ctrl+D 删除） | ☰ 会话列表（同样五个选项在列表菜单和长按里） |
| `/name` | `session.name` | 命令 | 点标题 |
| `/session` | `session.info` | 文本块输出（路径、ID、消息数、token、费用） | 上下文环 → 会话信息页 |
| `/tree` | `session.tree` | 树选择器（筛选、标签、折叠，快捷键映射成工具条按钮） | 会话菜单 › 分支树 |
| `/fork` | `session.fork` | 用户消息选择器 | 消息长按 |
| `/clone` | `session.clone` | 命令 | 会话菜单 |
| `/compact` | `session.compact` | 命令 | 上下文环 |
| `/import` | `session.import` | 命令加系统文件选择器（SAF） | 会话列表菜单 › 导入 |
| `/copy` | `copy.last` | 命令 | 长按复制 |
| `/export` | `session.export` | 命令 | 会话菜单 › 导出 |
| `/share` | `session.share` | 命令（需要 gh 或 Radius 登录，未登录时提示） | 会话菜单 › 分享链接 |
| `/bug` | `bug` | 命令 | ⌘「报告问题」 |
| `/trust` | `trust` | 命令 | ⌘「项目信任」；首次打开项目时弹窗 |
| `/mcp` | `mcp` | 扩展 UI 弹层 | ⌘「MCP 服务器」 |
| `/reload` | `reload` | 命令 | ⌘「重新加载资源」；改完 pi 设置后自动执行 |
| `/hotkeys` | `hotkeys` | 显示快捷键与触屏映射表（§3.4） | ⌘「手势与快捷键」 |
| `/changelog` | `changelog` | 显示包内 CHANGELOG.md | ⌘「更新日志」 |
| `/debug` | `debug` | 写 pi-debug.log | ⌘「诊断」：导出日志 |
| `/quit` | `engine.stop` | 停止引擎 | ⌘「停止引擎」 |
| `/模板名` | `templates` | 补全并显示 argument-hint | `/` 面板 → 参数输入框 |
| `/skill:名` | `skills` | 补全 | `/` 面板技能组 |
| 扩展命令 | `ext.command:<名>`（动态） | 补全 | `/` 面板与 ⌘「扩展命令」组 |
| 本方案新增本地命令 `/ui`、`/term` | `form.toggle` | 切到 UI 形态 | 切到终端形态（另有常驻按钮，§5） |

彩蛋（`/arminsayshi` 等）不做，与清单一致。

### 2.5 清单 §4 内置工具

两种形态都由 `ToolRenderers` 按工具名分派。数据层只有一份（`ChatRow.Tool`），各形态各有一个渲染器表。

| 工具 | 终端形态 | UI 形态 |
|---|---|---|
| `read` | `read path:范围` 标题加折叠输出；图片结果内联显示 | 过程行「读取 文件名」，展开看内容和图片 |
| `write` | 标题加代码预览（语法色） | 「写入 文件名」，展开看代码 |
| `edit` | 红绿 diff（`toolDiff*` token，复用 `LineDiff`） | 「编辑 文件名 +3 −1」，展开看 diff |
| `bash` | `$ cmd` 加流式 ANSI 输出；截断时附「完整输出」链接，打开 `OutputViewer` 读 `full_output_path` | 「运行 命令」加等宽输出块，有同样的完整输出链接 |
| `grep` / `find` / `ls` | 标题加列表 | 「搜索 / 查找 / 列出」过程行 |
| `powershell` | 不适用（Windows） | 不适用。⌘「工具开关」里显示为灰色并说明原因 |
| `codemode` | 脚本块加输出 | 「脚本」过程行，展开看代码 |
| `tool_search` | 普通工具行 | 普通过程行 |
| MCP 工具 | 普通工具行，前面加 `mcp__服务器` | 过程行，前面加服务器名 |

开关这些工具的入口统一在动作 `settings.tools`（§2.1）。

### 2.6 清单 §5 会话

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 存储位置 | `session.info` | `/session` 显示路径 | 会话信息页 |
| 格式（v3 JSONL 条目树） | —（底座） | — | — |
| 继续最近 / 恢复 | `session.resume` | 冷启动回到上次会话（ResumePolicy）；`/resume` | 同左；☰ |
| 新建 | `session.new` | `/new` | ＋ |
| 分叉 / 复制 | `session.fork` / `session.clone` | `/fork`、`/clone` | 长按、会话菜单 |
| 树导航（原地切换分支、选中的用户消息原文回到编辑器、分支摘要提示） | `session.tree` | `/tree`；双击 Esc（遵循 `doubleEscapeAction`） | 分支树页：点节点切换，弹「是否摘要离开的分支」。原文放回**共享草稿**，所以两种形态都能看到 |
| 树筛选 | `tree.filter` | 工具条 5 个筛选键 | 树页顶部筛选胶囊 |
| 标签 | `tree.label` | 树中 Shift+L 对应「标签」键；Shift+T 对应「时间」键 | 节点长按 › 打标签 / 显示时间 |
| 会话名 | `session.name` | 页脚 | 标题 |
| 删除会话 | `session.delete` | 选择器 Ctrl+D 键 | 会话列表左滑删除，5 秒内可撤销 |
| 选择器选项 | `session.resume` | 工具条 | 列表菜单 |
| 压缩 | `session.compact` | 摘要块 | 压缩卡加上下文环 |
| 导出 | `session.export` | `/export` | 会话菜单 |
| 分享 | `session.share` | `/share` | 会话菜单 |
| 导入 | `session.import` | `/import` | 列表菜单 |
| 临时会话 | `session.newEphemeral` | `/new --ephemeral` | ＋ 长按 |
| 工作目录 | `session.cwd` | 页脚 `~/路径 (分支)`；目录不存在时用 pi 的询问 | 顶栏副标题显示目录；点开可「在其他目录新建会话」（目录选择器只列目录，不是文件浏览器） |

### 2.7 清单 §6 模型与服务商

全部见 §6「模型配置」。逐项对应如下：

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 内置服务商（42 个加 radius、llama） | `auth.login` | `/login` 列表 | 设置 › 模型 › 服务商 |
| OAuth / 订阅登录 | `auth.login` | 登录对话框 | 服务商页 › 用账号登录 |
| API key 来源与优先级（含 `!命令` key、`env`） | `auth.login` | `/login › API key` | 服务商页「Key」，高级里可写 `!命令` 和额外 env |
| 自定义服务商 `models.json` | `provider.custom` | `/settings › 自定义服务商` 表单 | 设置 › 模型 › 添加自定义服务商（发现模型、测试连接） |
| 模型切换 | `model.select` | `/model` | 胶囊 |
| 循环切换 | `model.cycle` | 页脚滑动 | 胶囊滑动 |
| 作用域模型（可带每模型思考档） | `model.scoped` | `/scoped-models` | 「常用」页签，每行可选固定思考档 |
| 思考强度（7 档、budgets、modelThinkingLevels） | `thinking.select` | 边框色 | 胶囊；预算在设置 › 模型 › 高级 |
| 默认模型 | `model.setDefault` | 选择器「设为默认」 | 模型面板行长按 › 设为默认 |
| 模型目录更新 | `model.refreshCatalog` | `!pi update --models` | 模型面板右上「刷新」 |
| llama.cpp | `ext.llama` | `/llama` | ⌘ |
| 虚拟模型 / 分类器 / 图像模型 | —（随扩展） | 随扩展出现 | 随扩展出现在模型列表与 ⌘ |
