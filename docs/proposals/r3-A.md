# pi-go 第三轮重新设计 · 方案 A

> 设计者 A（独立方案，没有参考其他 r3 方案）。写于 2026-11-04。
> 依据：`docs/pi-features.md`（pi 1.5.0 全功能清单，下文简称「清单」，条目按「§节号 · 名称」引用）、main 上的现有代码（`core/*`、`feature/*`、`app`）。
> 用户要求（2026-11-04）：
> - 根据 pi 本身重新设计界面，删掉无用功能。设置里先只保留模型配置，做成极简好看的风格。
> - pi 该有的功能一项不能少，分终端和 UI 两种形态。
> - **06:09 新增**：清单里的全部功能在 UI 形态里都要有入口，终端形态也要完整。两种形态共用同一会话、同一引擎状态，一键切换，不丢上下文、不重启引擎、不中断生成、保留草稿。
> - 不要吉祥物。

---

## 0. 方案一句话

**同一个 `pi --mode rpc` 进程，同一个应用级 `ChatController` 和一套「pi 命令」实现，配两个渲染器：`PiTerm`（用 Compose 一比一重画 pi TUI）和 `PiPlain`（极简原生界面）。** 形态只是 `CompositionLocal` 里的一个值，切换只换渲染层，不重建 Activity，不碰引擎。

三个关键设计：

1. **「pi 命令注册表」(`PiCommandRegistry`)，每个功能只写一遍逻辑，各配一个终端视图和一个 UI 视图。**
   - pi 的 23 个内置 TUI 命令、`/mcp`、`/llama`、资源命令，以及清单里不以命令出现的功能（循环模型、撤回队列、`@文件`、转录搜索等），都在注册表里登记为一个 `PiAction`。
   - 每个 `PiAction` 有三部分：逻辑、终端视图、UI 视图。
   - 覆盖率测试直接比对注册表和清单。少一个入口，CI 就红（见 §7.4）。
2. **UI 形态的总入口是「⌘ 面板」，也就是输入框左边的「/」按钮。** 它列出全部 pi 命令，分组显示、可搜索，每项打开原生底部面板。所以清单里的功能在 UI 形态**都能从「/」到达**；常用的另外放在顶栏、长按菜单和侧边栏，作为快捷入口。设置页只放模型，不会因为要覆盖全部功能而变臃肿。
3. **随 App 带一个 `pigo-bridge` 扩展**，补上 RPC 缺的能力：原地切换分支、标签、热重载、导入、信任、`@` 文件检索。启动时用 `-e` 加载，命令名一律以 `pigo-` 开头。它的输出走 `extension_ui_request`，两种形态都能显示。

---

## 1. 功能保留 / 删除 / 改造清单

判断标准只有一条：**pi 1.5.0 自己有没有**（TUI、RPC、CLI、配置文件任一处有，都算有）。安卓运行必需的宿主功能，比如运行环境安装、前台服务、Keystore，单列为「宿主必需」，保留但不放到显眼位置。

### 1.1 core 层

| 模块 / 功能 | 文件（主要） | 结论 | 理由 |
|---|---|---|---|
| `core/runtime`：PRoot、Ubuntu、Node、pi 安装、镜像、续传 | 全部 | **保留** | 宿主必需，pi 跑在里面 |
| `core/protocol`：JSONL 分帧、id 关联、`ChatReducer`、事件归约 | `RpcChannel`、`JsonlFramer`、`ChatReducer`、`RecordCodec`、`ContractMapping`、`Responses`、`ExtraResponses`、`Diagnostics`、`MessageMetaTracker` | **保留** | 33 个命令和全部事件已经接齐，这是两种形态共用的底座 |
| 同上：轮末统计 | `TurnStats.kt`、`RunResults.kt` | **改造** | `RunResults` 保留（完成通知要用）。`TurnStats` 中「本轮改动文件」「输出速率」是 Pi-Web 的，pi 没有，删掉。「本轮耗时」保留，因为 pi 的 loader 上有计时 |
| 同上：压缩摘要 | `CompactionSummary.kt` | **保留** | pi 原生（压缩块） |
| `core/engine`：`PiEngine`、`EngineService` 前台服务、`EngineConfig` | 全部 | **保留并改造** | 新增两件事：①启动参数里带 `-e pigo-bridge`；②把 `settings.json` 能控制的项留给 pi 读，不再用启动参数写死 |
| 同上：任务通知 | `TaskNotifier.kt`、`AndroidTaskNotifications.kt` | **改造** | 前台服务通知是宿主必需，保留。「回合完成通知」pi 只有终端进度提示（OSC 9;4），所以做成开关，默认关 |
| `core/contract` | 全部 | **保留**，只新增 | 新增 `PiAction`、`FormMode`、`PiSettingsSchema` 等（见 §7） |
| `core/store`：Keystore、`auth.json` 锁、`models.json` 与 `settings.json` 读写 | `KeystoreSecretStore`、`KeystoreCipher`、`AuthJsonLock`、`PiProviderConfigStore`、`JsonFiles`、`KeyRedaction` | **保留** | 模型配置的底座 |
| 同上：服务商表 | `ProviderSpecs`、`ProviderPresets`、`BuiltinCatalog` | **保留** | 全部内置服务商（用户要求） |
| 同上：测试连接、获取模型列表 | `ProviderTester`、`ModelDiscovery` | **保留** | 配置自定义服务商时必须要（用户要求保留模型配置） |
| 同上：模型偏好 | `ModelPrefsStore` | **改造** | 去掉汇率字段。默认模型、思考强度、`enabledModels` 改为直接读写 pi 的 `settings.json`，不再另存一份 |
| 同上：会话仓库 | `FileSessionRepository`、`SessionFileParser`、`ResumePolicy` | **改造** | 删除回收站（pi 是直接删除），改为「删除后 5 秒内可撤销」：先移到 `.pigo-pending-delete/`，5 秒后真删。`SessionFileParser` 只读头尾各 64 KB 的限制，改用 `feature/commands` 里已有的流式扫描器 |
| 同上：代理 | `HostProxy` | **保留**，改为写 pi 的 `httpProxy` | pi 原生键，国内必需 |
| `core/catalog`：目录导出、OAuth、PKCE、自定义服务商 | `CatalogRepository`、`RealModelCatalog`、`RealAuthManager`、`OAuthLoginSession`、`OAuthProtocol`、`Pkce`、`RealCustomProviderStore`、`pigo-*.mjs` | **保留** | pi 的 `/login`、`/logout`、`models.json` |
| 同上：App 设置 | `RealAppSettingsStore` | **改造** | 删掉汇率、主题皮肤等。只剩宿主自己的少量设置：当前形态、完成通知开关 |
| `core/workspace`：图片预处理、附件 | `ImagePrep`、`AndroidImageCodec`、`Attachments`、`FileNames`、`FileTypes`、`GuestPaths` | **保留** | 图片输入、`@文件` 都要用 |
| 同上：Git 解析 | `GitParse.kt` | **改造** | 只保留「读当前分支名」，页脚要显示。status 和 diff 解析删掉 |
| 同上：会话导出 | `SessionExport.kt` | **改造** | HTML 导出改为直接调 rpc `export_html`。JSONL 导出就是复制会话文件，pi 的 `/export x.jsonl` 也是这样 |
| 同上：工作区读写 | `RootfsWorkspace`、`WorkspaceServices` | **改造** | 只保留给 `@` 检索、导入导出、信任判断用的读取能力，不再为文件浏览器服务 |
| `core/ui`：dsh 皮肤 | `DshPalette`、`DshTokens`、`Components`、`ThemePreviews` | **删除** | 用户要求重新设计、极简。换成 `PiTheme`（终端，用 pi 的 token）和 `PlainTheme`（UI 形态） |
| 同上：`Theme.kt`、`Type.kt`、`AppIcons.kt` | | **改造** | 改为提供两套主题和等宽字体 |

### 1.2 feature 层与 app

| 模块 / 功能 | 文件（主要） | 结论 | 理由 |
|---|---|---|---|
| `feature/chat` 逻辑：`ChatController`、`Outbox`、`ComposerLogic`、`SlashCompletion`、`ExtUi`、`ErrorText`、`PhaseLabel`、`MarkdownSegments`、`ChatRows` | `feature/chat/…` 非 ui 部分 | **保留并扩充** | 对话、引导、排队、停止、扩展 UI 都是 pi 原生。新增循环模型、撤回队列、输入历史、`@` 引用、`!` 模式、`PiAction` 分发 |
| `feature/chat` 界面：dsh 风格的 `ChatScreen`、`Messages`、`Composer`、`TopBar`、`ExtDialogs` | `feature/chat/…/ui/**` | **重写** | 改造成 UI 形态（`PiPlain`）。可复用的结构（行类型、扩展对话框状态机）留下 |
| `feature/commands`：命令面板、技能、模板 | `CommandPalette`、`SkillsScreen`、`SkillsController`、`SkillTemplateManager` | **改造** | pi 原生（`/skill:`、模板）。界面并入 ⌘ 面板 |
| 同上：会话扫描与索引 | `SessionScanner`、`PiSessionIndex`、`SessionTranscriptReader`、`SessionMeta`、`JsonFileStore` | **保留** | 实现 `/resume`（RPC 没有会话列表） |
| 同上：草稿 | `DraftStore` | **保留** | 草稿跨形态共享，也防止进程被系统杀掉时丢草稿 |
| 同上：自动起名 | `AutoNamer`、`AutoTitle` | **删除** | pi 没有。未命名会话按 pi 的做法显示第一条用户消息 |
| `feature/files`：附件 | `AttachmentController`、`Pickers`、`AttachmentSheet` | **保留并改造** | 图片输入是 pi 原生。弹层按极简风格重画 |
| 同上：导出与分享 | `ExportSheet`、`FileShare`、`Share`、`ShareCache`、`ShareUriPolicy`、`PigoFilesProvider` | **保留**（导出部分） | `/export` 是 pi 原生。分享 Intent 用来把导出文件交给系统 |
| 同上：输出查看器、ANSI | `OutputViewer`、`Ansi` | **改造** | `Ansi` 移到终端渲染器复用。全屏查看并入「展开工具块」（pi 的 `Ctrl+O`） |
| 同上：文件浏览器、预览、语法高亮 | `FilesBrowserScreen`、`FilePreviewScreen`、`Highlight`、`FilesController`、`Common`、`Back` | **删除** | pi 没有文件浏览器，只有 `@文件` 模糊搜索。`Highlight` 的词法着色可以给代码块用，先移到 `core/ui/markdown` 再删原文件 |
| 同上：Git 页面 | `GitScreens` | **删除** | pi 没有 |
| 同上：系统分享入口（Share Target） | `Share*` 的接收部分 | **删除** | pi 没有。图片和文件从 ＋ 进来 |
| `feature/usage`：用量弹层、汇率 | `UsageSheet`、`UsageController`、`UsageFormat` | **改造** | 改成 pi 的 `/session` 信息页和页脚统计（美元、`(sub)`）。汇率删掉 |
| 同上：每轮统计卡 | `TurnSummary`、`TurnSummaryRow` | **删除** | pi 没有 |
| 同上：压缩卡、diff | `CompactionCard`、`CompactionSummary`、`DiffView`、`LineDiff` | **保留** | pi 原生（压缩块、edit 的 diff） |
| 同上：会话树 | `SessionTreeScreen`、`SessionTreeRows` | **改造** | 补上原地切换、标签、筛选、分支摘要（通过桥接扩展） |
| `feature/models`：模型选择器、服务商页、自定义服务商 | `ModelPicker`、`PickerLogic`、`ProviderScreens`、`CustomAndSettingsScreens` | **保留并重画** | 用户唯一保留的设置项 |
| `feature/sessions`：侧边栏 | `SessionsScreen`、`DrawerGrouping`、`DrawerStyle`、`SessionFormat` | **改造** | 对应 pi 的 `/resume`：排序最近或名称、只看已命名、显示路径、重命名、删除 |
| `feature/settings`：dsh 设置页、`DshComponents` | `SettingsScreen`、`DshComponents`、`SettingsPreviews` | **删除并重写** | 设置首页只有「模型」。其他 pi 设置项通过「pi 设置（settings.json）」进入，由 schema 驱动的通用编辑器呈现 |
| 同上：会话默认设置 | `SessionDefaults` | **改造** | 并入 schema 编辑器（队列、压缩、重试都是 `settings.json` 的键） |
| 同上：ROM 与后台引导 | `RomGuide`、`BackgroundGuide` | **保留，换位置** | 宿主必需。不放在设置首页，改为引擎被系统杀掉时弹出的提示里，以及「关于」页 |
| 同上：发现、代理 | `DiscoveryUi`、`KeyText`、`LatestOnly` | **合并** | 并入模型配置页 |
| `feature/onboarding`：首启安装 | 全部 | **保留，重画** | 宿主必需。对应 pi 的 first-time-setup。界面改成极简风格，装完后进入上次的形态（默认 UI） |
| 吉祥物 | 清单 §12 指出 main 上已经没有资源 | **删除残留** | 全局搜索 `mascot`、`Deepy`、`鲸`、`Elaina`、`Doro`。有残留就删，NOTICE 中的相关说明一起删 |
| `app`：导航、抽屉、`WiredPages`、`AppGraph`、`*Bindings` | 全部 | **重写导航，保留依赖注入** | 导航改为一个主页面（聊天）加底部面板，二级页面只剩：模型配置、会话树、pi 设置、关于 |

### 1.3 新增（为了「pi 有的一项不少」）

| 新增 | 理由 |
|---|---|
| `feature/term`：终端形态渲染器 | 用户要求 |
| `core/picmd`：pi 命令注册表与本地实现（`/settings`、`/scoped-models`、`/resume`、`/tree` 切换和标签、`/import`、`/share`、`/trust`、`/mcp`、包管理、上下文文件、`/hotkeys`、`/changelog`、`/debug`、`/bug`、`/quit`、`/reload`） | 这些在 RPC 里没有对应命令（清单 §0.2） |
| `core/engine/src/main/assets/pigo-bridge/`：桥接扩展 | 实现 `navigateTree`、`setLabel`、`reload`、`switchSession`（导入）。扩展 API 是 pi 原生 |
| `@文件` 检索 | pi 原生输入语法，pi-go 现在没有 |

---
## 2. 逐项对照清单（docs/pi-features.md）

记法：
- **终端**：终端形态（`PiTerm`）里怎么用。
- **UI**：UI 形态（`PiPlain`）的入口。`⌘` 指输入框左侧「/」按钮打开的命令面板；「会话菜单」指顶栏标题点开的菜单。
- **实现**：负责的工作流（见 §7）。
- **注**：我和清单建议不同的地方，或需要说明的理由。

凡是写「⌘ › X」的，X 就是该命令在面板里的名字，点开后是原生底部面板。它和终端形态共用 `core/picmd` 里同一个 `PiAction`。

### 2.1 清单 §1.1 运行模式

| 条目 | 终端 | UI | 实现 | 注 |
|---|---|---|---|---|
| 交互 TUI | 整个 `PiTerm` | 整个 `PiPlain` | term / plain | 两种形态都由同一个 rpc 进程驱动 |
| print `-p` | `!pi -p "…"`（在容器里跑一次就退出，输出显示为 bash 块） | ⌘ › 一次性提问：打开输入框，结果以 bash 块显示，不进当前会话 | picmd | **与清单不同**：清单写「不单列」。用户要求每项在 UI 都有入口，所以给一个轻量入口，实现上就是 `bash` 加 `excludeFromContext` 跑 `pi -p` |
| `--mode json` | `!pi --mode json -p "…"` | 同上一项的「一次性提问」面板里，有「输出 JSON 事件流」开关 | picmd | 同上 |
| `--mode rpc` | 底座 | 底座 | core | — |
| `pi install/remove/update/list/config` | `!pi install …`，以及 `/packages` 选择器 | ⌘ › 扩展与包：列表、安装（npm、git、本地）、移除、更新、`config` | picmd | 用 `GuestRunner` 执行 CLI，执行完后 `/reload` |
| `pi auth print-api-key/print-bearer-token` | `!pi auth print-api-key --provider x` | 服务商页 › 「⋯」 › 复制凭据给外部客户端（二次确认 + 系统锁屏验证，复制后 60 秒清空剪贴板） | models | **与清单不同**：清单不做 UI 入口。为满足「一项不少」，做成一个藏得深、受保护的入口 |
| `pi mcp add/list/remove…` | `!pi mcp …`，以及 `/mcp` | ⌘ › MCP 服务器 | picmd | — |

### 2.2 清单 §1.2 CLI 选项

| 条目 | 终端 | UI | 实现 | 注 |
|---|---|---|---|---|
| `--provider` `--model` | 页脚右侧显示；`/model` | 顶栏模型胶囊 | chat / models | 启动后统一用 `set_model` |
| `--api-key` | `/login` › API key | 服务商页 › 填 Key | models | 效果等价（写 `auth.json`）。不把 Key 放进进程参数，否则 `ps` 能看到 |
| `--system-prompt` `--append-system-prompt` | `/settings` › 系统提示（编辑 `SYSTEM.md`、`APPEND_SYSTEM.md`） | ⌘ › 上下文文件 › 系统提示 | picmd | — |
| `-c` `-r` `--session` `--session-id` | `/resume` 选择器 | 侧边栏 | sessions | 冷启动时默认继续最近会话（`ResumePolicy`） |
| `--fork` | `/fork` | 消息长按 › 从这里分叉；⌘ › 分叉 | chat | — |
| `--session-dir` | `/settings` › 会话 › `sessionDir` | pi 设置 › 会话 | picmd | — |
| `--no-session` | `/new --ephemeral`（本地命令） | ＋新对话长按 › 临时会话 | picmd | 实现方式：`new_session` 后由桥接扩展标记，退出时删除会话文件。这是近似实现，1.5.0 RPC 不能运行中切换到不保存模式，已列入风险 |
| `-n/--name` | `/name` | 点标题改名 | chat | — |
| `--models` | `/scoped-models` | 模型面板 › 常用（星标、拖动排序） | models | 写 `enabledModels` |
| `-nt` `-nbt` `-t` `-xt` | `/settings` › 工具 | ⌘ › 工具 | picmd | 写 `defaultTools`，然后 `/reload` |
| `--thinking` | 编辑器边框颜色，`/thinking` | 思考胶囊 | chat | — |
| `-e` `-ne` | `/settings` › 扩展 | ⌘ › 扩展与包 › 扩展开关 | picmd | `pigo-bridge` 固定加载，列表里不显示 |
| `--no-mcp` | `/mcp` 总开关 | ⌘ › MCP 服务器 › 总开关 | picmd | — |
| `--skill` `-ns` | `/skill:名` 补全，`/settings` › 技能 | ⌘ › 技能分组；⌘ › 技能管理 | picmd | — |
| `--prompt-template` `-np` | `/模板名` 补全 | ⌘ › 模板分组；⌘ › 模板管理 | picmd | — |
| `--theme` `--use-theme` `--no-themes` | 用 pi 的 dark、light、system token；`/settings` › 主题（含自定义主题 JSON） | 关于与外观 › 外观（浅色、深色、跟随系统）；终端主题也在这里选 | term / plain | UI 形态有自己的极简主题。pi 的主题文件只作用于终端形态 |
| `-nc` | `/settings` › 上下文文件开关 | ⌘ › 上下文文件 › 开关 | picmd | — |
| `--export` | `/export [path]` | 会话菜单 › 导出 | chat | — |
| `--list-models` | `/model` | 模型面板 | models | — |
| `--verbose` | 页眉显示已加载的资源 | 空会话页的「已加载」行；⌘ › 会话信息 › 已加载资源 | term / plain | — |
| `--tui-mode` | 只做全屏 | 不适用 | — | 手机上没有终端回滚。UI 形态本身就是全屏，这一项无意义 |
| `-a` `-na` | `/trust` | 打开工作目录时弹窗；⌘ › 信任此项目 | picmd | — |
| `--offline` | `/settings` › 网络 › 离线 | pi 设置 › 网络 › 离线 | picmd | 写 `PI_OFFLINE` 环境变量，引擎**下次启动**生效，界面上写明 |
| `-h` `-v` | `/help`，页眉显示版本 | 关于与外观 › 版本、帮助 | plain | — |

### 2.3 清单 §2.1 RPC 命令（33 个）

| 命令 | 终端 | UI | 实现 |
|---|---|---|---|
| `prompt` | 编辑器回车（按键条 ⏎） | 发送键 | chat |
| `steer` | 生成中按回车 | 生成中发送键显示「引导」 | chat |
| `follow_up` | Alt+Enter；按键条 ⇣ | 长按发送键 › 排队 | chat |
| `abort` | Esc | 停止键 | chat |
| `clear_queue` | Alt+↑；按键条 ↑队列；Esc 时先清队列，文字放回编辑器 | 队列条 › 撤回 | chat |
| `new_session` | `/new` | ＋新对话 | chat |
| `get_state` | 页脚 | 顶栏状态点；⌘ › 会话信息 | chat |
| `get_messages` | 恢复会话时重建消息区 | 同左 | core |
| `set_model` | `/model`、Ctrl+L、点页脚模型名 | 模型胶囊 › 面板 | models |
| `cycle_model` | Ctrl+P / Shift+Ctrl+P；页脚模型名左右滑 | 模型胶囊左右滑；面板里「下一个」 | chat |
| `get_available_models` | 选择器数据 | 面板「可用」组 | models |
| `set_thinking_level` | `/thinking` | 思考胶囊 › 档位 | chat |
| `cycle_thinking_level` | Shift+Tab；点编辑器边框 | 点思考胶囊 | chat |
| `get_available_thinking_levels` | 选择器数据 | 胶囊里的档位 | chat |
| `set_steering_mode` / `set_follow_up_mode` | `/settings` › 队列 | pi 设置 › 对话 | picmd |
| `compact` | `/compact [说明]` | 上下文环 › 压缩（可写说明）；⌘ › 压缩 | chat |
| `set_auto_compaction` | `/settings` | pi 设置 › 压缩 | picmd |
| `set_auto_retry` / `abort_retry` | 状态行「重试 2/3」，Esc 取消；`/settings` | 重试横条 › 取消重试（真正调用 `abort_retry`，不再用 `stop()` 代替）；pi 设置 › 重试 | chat |
| `bash` / `abort_bash` | `!` / `!!`，Esc 中止 | 输入以 `!` 开头时进入命令模式；停止键中止 | chat |
| `get_session_stats` | `/session`，页脚 | ⌘ › 会话信息（点上下文环也能打开） | chat |
| `export_html` | `/export` | 会话菜单 › 导出 › HTML | chat |
| `switch_session` | `/resume` | 侧边栏点会话 | sessions |
| `fork` | `/fork` | 长按 › 分叉 | chat |
| `clone` | `/clone` | 会话菜单 › 复制会话 | chat |
| `get_fork_messages` | `/fork` 选择器 | ⌘ › 分叉（列出可分叉的消息） | chat |
| `get_entries` | 增量同步 | 同左 | core |
| `get_tree` | `/tree` | 会话菜单 › 分支树 | picmd |
| `get_last_assistant_text` | `/copy`、Ctrl+X | 长按 › 复制；⌘ › 复制最后回复 | chat |
| `set_session_name` | `/name` | 点标题 | chat |
| `get_commands` | `/` 补全 | ⌘ 的扩展、技能、模板分组 | chat |

清单 §2.1 末尾提到的「RPC 没有、需要补」的能力：会话列表、原地切换分支、标签、热重载、登录、导入。它们分别对应 2.5 中的 `/resume`、`/tree`、`/reload`、`/login`、`/import`。

### 2.4 清单 §2.2 事件

| 事件 | 终端 | UI | 实现 |
|---|---|---|---|
| `agent_start/end` | 状态行 loader 出现或消失 | 发送键 ↔ 停止键 | chat |
| `agent_settled` | loader 消失 | 恢复发送键；完成通知（开关打开时） | chat / core |
| `turn_start/end` | 不单独显示 | 不单独显示 | core |
| `message_start/end` | 消息块 | 消息 | 共用 |
| `message_update` | 流式文字，思考块用 `thinkingText` 色，工具调用块 | 流式渲染 | 共用 |
| `tool_execution_*` | 工具块，按 pending、success、error 三种背景 | 过程行（状态点 + 时长），可展开 | 共用 |
| `queue_update` | 编辑器上方列出排队消息 | 队列条 | 共用 |
| `entry_appended` | `customMessage` 色块 | 灰色系统行 | 共用 |
| `session_info_changed` | 页脚会话名 | 顶栏标题 | 共用 |
| `thinking_level_changed` | 编辑器边框颜色 | 思考胶囊 | 共用 |
| `compaction_start/end` | 「压缩中…」，完成后显示摘要块 | 压缩卡片 | 共用 |
| `auto_retry_start/end` | 倒计时状态行 | 重试横条 | 共用 |
| `summarization_retry_*` | 同上 | 同上 | 共用 |
| `bash_execution_update` | `!` 输出实时滚动 | 等宽输出块 | 共用 |
| `extension_error` | 红色提示行 | 吐司提示 + ⌘ › 诊断 | 共用 |
| `extension_ui_request` | pi 风格弹层；`setStatus` 显示在页脚，`setWidget` 显示在编辑器上方，`setTitle` 显示在页眉，`set_editor_text` 写入编辑器 | 底部面板；`setStatus` 显示为顶栏副标题，`setWidget` 显示为输入框上方卡片，`setTitle` 显示为标题，`set_editor_text` 写入输入框 | 共用（对话框状态放在 controller 里） |

### 2.5 清单 §3 斜杠命令

| 命令 | 终端 | UI | 实现 |
|---|---|---|---|
| `/settings` | pi 风格的 SettingsList | 设置（只有「模型」）+ pi 设置（schema 编辑器）；⌘ › 设置 | picmd |
| `/model` | 选择器 | 模型胶囊 | models |
| `/thinking` | 选择器 | 思考胶囊 | chat |
| `/scoped-models` | 多选列表；拖动排序；「保存」按钮对应 Ctrl+S | 模型面板 › 常用 › 编辑 | models |
| `/login` | 服务商选择器 › 登录对话框 | 设置 › 模型 › 服务商 › 登录；⌘ › 登录 | models |
| `/logout` | 选择器 | 服务商页 › 退出登录；⌘ › 退出登录 | models |
| `/llama` | 扩展 UI | ⌘ › 扩展分组 › llama | 共用 |
| `/new` | 命令 | ＋ | chat |
| `/resume` | 会话选择器 | 侧边栏 | sessions |
| `/name` | 命令 | 点标题 | chat |
| `/session` | 文本块 | ⌘ › 会话信息 | chat |
| `/tree` | 树形选择器 | 会话菜单 › 分支树 | picmd |
| `/fork` | 用户消息选择器 | 长按 / ⌘ | chat |
| `/clone` | 命令 | 会话菜单 | chat |
| `/compact` | 命令 | 上下文环 / ⌘ | chat |
| `/import` | 命令（路径或系统文件选择器） | 侧边栏 › 导入；⌘ › 导入会话 | picmd |
| `/copy` | 命令 | 长按 / ⌘ | chat |
| `/export` | 命令 | 会话菜单 › 导出 | chat |
| `/share` | 命令 | 会话菜单 › 分享链接 | picmd |
| `/bug` | 命令 | 关于 › 报告问题；⌘ › 报告问题 | picmd |
| `/trust` | 命令 | 打开目录时弹窗；⌘ › 信任此项目 | picmd |
| `/mcp` | 扩展 UI | ⌘ › MCP 服务器 | picmd |
| `/reload` | 命令 | ⌘ › 重新加载；在设置中修改后自动重载 | picmd |
| `/hotkeys` | 表格 | 关于 › 手势与快捷键；⌘ › 快捷键 | picmd |
| `/changelog` | 显示包内 `CHANGELOG.md` | 关于 › 更新日志；⌘ | picmd |
| `/debug` | 命令 | 关于 › 导出诊断；⌘ | picmd |
| `/quit` | 命令：结束引擎 | 关于 › 停止引擎；⌘ › 退出 | picmd |
| `/模板名` | 补全，显示 `argument-hint` | ⌘ › 模板，选中后弹出参数框 | chat |
| `/skill:名` | 补全 | ⌘ › 技能 | chat |
| 扩展命令 | 补全 | ⌘ › 扩展 | chat |

### 2.6 清单 §4 内置工具

两种形态都用同一个 `ToolPresenter`：按工具名决定标题、摘要和详情的数据，再交给各自的渲染器。

| 工具 | 终端 | UI |
|---|---|---|
| `read` | `read path:范围`，折叠时只显示前几行，Ctrl+O 展开 | 过程行「读取 文件名」，展开看内容；`image` 块显示为图片 |
| `write` | 标题 + 带高亮的内容预览 | 「写入 文件名」+ 代码预览 |
| `edit` | `toolDiff*` 三色 diff | 「编辑 文件名 +3 −1」，展开看 diff |
| `bash` | `$ cmd`，原样显示 ANSI；显示 `exit_code`、截断提示和 `full_output_path` | 「运行 命令」+ 等宽输出；截断时提供「查看完整输出」 |
| `grep` | 匹配列表 | 「搜索 模式」 |
| `find` | 文件列表 | 「查找 模式」 |
| `ls` | 列表 | 「列出 目录」 |
| `powershell` | 不适用（安卓没有） | 不适用 |
| `codemode` | 代码块 + 输出 | 「脚本」，展开看代码和输出；在 ⌘ › 工具中开启 |
| `tool_search` | 普通工具行 | 过程行 |
| MCP 工具 | 普通工具行 | 过程行，前面加服务器名 |

### 2.7 清单 §5 会话

| 条目 | 终端 | UI | 实现 |
|---|---|---|---|
| 存储位置 | `/session` 显示路径；`/settings` › `sessionDir` | ⌘ › 会话信息 › 路径；pi 设置 › 会话 | picmd |
| 格式（v3 JSONL） | — | — | core（扫描器按 v3 格式解析） |
| 继续 / 恢复 | `/resume` | 侧边栏；冷启动时自动继续 | sessions |
| 新建 | `/new` | ＋ | chat |
| 分叉 / 复制 | `/fork`、`/clone` | 长按、会话菜单 | chat |
| 树导航（含分支摘要） | `/tree`；双击 Esc（`doubleEscapeAction`） | 分支树页：点节点切换，弹出「摘要离开的分支？」 | picmd + bridge |
| 树筛选 | 选择器工具栏 | 树页顶部筛选胶囊（5 种模式） | picmd |
| 标签 | 树中按 L 键或长按 | 节点长按 › 标签；按钮切换是否显示时间 | picmd + bridge |
| 会话名 | 页脚，`/name` | 标题 | chat |
| 删除会话 | 选择器里删除 | 侧边栏左滑 → 5 秒撤销 | sessions |
| 选择器选项（路径、排序、只看已命名、重命名） | 选择器工具栏 | 侧边栏顶部「⋯」菜单；长按会话 › 重命名 | sessions |
| 压缩（手动 / 自动） | 摘要块 | 压缩卡片 + 上下文环（超阈值变黄、变红） | 共用 |
| 导出 | `/export` | 会话菜单 | chat |
| 分享 | `/share` | 会话菜单 | picmd |
| 导入 | `/import` | 侧边栏 › 导入 | picmd |
| 临时会话 | `/new --ephemeral` | ＋ 长按 | picmd |
| 工作目录 | 页脚 `~/路径 (git 分支)` | 顶栏副标题；点击后新建会话时可以换目录（目录选择器用 `@` 检索器的目录模式） | chat |

### 2.8 清单 §6 模型与服务商

见 §6 模型配置。逐项对应如下：

| 条目 | 终端 | UI |
|---|---|---|
| 内置服务商（约 42 个） | `/login` 列表 | 设置 › 模型 › 服务商列表 |
| OAuth / 订阅登录 | 登录对话框（URL、设备码、粘贴回调） | 服务商页 › 用账号登录（Custom Tab，回调后自动完成） |
| API key 来源与优先级 | `/login` › API key | 服务商页 › Key（支持 `!命令` 形式和 `env`，放在「高级」里） |
| 自定义服务商 `models.json` | `/settings` › 自定义服务商（表单），或用 `!` 编辑文件 | 设置 › 模型 › 添加自定义服务商 |
| 模型切换 | `/model` | 模型胶囊 |
| 循环切换 | Ctrl+P，页脚滑动 | 胶囊滑动 |
| 作用域模型 | `/scoped-models` | 模型面板 › 常用 |
| 思考强度（7 档） | 边框颜色，`/thinking` | 思考胶囊（只显示当前模型支持的档位） |
| 默认模型 | 选择器里「设为默认」 | 面板 › 设为默认 |
| 模型目录更新 | `!pi update --models` | 模型面板 › 刷新 |
| llama.cpp 本地模型 | `/llama` | ⌘ › llama |
| 虚拟模型、分类器、图像模型 | 随扩展出现 | 随扩展出现（扩展 UI 协议） |

### 2.9 清单 §7 技能、扩展、模板、上下文文件、主题、MCP、包

| 条目 | 终端 | UI | 实现 |
|---|---|---|---|
| 技能 | `/skill:名`，页眉列出 | ⌘ › 技能；⌘ › 技能管理（列表、启用、导入、查看 `SKILL.md`） | picmd |
| 提示模板 | `/名`，显示参数提示 | ⌘ › 模板；模板管理（新建、编辑） | picmd |
| 扩展 | 扩展的弹层、状态、组件按 TUI 样式画 | 扩展 UI 协议画成原生；⌘ › 扩展与包 | 共用 |
| 内置扩展 | `/settings` › 扩展 | ⌘ › 扩展与包 › 内置开关 | picmd |
| 上下文文件 | 页眉列出 | ⌘ › 上下文文件：查看和编辑全局 `AGENTS.md`、`SYSTEM.md`、`APPEND_SYSTEM.md`，列出已加载的文件 | picmd |
| 主题 | pi 的 token，`/settings` › 主题 | 关于与外观 | term |
| MCP | `/mcp` | ⌘ › MCP 服务器 | picmd |
| 包 | `/packages`，`!pi install` | ⌘ › 扩展与包 | picmd |
| 项目信任 | `/trust` | 弹窗 + ⌘ | picmd |
| 键位文件 | 外接键盘按 `keybindings.json` 生效 | 关于 › 手势与快捷键（只读；带「编辑 keybindings.json」入口） | term |

### 2.10 清单 §8 `settings.json` 全部键与 §8.1 环境变量

- **终端**：`/settings` 用 pi 风格的 SettingsList，按清单 §8 的分组显示。
- **UI**：设置首页只有「模型」，底部一行「pi 设置（settings.json）」进入**由 schema 驱动的编辑器**，按同样的分组显示：显示、缓存、队列、编辑器、树、信任、工具、会话、压缩、图片、Markdown、网络、重试、Shell、包与资源、其他。
- **schema**：`PiSettingsSchema` 列出清单 §8 的每一个键，写明类型、默认值和中文说明。单测断言 schema 覆盖了清单 §8 的全部键。

各组的特殊处理：

| 分组 | 处理 |
|---|---|
| 模型 | 设置首页 › 模型（§6） |
| 全屏 TUI（`tuiMode` 等）、终端（`terminal.*`） | 只作用于终端形态（滚动条、选中即复制、内联图片宽度）。UI 形态的编辑器里放在「终端形态」分组，并注明对 UI 形态无效 |
| 编辑器 › `externalEditor` | 手机上没有外部编辑器。终端形态的 Ctrl+G 改为打开全屏编辑器；这个键保留可编辑，并注明在手机上的含义 |
| 网络 › `httpProxy` | 放在模型配置页顶部，也会在 schema 编辑器里出现。国内使用离不开代理 |
| 其他 › `enableInstallTelemetry` | App 首次写 `settings.json` 时默认设为 false，可以改回 |
| §8.1 环境变量 | 服务商 Key 不用环境变量（`auth.json` 优先级更高）；`PI_OFFLINE`、`PI_TELEMETRY` 映射成上面两个开关；渲染类变量对 RPC 无效，不提供；会话变量由 pi 自己设置 |

### 2.11 清单 §9 快捷键与触屏映射

终端形态完全按清单 §9 的「终端形态（触屏）」一列实现。接外接键盘时读 `keybindings.json`。UI 形态的对应关系：

| 动作 | UI 形态 |
|---|---|
| 中断 | 停止键 |
| 清空 / 退出 | 输入框 ×；退出 = ⌘ › 退出 |
| 挂起 | 不适用（安卓没有作业控制） |
| 外部编辑器 | 输入框「展开」全屏编辑 |
| 粘贴图片 | ＋ › 图片、相机、剪贴板；输入框长按 › 粘贴图片 |
| 换行 | 软键盘回车 |
| 补全 | 点补全列表 |
| 排队 | 长按发送键 |
| 撤回队列 | 队列条 › 撤回 |
| 复制 | 长按消息 |
| 选模型、循环模型 | 胶囊点按、左右滑动 |
| 循环思考、折叠思考 | 点思考胶囊；点思考块 |
| 展开工具 | 点过程行；会话菜单 › 全部展开 / 全部折叠 |
| 保存默认 | 面板 › 设为默认 |
| 编辑器光标与删除 | 交给输入法 |
| 历史 | 输入框为空时，在输入框上向上滑，翻看之前的提示 |
| 消息区滚动与跳转 | 「回到底部」按钮；会话菜单 › 搜索本会话；右侧快速滚动条；双指上下滑在用户消息之间跳转 |
| 会话选择器、树、作用域模型 | 各自页面的工具栏和长按菜单 |
| 未绑定的会话动作 | 不适用（外接键盘用户在终端形态绑定） |

### 2.12 清单 §10 输入语法

| 语法 | 终端 | UI |
|---|---|---|
| 生成中 Enter = 引导 | 同 pi | 发送键在生成中显示「引导」 |
| Alt+Enter = 排队 | ⇣ | 长按发送键 |
| Esc = 中止，排队文字回到编辑器 | Esc | 停止键；中止后排队文字自动回到输入框，有草稿时放进「已撤回」芯片 |
| `/命令` | 补全浮层，最多显示 `autocompleteMaxVisible` 条 | 输入 `/` 时弹出 ⌘ 面板的过滤视图 |
| `@文件` | 输入 `@` 后弹出模糊检索，Tab 补全 | 输入 `@`，或点 ＋ › 文件 |
| 图片 | 内联缩略图 | 输入框缩略图条 |
| `!cmd` / `!!cmd` | 编辑器边框变成 `bashMode` 色 | 输入框变成等宽字体，左侧显示「$」，提示是否把结果发给模型 |
| 双击 Esc | 打开树或分叉（遵循 `doubleEscapeAction`） | 会话菜单 › 分支树（不做双击手势，避免误触） |
| 模板参数 | 显示 `argument-hint` | 参数框 |

`@文件` 的实现：pi 是在 TUI 里展开 `@`，RPC 的 `prompt` 不处理 `@`。所以由 App 检索文件（`core/workspace`，用 `fd` 或 `find`，限制在 cwd 内），把选中的文件按 pi TUI 的方式（附加文件内容块）展开后再发送。展开格式需要对照 pi 1.5.0 `interactive-mode` 的源码确定，这件事归 chat 工作流，验收时用快照测试核对。

### 2.13 清单 §11 TUI 元素

| 元素 | 终端 | UI |
|---|---|---|
| 页眉 | 照搬，可折叠 | 空会话页：一行「目录 · 模型」，下面一行「已加载 N 个上下文文件、M 个技能」 |
| 消息区 | 等宽字体，pi token | 用户消息右侧浅灰块，助手消息是无边框正文 |
| 思考块 | `thinkingText` 色，可折叠 | 「思考了 12 秒」，点开看全文 |
| 工具调用块 | 三种背景色 | 过程行 |
| bash 执行块 | `bashMode` 色边框 | 等宽块 |
| 压缩 / 分支摘要块 | `customMessage` 色 | 卡片 |
| 技能调用消息 | 照搬 | 「使用技能 名称」 |
| 编辑器 | 边框颜色表示思考档位，下方是按键条 | 圆角输入框 |
| 自动补全 | 编辑器上方浮层 | 面板 |
| 队列显示 | 编辑器上方 | 队列条 |
| 状态行 | loader 加计时 | 顶栏副标题（「思考中 · 12s」） |
| 页脚 | 照搬，各段可点（模型 → `/model`，统计 → `/session`，路径 → 切换目录） | 顶栏只放模型胶囊和上下文环；完整统计在 ⌘ › 会话信息 |
| 选择器弹层 | 一个通用的 `PiSelector` | 一个通用的 `PlainSheet` |
| 登录对话框 | 照搬 | Custom Tab |
| 首次设置 | 安装完成后进入上次的形态 | 引导页 |
| 转录搜索 | 全屏搜索，用 `searchMatchBg` 高亮 | 会话菜单 › 搜索本会话 |
| Mermaid | 字符画 | 渲染成图（WebView 离线 mermaid.min.js，MIT；或者先显示代码，作为 P2） |
| 彩蛋 | 不做 | 不做 |

### 2.14 清单 §12、§13

- §12 每一项的处理见本文 §1。我的结论和清单一致，只有两处不同：
  1. 回收站改成「5 秒撤销」，这一点相同；
  2. 完成通知默认关闭，这一点相同；
  3. 但「本轮耗时」要保留，因为它就是 pi loader 上的计时。
- §13 见本文 §5。

---
## 3. 终端形态（`PiTerm`）：还原 pi TUI

### 3.1 渲染方式

- **不开伪终端，也不跑第二个 pi。** 所有内容都由 Compose 绘制，数据来自共享的 `ChatScreenState`。
- **网格**：用等宽的「字符格」排版。行高为 1.35 倍字号，默认 13sp，可以双指缩放到 10–18sp。屏宽按格数计算（`cols = width / cellWidth`），pi 组件里依赖宽度的折行、截断和 `outputPad`，都按这个列数换算。
- **字体**：西文用 JetBrains Mono（OFL-1.1）。中日韩字符回退到系统等宽字体或 Noto Sans Mono CJK，按 East Asian Width 规则占 2 格。框线**不用制表符拼**，用 `drawBehind` 直接画线，这样中文混排时边框不会错位。
- **列表**：外层用一个 `LazyColumn`。每个 pi 组件（用户消息、助手消息、思考、工具、bash、压缩、自定义消息）对应一个 item。item 内部用 `AnnotatedString` 着色，Markdown 用同一个 `MarkdownSegments`，按 pi 的 `md*` token 上色。流式更新时只重组最后一个 item，沿用 chat-ui 的 `StreamingJankTest` 作为门槛：慢帧低于 5%。
- **ANSI**：bash 输出和工具输出用现有的 `feature/files/…/Ansi.kt` 解析，支持 16、256 色和真彩色，移到 `feature/term` 里复用。

### 3.2 配色

- 把 pi 1.5.0 的 `theme/dark.json` 和 `theme/light.json` **原样打包**到 `feature/term/src/main/assets/pi-theme/`（MIT，在 notices 里记来源）。
- 运行时解析 `vars`，做 okhsl 到 sRGB 的换算，生成 56 个 token 对应的 `PiColors`。
- `system` 主题跟随安卓的深浅色。
- 支持 `~/.pi/agent/themes/*.json` 自定义主题，读法相同，用 `/settings` › 主题 选择，写入 `settings.json` 的 `theme`。
- 状态栏和导航栏颜色取 `PiColors.background`，做到沉浸式。

### 3.3 布局（竖屏）

```
┌──────────────────────────────────────┐
│ pi v1.5.0   ~/proj                   │ ← 页眉（可折叠：上下文文件、技能、扩展、模型范围）
│ ── AGENTS.md · 2 skills · 1 ext ──   │
│                                      │
│ ▌帮我把 README 翻成英文               │ ← userMessageBg
│                                      │
│ ⠿ thinking… (斜体 thinkingText)       │
│ ┌ read README.md:1-80 ───────────── ┐│ ← toolSuccessBg，默认折叠
│ │ # pi-go …                     ⋯ ││
│ └──────────────────────────────────┘│
│ 好的，以下是英文版 …                  │ ← Markdown（md* token）
│                                      │
│ ⠋ Working… 12s  · 重试 1/3            │ ← 状态行（loader、计时、setStatus）
│ ↳ steer: 顺便改一下标题               │ ← 队列
│╭──────────────────────────────────╮  │ ← 编辑器，边框颜色 = 思考档位
││ > _                              │  │   以 ! 开头时边框为 bashMode 色
│╰──────────────────────────────────╯  │
│ ~/proj (main) • 翻译  ↑12k ↓3k R8k $0.04  42%/200k  (anthropic) sonnet • high │ ← 页脚（可横向滚动，各段可点）
│ [Esc][Tab][⇣][↑Q][^C][⌃O][/][@][!][⌕][⤢][⇄UI] │ ← 按键条
└──────────────────────────────────────┘
```

- 页脚完全照 pi 的 `footer.js` 排版。屏幕放不下时，第二行可以横向滚动，不截断。
- **形态切换键 `⇄UI`** 固定在按键条最右边；页脚右端也有一个。

### 3.4 编辑器

- 用 `BasicTextField` 加自绘边框，可以多行，最多 8 行，超过后内部滚动。
- **软键盘回车 = 换行，按键条 ⏎（或外接键盘 Enter）= 发送**。这一点和清单 §9 一致。也可以在 `/settings` 里改成「回车发送」。
- 输入 `/` 时，编辑器上方弹出补全浮层，最多显示 `autocompleteMaxVisible` 条，按扩展、技能、模板、内置命令分组，并显示 description 和 `argument-hint`。点按或 Tab 补全。
- 输入 `@` 时弹出文件模糊检索，范围是 cwd。
- 生成中按回车 = 引导；⇣ = 排队；↑Q = 把队列撤回到编辑器。
- 编辑器为空时，按键条的 ↑↓ 翻历史提示。历史取自当前会话的用户消息，加上 `DraftStore` 里的历史记录。

### 3.5 按键条与快捷键

- 按键条上的键：Esc、Tab、⇣（Alt+Enter）、↑Q（Alt+↑）、^C、⌃O、/、@、!、⌕、⤢（Ctrl+G 全屏编辑）、⇄UI。
- 长按某个键会弹出它的次要动作。例如长按 ^C 是「退出（停止引擎）」，长按 ⌃O 是「全部折叠」。
- 页脚模型名：点按 = Ctrl+L，左右滑 = Ctrl+P / Shift+Ctrl+P。
- 点编辑器边框 = Shift+Tab，循环思考档位。
- 连按两次 Esc = `doubleEscapeAction`。
- 接外接键盘时，用 `onPreviewKeyEvent` 读取 `keybindings.json`（缺省时用 pi 的默认键位），完全按 pi 的键位执行；`/hotkeys` 显示的就是这张表。

### 3.6 工具块与思考块

- 工具块用三种背景：`toolPendingBg`、`toolSuccessBg`、`toolErrorBg`。
- 标题按 pi 各工具的 `renderCall` 格式（`read path:1-80`、`$ cmd`、`edit path`），输出用 `toolOutput` 色。
- 默认折叠，只显示前几行，和 pi 的行为一致。点按展开一个，⌃O 展开全部。
- `edit` 显示三色 diff（复用 `LineDiff`）。
- 截断的输出显示 `full_output_path`，点按会用 `!cat` 打开完整输出。
- 思考块用 `thinkingText` 色、斜体，点按或 Ctrl+T 折叠。`hideThinkingBlock` 设置生效。
- 运行中的工具，在块的标题后面显示 spinner 和计时。回合结束（`agent_settled`）后仍未走到终态的工具，标为「未执行」或「已中断」。这沿用 chat-ui 第 2 轮审查的修复。

### 3.7 选择器与扩展 UI

- 一个通用的 `PiSelector`：上面是搜索框，中间是列表，选中项用 `selectedBg`，底部是 pi 风格的按键提示行，提示里的每一项也是可点的按钮。
- `/model`、`/thinking`、`/scoped-models`、`/resume`、`/tree`、`/fork`、`/settings`、`/login`、`/trust`、主题选择，以及扩展的 `select`、`confirm`、`input`、`editor`，全部用这个组件。
- `/session`、`/hotkeys`、`/changelog` 这类「文本输出」，作为 `customMessage` 色块插入消息区。它们是本地生成的，不写入会话文件。

---

## 4. UI 形态（`PiPlain`）：极简原生界面

### 4.1 原则

1. **一页为主**：只有一个对话页。其余功能都是底部面板，或者从对话页推进去的一层二级页（模型配置、分支树、pi 设置、关于），不会超过两层。
2. **内容优先**：没有卡片阴影、渐变、玻璃效果、吉祥物、插画。界面只靠留白、字重和一种强调色来区分层次。
3. **所有 pi 功能都能从「/」到达**；常用功能另外放快捷入口（见 §0）。

### 4.2 视觉

- **颜色**：浅色背景 `#FAFAF9`、正文 `#1C1C1E`、次要文字 `#8A8A8E`、分隔线 `#E7E7E5`；深色背景 `#0F0F10`、正文 `#EDEDED`、次要文字 `#8E8E93`、分隔线 `#262628`。强调色只有一个，取 pi `dark.json` 的 accent（偏紫），用于发送键、选中状态和链接。成功、失败只用很小的状态点表示，不铺大面积颜色。
- **字体**：正文用系统 sans（中文为 Noto Sans CJK），16sp，行高 1.55；代码和工具输出用 JetBrains Mono 13sp；标题 600 字重。不出现第三种字号以外的大字。
- **间距**：8dp 网格；消息之间 16dp；屏幕左右边距 20dp。
- **动效**：只用 150–200ms 的淡入淡出和高度展开，曲线为 standard easing，不用弹跳。流式文字不加打字机动画，直接追加。形态切换做 120ms 交叉淡入。

### 4.3 对话页线框

```
┌───────────────────────────────┐
│ ☰  翻译 README          ⇄  ⋯ │ ← 标题（点按改名、打开会话菜单）、形态切换、菜单
│    ~/proj · 思考中 12s        │ ← 副标题：cwd、状态（扩展 setStatus 也显示在这里）
├───────────────────────────────┤
│                               │
│        帮我把 README 翻成英文 ▐│ ← 用户消息：右对齐浅灰块
│                               │
│ ◦ 思考了 6 秒                 │ ← 折叠行
│ ● 读取 README.md       0.2s   │ ← 过程行（点按展开）
│ ● 编辑 README.md  +12 −9 1.1s │
│                               │
│ 好的，英文版如下……            │ ← 助手正文，无边框
│                               │
│ ┄ 已压缩 150k → 32k ┄         │ ← 压缩卡（点按看摘要）
├───────────────────────────────┤
│ 引导：顺便改一下标题   撤回   │ ← 队列条
│ [sonnet ▾] [高 ◔]      ◑ 42%  │ ← 模型胶囊（可滑动）、思考胶囊、上下文环
│ ╭──────────────────────────╮  │
│ │ /  发消息…           ＋  ↑ │  │ ← 「/」= ⌘ 面板；＋ = 图片、相机、文件(@)、剪贴板
│ ╰──────────────────────────╯  │   ↑ = 发送；生成中变成 ■ 停止或「引导」
└───────────────────────────────┘
```

- 生成中，发送键的状态是：
  - 输入框为空时显示 ■ 停止；
  - 有文字时显示「引导」；
  - 长按显示「排队 / 停止」。
  这样解决了第 1 轮 Q4 的问题：有草稿时也能停止。
- **会话菜单（⋯）**：分支树、复制会话、导出（HTML / JSONL）、分享链接、搜索本会话、全部展开 / 折叠、会话信息、切换形态。
- **侧边栏（☰）**：上方是 ＋新对话（长按选临时会话或指定目录），接着是搜索框和「⋯」菜单（排序、只看已命名、显示路径、导入会话），下面是会话列表，左滑删除并可在 5 秒内撤销，长按可重命名。底部两项：设置（模型）、关于与外观。

### 4.4 ⌘ 面板（「/」）

```
┌───────────────────────────────┐
│ ⌕ 搜索命令                    │
│ 常用   新对话 · 压缩 · 分叉 · 复制回复 · 会话信息 │
│ 会话   恢复 · 分支树 · 复制会话 · 导入 · 导出 · 分享 · 改名 · 一次性提问 │
│ 模型   选模型 · 思考强度 · 常用模型 · 登录 · 退出登录 │
│ 资源   技能… · 模板… · 扩展命令… · 上下文文件 · 扩展与包 · MCP · 工具 │
│ 项目   信任此项目 · 重新加载 │
│ 应用   设置 · pi 设置 · 快捷键 · 更新日志 · 诊断 · 报告问题 · 退出 │
└───────────────────────────────┘
```

- 数据来自 `PiCommandRegistry` 和 `get_commands`。
- 每一项都显示 pi 的原命令名，比如「分支树 /tree」，这样终端用户也能对上号。

### 4.5 关键组件

- `PlainMessage`：用户消息、助手消息、思考、过程行、bash 块、压缩卡、系统行。
- `PlainComposer`：输入框、＋、「/」、发送；`!` 命令模式；`@` 检索；图片缩略图条。
- `PlainSheet`：所有面板的统一容器，带拖动条、标题和可选的搜索框。
- `ModelPill`、`ThinkingPill`、`ContextRing`。
- `PlainSettingsList`：schema 编辑器，按开关、选项、数字、文本、列表几种类型渲染。

---

## 5. 两种形态切换与共享层

### 5.1 分层

```
                 ┌─────────── PiPlain（UI 形态渲染） ───────────┐
FormHost ── when(form) ┤                                         ├── 同一个 state、同一个 actions
                 └─────────── PiTerm （终端形态渲染） ───────────┘
                                   │
     ChatController（应用级单例，已存在于 AppGraph.chatDeps）
     ├─ ChatScreenState：消息行、流式消息、工具状态、队列、草稿、附件、对话框、横条
     ├─ PiActions：submit / steer / followUp / stop / cycleModel / … / run(PiAction)
     └─ UiSession：两种形态共用的「打开中的面板」状态（见 5.3）
                                   │
     PiCommandRegistry（core/picmd）── EngineExtras / PiEngine（唯一的 pi --mode rpc）
```

- `FormMode { TERMINAL, PLAIN }` 只存在两个地方：`AppSettings`，用于记住上次的形态；以及 `CompositionLocal`。**`ChatController` 不知道当前是哪种形态。**
- 切换形态只改 `CompositionLocal` 的值，不调用 `recreate()`，不重新导航，`NavBackStackEntry` 也不变。两套主题都通过 `CompositionLocal` 提供，切换后同一帧内就生效。
- 输入框的文字、光标、选区，存在 controller 的 `composer` 里，类型为 `TextFieldValue`，两个编辑器读写同一个值。附件、`@` 引用、`!` 模式也一样。
- **滚动位置**：两种形态的列表都用 `entryId` 作为 key。切换前记下当前可见的第一条的 `entryId` 和偏移，切换后滚动到同一条。如果原来停在底部（跟随流式输出），切换后继续跟随。

### 5.2 入口

- 终端形态：按键条的 `⇄UI`，以及页脚右端。
- UI 形态：顶栏的 `⇄`，以及会话菜单。
- 两种形态里都可以输入 `/ui`、`/term`。这两个是本地命令，不发给 pi。
- 外接键盘：Ctrl+Shift+U（pi 没有占用这个组合）。

### 5.3 切换时不能丢的东西

| 状态 | 存放位置 | 切换时 |
|---|---|---|
| 流式生成中的回复 | `ChatReducer` → `ChatState` | 继续流式输出，不发 abort |
| 队列、重试倒计时、压缩进度 | 同上 | 原样显示 |
| 草稿、光标、附件、`!`/`@` 状态 | `ChatController.composer` + `DraftStore` | 原样带过去 |
| 扩展阻塞对话框（`select`/`confirm`/`input`/`editor`） | `ChatController.dialogs`（已存在） | 另一种形态立刻显示同一个 id 的对话框，不回复 cancelled |
| 打开中的面板（模型选择器、`/tree` 等） | `UiSession.openPanel: PanelRequest?` | 另一种形态打开对应的视图。注意：选择器里**输入到一半的搜索词**也放在 `UiSession` |
| 引擎进程 | `EngineService`（应用级） | 不受影响 |

### 5.4 引擎层要保证的事

- 形态切换不发出任何 RPC 命令。测试里断言切换前后命令日志没有新增。
- `MainActivity` 的配置变更（例如旋转）沿用现在的处理方式。形态切换不触发 Activity 重建。

---

## 6. 模型配置（唯一的设置项）

### 6.1 设置首页

```
设置
──────────────────────────
模型
  默认模型        sonnet-4.5 · 高 ›
  常用模型        5 个 ›
  服务商          已配置 3 / 42 ›
  自定义服务商    1 个 ›
  网络代理        http://127.0.0.1:7890 ›
──────────────────────────
pi 设置（settings.json） ›         ← 二级：schema 编辑器
关于与外观 ›                       ← 版本、外观、手势与快捷键、更新日志、诊断、后台保活说明
```

用户说「设置里只保留模型配置」。首页的主体只有模型。另外两行是二级入口，是为了满足「pi 的功能一项不少」和安卓宿主的需要。它们放在最底部，并且做得很轻。

### 6.2 服务商列表

- 分组显示：「已配置」「订阅登录（OAuth）」「API Key」「云平台（Bedrock / Vertex / Azure / Cloudflare）」「自定义」。
- 每行显示服务商名、认证方式图标、状态点。
- 数据来自 W2 已经实现的 42 个服务商目录（`CatalogRepository`，按 pi 版本缓存）。
- 搜索框支持拼音首字母，例如输入「qw」可以找到 qwen。
- **服务商详情页**：
  - Key 输入（存 Keystore，然后写 `auth.json`）或「用账号登录」；
  - 云平台额外的字段（region、resource、account id）；
  - 测试连接；
  - 退出登录；
  - 「⋯」里有复制凭据给外部客户端（见 2.1）。
- **自定义服务商**：
  - 字段包括 `baseUrl`、`api`（下拉选 openai-completions、openai-responses、anthropic-messages、google-generative-ai、bedrock-converse-stream、mistral-conversations）、Key、headers；
  - 「获取模型列表」自动回填 contextWindow、reasoning 和是否支持图片输入；
  - 每个模型的 cost、`compat`、`modelOverrides` 放在「高级」里；
  - 保存后写 `models.json`，并调用 `/reload`。

### 6.3 会话内切换模型与思考强度

- **模型胶囊**：点按打开模型面板（UI 形态是半屏面板，终端形态是 `/model` 选择器）。面板从上到下：
  - 搜索框；
  - 「常用」（`enabledModels`，可以给每个模型固定思考档位，例如 `sonnet:high`）；
  - 「可用」（`get_available_models`）；
  - 「其他」：未配置凭据的模型灰显，点按会进入该服务商的配置页，配好后自动回到对话并选中这个模型。这是 PR #14 已有的 PendingModel 逻辑。
- 每行显示上下文窗口、是否推理、是否支持图片、单价（美元）。
- 底部操作：「设为默认」（写 `defaultProvider`、`defaultModel`、`defaultThinkingLevel`）、「刷新目录」。
- 左右滑动胶囊 = `cycle_model`，只在常用模型之间循环。生成中也可以切换，从下一轮开始生效，并给出提示。
- **思考胶囊**：点按 = `cycle_thinking_level`；长按列出全部档位，只显示当前模型支持的（`get_available_thinking_levels`）。模型不支持推理时隐藏。终端形态里，编辑器边框颜色表示当前档位（`thinkingOff`…`thinkingMax`）。
- 费用一律按 pi 给的美元显示，订阅账号显示 `(sub)`。不做人民币换算，因为 pi 没有这个功能。

---

> （§7–§8 正在写）
