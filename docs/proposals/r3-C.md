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

### 2.8 清单 §7 技能、扩展、模板、上下文文件、主题、MCP、包

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 技能 | `skills` | `/skill:名`；页眉列出 | ⌘「技能」：列表、启用开关、查看 SKILL.md、从文件导入（SAF） |
| 提示模板 | `templates` | 补全加参数提示 | `/` 面板；选中后弹参数框（按 `$1`、`$@` 生成字段） |
| 扩展 | `packages`（扩展页签） | 扩展弹层、状态、组件按 TUI 样式画 | 同一协议画成原生面板 |
| 内置扩展（llama、codemode、tool-search、mcp） | `packages` | `/settings › 扩展` | ⌘「扩展与包」里的开关 |
| 上下文文件 | `settings.contextFiles` | 页眉列出；`/settings` | ⌘「上下文文件」：列出已加载文件，可编辑全局 AGENTS.md；还有 SYSTEM.md、APPEND_SYSTEM.md（与 `context.system` 同页） |
| 主题 | `appearance` | pi 的 dark / light 加已装主题 JSON | 极简主题：浅、深、跟随系统 |
| MCP | `mcp` | `/mcp` | ⌘「MCP 服务器」：列表、添加（stdio / HTTP）、登录、重连、开关 |
| 包 | `packages` | `/packages`、`!pi install` | ⌘「扩展与包」 |
| 项目信任 | `trust` | `/trust` | 弹窗加 ⌘ |
| 键位文件 `keybindings.json` | `hotkeys` | 外接键盘按文件生效 | ⌘「手势与快捷键」（只读表，加「编辑 keybindings.json」入口） |

### 2.9 清单 §8 `settings.json` 全部键、§8.1 环境变量

**不同意清单的一处安排。** 清单建议把非模型键放进「设置页 › 高级」。用户说「设置里只保留配置模型」，所以本方案的设置页**只有模型**。这些键全部归到 pi 自己的 `/settings` 功能里：
- UI 形态：⌘「pi 设置」，列表结构和分组与 pi 的 SettingsList 相同。
- 终端形态：`/settings`。

pi 的设置本来就是一个斜杠命令而不是 App 设置，所以这样放既不少功能，也符合用户原话。实现上两种形态共用 `PiSettings`（读写 `~/.pi/agent/settings.json` 与项目 `.pi/settings.json`，写完调 `reload`）。

| 分组 | 终端 | UI（⌘「pi 设置」中的分组） | 备注 |
|---|---|---|---|
| 模型键 | `/settings › 模型` | **设置页 › 模型**（唯一的设置页） | 两边写同一个文件 |
| 显示（hideThinkingBlock、showCacheMissNotices、quietStartup、theme） | ✔ | 显示 | `theme` 只影响终端形态；UI 形态的外观单独在 `appearance` |
| 缓存（cacheWarming） | ✔ | 缓存 | |
| 队列（steeringMode、followUpMode） | ✔ | 队列 | 同时调 rpc `set_*_mode` |
| 编辑器（externalEditor、doubleEscapeAction、autocompleteMaxVisible、editorPaddingX、showHardwareCursor） | ✔ | 编辑器 | `externalEditor`、`showHardwareCursor` 标「手机不适用」，只读显示 |
| 树（treeFilterMode、branchSummary.*） | ✔ | 会话树 | |
| 信任（defaultProjectTrust） | ✔ | 安全 | 只写全局 |
| 工具（defaultTools、codemode.*） | ✔ | 工具 | |
| 会话（sessionDir、sessionShareDestination） | ✔ | 会话 | |
| 压缩（compaction.*） | ✔ | 压缩 | 同时调 `set_auto_compaction` |
| 全屏 TUI（tuiMode、fullscreen*、outputPad） | 终端形态内部用（滚动条、选中即复制、outputPad） | 显示为「仅终端形态」，可在此修改 | 改了下次进终端形态就生效 |
| 终端（terminal.*） | `imageWidthCells`、`showImages` 用于内联图片 | 同上「仅终端形态」 | |
| 图片（images.*） | ✔ | 图片 | |
| Markdown（codeBlockIndent、mermaid） | 缩进；Mermaid 用字符画 | Markdown | UI 形态 Mermaid 先显示代码块加「预览」（WebView 离线渲染，P2）；不同意清单的「必须渲染成图」，理由：要打包 mermaid.js（约 2 MB）和 WebView，P2 再做 |
| 网络（transport、httpProxy、超时） | ✔ | 网络 | 代理保留（国内需要） |
| 重试（retry.*） | ✔ | 重试 | |
| Shell（shellPath、shellCommandPrefix） | ✔ | Shell | |
| 包与资源（npmCommand…、enableSkillCommands） | ✔ | 扩展与资源 | |
| 其他（collapseChangelog、遥测、analytics、warnings.*） | ✔ | 隐私与其他 | 遥测默认关 |
| 环境变量：服务商 key | 不显示 | 不显示 | 走 auth.json |
| 环境变量：进程（PI_OFFLINE、PI_TELEMETRY 等） | 由 `EngineConfig` 注入；在 `/settings › 网络/隐私` 呈现 | 同左 | 改了需重启引擎，界面明确提示「将重启引擎」，这是唯一会重启引擎的路径 |
| 环境变量：渲染、bash 会话变量 | 不适用 / pi 自管 | 同左 | ⌘「帮助」里说明 |

### 2.10 清单 §9 快捷键（触屏映射见 §3.4）

| 动作 | 终端形态 | UI 形态 |
|---|---|---|
| 中断 | Esc 键 | 停止键 |
| 清空 / 退出 | ^C：第一次清空，第二次提示停止引擎 | 输入框里的 × |
| 挂起 | 不适用（说明） | 不适用（说明，`help.suspend`） |
| 外部编辑器 | ⤢：全屏编辑器 | 输入框「展开」 |
| 粘贴图片 | 长按编辑器 › 粘贴图片；键条 📎 | ＋ › 图片 / 相机 / 剪贴板 |
| 换行 | 软键盘回车 = 换行；键条 ⏎ = 发送（可在 pi 设置 › 编辑器里互换） | 回车换行；发送键发送 |
| 补全 | 键条 Tab | 点选 |
| 排队 | 键条 ⇣ | 发送键长按 |
| 撤回队列 | 键条 ↑队 | 队列条撤回 |
| 复制 | 长按消息 | 长按消息 |
| 选模型 / 循环模型 | 点、滑页脚模型段 | 点、滑模型胶囊 |
| 循环思考 / 折叠思考 | 点边框 / 点思考块 | 点胶囊 / 点思考行 |
| 展开工具 | 点工具块；键条 ⌃O 全部展开 | 点过程行；⌘「展开全部工具」 |
| 保存默认 | 选择器「设为默认」 | 同左 |
| 编辑器光标与删除 | 外接键盘照 pi；触屏交给输入法 | 输入法 |
| 历史 | 键条 ↑↓ 翻历史提示 | 输入框为空时上滑，弹出历史提示列表 |
| 消息区滚动、跳转、搜索 | 手势；键条 ⌕ 打开转录搜索；右侧滚动条；键条「⇞⇟」跳到上一个或下一个提示 | 顶栏 ⋯ › 搜索本会话；回到底部键；长按回到底部键可跳到上一条提示 |
| 会话选择器动作 | 工具条 | 列表菜单和长按 |
| 树动作 | 工具条 | 筛选胶囊和长按 |
| 作用域模型动作 | 工具条加拖动 | 同左 |
| 未绑定的会话动作 | 外接键盘可在 keybindings.json 绑定 | ⌘ 里都有 |

### 2.11 清单 §10 输入语法

| 语法 | 终端形态 | UI 形态 |
|---|---|---|
| Enter（生成中引导）、Alt+Enter（排队）、Esc（中止，排队文字回编辑器） | 照 pi | 发送键「引导」；长按排队；停止后排队文字回到共享草稿 |
| `/命令` | 补全浮层（最多 `autocompleteMaxVisible` 条） | `/` 面板（与 ⌘ 同源） |
| `@文件` | `@` 弹出模糊文件搜索，Tab 补全 | 输入 `@` 或「＋ › 引用文件」，弹出搜索面板（`RootfsWorkspace.search`，按 cwd 用 `find`/`rg`）。只做搜索，不做浏览器。发送时按 pi 语义展开为路径引用 |
| 图片 | 内联缩略图 | 输入框里的缩略图条 |
| `!cmd` / `!!cmd` | 编辑器边框变 `bashMode` 色 | 输入框变等宽，提示「将运行命令」或「不发给模型」 |
| 双击 Esc | 照 `doubleEscapeAction` | 不做手势，入口在会话菜单（分支树、分叉） |
| 模板参数 | argument-hint | 参数框 |

### 2.12 清单 §11 TUI 元素

终端形态逐个还原（§3）。UI 形态对应关系：

| 元素 | UI 形态 |
|---|---|
| 页眉 | 空会话首屏：目录 · 模型，一行；「已加载 N 个上下文文件、M 个技能」可点开（`session.info`） |
| 消息区 | 用户消息：右对齐浅色圆角块；助手消息：无框正文 |
| 思考块 | 折叠行 |
| 工具调用块 | 过程行 |
| bash 块 | 等宽输出块 |
| 压缩 / 分支摘要块 | 摘要卡 |
| 技能调用消息 | 一行「使用技能 名称」 |
| 编辑器 | 圆角输入框，配 ＋、思考胶囊和发送键 |
| 自动补全 | `/` 面板、`@` 面板 |
| 队列显示 | 队列条 |
| 状态行 | 顶栏副标题（loader 计时、重试、扩展 setStatus） |
| 页脚 | 顶栏只放模型胶囊和上下文环；完整统计（↑↓、缓存读写、命中率、费用、上下文%/窗口）在会话信息页 |
| 选择器弹层 | 底部面板（统一组件 `CalmSheetList`） |
| 登录对话框 | 浏览器加回调自动完成；备选为粘贴代码 |
| 首次设置 | 安装引导页（`feature/onboarding` 重画） |
| 转录搜索 | ⋯ › 搜索本会话（高亮命中，上下跳转） |
| Mermaid | 代码块加预览（P2，见 §2.9） |
| 彩蛋 | 不做（与清单一致） |
| 配色 | UI 形态用自己的极简色板；终端形态原样打包 pi 的 dark.json、light.json（MIT，NOTICE 记一行） |

### 2.13 清单 §12 候选删除项

结论见 §1。与清单不同的两处：
- **回收站**：删除，改成 5 秒撤销。
- **任务完成通知**：保留但默认关闭。

清单里其他各条全部采纳。

### 2.14 清单 §13 无缝切换

采纳清单的思路（单进程，只换渲染层），细化在 §5。在清单基础上补三点：
1. **共享草稿。** 草稿从 Composable 里的 `TextFieldValue` 上移到 `SessionHost.draft: MutableStateFlow<DraftState>`，包含文字、光标、选区、图片、`@` 引用和 `!` 模式。
2. **共享弹层。** 用 `SessionHost.overlay` 栈，避免切换时扩展对话框丢失。
3. **可测的不中断判据**，见 §7.4。

---

## 3. 终端形态（`feature/term`，`TermSurface`）

### 3.1 渲染方式：用 Compose 画字符网格，不跑第二个 pi

- **不嵌真终端、不开第二个 `pi`。** 原因是两个进程意味着两份状态，与「无缝切换」矛盾（清单 §0.1）。
- **按列宽排版的 Compose 文本。**
  - 全局等宽字体 JetBrains Mono（OFL，仓库里已有，`core/ui/res/font`），开启 `fontFeatureSettings="calt 0"` 关掉连字，保证一字一格。
  - 启动时用 `TextMeasurer` 量出单个字符宽度，算出 `cols = floor(可用宽度 / 字符宽度)`。所有边框（`─ │ ╭ ╮`）、页脚左右对齐、补全浮层宽度都按 `cols` 计算，效果与 pi 在 `cols` 列终端里一样。
  - CJK 字符按东亚宽度占两列，用 `wcwidth` 表。表从 pi 依赖的 `string-width` 规则移植，记入 NOTICE。
- **结构。** 一个 `LazyColumn` 加三个固定区：
  ```
  ┌ 页眉（可滚走，含 pi 版本、按键提示、已加载上下文/技能/扩展/模型范围）
  │ 消息区：TermBlock 列表（User / Assistant / Thinking / Tool / Bash / Summary / Custom / Error）
  ├ 状态行：loader ⠋ + 计时 / 重试倒计时 / setStatus
  ├ 队列列表 + setWidget
  ├ 编辑器（带边框，多行，边框色 = 思考档位；bash 模式变 bashMode 色）
  ├ 补全浮层（编辑器上方，最多 autocompleteMaxVisible 行）
  ├ 页脚（两行）：~/proj (main) • 会话名      ↑12k ↓3.4k R80k W2k CH71% $0.042  38%/200k  (anthropic) claude-sonnet-4.5 • high
  └ 触屏按键条（可隐藏；外接键盘时自动隐藏）
  ```
- **每个块是一个 `AnnotatedString`。** 由 `TermFormatter`（纯函数，可单测）把 `ChatRow` 转成「若干行，每行是带 token 样式的片段」。Markdown 走 pi 的风格：标题 `mdHeading` 加粗，代码块缩进 `codeBlockIndent`，列表符号 `mdListBullet`，链接下划线。语法高亮用现有的高亮器，改为输出 `syntax*` token。
- **ANSI。** bash 输出用 `Ansi.kt`（迁移到共享层）解析成片段。
- **选中与复制。** `SelectionContainer` 包住消息区；`fullscreenCopyOnSelect=true` 时选中即复制（与 pi 一致）。

### 3.2 配色

- 把 pi 的 `theme/dark.json`、`light.json` **原样**放进 `feature/term/src/main/assets/pi-theme/`。
- `PiThemeLoader` 解析 `vars` 加 56 个 `colors`，把 okhsl 换算成 sRGB，得到 `PiTokens`（data class，56 个 `Color`）。
- 主题跟随 `/settings › theme`：system 对应安卓深浅色，也支持 dark、light，以及用户装在 `~/.pi/agent/themes/` 下的 JSON。
- 页面背景用终端默认背景：dark 为 `#1e1e2e` 一类，以 token 值为准。

### 3.3 编辑器与补全

- 编辑器是 `BasicTextField`，绑定 `SessionHost.draft`。边框用 `drawBehind` 画 pi 的圆角框，左上角显示 `thinkingXxx` 色的档位文字。
- 首字符是 `/` 时，按 `SlashCompletion` 弹补全，候选来自 `PiActions` 的斜杠别名加 `get_commands`。
- `@` 弹文件模糊搜索；`!`/`!!` 时边框变色。
- 补全浮层的选中行用 `selectedBg`。上下键或点击选择，Tab 补全。
- 历史：编辑器为空时，键条 ↑↓ 翻 `SessionHost.promptHistory`（本会话的用户消息，加上全局最近 100 条）。

### 3.4 快捷键 → 触屏映射（键条与手势）

键条一行，可横滑，按钮为等宽字样。顺序：

`Esc  Tab  ⇣排队  ↑队  ^C  ⌃O  ⌕  ⇞  ⇟  ⤢  /  @  !  📎  ⌂UI`

| pi 键 | 触屏 |
|---|---|
| Esc / 双击 Esc | Esc 键 / 连点两下 |
| Enter / Alt+Enter | 键条右端 ⏎ / ⇣ |
| Alt+↑ | ↑队 |
| Ctrl+C | ^C |
| Ctrl+O | 点工具块（单个）；⌃O（全部） |
| Ctrl+T | 点思考块 |
| Shift+Tab | 点编辑器边框 |
| Ctrl+L / Ctrl+P | 点页脚模型段 / 在页脚模型段左右滑 |
| Ctrl+G | ⤢ |
| Ctrl+V（图片） | 📎，或长按编辑器 › 粘贴图片 |
| Ctrl+X | 长按助手块 › 复制 |
| Ctrl+Shift+F | ⌕ |
| Ctrl+Shift+↑/↓ | ⇞ / ⇟ |
| 选择器内 Ctrl+S/N/R/D/P 等 | 选择器底部提示行变成可点的「键帽」（pi 的提示行本来就列出这些键，直接让它们可点） |

**外接键盘**：`onPreviewKeyEvent` 读取 `~/.pi/agent/keybindings.json`（缺省用 pi 默认表），完全照 pi 的键位处理。

### 3.5 工具调用与思考块

- **工具块**：pending、success、error 三种底色（`toolPendingBg/SuccessBg/ErrorBg`），标题用 `toolTitle`（如 `read src/a.kt:1-80`），输出用 `toolOutput`。默认折叠到前 N 行加「… 12 more lines (ctrl+o)」，与 pi 的文案一致（翻成中文时保留键名）。
- **`edit`**：显示 diff，用 `toolDiffAdded/Removed/Context` 三色。
- **思考块**：`thinkingText` 色，斜体；`hideThinkingBlock` 时只显示一行「Thinking…」。
- **选择器弹层**：统一做一个 `TermSelectList` 组件（顶部搜索、列表、底部键帽提示行），模型、思考、作用域模型、会话、树、分叉、设置、主题、OAuth、信任、扩展 UI 的 select 全部复用它。

---

## 4. UI 形态（`CalmSurface`）：极简

### 4.1 原则

- **一页为主。** 聊天页就是全部；其余功能在三个面板里：☰ 会话列表、⌘ 命令中心、模型面板。
- **无玻璃、无渐变、无吉祥物。** 大面积留白，正文主导，只有一个强调色。
- **信息按需出现。** 统计、路径、已加载资源都收在上下文环和会话信息页，不常驻。

### 4.2 配色与字体

| 用途 | 浅色 | 深色 |
|---|---|---|
| 背景 | `#FAFAF8` | `#111111` |
| 表面（输入框、面板） | `#FFFFFF`，加 1dp 描边 `#E8E8E4` | `#1A1A1A`，描边 `#2A2A2A` |
| 正文 | `#1A1A1A` | `#ECECEC` |
| 次要 | `#6B6B6B` | `#9A9A9A` |
| 强调（唯一） | pi 的 accent（violet）偏中性版本 `#6D5BD0` | `#9C8CFF` |
| 状态 | 成功 `#2F9E6B` / 错误 `#D0453B` / 进行中 = 强调色 | 同色系提亮 |

- **字体**：正文用系统 Sans（中文跟随系统），16sp，行高 1.55；代码与命令用 JetBrains Mono 14sp。
- **形状**：圆角 12dp（输入框 20dp）。

### 4.3 页面结构（线框）

```
┌──────────────────────────────────────┐
│ ☰   重构登录流程 ▾            ⌘  ⇄   │  ← 标题可点改名；⇄ = 切到终端形态
│     ~/proj · 生成中 0:12             │  ← 副标题：目录 / 状态 / setStatus
├──────────────────────────────────────┤
│                    帮我把 auth 拆出来 │  ← 用户消息（右，浅色块）
│                                      │
│ ▸ 思考了 6 秒                        │
│ ● 读取 src/auth/Login.kt      0.2s   │  ← 过程行（点开看内容）
│ ● 编辑 Login.kt  +24 −9       0.1s   │
│ ◌ 运行 ./gradlew test               │  ← 进行中
│ 我把 Auth 拆成了三个文件……           │  ← 助手正文，流式
│                                      │
│ ┌ 已压缩 150k → 32k ─────────── ▸ ┐  │
├──────────────────────────────────────┤
│ 排队 1 条：再跑一次 lint      撤回   │  ← 队列条（有才出现）
│ ┌──────────────────────────────────┐ │
│ │ 输入消息，/ 命令，@ 文件，! 运行  │ │
│ │ [img]                            │ │
│ │ ＋   ◐ high   sonnet-4.5 ◯38%  ■ │ │  ← ＋附件 · 思考胶囊 · 模型胶囊 · 上下文环 · 发送/停止
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

**⌘ 命令中心**（底部面板，全高）：

```
┌ ⌘ 搜索 pi 功能…                  ┐
│ 会话   新建 · 恢复 · 分叉 · 复制 · 分支树 · 压缩 · 导出 · 分享 · 导入 · 改名 · 会话信息 · 临时会话
│ 模型   选择模型 · 下一个 · 常用模型 · 思考强度 · 设为默认 · 刷新目录 · 登录 · 退出登录
│ pi 设置 显示 · 队列 · 编辑器 · 会话树 · 工具 · 压缩 · 重试 · 网络 · 图片 · Shell · 安全 · 隐私
│ 资源   技能 · 提示模板 · 扩展与包 · MCP · 上下文文件 · 系统提示 · 项目信任 · 重新加载
│ 扩展命令（get_commands 动态）
│ 帮助   快捷键与手势 · 更新日志 · 报告问题 · 诊断 · 关于 · 停止引擎 · 说明类条目
└────────────────────────────────────┘
```

- 每一项都是一条 `PiAction`，`testTag("action:<id>")`。
- 在输入框里敲 `/` 时，同一个列表变成内联补全面板，只按斜杠别名过滤。

### 4.4 关键组件

- `CalmComposer`（共享草稿）、`ProcessRow`（工具、思考、bash 共用一行骨架）、`SummaryCard`（压缩、分支摘要）、`CalmSheetList`（全部选择器的统一底部面板）、`ContextRing`、`ModelPill`、`ThinkingPill`、`QueueBar`、`ExtUiSheet`（扩展对话框）。
- 长按消息菜单：复制、复制 Markdown、从这里分叉（用户消息）、在分支树中定位。

### 4.5 动效原则

- 只做三类动效，统一 180ms、`FastOutSlowIn`：
  1. 新块淡入加 4dp 上移；
  2. 面板滑入；
  3. 发送键与停止键的形变。
- 流式文字不做逐字动画，直接追加，避免掉帧。
- 切换形态时做 120ms 交叉淡化。**只淡化渲染层，不重建状态**（§5）。

---

## 5. 两种形态的切换与共享层

### 5.1 分层

```
                ┌───────────── 视图层（可互换，零业务状态）─────────────┐
                │  TermSurface (feature/term)    CalmSurface (feature/chat/ui/calm) │
                └──────────────▲───────────────────────▲─────────────┘
                               │ state: StateFlow       │ actions: PiActions
┌──────────────────────────── SessionHost（app 单例，与进程同寿）────────────────────────────┐
│ engine: EngineClient + extras   chat: ChatController (ChatScreenState)                     │
│ draft: MutableStateFlow<DraftState>  (文字/光标/图片/@引用/!模式；DraftStore 持久化)       │
│ overlay: OverlayStack  (选择器/扩展对话框/确认框的「模型」，不是 Composable)               │
│ form: MutableStateFlow<UiForm>  (TERM | CALM，存 DataStore)                                │
│ promptHistory, scrollAnchor: MutableStateFlow<String?>  (按 ChatItem.key 记录阅读位置)     │
│ PiActions (动作表)   PiSettings (settings.json)   SessionCatalog (会话列表/导入/删除撤销) │
└────────────────────────────────────────────────────────────────────────────────────────────┘
                               │
                    PiEngine ── pi --mode rpc（唯一进程） ── pigo-bridge.ts（-e 加载）
```

### 5.2 切换

- **入口**：
  - UI 形态顶栏右端 ⇄；
  - 终端形态键条最右「⌂UI」，或页脚右端的 `[UI]` 键帽；
  - 本地命令 `/ui`、`/term`；
  - 外接键盘的 `Ctrl+Alt+T`（pi 没有占用这个组合）。
- **实现**：
  ```kotlin
  val form by host.form.collectAsState()
  Crossfade(form, animationSpec = tween(120)) { f ->
      when (f) { UiForm.TERM -> TermSurface(host); UiForm.CALM -> CalmSurface(host) }
  }
  ```
  `TermSurface` 和 `CalmSurface` 只订阅 `host` 里的 Flow，并调用 `host.actions`。切换只是让其中一个离开组合：
  - 引擎、控制器、Flow 都在 `SessionHost` 里，不受影响；
  - 正在流式输出的消息继续写进 `ChatReducer`，新形态一订阅就看到最新内容；
  - 草稿不在 Composable 里，所以不会丢；
  - IME 焦点在切换后重新请求，光标位置取 `draft.selection`。
- **弹层不丢**：两个形态都按 `host.overlay.top` 渲染（终端画 `TermSelectList`，UI 画 `CalmSheetList`）。扩展 `extension_ui_request` 的 id 与超时由 `ChatController` 持有，切换期间继续计时，不会被当成取消。
- **阅读位置**：各形态离开时，把当前首个可见块的 `ChatItem.key` 写入 `scrollAnchor`；进入时 `scrollToItem(indexOf(anchor))`。正在贴底跟随的保持贴底。
- **生命周期**：`SessionHost` 由 `AppGraph` 持有。旋转、切深色、切形态都不重建；进程被系统杀掉后按 `ResumePolicy` 加 `DraftStore` 恢复。

### 5.3 `PiActions` 动作表（覆盖清单的机制）

```kotlin
data class PiAction(
  val id: String,                 // "session.fork"
  val title: String,              // "从消息分叉"
  val group: ActionGroup,         // SESSION / MODEL / SETTINGS / RESOURCES / EXT / HELP
  val slash: List<String>,        // ["fork"]
  val featureRefs: List<String>,  // 对应清单条目，如 ["§2.1 fork", "§3 /fork", "§5 分叉 / 复制"]
  val ui: UiEntry,                // Command(⌘) | Persistent(tag) | LongPress | Explain(text)
  val term: TermEntry,            // Slash | KeyStrip(key) | Footer(segment) | Explain(text)
  val run: suspend ActionScope.(args: String?) -> ActionResult, // 打开 overlay / 调 rpc / 写文件
)
```

- `run` 只写一次，两种形态共用。
- `ActionResult.Overlay(model)` 把一个与形态无关的面板模型（列表、表单、确认、文本）压进 `overlay` 栈，由各形态自己画。所以「pi 设置」「技能」「MCP」「树」等页面的**逻辑只写一份**。

### 5.4 补齐 RPC 缺口：`pigo-bridge.ts`

随 App 打包 `assets/pi/pigo-bridge.ts`，由 `EngineConfig` 用 `-e` 加载。它注册以下隐藏命令，结果通过 `extension_ui_request` 的 `notify`/`setStatus` 加 JSON 前缀回传：
- `/pigo:tree-nav <entryId> [--summarize]` → `ctx.navigateTree`
- `/pigo:label <entryId> <text>` → `pi.setLabel`
- `/pigo:reload` → `ctx.reload()`
- `/pigo:switch <path>` → `ctx.switchSession`

文件型功能（`settings.json`、`auth.json`、`models.json`、`mcp.json`、`keybindings.json`、SYSTEM/AGENTS 文件、会话导入、信任）由 Kotlin 直接读写，写完调 `/pigo:reload`。`/share`、`/bug`、`/changelog`、包管理用 `GuestRunner` 执行 `pi` CLI。

---

## 6. 模型配置（唯一的设置项）

**入口**：设置页（☰ 底部的齿轮）只有一项「模型」；另外从模型胶囊进入的模型面板底部有「管理服务商」。

```
设置 › 模型
┌────────────────────────────────────┐
│ 默认模型   claude-sonnet-4.5 · high ›│  ← defaultProvider/Model/ThinkingLevel
│ 常用模型   4 个 ›                    │  ← enabledModels（=/scoped-models）
├ 已连接 ─────────────────────────────┤
│ ● Anthropic   账号 · Claude Max   › │
│ ● DeepSeek    Key ···a1f3          › │
├ 全部服务商（42）  🔍 ───────────────┤
│ ○ Amazon Bedrock         区域/Key › │
│ ○ Azure OpenAI           资源/Key › │
│ …                                   │
├ 自定义 ─────────────────────────────┤
│ ＋ 添加自定义服务商（OpenAI/Anthropic/…兼容）│
│ ● my-vllm   http://10.0.0.2:8000   › │
├ 高级 ───────────────────────────────┤
│ 思考预算 thinkingBudgets · 每模型思考档 modelThinkingLevels · 刷新模型目录 │
└────────────────────────────────────┘
```

- **服务商页**：沿用 W2 的 `ProviderConfigScreen` 逻辑（OAuth、Key、多字段、回填 `savedFields`、测试），只换视觉。OAuth 走 Custom Tab，回调后自动完成，备选为粘贴代码；Key 只显示末四位。
- **自定义服务商**：沿用 `CustomProviderScreen` 加 `ModelDiscovery`/`ProviderTester`。字段包括 baseUrl、api 类型（openai-completions / openai-responses / anthropic-messages / bedrock-converse-stream / mistral-conversations…）、Key（可写 `!命令`）、headers、模型列表（自动发现，可手动加）、modelOverrides、compat。
- **会话内切换**：
  - **模型面板**（UI：点模型胶囊；终端：`/model`、点页脚）三个页签：
    - 「全部」：可搜索，已配置的在前，未配置的灰显，点了跳服务商页，配好后自动选回刚才点的那个模型；
    - 「常用」：星标、拖动排序、每模型固定思考档；
    - 「最近」。
  - 选择调 `set_model`，**生成中也可切**，提示「下一轮生效」。
  - **思考强度**：UI 形态的胶囊单击走 `cycle_thinking_level`，长按弹 7 档（只列 `get_available_thinking_levels` 返回的）；终端形态点编辑器边框或用 `/thinking`。
  - 「设为默认」写 `settings.json` 的 default*（与 pi 的 Ctrl+S 同义）。
- **两种形态共用同一套面板模型**（§5.3），所以终端里的 `/login`、`/model` 和 UI 里的设置页数据完全一致。

---
