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
