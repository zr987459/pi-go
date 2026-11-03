# pi-go 第三轮设计定稿（DESIGN-r3）

> 状态：**定稿**，2026-11-04（CST）。取代 `docs/DESIGN.md` 中与本文冲突的部分；未提到的约定（运行环境安装、凭据加密、PRoot 进程管理等）沿用 `docs/DESIGN.md`。
> 底稿：`docs/proposals/r3-C.md`（评审 3:0 选 C；评审 1：A 8.5 / B 5.5 / C 9；评审 2：投 C；评审 3：A 8.5 / B 6 / C 9）。
> 融合：三份评审（`docs/votes/r3-vote-1/2/3.md`）指出的 A、B 优点，C 自身需修正的地方，以及三张遗漏表里的全部缺项。
> 对照基准：`docs/pi-features.md`（pi 1.5.0 全功能清单，下称「清单」，按节号引用）。
> 基线：`main@527708e` 之后的 main（只多了 docs 提交）。

用户原话：
1. 「根据 pi 自己重新设计一个界面，把一些无用的功能删了，先只保留配置模型，做成那种极简好看的风格」
2. 「pi 该有的功能不能少，可以分成终端和 UI 界面两种形态」
3. （06:09 硬性要求）「再把功能加到 UI 界面上，两种模式无缝切换」：
   - **硬性要求 ①**：清单里的全部 pi 功能，UI 形态都必须有对应入口（不能只在终端形态有），终端形态也要完整。
   - **硬性要求 ②**：两种形态共用同一会话、同一引擎状态。切换时不丢上下文、不重启引擎、不中断正在生成的回复、保留输入框草稿，一键即切。

---

## 0. 调度者已拍板的事项

| # | 事项 | 结论 | 对设计的影响 |
|---|---|---|---|
| D1 | 删除会话 | **保留「移进回收目录」**（REVIEW.md 的数据安全要求），可以再加一个 5 秒撤销提示；**不需要回收站浏览页** | `FileSessionRepository` 的回收目录逻辑保留；撤销就是从回收目录移回来；设置里的回收站页删除；REVIEW.md 不用改 |
| D2 | 人民币、汇率显示 | **删除**（用户最新要求删无用功能，pi 本身没有）；保留 pi 原生的费用和用量显示方式（美元，订阅显示 `(sub)`） | 取代 2026-11-03 的「决策 3」，不再需要所有者确认 |
| D3 | CI | **授权**在 `.github/workflows/` 加：单元测试、覆盖测试（读 `pi-features.md` 逐项核对 UI 入口）、可在 CI 跑的切换测试和仪器测试编译 | 由 r3/core 负责，改动在 PR 里单独标明（§8.4） |
| D4 | 吉祥物 | **不要** | 全仓清残留并写进测试（§1.4） |

本文自己定的取舍（评审有分歧的地方）：

| 事项 | 结论 | 理由 |
|---|---|---|
| 系统分享入口（Share Target，「发给 pi」） | **删除** | pi 没有；评审 1、2 主张删，评审 3 主张留。按用户「pi 没有就删」的口径删。附件选择（相册、相机、文件）保留，不受影响 |
| `print -p` / `--mode json` | UI 形态给**真实入口**「一次性提问」（采纳 A），不只是说明 | 「pi 该有的功能不能少」；实现成本低：`bash` + `excludeFromContext` 跑 `pi -p` |
| `pi auth print-*` | 两种形态先做**说明条目**（采纳 C）；受保护的复制入口（二次确认 + 锁屏验证 + 60 秒清空剪贴板）列为 P2 | 清单建议不放按钮；说明条目已经满足「有入口」 |
| Mermaid | UI 形态默认显示代码块，点「图示」用**和终端形态相同的字符画渲染**（pi 自己在终端里就是这样画的）；WebView 渲染成图列为 P2 | 硬性要求 ① 要的是「有对应功能」。pi 的能力就是字符画，不打包 2 MB 的 mermaid.js 也不缺功能 |
| 任务完成通知 | 保留，**默认关** | 对应 pi 的终端进度提示（OSC 9;4）；前台服务通知是平台必需 |
| 「本轮耗时」 | 保留（`TurnStats` 只留这一项和累计统计） | pi 的 loader 上有计时 |
| 交付节奏 | **先交付 UI 形态**（用户日常用），终端形态随后（采纳 B） | 只影响合入和发布顺序，不影响并行开发（§8.3） |

---

## 1. 功能保留 / 删除最终清单

判定标准：
- pi 1.5.0 原生具备的：**保留**，以清单为准。
- pi 没有、但安卓上跑 pi 必需的（安装运行环境、前台服务、凭据加密、附件选择器）：**保留**，标「平台必需」。
- 其他 pi 没有的：**删除**。

### 1.1 删除清单（每项写明属主工作流，属主才能删）

| # | 删除项 | 文件 / 位置 | pi 有吗 | 属主 | 去向 |
|---|---|---|---|---|---|
| X1 | 吉祥物残留 | 全仓 `grep -riE "mascot\|鲸\|deepy\|pet"`（含 `settings.gradle.kts` 注释里的 `:feature:pet`、NOTICE 相关段） | 没有 | 各自范围的属主；`settings.gradle.kts` 归 r3/core，NOTICE 归 r3/app | 无 |
| X2 | 汇率、人民币费用 | `core/store/…/ModelPrefsStore.kt` 的汇率字段、`core/catalog/…/RealAppSettingsStore.kt` 的 `CostDisplay`、`feature/models/…/CustomAndSettingsScreens.kt` 里的 `NetworkAndCostScreen`、`feature/usage/logic/UsageFormat.kt` 的 ¥ | 没有 | r3/models（store、catalog、models）；`UsageFormat.kt` 随 `feature/usage` 由 r3/app 删 | 费用照 pi 显示美元 |
| X3 | 用量页、每轮统计卡 | `feature/usage/ui/UsageSheet.kt`、`TurnSummaryRow.kt`、`logic/TurnSummary.kt`、`UsageController.kt`、`UsageFormat.kt` | 部分（pi 有 `/session` 和页脚） | r3/app（随 `feature/usage` 整模块删） | 统计只在终端页脚和「会话信息」（= `/session`） |
| X4 | 文件浏览器、文件预览、语法高亮页、Git 页 | `feature/files/ui/FilesBrowserScreen.kt`、`FilePreviewScreen.kt`、`Highlight.kt`、`GitScreens.kt`、`Common.kt`、`Back.kt`、`browser/FilesController.kt`、`FilesHost.kt`、`FilesRuntime.kt` 中浏览器部分 | 没有（pi 只有 `@文件` 搜索、页脚分支名） | r3/app | 文件入口改为 `@` 搜索面板 |
| X5 | Git 解析 | `core/workspace/…/GitParse.kt` 及其测试；`RootfsWorkspace` 的 git status / diff / worktree API | 没有 | r3/core | 只保留「读当前分支名」（页脚用），挪进 `RootfsWorkspace.branchName()` |
| X6 | 自动命名会话 | `feature/commands/data/AutoNamer.kt`、`AutoTitle.kt` 及测试 | 没有（只有 `/name`） | r3/session | 未命名会话显示第一条用户消息（与 pi 选择器一致） |
| X7 | 回收站**浏览页** | `feature/settings` 里的回收站页、`app/nav` 的对应路由 | 没有 | r3/models（settings）、r3/app（nav） | 回收目录本身保留（D1） |
| X8 | dsh-Go 视觉 | `core/ui/…/DshPalette.kt`、`DshTokens.kt`、`Components.kt` 的玻璃面板与渐变、`AppIcons` 的 dsh 字形、`core/ui/src/main/res/font/dsh_icons.ttf`、`ThemePreviews`、`feature/settings/…/DshComponents.kt`、`feature/sessions/…/DrawerStyle.kt` | 不适用 | r3/calm（core/ui、sessions）、r3/models（settings） | 换成 `CalmTheme`（UI 形态）与 `PiTermTheme`（终端形态） |
| X9 | 空会话建议胶囊 | `feature/chat/ui/SuggestionText.kt`、`feature/chat/src/test/…/SuggestionTextTest.kt` | 没有 | r3/calm（两个文件都归它，见 §8.1） | 空会话显示 pi 页眉信息 |
| X10 | 系统分享入口 | `feature/files/share/Share.kt`、`ShareCache.kt`、`ShareUriPolicy.kt`、`feature/files` 清单里的 `ShareTargetActivity`、`ShareSecurityTest.kt` | 没有 | r3/app | 无 |
| X11 | 旧导出弹层、旧会话树页、旧压缩卡、旧 diff 视图 | `feature/files/export/ExportSheet.kt`、`feature/usage/ui/SessionTreeScreen.kt`、`CompactionCard.kt`、`DiffView.kt` | 功能 pi 有，界面重画 | r3/app | 功能改由 `PiActions` + 两种形态各自的渲染器提供 |
| X12 | 技能管理旧页面 | `feature/commands/ui/**`（`SkillsScreen.kt` 等） | 功能 pi 有，界面重画 | r3/session | 改为动作 `skills` 的面板模型 |
| X13 | 旧聊天界面 | `feature/chat/ui/*`（ChatScreen、TopBar、Composer、Messages、ChatWiring、ExtDialogs、MarkdownText 的 dsh 版本） | 功能 pi 有，界面重画 | r3/calm | `CalmSurface` |
| X14 | 旧设置首页及非模型设置项 | `feature/settings/…/SettingsScreen.kt` 旧实现、网络与费用页 | 部分 | r3/models | 设置页只剩「模型」；pi 的其他设置进 ⌘ › pi 设置（§6.5） |
| X15 | `feature/usage` 整模块 | `settings.gradle.kts` 的 `include(":feature:usage")` | — | r3/core（单独小 PR，在 r3/app 删除模块后） | 逻辑文件先由 r3/session 移走（§8.2） |

### 1.2 保留清单（含改造）

| 模块 / 文件 | 结论 | 理由 |
|---|---|---|
| `core/protocol`（RpcChannel、JsonlFramer、ChatReducer、ContractMapping、RecordCodec、Responses、ExtraResponses、MessageMetaTracker、Diagnostics、`CompactionSummary`、RunResults、Support） | 保留 | 33 个 RPC 命令与全部事件的唯一通道（清单 §2）。`core/protocol/CompactionSummary.kt` 是压缩摘要的**唯一**实现，`feature/usage/logic/CompactionSummary.kt` 里有用的部分并进来后删掉 |
| `core/protocol/TurnStats.kt` | 改造 | 只留累计统计（页脚、`/session`）与本轮耗时（loader 计时）；不再产出每轮统计卡 |
| `core/engine`（PiEngine、EngineConfig、EnginePorts、AndroidPorts、EngineService） | 保留 | 引擎本体、前台服务（平台必需）。新增 `-e <pigo-bridge.ts>` 启动参数（§5.6） |
| `core/engine/TaskNotifier.kt`、`AndroidTaskNotifications.kt` | 保留，完成通知默认关 | 对应 pi 的进度提示；前台服务通知必须保留 |
| `core/runtime`（全部，含 `process/ProcessTree.kt`） | 保留 | PRoot 与 pi 安装（平台必需）。`ProcessTree` 还用于切换测试读 pi 进程 PID |
| `core/store`：`FileSessionRepository` | 保留并小改 | 删除仍进回收目录（D1）；新增「撤销最近一次删除」API（从回收目录移回）；回收站列举 API 不再有界面调用 |
| `core/store`：`PiProviderConfigStore`、`ProviderSpecs`、`ProviderPresets`、`BuiltinCatalog`、`ModelDiscovery`、`ProviderTester`、`AuthJsonLock`、`KeystoreSecretStore`/`KeystoreCipher`、`KeyRedaction`、`HostProxy`、`GuestPaths`、`JsonFiles`、`ResumePolicy`、`SessionFileParser` | 保留 | 模型配置与会话读取 |
| `core/store/ModelPrefsStore.kt` | 改造 | 删汇率；保留最近使用；默认模型改为读写 `settings.json` 的 `defaultProvider`/`defaultModel`/`defaultThinkingLevel` |
| `core/catalog`（AuthService、OAuth*、Pkce、Real*、CatalogRepository、ModelIndex、GuestScripts） | 保留 | 42 个服务商、OAuth、自定义服务商 |
| `core/catalog/RealAppSettingsStore.kt` | 改造 | 删 `CostDisplay`；代理改为 pi 的 `httpProxy`（经 `PiSettings` 读写）；只留 App 自己的设置：上次形态 `UiForm`、外观、完成通知开关 |
| `core/workspace`：`Attachments`、`ImagePrep`、`AndroidImageCodec`、`FileTypes`、`FileNames`、`GuestPaths` | 保留 | 图片和附件（`prompt.images[]`、`images.autoResize`） |
| `core/workspace/SessionExport.kt` | 保留 | `/export`（HTML、JSONL） |
| `core/workspace/RootfsWorkspace.kt` | 改造 | 只留：`@文件` 模糊搜索、读文件内容（`@` 展开、`full_output_path`）、`branchName()`、目录选择（只列目录，给「在其他目录新建会话」用） |
| `feature/chat` 逻辑（ChatController、ChatRows、ComposerLogic、Outbox、ExtUi、SlashCompletion、PhaseLabel、ErrorText、MarkdownSegments） | 保留，作为共享层 | 流式、工具、排队、引导、中止、扩展对话框（清单 §2、§10） |
| `feature/commands` 数据层（DraftStore、JsonFileStore、PiSessionIndex、SessionScanner、SessionTranscriptReader、SessionMeta、SkillTemplateManager、SkillsController、CommandPalette 逻辑） | 保留 | 草稿持久化（切换形态、进程被杀都要用）、`/resume` 列表、技能与模板 |
| `feature/files`：`AttachmentController`、`AttachmentSheet` 逻辑、`Pickers`、`FileShare`、`PigoFilesProvider`、`output/Ansi.kt`、`output/OutputViewer.kt` | 保留 | 附件（平台必需）、`/export` 后系统分享、bash 输出 ANSI、截断后查看 `full_output_path`（清单 §4）。`Ansi.kt` 由 r3/session **移动**进 `core/session`（§8.2）；`OutputViewer` 留在 `feature/files`，两种形态共用，按形态传主题 |
| `feature/usage/logic/LineDiff.kt`、`SessionTreeRows.kt` | 保留，**移动**进 `core/session` | `edit` 工具 diff、`/tree` 的数据 |
| `feature/models` | 保留并重画 | 用户唯一保留的设置 |
| `feature/sessions` | 改造 | `/resume` 会话选择器的 UI 形态 |
| `feature/settings` | 改造 | 只剩「设置 › 模型」的外壳 |
| `feature/settings/RomGuide.kt`、`BackgroundGuide.kt` | 保留并移位（平台必需） | 只在「引擎被系统杀掉」的提示里出现，不在设置页 |
| `feature/onboarding` | 保留并重画 | 装 PRoot 和 pi（清单 §11「首次设置」） |
| 网络镜像 `MirrorProbe`/`DomesticInstaller` | 保留，只在安装页 | 国内安装必需 |
| `core/ui/src/main/res/font/jetbrains_mono_*.ttf` | 保留 | 终端形态与代码块字体，已在仓库里（不需要再加） |

### 1.3 新增

| 新增 | 位置 | 作用 |
|---|---|---|
| `SessionHost`、`DraftState`、`OverlayModel`、`UiForm`、`PiActionCatalog`、`PiSettingsSchema` 等契约 + Fake | `core/contract/**`（只新增文件） | 两种形态共用的状态与动作（§5） |
| `core/session` 新模块 | `core/session/**` | `SessionHostImpl`、`PiActions` 的执行实现、`PiSettings`、`SessionCatalog`、`@` 展开、`Ansi`、`LineDiff`、`SessionTreeRows`、覆盖测试 |
| `feature/term` 新模块 | `feature/term/**` | 终端形态 |
| `pigo-bridge.ts` | `core/engine/src/main/assets/pi/pigo-bridge.ts` | 补 RPC 缺口的随包扩展（§5.6） |
| pi 主题 JSON | `feature/term/src/main/assets/pi-theme/{dark,light}.json` | 原样打包（MIT），NOTICE 记一行 |
| 覆盖映射 | `docs/r3/coverage-map.tsv` | 硬性要求 ① 的机读映射（§7.1） |

### 1.4 防回潮

- `core/session` 的单测 `NoRemovedFeaturesTest`：扫描全仓 `src/main`，断言不再出现 `mascot`、`鲸`、`Deepy`、`cny`、`汇率`、`exchangeRate`、`Dsh`、`dsh_icons`、`ShareTargetActivity`、`AutoNamer`、`FilesBrowserScreen`、`GitScreens`。r3/app 合入后启用（之前标 `@Ignore("r3/app 合入后启用")`）。
- 各工作流期间要删但还被引用的类，先加 `@Deprecated("r3: <属主> 删除")`，新代码不得引用（PR 里贴 `grep` 结果）。

---

## 2. 对照清单逐项覆盖（硬性要求 ①）

### 2.0 读表说明

- **⌘**：UI 形态的命令中心（顶栏 ⌘，或在输入框敲 `/`），可搜索中文名、斜杠名和拼音首字母，按组列出全部动作；每项显示 pi 的原命令名（如「分支树 /tree」）。
- **常驻**：UI 形态始终可见的控件。
- **/x**：终端形态的斜杠命令，同时出现在 `/` 补全里。
- **键条**：终端形态编辑器下方的触屏按键条（§3.4）。
- **动作 id**：`PiActionCatalog` 里的 id，覆盖测试按 id 核对（§7.1）。
- **说明条目**：清单写「不适用 / 只做说明」的项，两种形态各有一个可点的说明条目（`Explain`，说明文字非空），覆盖测试同样要求它存在。
- 每行最终以 `docs/r3/coverage-map.tsv` 为准；本文的表是它的人读版本。

### 2.1 清单 §1.1 运行模式

| 清单条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 交互 TUI | FORM | `TermSurface` 全屏 | `CalmSurface` |
| print `-p` | `oneshot` | `!pi -p "…"`，输出显示为 bash 块；`/oneshot`（本地命令）打开同一面板 | ⌘「一次性提问」：输入问题，结果以 bash 块显示，**不进当前会话**（实现：`bash` + `excludeFromContext` 跑 `pi -p`） |
| `--mode json` | `oneshot` | `!pi --mode json -p "…"` | 「一次性提问」面板里的「输出 JSON 事件流」开关 |
| `--mode rpc` | BASE | 底座 | 底座 |
| `pi install/remove/update/list/config` | `packages` | `/packages` 列表（安装 / 移除 / 更新 / 配置），也可 `!pi install …` | ⌘「扩展与包」：列表、安装（npm / git / 本地路径）、移除、更新、配置；执行完自动 `reload` |
| `pi auth print-api-key / print-bearer-token` | `help.authPrint` | 说明条目（可自行 `!pi auth …`） | ⌘「凭据打印说明」（说明条目）；受保护复制入口 P2 |
| `pi mcp add/list/remove…` | `mcp` | `/mcp`，也可 `!pi mcp …` | ⌘「MCP 服务器」 |

### 2.2 清单 §1.2 CLI 选项

| 清单条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| `--provider` `--model` | `model.select` | 页脚模型段、`/model` | 常驻：模型胶囊 |
| `--api-key` | `help.apiKey` | 说明条目 | ⌘ 说明条目：「本 App 用 auth.json 管理凭据，不通过进程参数传 Key」，附「去配置 Key」按钮（跳 `auth.login`） |
| `--system-prompt` `--append-system-prompt` | `context.system` | `/settings › 系统提示`（编辑 `SYSTEM.md`、`APPEND_SYSTEM.md`） | ⌘「系统提示」编辑页 |
| `-c/-r/--session/--session-id` | `session.resume` | 冷启动回到上次会话（ResumePolicy）；`/resume` | 常驻：☰ 会话列表 |
| `--fork` | `session.fork` | `/fork` | 消息长按 › 从这里分叉；⌘「分叉」 |
| `--session-dir` | `settings.session` | `/settings › 会话 › sessionDir` | ⌘「pi 设置 › 会话」 |
| `--no-session` | `session.newEphemeral` | `/new --ephemeral` | 「＋」长按 › 临时会话；⌘「临时会话」 |
| `-n/--name` | `session.name` | `/name` | 常驻：点标题改名 |
| `--models` | `model.scoped` | `/scoped-models` | 模型面板「常用」页签；⌘「常用模型」 |
| `-nt` `-nbt` `-t` `-xt` | `settings.tools` | `/settings › 工具` | ⌘「工具开关」（含 grep、find、ls、codemode；powershell 灰显并说明） |
| `--thinking` | `thinking.select` | 编辑器边框色、`/thinking` | 常驻：思考胶囊 |
| `-e/--extension` `-ne` | `packages` | `/settings › 扩展`；`/packages` 扩展页签 | ⌘「扩展与包 › 扩展」 |
| `--no-mcp` | `mcp` | `/mcp` 顶部总开关 | ⌘「MCP 服务器」顶部总开关 |
| `--skill` `-ns` | `skills` | `/skill:名` 补全；页眉列出技能 | ⌘「技能」；`/` 面板技能组 |
| `--prompt-template` `-np` | `templates` | `/模板名` 补全 | ⌘「提示模板」；`/` 面板模板组 |
| `--theme` `--use-theme` `--no-themes` | `appearance` | `/settings › 主题`（dark / light / system / 已装主题） | ⌘「外观」：浅色 / 深色 / 跟随系统；终端形态主题单独一项 |
| `-nc/--no-context-files` | `settings.contextFiles` | `/settings › 上下文文件` | ⌘「上下文文件」顶部开关 |
| `--export` | `session.export` | `/export [path]` | 会话菜单 › 导出；⌘「导出」 |
| `--list-models` | `model.select` | `/model` | 模型面板 |
| `--verbose` | `session.info` | 页眉（`quietStartup=false` 时展开） | 空会话首屏「已加载 N 个上下文文件 / M 个技能」可点开；会话信息页 › 已加载资源 |
| `--tui-mode` | `help.tuiMode` | 说明条目（手机只有全屏） | ⌘ 说明条目 |
| `-a/--approve` `-na` | `trust` | `/trust`；首次打开带 `.pi/` 的目录时弹信任选择器 | 首次打开时弹窗；⌘「项目信任」 |
| `--offline` | `settings.network` | `/settings › 网络 › 离线` | ⌘「pi 设置 › 网络 › 离线启动」（会重启引擎，先确认，§5.5） |
| `-h` `-v` | `help`、`about` | `/help`；页眉版本号 | ⌘「帮助」「关于」 |

### 2.3 清单 §2.1 RPC 命令（33 个）

| 命令 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| `prompt` | `send` | 键条 ⏎（或外接键盘 Enter） | 常驻：发送键 |
| `steer` | `send`（生成中） | 生成中 ⏎ | 生成中输入框有字时发送键显示「引导」 |
| `follow_up` | `send.followUp` | Alt+Enter；键条 ⇣ | 发送键长按 › 排队 |
| `abort` | `abort` | Esc；键条 Esc | 生成中输入框为空时发送键显示 ■；有字时长按 › 停止（§4.4 发送键三态） |
| `clear_queue` | `queue.dequeue` | Alt+↑；键条 ↑队 | 队列条「撤回」 |
| `new_session` | `session.new` | `/new` | 常驻：顶栏 ＋ |
| `get_state` | BASE | 页脚 | 顶栏状态、会话信息 |
| `get_messages` | BASE | 恢复会话时重建 | 同左 |
| `set_model` | `model.select` | `/model`、Ctrl+L、点页脚模型段 | 模型胶囊 → 模型面板 |
| `cycle_model` | `model.cycle` | Ctrl+P / Shift+Ctrl+P；页脚模型段左右滑 | 模型胶囊左右滑；⌘「下一个模型」「上一个模型」 |
| `get_available_models` | BASE | 选择器数据 | 同左 |
| `set_thinking_level` | `thinking.select` | `/thinking` | 思考胶囊长按 → 档位列表 |
| `cycle_thinking_level` | `thinking.cycle` | Shift+Tab；点编辑器边框 | 思考胶囊单击 |
| `get_available_thinking_levels` | BASE | 选择器数据 | 同左 |
| `set_steering_mode` / `set_follow_up_mode` | `settings.queue` | `/settings › 队列` | ⌘「pi 设置 › 队列」 |
| `compact` | `session.compact` | `/compact [说明]` | 上下文环 › 立即压缩（可填说明）；⌘「压缩」 |
| `set_auto_compaction` | `settings.compaction` | `/settings › 压缩` | ⌘「pi 设置 › 压缩」 |
| `set_auto_retry` | `settings.retry` | `/settings › 重试` | ⌘「pi 设置 › 重试」 |
| `abort_retry` | `retry.abort` | 状态行「重试 2/3」时按 Esc | 重试横条「取消」。**只发 `abort_retry`**，不再用 `stop()`/`clear_queue` 代替 |
| `bash` | `bash` | `!cmd` / `!!cmd` | 输入以 `!` 开头进入命令模式；「＋ › 运行命令」 |
| `abort_bash` | `bash.abort` | 运行中 Esc | 运行中停止键 |
| `get_session_stats` | `session.info` | `/session` | 上下文环点开 → 会话信息页 |
| `export_html` | `session.export` | `/export` | 会话菜单 › 导出 |
| `switch_session` | `session.resume` | `/resume` | 会话列表点选 |
| `fork` | `session.fork` | `/fork` 选择器 | 用户消息长按 › 从这里分叉 |
| `get_fork_messages` | `session.fork` | `/fork` 选择器数据 | ⌘「分叉」打开用户消息列表 |
| `clone` | `session.clone` | `/clone` | 会话菜单 › 复制会话 |
| `get_entries` | BASE | 增量同步 | 同左 |
| `get_tree` | `session.tree` | `/tree` | 会话菜单 › 分支树 |
| `get_last_assistant_text` | `copy.last` | `/copy`；长按助手块 › 复制 | 助手消息长按 › 复制；⌘「复制最后回复」 |
| `set_session_name` | `session.name` | `/name` | 点标题改名 |
| `get_commands` | BASE | `/` 补全数据 | `/` 面板与 ⌘「扩展命令」组 |
| RPC 缺口：会话列表 / 原地切分支 / 标签 / 热重载 / 登录 / 导入 | `session.resume` / `session.tree` / `tree.label` / `reload` / `auth.login` / `session.import` | 见各行 | 见各行；实现见 §5.6 |

### 2.4 清单 §2.2 RPC 事件

事件只归约一次（`ChatReducer`），两种形态都只读 `SessionHost.chat` 的状态。

| 事件 | 终端形态 | UI 形态 |
|---|---|---|
| `agent_start` / `agent_end` | 状态行 loader 出现 / 消失 | 发送键变停止键；副标题「生成中 0:12」 |
| `agent_settled` | loader 消失（以它为准） | 停止键恢复为发送键 |
| `turn_start` / `turn_end` | 不显示，计入页脚统计与本轮耗时 | 不显示，计入会话信息 |
| `message_start` / `message_end` | 消息块，以 end 为准 | 消息，以 end 为准 |
| `message_update`（text / thinking / toolcall） | 流式 Markdown、`thinkingText` 思考块、工具调用块 | 流式正文、「思考了 N 秒」折叠行、过程行 |
| `tool_execution_start/update/end` | 三态底色工具块 | 过程行：状态点、动词、目标、时长，可展开 |
| `queue_update` | 编辑器上方列出排队消息 | 输入框上方队列条 |
| `entry_appended` | `customMessage` 色块 | 灰色系统行 |
| `session_info_changed` | 页脚会话名 | 顶栏标题 |
| `thinking_level_changed` | 编辑器边框色 | 思考胶囊 |
| `compaction_start/end` | 「压缩中…」loader + 摘要块 | 压缩卡（展开看「目标、约束、进度、决定、下一步、关键上下文」） |
| `auto_retry_start/end` | 状态行倒计时「重试 2/3」 | 重试横条 + 取消 |
| `summarization_retry_*` | 同上 | 同上 |
| `bash_execution_update` | `!` 输出实时滚动（ANSI） | 等宽输出块实时滚动 |
| `extension_error` | 红色提示行 | 吐司 + ⌘「诊断」里可看 |
| `extension_ui_request` 阻塞类 `select`/`confirm`/`input`/`editor`（含 `timeout`） | `TermSelectList` / pi 风格弹层 | `CalmSheet` 底部面板。两者画的是同一个 `OverlayModel.ExtDialog`，切换不丢（§5.3） |
| `notify` | 状态行一行 | 吐司 |
| `setStatus` | 页脚右侧状态段 | 顶栏副标题 |
| `setWidget` | 编辑器上方 widget 区 | 输入框上方 widget 区 |
| `setTitle` | 页眉标题行 | 顶栏标题旁的扩展标题（覆盖会话名显示，点开看原名） |
| `set_editor_text` | 写入共享草稿 | 写入共享草稿（两种形态同时可见） |

### 2.5 清单 §3 斜杠命令

UI 形态里，下面每一条都是 ⌘ 的一项；在输入框敲 `/` 时，同一份列表以补全面板出现。终端形态是 pi 原样的斜杠命令。

| 命令 | 动作 id | 终端形态 | UI 形态（除 ⌘ 外的快捷入口） |
|---|---|---|---|
| `/settings` | `settings` | pi 风格 SettingsList 弹层（按清单 §8 分组） | ⌘「pi 设置」（不在设置页，§6.5） |
| `/model` | `model.select` | 可搜索列表 | 模型胶囊 |
| `/thinking` | `thinking.select` | 选择器 | 思考胶囊 |
| `/scoped-models` | `model.scoped` | 多选列表；拖动排序；底部键帽「全选 / 清空 / 整个服务商 / 保存」 | 模型面板「常用」页签 |
| `/login` | `auth.login` | 服务商选择器 → 登录对话框（URL、设备码、可粘贴回调） | 设置 › 模型 › 服务商 › 登录 |
| `/logout` | `auth.logout` | 选择器 | 服务商页「退出登录」 |
| `/llama` | `ext.llama` | 扩展 UI 协议弹层 | ⌘「llama.cpp」（同一协议画成底部面板） |
| `/new` | `session.new` | 命令 | ＋ |
| `/resume` | `session.resume` | 会话选择器（搜索；底部键帽：显示路径、排序、只看已命名、重命名、删除） | ☰ 会话列表（同样五个选项在列表菜单与长按里） |
| `/name` | `session.name` | 命令 | 点标题 |
| `/session` | `session.info` | 本地文本块（路径、ID、消息数、token、费用），作为 `customMessage` 色块插入消息区，**不写入会话文件** | 会话信息页 |
| `/tree` | `session.tree` | 树选择器：筛选、标签、时间、折叠 / 展开（§2.7） | 会话菜单 › 分支树 |
| `/fork` | `session.fork` | 用户消息选择器 | 消息长按 |
| `/clone` | `session.clone` | 命令 | 会话菜单 |
| `/compact` | `session.compact` | 命令 | 上下文环 |
| `/import` | `session.import` | 命令 + 系统文件选择器（SAF） | 会话列表菜单 › 导入 |
| `/copy` | `copy.last` | 命令 | 长按复制 |
| `/export` | `session.export` | 命令（HTML / JSONL） | 会话菜单 › 导出（HTML / JSONL，可分享或保存） |
| `/share` | `session.share` | 命令；未登录 `gh` 或 Radius 时先给登录指引 | 会话菜单 › 分享链接（同样的登录前置提示） |
| `/bug` | `bug` | 命令 | ⌘「报告问题」 |
| `/trust` | `trust` | 命令 | ⌘「项目信任」；首次打开时弹窗 |
| `/mcp` | `mcp` | 扩展 UI 弹层 | ⌘「MCP 服务器」 |
| `/reload` | `reload` | 命令 | ⌘「重新加载资源」；改完 pi 设置后自动执行 |
| `/hotkeys` | `hotkeys` | 本地文本块（快捷键 + 触屏映射表，§3.4），不写入会话 | ⌘「手势与快捷键」 |
| `/changelog` | `changelog` | 本地文本块（包内 CHANGELOG.md），不写入会话 | ⌘「更新日志」 |
| `/debug` | `debug` | 写 `pi-debug.log` | ⌘「诊断」：导出日志（脱敏） |
| `/quit` | `engine.stop` | 命令；**生成中先确认** | ⌘「停止引擎」（帮助组），执行前确认 |
| `/模板名 [参数]` | `templates` | 补全，显示 `argument-hint` | `/` 面板 → 参数输入框（按 `$1`、`$@` 生成字段） |
| `/skill:名` | `skills` | 补全 | `/` 面板技能组 |
| 扩展命令 | `ext.command:<名>`（动态） | 补全 | `/` 面板与 ⌘「扩展命令」组 |
| 本地命令 `/ui`、`/term` | `form.toggle` | 切到 UI 形态 | 切到终端形态（另有常驻按钮，§5.2） |

彩蛋（`/arminsayshi`、`/dementedelves`）不做，与清单一致。

### 2.6 清单 §4 内置工具

数据只有一份（`ChatRow.Tool`），两种形态各有一个按工具名分派的渲染器表。

| 工具 | 终端形态 | UI 形态 |
|---|---|---|
| `read` | `read path:范围` 标题 + 折叠输出；图片结果内联 | 过程行「读取 文件名」，展开看内容和图片 |
| `write` | 标题 + 代码预览（`syntax*` 色） | 「写入 文件名」，展开看代码 |
| `edit` | 红绿 diff（`toolDiff*`，用 `LineDiff`） | 「编辑 文件名 +3 −1」，展开看 diff |
| `bash` | `$ cmd` + 流式 ANSI；截断时附「完整输出」，打开 `OutputViewer` 读 `full_output_path` | 「运行 命令」+ 等宽输出块，同样的完整输出链接 |
| `grep` / `find` / `ls` | 标题 + 列表 | 「搜索 / 查找 / 列出」过程行 |
| `powershell` | NA：说明条目（Windows 专用） | NA：⌘「工具开关」里灰显并说明 |
| `codemode` | 脚本块 + 输出 | 「脚本」过程行，展开看代码 |
| `tool_search` | 普通工具行 | 普通过程行 |
| MCP 工具 `mcp__服务器__工具` | 普通工具行，前缀 `mcp__服务器` | 过程行，前缀服务器名 |

开关这些工具的入口统一在 `settings.tools`。

### 2.7 清单 §5 会话

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 存储位置 | `session.info` | `/session` 显示路径 | 会话信息页显示路径 |
| 格式（v3 JSONL 条目树） | BASE | — | — |
| 继续最近 / 恢复 | `session.resume` | 冷启动回到上次；`/resume` | 同左；☰ |
| 新建 | `session.new` | `/new` | ＋ |
| 分叉 / 复制 | `session.fork` / `session.clone` | `/fork`、`/clone` | 长按、会话菜单 |
| 树导航（原地换分支；选用户消息时原文回编辑器；离开分支可生成摘要） | `session.tree` | `/tree`；双击 Esc（遵循 `doubleEscapeAction`） | 分支树页：点节点切换，弹「是否摘要离开的分支」（遵循 `branchSummary.skipPrompt`）；原文放回**共享草稿** |
| 树筛选（default、no-tools、user-only、labeled-only、all） | `tree.filter` | 树选择器底部 5 个键帽 | 树页顶部筛选胶囊 |
| 树折叠 / 展开（Ctrl+←/→） | `tree.fold` | 树选择器键帽「◂ 折叠」「▸ 展开」；点节点左侧三角 | 树节点左侧三角点按；树页菜单「全部折叠 / 全部展开」 |
| 标签（Shift+L） | `tree.label` | 键帽「标签」 | 节点长按 › 打标签 |
| 显示标签时间（Shift+T） | `tree.time` | 键帽「时间」 | 树页菜单「显示时间」开关 |
| 会话名 | `session.name` | 页脚 | 标题 |
| 删除会话 | `session.delete` | 选择器键帽「删除」（确认后进回收目录，状态行提示 5 秒内 `u` 键或点「撤销」恢复） | 会话列表左滑删除 → 进回收目录 → 5 秒撤销条 |
| 选择器选项：显示路径、排序、只看已命名、重命名 | `session.resume` | 选择器键帽 | 列表 ⋯ 菜单（排序：最近 / 名称；只看已命名；显示路径）、长按 › 重命名 |
| 压缩（手动 / 自动） | `session.compact` | 摘要块 | 压缩卡 + 上下文环 |
| 导出 | `session.export` | `/export` | 会话菜单 |
| 分享 | `session.share` | `/share` | 会话菜单 |
| 导入 | `session.import` | `/import` | 列表菜单 |
| 临时会话 | `session.newEphemeral` | `/new --ephemeral` | ＋ 长按 › 临时会话。实现见 §5.7（运行中不能切到 `--no-session`，近似实现，界面写明） |
| 工作目录 | `session.cwd` | 页脚 `~/路径 (分支)`；目录不存在时按 pi 的询问 | 顶栏副标题显示目录；点开 › 「在其他目录新建会话」（目录选择器只列目录，不是文件浏览器） |

### 2.8 清单 §6 模型与服务商

详见 §6。逐项入口：

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 内置服务商（42 个 + radius + llama） | `auth.login` | `/login` 列表 | 设置 › 模型 › 服务商 |
| OAuth / 订阅登录 | `auth.login` | 登录对话框 | 服务商页 › 用账号登录 |
| API key 来源与优先级（含 `!命令` key、`env`） | `auth.login` | `/login › API key` | 服务商页「Key」；高级里写 `!命令` 与额外 env |
| 自定义服务商 `models.json` | `provider.custom` | `/settings › 自定义服务商` 表单 | 设置 › 模型 › 添加自定义服务商（发现模型、测试连接） |
| 模型切换 | `model.select` | `/model` | 胶囊 |
| 循环切换 | `model.cycle` | 页脚模型段滑动；Ctrl+P | 胶囊滑动 |
| 作用域模型（可带每模型思考档） | `model.scoped` | `/scoped-models` | 「常用」页签，每行可选固定思考档 |
| 思考强度（7 档） | `thinking.select` | 边框色、`/thinking` | 胶囊 |
| `thinkingBudgets` / `modelThinkingLevels` | `model.thinkingAdvanced` | `/settings › 模型` | 设置 › 模型 › 高级 |
| 默认模型 | `model.setDefault` | 选择器键帽「设为默认」 | 模型面板行长按 › 设为默认 |
| 模型目录更新 | `model.refreshCatalog` | `!pi update --models`；选择器键帽「刷新」 | 模型面板右上「刷新」 |
| llama.cpp | `ext.llama` | `/llama` | ⌘ |
| 虚拟模型 / 分类器 / 图像模型 | BASE（随扩展） | 随扩展出现 | 随扩展出现在模型列表与 ⌘ |

### 2.9 清单 §7 技能、扩展、模板、上下文文件、主题、MCP、包

| 条目 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 技能 | `skills` | `/skill:名`；页眉列出 | ⌘「技能」：列表、启用开关、查看 SKILL.md、从文件导入（SAF） |
| 提示模板 | `templates` | 补全 + 参数提示 | `/` 面板 → 参数框；⌘「提示模板」：列表、查看、新建 |
| 扩展 | `packages` | 扩展弹层、状态、组件按 TUI 样式画 | 同一协议画成原生面板；⌘「扩展与包 › 扩展」 |
| 内置扩展（llama、codemode、tool-search、mcp） | `packages` | `/settings › 扩展` | ⌘「扩展与包」里的开关 |
| 上下文文件 | `settings.contextFiles` | 页眉列出；`/settings` | ⌘「上下文文件」：列出已加载文件、编辑全局 AGENTS.md；SYSTEM.md / APPEND_SYSTEM.md 同页（与 `context.system` 共用） |
| 主题 | `appearance` | pi 的 dark / light / system + `~/.pi/agent/themes/` 下的 JSON | ⌘「外观」：浅 / 深 / 跟随系统 |
| MCP | `mcp` | `/mcp` | ⌘「MCP 服务器」：列表、添加（stdio / HTTP）、登录、重连、开关、暴露范围 |
| 包 | `packages` | `/packages`、`!pi install` | ⌘「扩展与包」 |
| 项目信任 | `trust` | `/trust` | 弹窗 + ⌘ |
| 键位文件 `keybindings.json` | `hotkeys` | 外接键盘按文件生效 | ⌘「手势与快捷键」（只读表 + 「编辑 keybindings.json」） |

### 2.10 清单 §8 `settings.json` 全部键与 §8.1 环境变量

用户说「设置里只保留配置模型」，所以**设置页只有模型**。其余键归 pi 自己的 `/settings` 功能：UI 形态是 ⌘「pi 设置」，终端形态是 `/settings`。两种形态共用 `PiSettings`（§6.5）。

| 分组 | 键 | 终端 | UI（⌘「pi 设置」分组） | 备注 |
|---|---|---|---|---|
| 模型 | defaultProvider、defaultModel、defaultThinkingLevel、modelThinkingLevels、thinkingBudgets、enabledModels | `/settings › 模型` | **设置页 › 模型**（唯一的设置页） | 两边写同一个文件 |
| 显示 | hideThinkingBlock、showCacheMissNotices、quietStartup、theme | ✔ | 显示 | `hideThinkingBlock` 两种形态都生效（终端一行「Thinking…」，UI 隐藏折叠行）；`theme` 只影响终端形态 |
| 缓存 | cacheWarming | ✔ | 缓存 | |
| 队列 | steeringMode、followUpMode | ✔ | 队列 | 同时调 rpc `set_*_mode` |
| 编辑器 | externalEditor、doubleEscapeAction、autocompleteMaxVisible、editorPaddingX、showHardwareCursor | ✔ | 编辑器 | `externalEditor`、`showHardwareCursor` 标「手机不适用」只读；`autocompleteMaxVisible` 两种形态补全都遵循 |
| 树 | treeFilterMode、branchSummary.reserveTokens、branchSummary.skipPrompt | ✔ | 会话树 | |
| 信任 | defaultProjectTrust | ✔ | 安全 | 只写全局 |
| 工具 | defaultTools、codemode.mode、codemode.inlineBudget | ✔ | 工具 | |
| 会话 | sessionDir、sessionShareDestination | ✔ | 会话 | |
| 压缩 | compaction.enabled、reserveTokens、keepRecentTokens、modelOverrides | ✔ | 压缩 | 同时调 `set_auto_compaction` |
| 全屏 TUI | tuiMode、fullscreenExitOutput、fullscreenScrollbar、fullscreenCopyOnSelect、fullscreenWheelScrollLines、outputPad | 终端形态内部使用（滚动条、选中即复制、outputPad） | 「仅终端形态」分组，可改 | |
| 终端 | terminal.showImages、imageWidthCells、clearOnShrink、showTerminalProgress、hyperlinks、images、trueColor | 内联图片宽度等 | 同上「仅终端形态」 | |
| 图片 | images.autoResize、images.blockImages | ✔ | 图片 | |
| Markdown | markdown.codeBlockIndent、markdown.mermaid | 缩进；Mermaid 字符画 | Markdown | UI 形态 Mermaid 见 §0 |
| 网络 | transport、httpProxy、httpIdleTimeoutMs、websocketConnectTimeoutMs | ✔ | 网络 | 代理保留（国内需要） |
| 重试 | retry.enabled、maxRetries、baseDelayMs、maxAgentDelayMs、retry.provider.* | ✔ | 重试 | |
| Shell | shellPath、shellCommandPrefix | ✔ | Shell | |
| 包与资源 | npmCommand、selfUpdateCommand、packages、extensions、skills、prompts、themes、enableSkillCommands | ✔ | 扩展与资源 | |
| 其他 | collapseChangelog、enableInstallTelemetry、enableAnalytics、warnings.anthropicExtraUsage | ✔ | 隐私与其他 | 遥测默认关 |
| 环境变量：服务商 key | — | 不显示 | 不显示（说明条目 `help.env`） | 走 auth.json |
| 环境变量：进程（PI_OFFLINE、PI_TELEMETRY 等） | — | `EngineConfig` 注入；在 `/settings › 网络 / 隐私` 呈现 | 同左 | 改了要重启引擎：界面先确认，这是**唯一**会重启引擎的路径（§5.5） |
| 环境变量：渲染类、bash 会话变量 | — | 说明条目 | ⌘「帮助」里的说明条目 `help.env` | 不适用 / pi 自管 |

### 2.11 清单 §9 快捷键（触屏映射见 §3.4）

| 动作 | 动作 id | 终端形态 | UI 形态 |
|---|---|---|---|
| 中断 | `abort` | 键条 Esc | 停止键（三态） |
| 清空 / 退出 | `editor.clear` | 键条 ^C：第一次清空编辑器，空编辑器再按一次提示停止引擎（确认） | 输入框里的 × |
| 挂起 | `help.suspend` | 说明条目 | 说明条目 |
| 外部编辑器 | `editor.expand` | 键条 ⤢：全屏编辑器 | 输入框右上「展开」 |
| 粘贴图片 | `attach.image` | 键条 📎；长按编辑器 › 粘贴图片 | ＋ › 图片 / 相机 / 剪贴板 |
| 换行 | FORM | 软键盘回车 = 换行；键条 ⏎ = 发送（pi 设置 › 编辑器里可互换） | 回车换行，发送键发送 |
| 补全 | FORM | 键条 Tab | 点选 |
| 排队 | `send.followUp` | 键条 ⇣ | 发送键长按 |
| 撤回队列 | `queue.dequeue` | 键条 ↑队 | 队列条撤回 |
| 复制 | `copy.last` | 长按消息 | 长按消息 |
| 选模型 / 循环模型 | `model.select` / `model.cycle` | 点 / 滑页脚模型段 | 点 / 滑模型胶囊 |
| 循环思考 | `thinking.cycle` | 点编辑器边框 | 点思考胶囊 |
| 折叠思考 | `thinking.toggle` | 点思考块 | 点思考行 |
| 展开工具 | `tools.expandAll` | 点工具块；键条 ⌃O 全部 | 点过程行；会话菜单 › 全部展开 / 折叠 |
| 保存默认 | `model.setDefault` | 选择器键帽 | 同左 |
| 编辑器光标与删除 | FORM | 外接键盘照 pi；触屏交给输入法 | 输入法 |
| 历史 | `editor.history` | 键条 ↑↓ 翻历史提示 | 输入框为空时上滑，弹历史提示列表 |
| 消息区滚动、跳转、搜索 | `transcript.search`、`transcript.jump` | 手势滚动；键条 ⌕ 搜索；键条 ⇞⇟ 跳上一条 / 下一条提示；右侧滚动条 | 会话菜单 › 搜索本会话（高亮、上下跳）；回到底部键，长按回到底部键跳上一条提示 |
| 会话选择器动作 | `session.resume`、`session.delete` | 选择器键帽 | 列表菜单、长按 |
| 树动作 | `tree.*` | 选择器键帽 | 筛选胶囊、三角、长按、菜单 |
| 作用域模型动作 | `model.scoped` | 键帽 + 拖动 | 同左 |
| 未绑定的会话动作 | — | 外接键盘可在 keybindings.json 绑定 | ⌘ 里都有 |

### 2.12 清单 §10 输入语法

| 语法 | 终端形态 | UI 形态 |
|---|---|---|
| Enter（生成中引导） | 照 pi | 发送键「引导」 |
| Alt+Enter（排队） | 键条 ⇣ | 长按发送 |
| Esc（中止；排队文字回编辑器） | Esc | 停止；排队文字回**共享草稿**；已有草稿时以「已撤回 n 条」芯片显示，不覆盖 |
| `/命令` | 补全浮层（最多 `autocompleteMaxVisible` 条） | `/` 面板（与 ⌘ 同源） |
| `@文件` | `@` 弹模糊文件搜索，Tab 补全路径 | 输入 `@` 或「＋ › 引用文件」，弹搜索面板。展开格式**以 pi 1.5.0 `interactive-mode` 源码为准**，快照测试核对（§8，r3/session 验收） |
| 图片 | 内联缩略图（`terminal.showImages`） | 输入框缩略图条 |
| `!cmd` | 边框变 `bashMode` 色 | 输入框变等宽，左侧「$」，提示「将运行命令，输出发给模型」 |
| `!!cmd` | 同上 | 提示「不发给模型」 |
| 双击 Esc | 遵循 `doubleEscapeAction` | 不做手势（避免误触），入口在会话菜单（分支树、分叉） |
| 模板参数 | argument-hint | 参数框 |

### 2.13 清单 §11 TUI 元素

| 元素 | 终端形态（§3） | UI 形态（§4） |
|---|---|---|
| 页眉 | 照搬（pi 标志、版本、按键提示、已加载资源），可折叠 | 空会话首屏：「目录 · 模型」一行，「已加载 N 个上下文文件、M 个技能」可点开 |
| 消息区 | 等宽 + pi token | 用户消息右侧浅色块；助手消息无框正文 |
| 思考块 | `thinkingText` 色 | 折叠行 |
| 工具调用块 | 三态底色 | 过程行 |
| bash 块 | `bashMode` 色边框 | 等宽输出块 |
| 压缩 / 分支摘要块 | `customMessage*` 色 | 摘要卡 |
| 技能调用消息 | 照搬 | 一行「使用技能 名称」 |
| 编辑器 | 带边框、边框色 = 思考档、`!` 变色、多行 + 键条 | 圆角输入框 + ＋ + 思考胶囊 + 模型胶囊 + 上下文环 + 发送键 |
| 自动补全 | 编辑器上方浮层 | `/` 面板、`@` 面板 |
| 队列显示 | 编辑器上方 | 队列条 |
| 状态行 | loader + 计时、重试倒计时、`setStatus` | 顶栏副标题 |
| 页脚 | **照搬**：`~/路径 (分支) • 会话名`、`↑入 ↓出 R W CH% $费用 (sub)`、`上下文%/窗口`、`(provider) 模型 • 思考`；各段可点；窄屏按 pi 规则截断（先省统计段，再缩路径），点页脚展开完整两行 | 顶栏只放模型胶囊与上下文环；完整统计在会话信息页 |
| 选择器弹层 | `TermSelectList`（搜索 + 列表 + 可点键帽提示行） | `CalmSheet` 底部面板 |
| 登录对话框 | 照搬（URL、设备码、粘贴回调） | 浏览器（Custom Tab）+ 回调自动完成；备选粘贴代码 |
| 首次设置 | 安装完成后进上次的形态（默认 UI） | 安装引导页 |
| 转录搜索 | 全屏搜索，`searchMatchBg` 高亮 | 会话菜单 › 搜索本会话 |
| Mermaid | 字符画 | 代码块 +「图示」（同一字符画渲染器）；WebView 成图 P2 |
| 彩蛋 | 不做 | 不做 |
| 配色 | 原样打包 pi `dark.json`/`light.json` | 极简色板（§4.2） |

### 2.14 清单 §12、§13

- §12 的候选删除：按 §1.1 执行。与清单不同处：回收站浏览页删、回收目录留（D1）；任务完成通知保留但默认关；Share Target 删。
- §13 无缝切换：§5 细化，验收见 §7.2。

---

## 3. 终端形态（`feature/term`，`TermSurface`）

### 3.1 渲染：Compose 画字符网格，不跑第二个 pi

- 不嵌真终端、不开第二个 `pi`：两个进程就是两份状态，与硬性要求 ② 矛盾（清单 §0.1）。
- 等宽字体用仓库里已有的 JetBrains Mono（`core/ui/src/main/res/font/jetbrains_mono_*.ttf`），`fontFeatureSettings = "calt 0"` 关连字，一字一格。字号默认 13sp，**双指缩放 10–18sp**（采纳 A），缩放后重新计算列数。
- 用 `TextMeasurer` 量单字宽，`cols = floor(可用宽度 / 字宽)`；页脚对齐、补全浮层宽度、截断都按 `cols` 计算。
- CJK 按东亚宽度占 2 列，`wcwidth` 表从 pi 依赖的 `string-width` 规则移植（NOTICE 记来源）。
- **框线用 `drawBehind` 绘制**，不拼 `─│╭╮` 字符（采纳 A），中英混排时边框不错位。
- 结构：一个 `LazyColumn` + 固定区：

```
┌ 页眉（可滚走：pi 版本、按键提示、已加载上下文 / 技能 / 扩展 / 模型范围）
│ 消息区：TermBlock（User / Assistant / Thinking / Tool / Bash / Summary / Custom / SkillInvocation / Error / LocalText）
├ 状态行：loader ⠋ + 计时 / 重试倒计时 / notify
├ 队列列表 + setWidget 区
├ 编辑器（drawBehind 圆角框，多行；边框色 = 思考档；bash 模式 = bashMode 色）
├ 补全浮层（编辑器上方，最多 autocompleteMaxVisible 行）
├ 页脚（两行）：~/proj (main) • 会话名        ↑12k ↓3.4k R80k W2k CH71% $0.042  38%/200k  (anthropic) claude-sonnet-4.5 • high  [UI]
└ 触屏按键条（可收起；接外接键盘时自动收起）
```

- 每个块由 `TermFormatter`（纯函数，可单测）从 `ChatRow` 转成「行 → 带 token 样式的片段」。Markdown 按 pi 风格：`mdHeading` 加粗、`codeBlockIndent` 缩进、`mdListBullet`、链接下划线；代码高亮输出 `syntax*` token。
- ANSI 用 `core/session` 的 `Ansi`（从 `feature/files` 移过来）。
- `SelectionContainer` 包住消息区；`fullscreenCopyOnSelect=true` 时选中即复制。

### 3.2 配色

- `feature/term/src/main/assets/pi-theme/dark.json`、`light.json` 原样打包。
- `PiThemeLoader` 解析 `vars` + 56 个 `colors`，okhsl → sRGB，得到 `PiTokens`（56 个 `Color`）。
- 跟随 `/settings › theme`：`system` 对应安卓深浅色；也支持 `~/.pi/agent/themes/*.json`。

### 3.3 编辑器与补全

- `BasicTextField` 绑定 `SessionHost.draft`（共享草稿）。左上角显示 `thinkingXxx` 色的档位字样。
- 首字符 `/` → 补全，候选 = `PiActionCatalog` 的斜杠别名 + `get_commands`；`@` → 文件模糊搜索；`!`/`!!` → 边框变色。
- 补全选中行 `selectedBg`；点击或键条 Tab 补全。
- 历史：编辑器为空时键条 ↑↓ 翻 `SessionHost.promptHistory`（本会话用户消息 + 全局最近 100 条）。

### 3.4 快捷键 → 触屏映射

键条一行可横滑，顺序：

`Esc  Tab  ⇣排队  ↑队  ^C  ⌃O  ⌕  ⇞  ⇟  ⤢  /  @  !  📎  ⇄UI  ⏎`

| pi 键 | 触屏 |
|---|---|
| Esc / 双击 Esc | Esc 键 / 连点两下 |
| Enter / Alt+Enter | ⏎ / ⇣ |
| Alt+↑ | ↑队 |
| Ctrl+C | ^C |
| Ctrl+O | 点工具块（单个）；⌃O（全部） |
| Ctrl+T | 点思考块 |
| Shift+Tab | 点编辑器边框 |
| Ctrl+L / Ctrl+P / Shift+Ctrl+P | 点页脚模型段 / 左滑 / 右滑 |
| Ctrl+G | ⤢ |
| Ctrl+V（图片） | 📎，或长按编辑器 › 粘贴图片 |
| Ctrl+X | 长按助手块 › 复制 |
| Ctrl+Shift+F | ⌕ |
| Ctrl+Shift+↑/↓ | ⇞ / ⇟ |
| 选择器内 Ctrl+S/N/R/D/P、树内 Ctrl+←/→、Shift+L、Shift+T、Ctrl+D/T/U/L/A/O | 选择器底部提示行的**可点键帽**（pi 的提示行本来就列出这些键） |
| 形态切换 | ⇄UI；页脚 `[UI]` |

**外接键盘**：`onPreviewKeyEvent` 读 `~/.pi/agent/keybindings.json`（缺省用 pi 默认表），完全照 pi。形态切换组合键 **Ctrl+Alt+T**，须通过单测 `SwitchChordNoConflictTest`：从 pi 1.5.0 包内默认键位表（`dist` 里的默认 keybindings，夹具随测试保存）断言该组合未被占用；被占用就换 Ctrl+Shift+U 并同样测试。

### 3.5 工具块、思考块、选择器

- 工具块：`toolPendingBg/SuccessBg/ErrorBg` 三态底色；标题 `toolTitle`（如 `read src/a.kt:1-80`）；输出 `toolOutput`；默认折叠到前 N 行加「… 12 more lines (ctrl+o)」（中文界面保留键名）。
- `edit`：`toolDiffAdded/Removed/Context` 三色。
- 思考块：`thinkingText` 色斜体；`hideThinkingBlock` 时只显示一行「Thinking…」。
- `TermSelectList`：顶部搜索 + 列表 + 键帽提示行；模型、思考、作用域模型、会话、树、分叉、设置、主题、OAuth、信任、扩展 select 全部复用。它渲染的是 `OverlayModel`（§5.3），与 UI 形态同一份数据。

---

## 4. UI 形态（`CalmSurface`）：极简

### 4.1 原则

- 一页为主：聊天页就是全部；其余在三个面板：☰ 会话列表、⌘ 命令中心、模型面板。
- 无玻璃、无渐变、无吉祥物；大面积留白，正文主导，只有一个强调色。
- 信息按需出现：统计、路径、已加载资源收在上下文环和会话信息页。

### 4.2 配色与字体

| 用途 | 浅色 | 深色 |
|---|---|---|
| 背景 | `#FAFAF8` | `#111111` |
| 表面（输入框、面板） | `#FFFFFF` + 1dp 描边 `#E8E8E4` | `#1A1A1A` + 描边 `#2A2A2A` |
| 正文 | `#1A1A1A` | `#ECECEC` |
| 次要 | `#6B6B6B` | `#9A9A9A` |
| 强调（唯一） | `#6D5BD0`（pi accent 的中性化） | `#9C8CFF` |
| 状态 | 成功 `#2F9E6B` / 错误 `#D0453B` / 进行中 = 强调色 | 同色系提亮 |

- 正文：系统 Sans 16sp，行高 1.55；代码与命令：JetBrains Mono 14sp。
- 圆角 12dp（输入框 20dp）；8dp 网格。
- 图标：Material Symbols Rounded 子集（替换 `dsh_icons.ttf`）。

### 4.3 页面结构

```
┌──────────────────────────────────────┐
│ ☰   重构登录流程 ▾          ⌘  ⋯  ⇄  │  ← 标题点按改名；⋯ 会话菜单；⇄ 切到终端形态
│     ~/proj · 生成中 0:12             │  ← 副标题：目录 / 状态 / setStatus
├──────────────────────────────────────┤
│                    帮我把 auth 拆出来 │  ← 用户消息（右，浅色块）
│ ▸ 思考了 6 秒                        │
│ ● 读取 src/auth/Login.kt      0.2s   │  ← 过程行（点开看内容）
│ ● 编辑 Login.kt  +24 −9       0.1s   │
│ ◌ 运行 ./gradlew test               │  ← 进行中
│ 我把 Auth 拆成了三个文件……           │  ← 助手正文，流式
│ ┌ 已压缩 150k → 32k ─────────── ▸ ┐  │
├──────────────────────────────────────┤
│ 排队 1 条：再跑一次 lint      撤回   │  ← 队列条（有才出现）
│ [widget 区]                           │  ← 扩展 setWidget（有才出现）
│ ┌──────────────────────────────────┐ │
│ │ 输入消息，/ 命令，@ 文件，! 运行  │ │
│ │ [img]                         ⤢  │ │  ← ⤢ 展开全屏编辑；× 清空
│ │ ＋   ◐ high   sonnet-4.5 ◯38%  ■ │ │  ← ＋附件 · 思考胶囊 · 模型胶囊 · 上下文环 · 发送/引导/停止
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

- **会话菜单（⋯）**：分支树、复制会话、导出（HTML / JSONL）、分享链接、搜索本会话、全部展开 / 折叠工具、会话信息、切换形态。
- **☰ 会话列表**：顶部 ＋新对话（长按：临时会话 / 在其他目录新建）、搜索框、⋯ 菜单（排序：最近 / 名称、只看已命名、显示路径、导入会话）；列表左滑删除（进回收目录 + 5 秒撤销），长按重命名；底部齿轮 = 设置（只有「模型」）。
- **⌘ 命令中心**（底部面板，全高）：

```
┌ ⌘ 搜索 pi 功能（中文 / 斜杠名 / 拼音首字母）┐
│ 会话   新建 · 恢复 · 分叉 · 复制 · 分支树 · 压缩 · 导出 · 分享 · 导入 · 改名 · 会话信息 · 临时会话 · 一次性提问 · 搜索本会话
│ 模型   选择模型 · 上/下一个 · 常用模型 · 思考强度 · 设为默认 · 刷新目录 · 登录 · 退出登录
│ pi 设置 显示 · 缓存 · 队列 · 编辑器 · 会话树 · 安全 · 工具 · 会话 · 压缩 · 图片 · Markdown · 网络 · 重试 · Shell · 扩展与资源 · 隐私 · 仅终端形态
│ 资源   技能 · 提示模板 · 扩展与包 · MCP · 上下文文件 · 系统提示 · 项目信任 · 重新加载 · llama.cpp
│ 扩展命令（get_commands 动态）
│ 帮助   手势与快捷键 · 更新日志 · 报告问题 · 诊断 · 外观 · 关于 · 停止引擎 · 说明类条目
└──────────────────────────────────────────┘
```

- 每项是一条 `PiAction`，`testTag("action:<id>")`，显示 pi 原命令名。
- 输入框敲 `/`：同一列表变内联补全面板，按斜杠别名过滤。
- 模型设置页底部一行：「pi 的其他设置在 ⌘ › pi 设置」。

### 4.4 发送键三态（采纳 A，修第 1 轮 Q4「有草稿时停不下来」）

| 状态 | 输入框 | 发送键 | 长按 |
|---|---|---|---|
| 空闲 | 任意 | ↑ 发送（空时禁用） | 排队不可用 |
| 生成中 | 空 | ■ 停止（`abort`；重试中则 `abort_retry`；bash 运行中则 `abort_bash`） | — |
| 生成中 | 有字 | 「引导」（`steer`） | 菜单：排队（`follow_up`）/ 停止 |

`!` 命令模式下按钮文字为「运行」。

### 4.5 组件

`CalmComposer`（共享草稿）、`ProcessRow`（工具、思考、bash 共用骨架）、`SummaryCard`（压缩、分支摘要）、`CalmSheet`（全部 `OverlayModel` 的底部面板渲染器）、`ContextRing`、`ModelPill`、`ThinkingPill`、`QueueBar`、`WidgetArea`、`ExtDialogSheet`、`TranscriptSearch`、`MermaidAscii`。长按消息菜单：复制、复制 Markdown、从这里分叉（用户消息）、在分支树中定位。

### 4.6 动效

只做三类，180ms `FastOutSlowIn`：新块淡入 + 4dp 上移；面板滑入；发送 / 引导 / 停止键形变。流式文字直接追加不做逐字动画。切换形态 120ms 交叉淡化，**只淡化渲染层，不重建状态**。

---

## 5. 无缝切换架构（硬性要求 ②）

### 5.1 分层

```
          ┌──────────── 视图层（可互换，零业务状态） ────────────┐
          │ TermSurface（feature/term）   CalmSurface（feature/chat/ui/calm）│
          └───────────────▲───────────────────────▲────────────┘
                          │ StateFlow               │ PiActions.run(id)
┌──────────────────── SessionHost（AppGraph 单例，与进程同寿） ───────────────────┐
│ engine: EngineClient + EngineExtras      chat: ChatController（ChatScreenState）   │
│ draft: MutableStateFlow<DraftState>（文字、选区、图片、@ 引用、! 模式；DraftStore 持久化）│
│ overlay: OverlayStack（面板模型栈，含选择器里的搜索词、滚动位置、表单已填值）        │
│ form: MutableStateFlow<UiForm>（TERM | CALM，存 App 设置）                          │
│ promptHistory · scrollAnchor（按 ChatItem.key） · localBlocks（/session 等本地块）  │
│ actions: PiActions   settings: PiSettings   sessions: SessionCatalog               │
└──────────────────────────────────────────────────────────────────────────────────┘
                          │
             PiEngine ── pi --mode rpc（唯一进程）── pigo-bridge.ts（-e 加载）
```

规则：
- `ChatController`、`SessionHost` 的公开 API **没有任何形态参数**（反射单测 `FormSwitchContractTest`）。
- `form` 只被两处读：顶层 `FormHost`（决定画哪一层）与 App 设置（记住上次形态）。
- 两种形态的 Composable 不持有业务状态，只有纯视图状态（例如动画进度）。

### 5.2 切换入口（一键）

- UI 形态：顶栏 ⇄；会话菜单 › 切换形态。
- 终端形态：键条 ⇄UI；页脚右端 `[UI]` 键帽。
- 本地命令 `/ui`、`/term`（不发给 pi）。
- 外接键盘 Ctrl+Alt+T（§3.4 的冲突单测）。

实现：

```kotlin
val form by host.form.collectAsState()
Crossfade(form, animationSpec = tween(120)) { f ->
    when (f) { UiForm.TERM -> TermSurface(host); UiForm.CALM -> CalmSurface(host) }
}
```

### 5.3 切换时什么都不丢

| 状态 | 存放 | 切换时 |
|---|---|---|
| 引擎进程 | `EngineService` + `SessionHost.engine` | 不受影响；**切换不发出任何 RPC 命令**（命令日志前后无新增，单测断言） |
| 流式回复、工具状态、队列、重试倒计时、压缩进度 | `ChatReducer` → `ChatController.state` | 继续流式；新形态一订阅就看到最新内容 |
| 草稿：文字、光标、选区、图片、`@` 引用、`!` 模式、「已撤回」芯片 | `SessionHost.draft` + `DraftStore` | 原样带过去；IME 焦点在新形态重新请求，光标取 `draft.selection` |
| 扩展阻塞对话框（select / confirm / input / editor） | `ChatController` 持有 request id 与超时；`OverlayStack` 里是 `OverlayModel.ExtDialog` | 新形态立刻画同一个对话框；计时不中断；**不回 cancelled** |
| 打开中的选择器 / 面板（模型面板、`/tree`、会话选择器、pi 设置……） | `OverlayStack` 栈顶的 `OverlayModel`，含 **搜索框里输了一半的词**、当前页签、滚动位置、表单已填值（采纳 A 的 `UiSession`） | 新形态按同一模型画出来，搜索词、页签、已填值都在 |
| 阅读位置 | `scrollAnchor`（首个可见块的 `ChatItem.key` + 偏移）；贴底跟随标志 | 新形态 `scrollToItem(anchor)`；原来贴底的继续贴底 |
| 本地文本块（`/session`、`/hotkeys`、`/changelog` 的输出） | `SessionHost.localBlocks`（不写会话文件） | 两种形态都显示 |
| 生命周期 | `SessionHost` 由 `AppGraph` 持有 | 旋转、深浅色、切形态都不重建；进程被杀后按 `ResumePolicy` + `DraftStore` 恢复 |

### 5.4 `PiActions` 动作表

```kotlin
// core/contract（只新增）
data class PiActionSpec(
  val id: String,                 // "session.fork"
  val title: String,              // "从消息分叉"
  val group: ActionGroup,         // SESSION / MODEL / SETTINGS / RESOURCES / EXT / HELP
  val slash: List<String>,        // ["fork"]
  val featureRefs: List<String>,  // 对应清单行，如 ["§2.1 fork", "§3 /fork"]
  val ui: UiEntry,                // Command(⌘) | Persistent(tag) | LongPress(tag) | Explain(text)
  val term: TermEntry,            // Slash | KeyStrip(key) | Footer(segment) | KeyCap(selector,key) | Explain(text)
)
interface PiActions {
  val all: List<PiActionSpec>
  suspend fun run(id: String, args: String? = null): ActionResult
}
sealed interface ActionResult {
  data object Done : ActionResult
  data class Overlay(val model: OverlayModel) : ActionResult   // 压进 overlay 栈，由各形态自己画
  data class Toast(val text: String) : ActionResult
  data class LocalBlock(val title: String, val text: String) : ActionResult
}
```

- `run` 只写一次（`core/session`），两种形态共用。
- `OverlayModel` 是与形态无关的面板模型：`SelectList`（分组、搜索词、星标 / 开关 / 拖动排序、键帽）、`Form`（字段按 schema：开关、枚举、数字、字符串、字符串列表）、`Text`、`Confirm`、`Tree`（节点、折叠、标签、时间、筛选）、`ExtDialog`、`FullOutput`（`full_output_path`）。两种形态各画一套，逻辑只有一份。
- **依赖反转（修 C）**：`PiActions.run` 需要的模型配置面板（服务商列表、服务商表单、模型面板、OAuth 流程）由 `core/contract` 里的接口 `ModelPanels` 声明，`feature/models` 实现，`AppGraph` 注入 `core/session`。`core/session` 不依赖 `feature/models`。

### 5.5 唯一允许重启引擎的路径

进程环境变量类设置（`PI_OFFLINE`、`PI_TELEMETRY` 等）必须重启引擎才生效：界面先确认「将重启引擎，当前生成会被中止」。这条路径与切换形态无关，切换测试里断言 `restartCount == 0`。

### 5.6 补 RPC 缺口：`pigo-bridge.ts`

随 App 打包 `core/engine/src/main/assets/pi/pigo-bridge.ts`，`EngineConfig` 用 `-e` 加载。注册隐藏命令（经 `prompt` 发送，生成中也立即执行），结果通过 `extension_ui_request` 的 `notify`/`setStatus` 加 JSON 前缀回传：

- `/pigo:tree-nav <entryId> [--summarize]` → `ctx.navigateTree`
- `/pigo:label <entryId> <text>` → `pi.setLabel`
- `/pigo:reload` → `ctx.reload()`
- `/pigo:switch <path>` → `ctx.switchSession`

文件型功能（`settings.json`、`auth.json`、`models.json`、`mcp.json`、`keybindings.json`、SYSTEM / AGENTS 文件、会话导入、信任）由 Kotlin 直接读写，写完调 `/pigo:reload`。`/share`、`/bug`、`/changelog`、包管理、一次性提问用 `GuestRunner` 执行 `pi` CLI。

**退路**：扩展加载失败（`extension_error`）时，树内切换退化为「`fork` 出新会话」，reload 退化为「重启引擎（先确认）」，标签不可用并提示原因。

### 5.7 需要先实测的三件事（r3/core 第一件事，结果写进 PR）

| 事项 | 风险 | 处理 |
|---|---|---|
| `navigateTree`/`setLabel`/`reload` 在 RPC 模式下的行为 | 树切换、标签、热重载做不了 | 用真 pi 1.5.0（`core/engine/src/test/…/realpi/` 现有框架）做最小验证；不可行就启用 §5.6 的退路 |
| `/trust` 在 RPC 模式下 | 项目 `.pi/` 资源可能不加载 | 实测；必要时由 App 写信任文件或启动参数加 `-a`（仅对用户已确认信任的目录） |
| `--no-session` 运行中不能切换 | 临时会话只能近似 | `SessionHost` 标记临时会话；离开时（新建、切换、App 退出）把会话文件**移进回收目录**（遵守 D1），不出现在列表；界面写明「临时会话：离开后不保留」 |

### 5.8 `settings.json` 并发写

pi 自己也会写 `settings.json`（如保存默认模型）。`PiSettings` 的读-改-写放在与 `AuthJsonLock` 同款的文件锁里，**只改目标键、保留未知键与格式**，写完调 `/pigo:reload`。用夹具做前后快照单测。

---

## 6. 模型配置（唯一的设置项）

### 6.1 入口

- 设置（☰ 底部齿轮）只有一项「模型」。仪器测试断言设置页根节点只有一个分组。
- 模型面板（点模型胶囊 / `/model` / 点页脚模型段）底部有「管理服务商」。

```
设置 › 模型
┌────────────────────────────────────┐
│ 默认模型   claude-sonnet-4.5 · high ›│  ← defaultProvider / defaultModel / defaultThinkingLevel
│ 常用模型   4 个 ›                    │  ← enabledModels（= /scoped-models）
├ 已连接 ─────────────────────────────┤
│ ● Anthropic   账号 · Claude Max   › │
│ ● DeepSeek    Key ···a1f3          › │
├ 全部服务商（42）  🔍 ───────────────┤  ← 搜索支持拼音首字母（「qw」→ qwen）
│ ○ Amazon Bedrock         区域/Key › │
│ …                                   │
├ 自定义 ─────────────────────────────┤
│ ＋ 添加自定义服务商                  │
│ ● my-vllm   http://10.0.0.2:8000   › │
├ 高级 ───────────────────────────────┤
│ 思考预算 · 每模型思考档 · 刷新模型目录 │
└────────────────────────────────────┘
pi 的其他设置在 ⌘ › pi 设置
```

### 6.2 服务商页

沿用 W2 `ProviderConfigScreen` 的逻辑（OAuth、Key、多字段、回填 `savedFields`、测试连接），只换视觉。OAuth 走 Custom Tab，回调自动完成，备选粘贴代码；Key 只显示末四位；高级里可写 `!命令` 形式的 key 与额外 `env`。终端 `/login` 走同一套 `ModelPanels` 面板模型。

### 6.3 自定义服务商

沿用 `CustomProviderScreen` + `ModelDiscovery`/`ProviderTester`。字段：baseUrl、api 类型（openai-completions / openai-responses / anthropic-messages / bedrock-converse-stream / mistral-conversations…）、Key（可 `!命令`）、headers、模型列表（自动发现、可手动加）、modelOverrides、compat。

### 6.4 会话内切换

- 模型面板三个页签：「全部」（可搜索，已配置在前，未配置灰显，点了跳服务商页，配好后自动选回，复用 W5 `PendingModel`）、「常用」（星标、拖动排序、每模型固定思考档）、「最近」。
- 选择调 `set_model`；**生成中也可切**，提示「下一轮生效」。
- 思考强度：UI 胶囊单击 `cycle_thinking_level`，长按列出 `get_available_thinking_levels` 返回的档位；终端点编辑器边框或 `/thinking`。
- 「设为默认」写 `settings.json` 的 default*（等同 pi 的 Ctrl+S）。

### 6.5 pi 的其他设置（不在设置页）

- ⌘「pi 设置」/ 终端 `/settings`，分组同 §2.10。
- 控件**按 `PiSettingsSchema` 生成**（采纳 B）：开关、枚举、数字、字符串、字符串列表五类，**不提供自由文本 JSON 编辑**，防止写坏配置。
- 单测 `PiSettingsSchemaCoverageTest`：解析 `docs/pi-features.md` §8 表格里的全部键，断言 schema 每个键都有（采纳 A）。

---

## 7. 两条硬性要求的可执行验收

### 7.1 硬性要求 ①：UI 形态覆盖清单全部功能，终端形态完整

1. **覆盖映射** `docs/r3/coverage-map.tsv`：清单 §1–§11 每张表的每一行一条，格式 `节号<TAB>行首单元格原文<TAB>动作id | BASE | FORM | NA`。BASE = 底座（如 `get_entries`）；FORM = 形态本身（如换行、补全）；NA = 清单写明不适用（如 powershell），NA 也必须对应一个 `Explain` 动作（说明文字非空）。
2. **JVM 单测** `core/session/src/test/…/PiFeatureCoverageTest.kt`（r3/session）：
   - 解析 `docs/pi-features.md` 所有 `|` 表格的数据行首列（跳过表头和分隔行），断言每条都在 `coverage-map.tsv` 里，**一条不缺**；清单新增行而映射没跟上就失败。
   - 每个映射到的动作 id 都在 `PiActions.all` 里，且 `ui` 与 `term` 都不为空；`Explain` 的说明文字非空。
   - 没有孤儿动作（每个动作的 `featureRefs` 非空，且都能在清单里找到）。
   - 跑法：`./gradlew :core:session:testDebugUnitTest --tests '*PiFeatureCoverageTest*'`（文档路径经系统属性传入）。**进 CI**（§8.4）。
3. **仪器测试**（用 `FakeSessionHost`，CI 模拟器任务可跑）：
   - `feature/chat/src/androidTest/…/UiCoverageTest.kt`（r3/calm）：遍历 `PiActions.all`：`Command` → 打开 ⌘、搜索标题、点 `action:<id>`，断言出现该动作声明的结果标记（`overlay:<id>`、Fake 的 rpc 调用记录或 `explain:<id>`）；`Persistent` → 常驻控件可见且可点；`LongPress` → 在夹具消息上长按，菜单项存在。**任何一项缺失即失败。**
   - `feature/term/src/androidTest/…/TermCoverageTest.kt`（r3/term）：输入 `/别名` 回车，或点键条键 / 页脚段 / 键帽，断言同样的结果标记。
   - `app/src/androidTest/…/AppCoverageTest.kt`（r3/app）：在真实装配（`SessionHost` 实现 + Fake 引擎）上把上面两项各跑一遍。
4. **人工复核**：r3/app 的 PR 描述贴 `coverage-map.tsv` 统计（总行数、各动作数、BASE / FORM / NA 数及 NA 理由）。

### 7.2 硬性要求 ②：同一会话、同一引擎，切换不丢不断不重启、保留草稿、一键即切

1. **JVM 契约** `core/session/src/test/…/FormSwitchContractTest.kt`（r3/session）：反射断言 `ChatController`、`SessionHost` 的公开方法没有 `UiForm` 参数；`form` 变化前后 Fake 引擎的命令日志**没有新增任何命令**。
2. **JVM 行为** `core/session/src/test/…/SessionHostFormSwitchTest.kt`（r3/session），用带计数的 `FakeEngineClient`：
   1. 开始一条流式回复（每 50ms 一段，共 200 段）；草稿设为「草稿abc」，1 张图片、`@a.kt` 引用、光标在 3；压入一个扩展 `confirm` 和一个打开中的模型面板（搜索词「son」）；
   2. 生成期间连续切换 `form` 20 次；
   3. 断言：`startCount == 1 && restartCount == 0`；命令日志里没有 `abort`、`new_session`、`switch_session`，也没有对话框的 cancelled 回复；`chat.state` 是同一实例；最后一条助手消息 = 全部分段按序拼接（不丢不重）；`agent_settled` 只出现一次；`draft` 与切换前逐字段相等；overlay 栈相同（含搜索词「son」）；`sessionFile` 不变。
3. **仪器 A 档（无 Key，CI 模拟器任务跑）** `app/src/androidTest/…/FormSwitchStreamingTest.kt`（r3/app）：`BindingMode` 注入 `FakeSessionHost` 的慢速流（10 秒、500 段），经真实 UI 点 `form:toggle`（UI 顶栏 ⇄、终端键条 ⇄UI）每 300ms 切一次共 10 次。断言：屏幕上助手文本长度单调不减、最终等于全部分段拼接；切换后输入框显示「草稿abc」、光标位置正确、缩略图在；发送键 / 停止键状态正确；预先放入的扩展 `confirm` 在切换后仍显示，点「确定」后 Fake 收到同一 request id；打开中的模型面板搜索框里仍是「son」；列表仍贴底；Fake `startCount == 1`。
4. **仪器 B 档（真引擎；需 `pigoProvider`/`pigoKey` 参数，否则 Assume 跳过）**：
   1. 用 `SessionHost.debugEnginePid()`（经 `core/runtime/process/ProcessTree.kt`，不用 `pidof node`）记录 pi 进程 PID 与 `get_state.sessionId`；
   2. 发送「从 1 数到 300，每行一个数」；
   3. 第一个 `message_update` 之后每 1.5 秒切一次形态，共 6 次；
   4. 断言：PID、`sessionId` 不变；引擎状态全程没有 `Starting`/`Stopped`/`Crashed`；最终回复以 300 结束、`stopReason == "stop"`；草稿保留；切换后发「刚才最后一个数是几？」，回复包含 300（上下文保留）。
   - 跑法：`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.zr987459.pigo.FormSwitchStreamingTest [-Pandroid.testInstrumentationRunnerArguments.pigoProvider=deepseek -Pandroid.testInstrumentationRunnerArguments.pigoKey=…]`。
5. **人工**（`docs/testing/r3-device-checklist.md`）：真机切换前后在终端形态执行 `!echo $PPID` 与 `/session`，对比 PID 与 sessionFile；录屏确认流式文字不闪、不跳到顶部。

### 7.3 其他必须有的测试（来自评审）

| 测试 | 属主 | 内容 |
|---|---|---|
| `AbortRetryTest` | r3/session | `retry.abort` 只发 `abort_retry`，不发 `clear_queue`/`abort` |
| `QueueBackToDraftTest` | r3/session | 切换会话或引擎重启时，排队中 / 发送中的文字回到草稿（有草稿时进「已撤回」芯片） |
| `AtFileExpansionSnapshotTest` | r3/session | `@` 展开结果与 pi 1.5.0 `interactive-mode` 源码的格式一致（夹具记录源码出处） |
| `PiSettingsSnapshotTest` | r3/session | 改一个键：只该键变化，未知键与其他键保留；并发写在文件锁内 |
| `PiSettingsSchemaCoverageTest` | r3/session | schema 覆盖清单 §8 全部键 |
| `LocalBlocksNotPersistedTest` | r3/session | `/session`、`/hotkeys`、`/changelog` 的输出不写入会话文件 |
| `SwitchChordNoConflictTest` | r3/term | 形态切换组合键不与 pi 默认键位冲突 |
| `PiThemeTokensTest` | r3/term | 56 个 token 全部解析，值与在 node 里跑 pi 主题模块的输出一致 |
| `FooterSnapshotTest` | r3/term | 页脚排版与 pi `footer.js` 在同一组输入下一致；窄屏截断顺序正确 |
| `TermFormatterGoldenTest` | r3/term | 与真 pi 1.5.0 在 80 列下的输出逐行比对，差异 ≤ 5%（只允许宽字符边界差异） |
| `SettingsOnlyModelsTest` | r3/models | 设置页根节点只有「模型」一组 |
| `ProviderSearchPinyinTest` | r3/models | 「qw」只剩 qwen 相关；42 个服务商都可见 |
| `UndoDeleteTest` | r3/models | 删除进回收目录，5 秒内撤销后文件回到原路径，内容一致 |
| `NoRemovedFeaturesTest` | r3/session（r3/app 合入后启用） | §1.4 |
| 慢帧门槛 | r3/calm、r3/term | 1000 条流式 delta 下慢帧 < 5%（复用现有 `StreamingJankTest` 思路） |

---

## 8. 工作流（6 条，文件范围互不重叠）

### 8.1 范围总表

| # | 分支 | 文件范围（独占） | 依赖 |
|---|---|---|---|
| 1 | `r3/core` | `settings.gradle.kts`、`gradle/**`、所有 `build.gradle.kts`（scaffold 角色）、`.github/workflows/**`（D3 授权）、`core/contract/**`（只新增文件）、`core/engine/**`、`core/protocol/**`、`core/workspace/**`、`core/runtime/**`（只在需要时）、`docs/notices/r3-core.md`、`docs/protocol/**` | 无，最先开始 |
| 2 | `r3/session` | 新模块 `core/session/src/**`、`feature/chat/src/main/**`（**不含** `ui/**`）、`feature/chat/src/test/**`（**不含** `SuggestionTextTest.kt`）、`feature/commands/**`（含删除 `ui/**`）、从 `feature/usage` **移出**的 `logic/LineDiff.kt`、`logic/SessionTreeRows.kt`、`logic/CompactionSummary.kt` 及其测试、从 `feature/files` **移出**的 `output/Ansi.kt` 及其测试、`core/contract/**/actions/PiActionCatalog.kt`（r3/core 首推后移交给本线）、`docs/r3/coverage-map.tsv`、`docs/notices/r3-session.md` | r3/core 首推 |
| 3 | `r3/models` | `feature/models/**`、`feature/settings/**`、`core/catalog/**`、`core/store/**`、`docs/notices/r3-models.md` | r3/core 首推 |
| 4 | `r3/calm` | `feature/chat/src/main/**/ui/**`、`feature/chat/src/androidTest/**`、`feature/chat/src/test/**/SuggestionTextTest.kt`、`core/ui/**`、`feature/sessions/**`、`feature/onboarding/**`、`docs/notices/r3-calm.md` | r3/core 首推（开发期用 `FakeSessionHost`） |
| 5 | `r3/term` | `feature/term/**`（不含 `build.gradle.kts`）、`docs/notices/r3-term.md` | r3/core 首推（开发期用 `FakeSessionHost`） |
| 6 | `r3/app` | `app/**`（含 `androidTest`）、`feature/usage/**`（被 r3/session 移出的文件除外）、`feature/files/**`（被移出的 `Ansi.kt` 除外）、`THIRD_PARTY_NOTICES.md`（合并各 `docs/notices/r3-*.md`）、`docs/testing/**` | 删除部分第一天就能做「只被 app 引用」的；接线等 1–5 合入 |

说明：
- 本文与 `docs/r3/` 以外的 `docs/**` 只由本次定稿修改；各工作流只改自己的 `docs/notices/r3-<名>.md` 与 `docs/status/<分支名去斜杠>.md`。
- 字体 JetBrains Mono 已在 `core/ui/res/font`。r3/term 需要它时**只读引用**（`feature/term/build.gradle.kts` 由 r3/core 加 `:core:ui` 依赖），不改 `core/ui`。
- 会话删除撤销只由 `core/store/FileSessionRepository`（r3/models）实现；`SessionCatalog`（r3/session）只调用。
- `PiActionCatalog.kt`（全部动作 id、标题、斜杠名、featureRefs、两种入口）由 r3/core 首推时写出第一版，之后**只归 r3/session**（移交时点写进两边的状态文件）。`run` 的实现也在 r3/session。

### 8.2 删除如何安全进行（C 的属主删除 + A 的删除纪律）

1. **谁的范围谁删**（§1.1 每项已落到属主）。
2. **先断引用，再删文件**：被别的工作流引用的文件，引用方先在自己的 PR 去掉引用（或改指新位置），合入 main 后属主再删。
3. **移动不复制**（修 C）：`LineDiff`、`SessionTreeRows`、`Ansi` 由 r3/session 用 `git mv` 移进 `core/session`，**同一个 PR** 里把所有引用改到新位置（引用方在 r3/session 范围内的直接改；在别人范围内的，由 r3/session 先加 `typealias`/转发桥接，属主线改指后 r3/session 删桥）。`feature/usage/logic/CompactionSummary.kt` 有用的部分并进 `core/protocol/CompactionSummary.kt`（经 r3/core 的 PR）后由 r3/session 删除。过渡期不存在两份实现。
4. **只增不删期**：各线开发期间，要删但还被引用的类先加 `@Deprecated("r3: <属主> 删除")`，新代码不得引用；PR 里贴 `grep` 结果。
5. **按模块分提交**：每删一个模块或一组文件就跑 `./gradlew assembleDebug testDebugUnitTest`，一个提交一组；出红回到上一个提交修，不强推。PR 描述逐个列出被删文件路径。
6. **gradle include**：`settings.gradle.kts` 去掉 `include(":feature:usage")` 与注释里的 `:feature:pet` 等，由 r3/core 在 r3/app 删模块的 PR 合入**之后**单独提只改这几行的小 PR。
7. **用户数据**：删除会话的回收目录保留（D1），旧回收站里的文件不动；汇率设置直接丢弃（无用户数据）；DraftStore 格式若变化须兼容读取旧格式。
8. 所有分支每次推送前 `git fetch && git merge origin/main`（不 rebase、不 force push），冲突在自己范围内解决。

### 8.3 合入顺序

1. **r3/core 首推**（≤ 1 小时）：新模块骨架（`core/session`、`feature/term`）、契约 + Fake（`FakeSessionHost` 带可编程流式与命令日志）、`PiActionCatalog` 第一版（全部 id，`run` 占位）、CI 改动。合入 main 后其余五线开工。
2. 并行开发：r3/session ∥ r3/models ∥ r3/calm ∥ r3/term ∥ r3/core（实现：桥接、`@` 搜索、实测三件事），r3/app 先做「只被 app 引用」的删除准备。
3. 合入：**r3/core（实现）→ r3/session → r3/models → r3/calm → r3/app 第一个 PR（接线 UI 形态，此时终端形态入口显示「终端形态尚未合入」占位，不发布）**。
4. **r3/term → r3/app 第二个 PR**（接线终端形态、删除旧模块文件、`AppCoverageTest`、`FormSwitchStreamingTest`、NOTICE、真机清单）。
5. **r3/core 小 PR**：去掉 `:feature:usage` include 与注释残留。
6. r3/session 启用 `NoRemovedFeaturesTest`（去掉 `@Ignore`）。

「先交付 UI 形态」体现在第 3 步：UI 形态先可在内部试用；**对外发布在第 4 步之后**，此时两条硬性要求的测试全部通过。

### 8.4 CI（D3 授权，r3/core 负责，PR 里单独标明）

在 `.github/workflows/android.yml` 增加（不删现有步骤，不改签名、发布配置）：
1. `./gradlew testDebugUnitTest`（全部模块单测，含 `PiFeatureCoverageTest`、`SessionHostFormSwitchTest`、`FormSwitchContractTest`、`PiSettingsSchemaCoverageTest`）。
2. `./gradlew assembleDebugAndroidTest`（全部模块仪器测试编译）。
3. 新 job「仪器测试（模拟器）」：`ubuntu-latest` + KVM + `reactivecircus/android-emulator-runner`（API 34，x86_64），跑 `UiCoverageTest`、`TermCoverageTest`、`AppCoverageTest`、`FormSwitchStreamingTest`（A 档；B 档因无 Key 自动跳过）。在对应测试合入前，该 job 对缺失的测试类不报错（用 `--tests` 过滤存在的类或 `continue-on-error: false` + 条件判断，r3/core 实现时选定并写明）。

### 8.5 各工作流验收标准

| 分支 | 验收（全部要在 PR 描述贴本地或 CI 结果） |
|---|---|
| r3/core | 首推：`assembleDebug testDebugUnitTest` 绿；`git diff main --stat` 只有新增文件 + `settings.gradle.kts` + `.github` 改动；`FakeSessionHost` 有单测（流式、命令日志）。实现：§5.7 三件事的实测结论；`realpi/` 集成测试：桥接加载成功，`/pigo:tree-nav` 后 `leafId` 改变且 `sessionFile` 不变，`/pigo:label` 后 `get_tree` 有标签，`/pigo:reload` 后新模板出现在 `get_commands`，生成中发 `/pigo:label` 立即执行且不打断生成；`@` 搜索单测；`branchName()` 单测；`GitParse` 已删且无引用；现有 protocol、engine 单测全绿；CI 新步骤生效 |
| r3/session | `PiFeatureCoverageTest`、`FormSwitchContractTest`、`SessionHostFormSwitchTest`（§7.1、§7.2 两条硬性要求的 JVM 部分）通过；§7.3 中属于本线的测试通过；`PiActions` 覆盖 coverage-map 全部非 BASE/FORM/NA 项且无孤儿；`AutoNamer`/`AutoTitle`、`feature/commands/ui/**` 已删；`LineDiff`/`SessionTreeRows`/`Ansi` 已移动、无重复实现 |
| r3/models | `SettingsOnlyModelsTest`、`ProviderSearchPinyinTest`、`UndoDeleteTest` 通过；42 个服务商可见可配置；OAuth 登录冒烟（Fake 回调）；自定义服务商发现与测试；未配置模型点选 → 配置 → 自动选回；生成中切模型提示「下一轮生效」；`ModelPanels` 实现产出的 `OverlayModel` 在两种形态的渲染器夹具里都能画；全仓 `grep -riE "cny|汇率|exchangeRate"` 无结果；回收站浏览页、`DshComponents`、`NetworkAndCostScreen` 已删；`feature/models`、`core/catalog`、`core/store` 单测全绿 |
| r3/calm | `UiCoverageTest`（硬性要求 ① 的 UI 部分）通过；用 `FakeSessionHost` 的切换夹具：UI 形态离开再进入后草稿、overlay（含搜索词）、阅读位置都恢复；⌘ 按中文名、斜杠名、拼音首字母都能找到每个动作；发送键三态 Compose 测试；1000 条 delta 慢帧 < 5%；深浅色截图；`core/ui` 无 dsh 资源（含 `dsh_icons.ttf`）、`SuggestionText` 已删 |
| r3/term | `TermCoverageTest`（硬性要求 ① 的终端部分）、`TermFormatterGoldenTest`、`PiThemeTokensTest`、`FooterSnapshotTest`、`SwitchChordNoConflictTest` 通过；切换夹具：终端形态离开再进入后草稿、overlay、阅读位置都恢复；外接键盘 Esc / Enter / Alt+Enter / Ctrl+L / Shift+Tab 与 pi 一致（仪器）；1000 条 delta 慢帧 < 5% |
| r3/app | `FormSwitchStreamingTest` A 档在 CI 模拟器过、B 档在真机过；`AppCoverageTest` 过；`assembleDebug assembleDebugAndroidTest` 绿；`feature/usage` 已移除、文件浏览器 / Git 页 / Share Target 已移除且无残留引用；导航只剩：安装 → 会话（`Crossfade(form)`）→ 设置 › 模型 → 关于；NOTICE 合并（pi 主题 JSON、wcwidth、各分支移植来源）；`docs/testing/r3-device-checklist.md` 完成 |

---

## 9. 复用、风险与取舍

### 9.1 复用

- 原样：`core/protocol`、`core/engine`（PR #13）、`core/runtime`；`core/catalog` 与 `core/store` 的服务商、OAuth、自定义、Key 加密；`feature/chat` 的 `ChatController`/`Outbox`/`ExtUi`/`SlashCompletion`/`ComposerLogic`；`feature/commands` 的 `DraftStore`/`PiSessionIndex`/`SessionScanner`/`SkillTemplateManager`；`feature/files` 的附件、导出后分享、`Ansi`/`OutputViewer`；`feature/usage` 的 `LineDiff`/`SessionTreeRows`；W5 的 `PendingModel` 与 `MessageMenu` 思路；`core/engine` 测试里现有的 `realpi/` 框架（`LocalRuntime`、`MockOpenAiServer`）。
- 重画：所有 Composable 视觉。

### 9.2 风险

| 风险 | 缓解 |
|---|---|
| 终端形态是「画出来的 TUI」，细节可能偏差 | 真 pi 80 列输出逐行比对、`footer.js` 快照、主题 JSON 原样打包 |
| 桥接扩展 API 在 RPC 下行为未实测 | r3/core 第一件事实测（§5.7）；不可行走退路（§5.6） |
| 动作表要一开始列全 | r3/core 首推只列 id 与入口，覆盖测试从第一天就能跑，追踪进度 |
| 设置页只剩模型，用户找不到 pi 设置 | ⌘ 支持中文、斜杠名、拼音首字母搜索；模型设置页底部指引 |
| 进程环境变量类设置要重启引擎 | 唯一允许重启的路径，先确认（§5.5） |
| 大量删除引发冲突 | §8.2 |
| `settings.json` 与 pi 并发写 | 文件锁 + 只改目标键（§5.8） |
| 窄屏（40–48 列）页脚放不下 | 按 pi 截断规则，点页脚展开两行 |
| r3/session 范围较大 | 已把 scaffold、契约、引擎、桥接、workspace 拆给 r3/core；r3/session 按「动作表 → 设置 → 会话目录 → 测试」分 PR 提交 |

---

## 10. 摘要

- 一个 pi 进程、一个 `SessionHost`、一张 `PiActions` 动作表；终端和 UI 只是两种画法，切换只换渲染层，不发任何 RPC。
- 设置页只剩模型配置；pi 的 `/settings` 作为 pi 功能放在 ⌘ › pi 设置，按 schema 生成控件。
- 删除：吉祥物残留、汇率、用量页与每轮统计卡、文件浏览器与预览、Git 页、自动起名、回收站浏览页、dsh 视觉、空会话建议胶囊、Share Target。保留：HTML / JSONL 导出、`@` 文件搜索、附件、OAuth、自定义服务商、回收目录（+ 5 秒撤销）、草稿持久化、前台服务。
- 硬性要求 ① 由解析清单原文的 `PiFeatureCoverageTest` + 两种形态的仪器覆盖测试把关；硬性要求 ② 由 JVM 契约、JVM 行为、仪器 A 档（CI）、真引擎 B 档四级测试把关。
- 6 条工作流：r3/core → r3/session ∥ r3/models ∥ r3/calm ∥ r3/term → r3/app；先交付 UI 形态，两形态都过验收后对外发布。

---

## 附：工作流派发表

> 每段可直接作为任务说明。通用规矩（每段都适用）：仓库 `zr987459/pi-go`；不删仓库或分支、不 force push、不改仓库可见性；只改本段「范围」里的文件；每次推送前 `git fetch && git merge origin/main`（不 rebase）；接手 2 分钟内推 `docs/status/<分支名去斜杠>.md`（已开始 + 计划），每阶段更新；完成标准 = 分支 CI 绿 + 本段验收全部有结果（能本地或 CI 跑的都要跑，真机 / 真引擎跑不了写明原因）+ 开 PR 到 main（PR 描述：做了什么、验收结果、删除文件逐个列出、移植来源、遗留）。设计依据：`docs/DESIGN-r3.md`（本文）与 `docs/pi-features.md`。

**① r3/core（scaffold、契约、引擎、桥接、workspace、CI）**
范围：`settings.gradle.kts`、`gradle/**`、所有 `build.gradle.kts`、`.github/workflows/**`、`core/contract/**`（只新增文件）、`core/engine/**`、`core/protocol/**`、`core/workspace/**`、`core/runtime/**`（仅必要时）、`docs/protocol/**`、`docs/notices/r3-core.md`。
任务：**首推（≤1 小时，必须最先合入）**：建 `core/session`、`feature/term` 模块骨架（build 文件 + 占位；`feature/term` 依赖 `:core:contract`、`:core:ui`、`:core:session`）；在 `core/contract` 只新增 `SessionHost`、`DraftState`、`OverlayModel`（SelectList / Form / Text / Confirm / Tree / ExtDialog / FullOutput）、`UiForm`、`PiActionSpec`/`PiActions`/`ActionResult`、`ModelPanels` 接口、`PiSettingsSchema` 类型、`actions/PiActionCatalog.kt`（DESIGN-r3 §2 全部动作 id、标题、斜杠名、featureRefs、UI 与终端入口；`run` 不在这里）、`fake/FakeSessionHost.kt`（可编程流式、命令日志、start/restart 计数）；CI 按 §8.4 加单测、仪器测试编译、模拟器 job（PR 里单独标明 `.github` 改动）。首推后 `PiActionCatalog.kt` 移交 r3/session。**实现**：§5.7 三件事先用真 pi 1.5.0 实测并写结论；`pigo-bridge.ts`（§5.6）与 `EngineConfig` 的 `-e` 加载与退路；`RootfsWorkspace` 收缩为 `@` 模糊搜索、读文件、`branchName()`、只列目录的选择器；删 `GitParse.kt` 与 git status/diff/worktree API；`TurnStats` 只留累计统计与本轮耗时；`feature/usage/logic/CompactionSummary.kt` 有用的部分并入 `core/protocol/CompactionSummary.kt`；`SessionHost.debugEnginePid()` 所需的 `ProcessTree` 读取接口。r3/app 删完 `feature/usage` 后，单独提小 PR 去掉 `include(":feature:usage")` 与注释里的 `:feature:pet` 等残留。
验收：见 §8.5 r3/core 行（含 `realpi/` 桥接四项集成测试、首推的 diff 只含新增文件）。

**② r3/session（共享层：SessionHost、动作表实现、pi 设置、会话目录、覆盖与切换测试）**
范围：新模块 `core/session/src/**`、`feature/chat/src/main/**`（不含 `ui/**`）、`feature/chat/src/test/**`（不含 `SuggestionTextTest.kt`）、`feature/commands/**`、从 `feature/usage` 移出的 `logic/LineDiff.kt`、`logic/SessionTreeRows.kt`、`logic/CompactionSummary.kt`（并入后删除）及其测试、从 `feature/files` 移出的 `output/Ansi.kt` 及测试、`core/contract/**/actions/PiActionCatalog.kt`（r3/core 首推后）、`docs/r3/coverage-map.tsv`、`docs/notices/r3-session.md`。
任务：实现 `SessionHostImpl`（持有 engine、`ChatController`、`draft`、`OverlayStack`（含搜索词、页签、滚动位置、表单已填值）、`form`、`promptHistory`、`scrollAnchor`、`localBlocks`），API 无形态参数；实现 `PiActions.run`（全部动作，模型配置相关经 `ModelPanels` 接口调用，不依赖 `feature/models`）；`PiSettings`（文件锁读-改-写、只改目标键、写完 `/pigo:reload`，schema 覆盖清单 §8 全部键）；`SessionCatalog`（列表、排序、只看已命名、显示路径、重命名、导入、删除调用 `FileSessionRepository` 并提供 5 秒撤销、临时会话近似实现）；`@文件` 展开（格式以 pi 1.5.0 `interactive-mode` 源码为准）；`retry.abort` 只发 `abort_retry`；切换会话 / 引擎重启时排队文字回草稿；一次性提问（`bash` + `excludeFromContext` 跑 `pi -p`，可选 `--mode json`）；`/session` `/hotkeys` `/changelog` 的本地块不写入会话；`git mv` 移动 `LineDiff`、`SessionTreeRows`、`Ansi` 并同 PR 改引用（他人范围的引用先用转发桥接）；删 `AutoNamer`、`AutoTitle`、`feature/commands/ui/**`；维护 `coverage-map.tsv`；写 `NoRemovedFeaturesTest`（先 `@Ignore`，r3/app 合入后启用）。按「动作表 → 设置 → 会话目录 → 测试」分 PR。
验收：§7.1 第 2 条 `PiFeatureCoverageTest`、§7.2 第 1–2 条 `FormSwitchContractTest` 与 `SessionHostFormSwitchTest`（两条硬性要求的 JVM 部分）、§7.3 中属本线的全部测试通过；见 §8.5 r3/session 行。

**③ r3/models（只剩模型的设置、服务商、模型面板、删汇率、删除撤销）**
范围：`feature/models/**`、`feature/settings/**`、`core/catalog/**`、`core/store/**`、`docs/notices/r3-models.md`。
任务：设置页只剩「模型」（默认模型、常用模型、已连接、全部 42 个服务商带拼音首字母搜索、自定义服务商、高级：思考预算 / 每模型思考档 / 刷新目录），页底指引「pi 的其他设置在 ⌘ › pi 设置」；服务商页与自定义服务商页沿用 W2 逻辑只换极简视觉（`core/ui` 的 `CalmTheme` 由 r3/calm 提供，开发期可先用 Material 默认主题）；实现 `ModelPanels` 接口，产出模型面板（全部 / 常用 / 最近）、服务商列表、服务商表单、OAuth 流程的 `OverlayModel`，供两种形态渲染与 `/login` `/logout` `/model` `/scoped-models` 使用；生成中切模型提示「下一轮生效」；默认模型读写 `settings.json` default*（经文件锁）；删汇率（`ModelPrefsStore`、`RealAppSettingsStore.CostDisplay`、`NetworkAndCostScreen`）、`DshComponents`、旧设置首页与回收站浏览页；`RomGuide`/`BackgroundGuide` 改为供「引擎被系统杀掉」提示调用；`FileSessionRepository` 保持删除进回收目录，新增撤销最近一次删除的 API。
验收：见 §8.5 r3/models 行（`SettingsOnlyModelsTest`、`ProviderSearchPinyinTest`、`UndoDeleteTest` 等）。

**④ r3/calm（UI 形态）**
范围：`feature/chat/src/main/**/ui/**`、`feature/chat/src/androidTest/**`、`feature/chat/src/test/**/SuggestionTextTest.kt`、`core/ui/**`、`feature/sessions/**`、`feature/onboarding/**`、`docs/notices/r3-calm.md`。
任务：按 DESIGN-r3 §4 实现 `CalmSurface`：顶栏（☰、标题改名、⌘、⋯ 会话菜单、⇄ 切换）、消息区（用户块、无框助手正文、思考折叠行、过程行、bash 输出块、压缩卡、技能调用行、本地块、Mermaid 代码块 + 字符画「图示」）、输入区（队列条、widget 区、共享草稿输入框、⤢ 展开、× 清空、＋ 附件 / 引用文件 / 运行命令、思考胶囊、模型胶囊（左右滑循环）、上下文环、发送键三态）、⌘ 命令中心（中文 / 斜杠 / 拼音首字母搜索，每项显示 pi 原命令名）、`/` 与 `@` 面板、转录搜索、历史提示（空输入上滑）；`CalmSheet` 渲染全部 `OverlayModel`（含扩展对话框、树：筛选胶囊、三角折叠、长按标签、显示时间开关；会话选择器；pi 设置按 schema 的控件）；会话列表（`/resume` 的 UI 形态：搜索、排序、只看已命名、显示路径、导入、左滑删除 + 5 秒撤销、长按重命名、临时会话、在其他目录新建）；`CalmTheme`（§4.2）与 Material Symbols 子集；onboarding 重画；删除 dsh 组件与 `dsh_icons.ttf`、`SuggestionText.kt` 及其测试、旧聊天界面文件。开发期用 `FakeSessionHost`。
验收：`UiCoverageTest`（§7.1 第 3 条，硬性要求 ① 的 UI 部分）、UI 形态切换夹具（离开再进入草稿 / overlay 含搜索词 / 阅读位置恢复）及 §8.5 r3/calm 行全部通过。

**⑤ r3/term（终端形态）**
范围：`feature/term/**`（不含 `build.gradle.kts`）、`docs/notices/r3-term.md`。
任务：按 DESIGN-r3 §3 实现 `TermSurface`：JetBrains Mono（只读引用 `core/ui` 字体，关连字、双指缩放 10–18sp）、按列宽排版与 `wcwidth`、`drawBehind` 画框线；页眉、消息区（`TermFormatter` 纯函数）、状态行、队列与 widget 区、编辑器（边框色 = 思考档、`!` 变色）、补全浮层（`autocompleteMaxVisible`）、页脚（照 pi，各段可点、窄屏截断、`[UI]` 键帽）、触屏键条（§3.4 全部键，含 ⇄UI）；`PiThemeLoader` 与原样打包的 `dark.json`/`light.json`；`TermSelectList` 渲染全部 `OverlayModel`（键帽提示行可点，树的折叠 / 标签 / 时间 / 筛选键帽）；外接键盘读 `keybindings.json`，形态切换组合键 Ctrl+Alt+T 并做冲突单测；工具三态、`edit` diff、`hideThinkingBlock`、「… N more lines (ctrl+o)」、选中即复制。开发期用 `FakeSessionHost`。
验收：`TermCoverageTest`（§7.1 第 3 条，硬性要求 ① 的终端部分）、终端形态切换夹具、`TermFormatterGoldenTest`（真 pi 80 列输出夹具）、`PiThemeTokensTest`、`FooterSnapshotTest`、`SwitchChordNoConflictTest` 及 §8.5 r3/term 行全部通过。

**⑥ r3/app（接线、删除旧模块、端到端验收、NOTICE）**
范围：`app/**`（含 `androidTest`）、`feature/usage/**`（r3/session 移出的文件除外）、`feature/files/**`（`output/Ansi.kt` 除外）、`THIRD_PARTY_NOTICES.md`、`docs/testing/**`。
任务：`AppGraph` 装配 `SessionHostImpl` 单例与 `ModelPanels` 实现；`FormHost` 用 `Crossfade(form)` 切换两层；导航只剩：安装 → 会话 → 设置 › 模型 → 关于；删除 `WiredPages` 旧页面、`PiGoDrawer` 旧抽屉（改用 r3/calm 的会话列表）、回收站路由；删除 `feature/usage` 全部剩余文件、`feature/files` 的浏览器 / 预览 / Git / `Highlight` / `FilesHost` / `FilesController` / Share Target（含清单里的 Activity 与 `ShareSecurityTest`）/ 旧 `ExportSheet`；保留附件、`Pickers`、`FileShare`、`PigoFilesProvider`、`OutputViewer`（按形态传主题）。分两个 PR：PR1 在 r3/calm 合入后接线 UI 形态（终端入口先显示「终端形态尚未合入」占位，不发布）；PR2 在 r3/term 合入后接线终端形态并完成删除与端到端测试。删除按模块分提交，每步 `assembleDebug testDebugUnitTest`；合并各 `docs/notices/r3-*.md` 进 NOTICE（含 pi 主题 JSON、wcwidth 来源）；写 `docs/testing/r3-device-checklist.md`。
验收：`FormSwitchStreamingTest`（§7.2 第 3–4 条：A 档 CI 模拟器必过，B 档真机过或写明原因）、`AppCoverageTest`（§7.1 第 3 条）、§7.2 第 5 条人工清单、§8.5 r3/app 行全部通过；PR 描述贴 `coverage-map.tsv` 统计。
