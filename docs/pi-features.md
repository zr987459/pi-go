# pi 1.5.0 全功能梳理（对照 pi-go）

> 来源：`npm pack @earendil-works/pi-coding-agent@1.5.0`，读了包内 `README.md`、`docs/*.md`（48 篇）、`dist/` 源码（`modes/rpc/rpc-types.d.ts`、`core/slash-commands.js`、`core/tools/*.js`、`modes/interactive/components/*`、`theme/dark.json`、`extensions/index.js`），实际运行 `node dist/bundle/cli.js --help`。没有凭记忆写。
> pi-go 对照基线：`main@527708e`（2026-11-04 拉取的 tarball）。写于 2026-11-04。
>
> 列的含义：
> - **pi-go 现状**：已有 / 部分 / 没有，后面是对应模块路径（`…` 代表 `src/main/kotlin/io/github/zr987459/pigo/…`）。
> - **终端形态呈现**：终端形态里怎么做（目标是像 pi TUI 一样）。
> - **UI 形态入口/呈现**：极简原生界面里的入口在哪、怎么显示。
> - 用户 06:09 的要求：**每个功能两种形态都要有**，所以没有「只在终端」或「不做」。只有 pi 本身在 RPC 下也做不到、对手机没意义的项，会标「两者都只做说明」并写理由。

---

## 0. 先说清三件影响全局的事

1. **两种形态共用一个 `pi --mode rpc` 进程。** 终端形态**不是**在伪终端里另开一个交互式 `pi`。如果另开，就是两个进程、两个会话状态，切换时必然丢上下文或要重启。所以终端形态应该是「用 Compose 画出 pi TUI 的样子」：等宽字体、pi 的配色 token、同样的布局和按键语义，背后是同一个 `PiEngine`、同一个 `ChatReducer` 状态、同一份草稿。切换只是换一层渲染（见 §13）。
2. **RPC 只有 33 个命令。** pi 的很多 TUI 功能**在 RPC 里没有对应命令**：`/settings`、`/login`、`/logout`、`/scoped-models`、`/resume` 的会话列表、`/tree` 的原地切换分支、标签（label）、`/import`、`/share`、`/copy`、`/reload`、`/hotkeys`、`/trust`、`/mcp`、`/changelog`、`/debug`、`/bug`、`/quit`。`get_commands` 也明确说内置 TUI 命令不包含在内，用 `prompt` 发这些命令不会执行。所以 pi-go 必须**自己实现**这些功能，两种形态共用同一套实现：读写 `~/.pi/agent/` 下的文件（`settings.json`、`auth.json`、`models.json`、`sessions/`），或者用下面第 3 点的扩展桥。
3. **补齐 RPC 缺口最省事的办法：随 App 带一个小扩展**（例如 `pigo-bridge.ts`，启动时用 `-e` 加载）。扩展 API 有 `ctx.navigateTree()`、`pi.setLabel()`、`ctx.reload()`、`ctx.switchSession()`、`ctx.compact()` 等。扩展注册的 `/命令` 通过 RPC `prompt` 发过去会**立即执行，生成中也可以**，结果用 `extension_ui_request` 的 `notify` 或 `setStatus` 传回。这样原地切换分支、打标签、热重载都能做，而且不用改 pi。

---

## 1. CLI 参数与运行模式

### 1.1 运行模式

| 功能 | 要点（pi 1.5.0） | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 交互 TUI（默认） | `pi`；全屏（默认）或 `--tui-mode regular` | 没有（pi-go 只用 rpc） | 用 Compose 重画 TUI：页眉、消息区、编辑器、页脚 | 原生聊天页 | 两种形态都由同一个 rpc 进程驱动（§0.1） |
| print `-p` | 处理完提示后退出，输出文本 | 没有 | 不单列。`!` 命令已经覆盖一次性执行 | 不单列 | 手机上没有「跑一次就退出」的需求，rpc 已覆盖 |
| `--mode json` | 输出会话头和事件流后退出 | 没有 | 同上 | 同上 | 事件格式和 rpc 相同，没有额外能力 |
| `--mode rpc` | stdin/stdout JSONL，长驻 | 已有：`core/engine/…/PiEngine.kt`、`core/protocol/…/RpcChannel.kt`、`JsonlFramer.kt` | 底座 | 底座 | 唯一的引擎通道 |
| `pi install/remove/update/list/config` | 管理 pi 包（扩展、技能、模板、主题），`update` 也能更新 pi 本身和模型目录 | 部分：`core/runtime` 只负责安装 pi 本体 | 输入 `!pi install …` 直接跑；`/packages` 列表 | 设置 › 扩展与包：列表、安装、移除、更新 | pi 的能力，不能少；用 `GuestRunner` 执行 CLI |
| `pi auth print-api-key / print-bearer-token` | 给外部客户端打印凭据 | 没有 | `!pi auth …` | 不做入口，只在说明里提 | 会把密钥打到屏幕上，没必要提供按钮 |
| `pi mcp add/list/remove…` | 管理 MCP 服务器 | 没有 | `!pi mcp …`；`/mcp` 面板 | 设置 › MCP 服务器 | pi 原生能力 |

### 1.2 CLI 选项（`pi --help` 全量）

| 选项 | 作用 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| `--provider` `--model <pattern[:thinking]>` | 启动时选模型，可写 `sonnet:high` | 已有：`core/engine/…/EngineConfig.kt` 启动参数、`core/store/…/ModelPrefsStore.kt` | 页脚显示当前模型；`/model` 切换 | 顶栏模型胶囊 | 启动后都用 `set_model` 切换 |
| `--api-key` | 本次运行用的 key（优先级最高） | 没有（走 `auth.json`） | 不做 | 不做 | 会进进程参数，不安全；`auth.json` 已覆盖 |
| `--system-prompt` `--append-system-prompt` | 替换或追加系统提示 | 没有 | `/settings` › 系统提示（写 `SYSTEM.md`、`APPEND_SYSTEM.md`） | 设置 › 系统提示 | pi 支持写文件，比改参数更持久 |
| `-c/--continue` `-r/--resume` `--session` `--session-id` | 继续最近会话、选会话、按路径或 ID 打开 | 已有：`core/store/…/ResumePolicy.kt`、`feature/sessions`、`switch_session` | `/resume` 弹出 pi 风格的会话选择器 | 侧边栏会话列表 | 核心能力 |
| `--fork <path\|id>` | 把会话分叉到当前目录 | 部分：rpc `fork` 已接，入口在 `feature/usage/…/SessionTreeScreen.kt` | `/fork` | 消息长按 › 从这里分叉 | 核心能力 |
| `--session-dir` `--no-session` `-n/--name` | 存储目录、不保存、会话名 | 部分：有命名（`feature/commands/…/AutoNamer.kt`），没有「临时会话」 | `/name`；`/new --ephemeral` | 新建会话菜单 › 临时会话；会话名可点击编辑 | pi 原生 |
| `--models <patterns>` | Ctrl+P 循环切换的模型范围 | 没有 | `/scoped-models` 选择器 | 模型面板 › 「常用」星标 | pi 原生（存为 `enabledModels`） |
| `-nt` `-nbt` `-t/--tools` `-xt` | 工具白名单和黑名单，支持 `+name`、`-name`、`mcp__*` | 没有（固定默认工具） | `/settings` › 工具 | 设置 › 工具开关（含 grep、find、ls、codemode） | pi 原生；只读模式靠它实现 |
| `--thinking <level>` | 启动时的思考强度 | 已有：`feature/settings/…/SessionDefaults.kt` | 编辑器边框颜色；`/thinking` | 输入框上方的思考档位 | 核心能力 |
| `-e/--extension` `-ne` | 加载或禁用扩展（含 `builtin:`） | 没有 | `/settings` › 扩展；启动时自动带 `pigo-bridge` | 设置 › 扩展与包 | pi 原生；桥接扩展要用它 |
| `--no-mcp` | 关闭内置 MCP | 没有 | `/mcp` 总开关 | 设置 › MCP 总开关 | pi 原生 |
| `--skill` `-ns` | 加载或禁用技能 | 部分：`feature/commands/…/SkillsScreen.kt`、`SkillTemplateManager.kt` | `/skill:名` 补全 | 「/」面板的技能分组；设置 › 技能 | pi 原生 |
| `--prompt-template` `-np` | 加载或禁用提示模板 | 部分：同上 | `/模板名` 补全 | 「/」面板的模板分组 | pi 原生 |
| `--theme` `--use-theme` `--no-themes` | 主题文件和启动主题 | 没有（pi-go 有自己的 dsh 主题，没读 pi 主题） | 直接用 pi 的 `dark`/`light`/`system` 配色 token | 设置 › 外观：浅色、深色、跟随系统 | 终端形态要像 pi，就得用 pi 的 token |
| `-nc/--no-context-files` | 不读 `AGENTS.md`、`CLAUDE.md` | 没有 | `/settings` › 上下文文件 | 设置 › 上下文文件开关 | pi 原生 |
| `--export <file> [out]` | 导出 HTML 后退出 | 已有：rpc `export_html`，`feature/files/…/ExportSheet.kt`、`core/workspace/…/SessionExport.kt` | `/export [path]` | 会话菜单 › 导出（HTML / JSONL） | pi 原生 |
| `--list-models [search]` | 列出可用模型 | 已有：`get_available_models` + `core/catalog/…/RealModelCatalog.kt` | `/model` 选择器 | 模型面板 | 核心能力 |
| `--verbose` | 强制详细启动信息 | 没有 | 页眉显示已加载资源（等价于 `quietStartup=false`） | 会话信息页 › 已加载资源 | 页眉内容来自它 |
| `--tui-mode` | 全屏或普通 | 没有 | 只做全屏（手机没有终端回滚） | 不适用 | 手机上只有全屏一种 |
| `-a/--approve` `-na` | 信任或忽略项目本地文件 | 没有 | `/trust` | 打开工作目录时弹「信任此项目？」 | pi 原生的安全机制 |
| `--offline` | 不联网启动 | 没有 | `/settings` › 离线 | 设置 › 网络 › 离线启动 | 国内网络下有用 |
| `-h` `-v` | 帮助、版本 | 部分（安装页显示版本） | `/help`、页眉版本号 | 设置 › 关于 | 显示即可 |

---

## 2. RPC 协议

### 2.1 命令（33 个，取自 `rpc-types.d.ts`）

pi-go 契约层**全部已建模**：`core/contract/…/RpcCommand.kt`、`EngineExtras.kt`，引擎实现在 `core/engine/…/PiEngine.kt`。下表的「现状」看的是**界面是否接了**。

| 命令 | 字段要点 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| `prompt` | `message`、`images[]`、`streamingBehavior: steer\|followUp`；返回 `disposition: started\|queued\|handled` | 已有：`feature/chat/…/ChatController.kt`、`Outbox.kt` | 编辑器回车 | 发送键 | 核心 |
| `steer` | 当前轮工具执行完后、下一次调用 LLM 前送达 | 已有：`Outbox.kt` | 生成中按回车 | 生成中发送 = 引导（按钮文字「引导」） | pi 的「Enter」语义 |
| `follow_up` | 整个任务结束后才送达 | 已有 | `Alt+Enter`；触屏用编辑器右侧「⇣排队」键 | 发送键长按 › 排队 | pi 的「Alt+Enter」语义 |
| `abort` | 中止并等到空闲 | 已有 | `Esc`；触屏用 Esc 键 | 停止键 | 核心 |
| `clear_queue` | 返回 `steering[]`、`followUp[]` 的原文 | 已有：`Composer.kt` | `Alt+↑` 把队列放回编辑器；Esc 时先清队列再中止，并恢复文字 | 队列条上的「撤回」 | pi 的 Esc 行为 |
| `new_session` | `parentSession?` | 已有 | `/new` | 顶栏「＋新对话」 | 核心 |
| `get_state` | 模型、思考强度、是否在生成或压缩、两种队列模式、会话文件、ID、名称、自动压缩、消息数、待发送数 | 已有 | 页脚 | 顶栏状态点 + 会话信息页 | 状态来源 |
| `get_messages` | 全部 `AgentMessage` | 已有 | 恢复会话时重建消息区 | 同左 | 核心 |
| `set_model` | `provider`、`modelId`，返回 Model | 已有：`feature/models/…/ModelPicker.kt` | `/model`、`Ctrl+L` | 模型面板 | 核心 |
| `cycle_model` | 下一个模型；`isScoped` | 部分：契约有，没入口 | `Ctrl+P`；触屏点页脚模型名 | 模型胶囊左右滑动 | pi 原生 |
| `get_available_models` | 已配置凭据的模型 | 已有 | 选择器数据 | 同左 | 核心 |
| `set_thinking_level` | off、minimal、low、medium、high、xhigh、max | 已有：`ModelPicker.kt` | `/thinking` | 思考档位胶囊 | 核心 |
| `cycle_thinking_level` | 下一档 | 部分 | `Shift+Tab`；触屏点编辑器边框 | 点思考胶囊循环 | pi 原生 |
| `get_available_thinking_levels` | 当前模型支持的档位 | 已有：`PickerLogic.kt` | 选择器数据 | 同左 | 核心 |
| `set_steering_mode` / `set_follow_up_mode` | `all` 或 `one-at-a-time` | 已有：`feature/settings/…/SessionDefaults.kt` | `/settings` | 设置 › 对话 › 排队方式 | pi 原生 |
| `compact` | `customInstructions?`；返回摘要和 token 前后对比 | 已有：`feature/usage/…/CompactionCard.kt` | `/compact [说明]` | 上下文环 › 立即压缩 | 核心 |
| `set_auto_compaction` | 开关 | 已有：`SessionDefaults.kt` | `/settings` | 设置 › 对话 › 自动压缩 | pi 原生 |
| `set_auto_retry` / `abort_retry` | 自动重试开关；取消重试 | 已有：重试横条在 `ChatScreen.kt` | 状态行显示「重试 2/3」，Esc 取消 | 横条 + 取消 | pi 原生 |
| `bash` / `abort_bash` | `command`、`excludeFromContext`；流式 `bash_execution_update` | 部分：`SlashCompletion.kt` 能识别 `!`；`feature/files/…/OutputViewer.kt` | `!cmd` / `!!cmd`，输出块用 `bashMode` 色 | 输入以 `!` 开头时变「终端模式」；工具栏「>_」按钮 | pi 的输入语法 |
| `get_session_stats` | 消息数、工具数、tokens（入/出/缓存读/缓存写）、费用、上下文占用 | 已有：`feature/usage/…/UsageSheet.kt` | `/session` | 会话信息页 | pi 原生（`/session`） |
| `export_html` | `outputPath?` | 已有：`ExportSheet.kt` | `/export` | 会话菜单 › 导出 | pi 原生 |
| `switch_session` | `sessionPath` | 已有：`feature/sessions` | `/resume` | 侧边栏点会话 | 核心 |
| `fork` | `entryId`，返回原文 | 已有（树页） | `/fork` 弹出用户消息选择器 | 消息长按 › 分叉 | 核心 |
| `clone` | 复制当前分支 | 已有：`app/…/WiredPages.kt` | `/clone` | 会话菜单 › 复制会话 | pi 原生 |
| `get_fork_messages` | 可分叉的用户消息 | 已有 | `/fork` 选择器数据 | 同左 | 核心 |
| `get_entries` | `since` 游标，`leafId` | 已有：`ChatReducer.kt` | 增量同步 | 同左 | 底座 |
| `get_tree` | 树节点含 `label`、`labelTimestamp` | 已有：`feature/usage/…/SessionTreeScreen.kt` | `/tree` 树形选择器 | 会话菜单 › 分支树 | 核心 |
| `get_last_assistant_text` | 最后一条助手文本 | 部分：契约有 | `/copy`、`Ctrl+X` | 消息长按 › 复制 | pi 原生 |
| `set_session_name` | 名称；事件 `session_info_changed` | 已有：`AutoNamer.kt` | `/name` | 点会话名编辑 | pi 原生 |
| `get_commands` | 扩展命令、模板、技能（不含内置 TUI 命令） | 已有：`feature/commands/…/CommandPalette.kt` | `/` 补全 | 「/」面板 | 核心 |

**RPC 没有、要靠桥接扩展或直接读写文件补的**：会话列表（读 `sessions/` 目录，`feature/commands/…/SessionScanner.kt`、`PiSessionIndex.kt` 已在做）、原地切换分支（`ctx.navigateTree`）、标签（`pi.setLabel`）、热重载（`ctx.reload`）、登录（写 `auth.json`，`core/catalog/…/RealAuthManager.kt` 已有）、导入（复制 JSONL 文件再 `switch_session`）。

### 2.2 事件（stdout）

| 事件 | 字段要点 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| `agent_start` / `agent_end` | `messages`、`willRetry` | 已有：`ChatReducer.kt` | 状态行出现或消失 loader | 发送键变停止键 | 运行状态 |
| `agent_settled` | `aborted` | 已有 | loader 消失 | 停止键恢复成发送键 | 真正结束的信号 |
| `turn_start` / `turn_end` | `message`、`toolResults` | 已有：`TurnStats.kt` | 不单独显示 | 不单独显示 | 内部统计 |
| `message_start` / `message_end` | 完整消息，以 end 为准 | 已有 | 消息块 | 消息气泡 | 核心 |
| `message_update` | `usage` + `assistantMessageEvent`：text/thinking/toolcall 的 `_start`、`_delta`、`_end` | 已有 | 流式文字、思考块（`thinkingText` 色）、工具调用块 | 流式渲染 | 核心 |
| `tool_execution_start/update/end` | `toolCallId`、`args`、`partialResult`、`result`、`isError`、`durationMs` | 已有：`ChatRows.kt`、`Messages.kt` | 工具块，背景色分 pending、success、error | 过程行，可展开 | 核心 |
| `queue_update` | `steering[]`、`followUp[]` 全量 | 已有：`Outbox.kt` | 编辑器上方列出排队消息 | 输入框上方的队列条 | 核心 |
| `entry_appended` | 扩展追加的自定义条目 | 部分 | `customMessage` 色块 | 系统提示行 | 扩展需要 |
| `session_info_changed` | `name` | 已有 | 页脚会话名 | 顶栏标题 | pi 原生 |
| `thinking_level_changed` | `level` | 已有 | 编辑器边框颜色 | 思考胶囊 | pi 原生 |
| `compaction_start/end` | `reason: manual\|threshold\|overflow`、`result`、`aborted`、`willRetry`、`errorMessage` | 已有：`core/protocol/…/CompactionSummary.kt`、`CompactionCard.kt` | 「压缩中…」loader + 摘要块 | 压缩卡片（可展开摘要） | 核心 |
| `auto_retry_start/end` | `attempt`、`maxAttempts`、`delayMs`、`errorMessage`、`finalError` | 已有 | 倒计时状态行 | 重试横条 | pi 原生 |
| `summarization_retry_*` | 压缩或分支摘要的重试 | 部分 | 同上 | 同上 | pi 原生 |
| `bash_execution_update`（只在 rpc） | `id`、`delta` | 部分：`OutputViewer.kt` | `!` 输出实时滚动 | 终端输出块 | pi 原生 |
| `extension_error`（只在 rpc） | `extensionPath`、`event`、`error` | 部分：`core/protocol/…/Diagnostics.kt` | 红色提示行 | 吐司提示 + 诊断页 | 排错需要 |
| `extension_ui_request` | 阻塞类 `select`、`confirm`、`input`、`editor`（可带 `timeout`）；通知类 `notify`、`setStatus`、`setWidget`、`setTitle`、`set_editor_text` | 已有：`feature/chat/…/ExtUi.kt`、`ExtDialogs.kt` | pi 风格的弹层；`setStatus` 显示在页脚，`setWidget` 显示在编辑器上方 | 底部弹窗；状态文字放顶栏副标题 | 扩展和桥接都靠它 |

---

## 3. 斜杠命令（内置 23 个，加上内置扩展 `/mcp`、`/llama`，以及资源命令）

来源：`dist/core/slash-commands.js`、`interactive-mode.js`。`/debug`、`/quit` 在交互模式里处理。另有两个彩蛋 `/arminsayshi`、`/dementedelves`，不做。

| 命令 | 作用 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| `/settings` | 设置菜单 | 部分：`feature/settings/…/SettingsScreen.kt`（项目比 pi 多） | pi 风格 `SettingsList` 弹层 | 设置页（按 §8 精简） | pi 原生；按用户要求精简到 pi 本身有的项 |
| `/model [p/m]` | 模型选择器（`Ctrl+L`） | 已有：`feature/models/…/ModelPicker.kt` | 可搜索的列表弹层 | 顶栏模型胶囊 › 底部面板 | 核心 |
| `/thinking [level]` | 思考强度 | 已有 | 选择器 | 思考胶囊 | 核心 |
| `/scoped-models` | 设置 Ctrl+P 循环的范围，可排序，`Ctrl+S` 保存 | 没有 | 多选列表，`Alt+↑/↓` 排序（触屏拖动） | 模型面板 › 「常用」编辑 | pi 原生 |
| `/login [provider]` | OAuth 或 API key 登录 | 已有：`core/catalog/…/OAuthLoginSession.kt`、`feature/models/…/ProviderScreens.kt` | 服务商选择器 › 登录对话框（显示 URL 和代码） | 设置 › 模型 › 服务商 › 登录 | 核心；RPC 没有，自己写 `auth.json` |
| `/logout` | 移除凭据 | 已有：`ProviderScreens.kt` | 选择器 | 服务商页 › 退出登录 | 核心 |
| `/llama` | 管理 llama.cpp 路由（内置扩展） | 没有 | 由扩展通过 `extension_ui` 呈现 | 「/」面板 | 手机上很少用，但它走扩展 UI 协议，零成本可用 |
| `/new` | 新会话 | 已有 | 命令 | ＋新对话 | 核心 |
| `/resume` | 会话选择器（搜索、重命名、删除、显示路径、排序、只看已命名） | 已有：`feature/sessions/…/SessionsScreen.kt` | pi 风格的选择器，快捷键映射成长按菜单 | 侧边栏 + 搜索 | 核心 |
| `/name [name]` | 设置或查看会话名 | 已有 | 命令 | 点标题编辑 | pi 原生 |
| `/session` | 会话信息与统计 | 已有：`UsageSheet.kt` | 文本块输出 | 会话信息页 | pi 原生 |
| `/tree` | 会话树导航（筛选、标签、折叠） | 部分：`SessionTreeScreen.kt` 只读，没有原地切换和标签 | 树形选择器，筛选和标签的快捷键映射成按钮 | 会话菜单 › 分支树 | 核心；切换和标签靠桥接扩展 |
| `/fork` | 从更早的用户消息分叉出新会话 | 已有 | 用户消息选择器 | 消息长按 | 核心 |
| `/clone` | 复制当前分支 | 已有 | 命令 | 会话菜单 | pi 原生 |
| `/compact [说明]` | 手动压缩 | 已有 | 命令 | 上下文环 | 核心 |
| `/import <path>` | 导入 JSONL（保留 ID 和工作目录） | 没有 | 命令 + 文件选择 | 侧边栏 › 导入会话（系统文件选择器） | pi 原生 |
| `/copy` | 复制最后一条回复（`Ctrl+X`） | 部分 | 命令 | 消息长按 › 复制 | pi 原生 |
| `/export [path]` | 导出 HTML 或 JSONL | 已有 | 命令 | 会话菜单 › 导出 | pi 原生 |
| `/share` | 上传后返回查看链接（Radius 或 GitHub gist） | 没有 | 命令（需要 `gh` 或 Radius 登录） | 会话菜单 › 分享链接 | pi 原生；需要登录，先给入口和提示 |
| `/bug [描述]` | 生成私有错误报告 | 没有 | 命令 | 设置 › 关于 › 报告问题 | pi 原生 |
| `/trust` | 保存项目信任决定 | 没有 | 命令 | 工作目录菜单 › 信任此项目 | pi 原生安全机制 |
| `/mcp` | MCP 管理（内置扩展） | 没有 | 扩展 UI 弹层 | 设置 › MCP | pi 原生 |
| `/reload` | 重载快捷键、扩展、技能、模板、主题、上下文文件 | 没有（现在只能重启引擎） | 命令 | 设置里改完后自动重载；「/」面板也有 | 用桥接扩展的 `ctx.reload()` 做，不用重启引擎 |
| `/hotkeys` | 快捷键表 | 没有 | 显示 §9 的映射表（含触屏手势） | 设置 › 关于 › 手势与快捷键 | pi 原生；外接键盘也能用 |
| `/changelog` | 更新日志 | 没有 | 显示包内 `CHANGELOG.md` | 设置 › 关于 › 更新日志 | pi 原生 |
| `/debug` | 写 `pi-debug.log` | 部分：`Diagnostics.kt` | 命令 | 设置 › 关于 › 导出诊断 | 排错 |
| `/quit` | 退出 | 没有 | 命令：结束引擎 | 设置 › 停止引擎 | 手机上「退出」= 停止引擎进程 |
| `/模板名 [参数]` | 提示模板 | 部分：`SkillTemplateManager.kt` | 补全，显示 `argument-hint` | 「/」面板 › 模板 | pi 原生 |
| `/skill:名` | 技能命令（`enableSkillCommands`） | 部分 | 补全 | 「/」面板 › 技能 | pi 原生 |
| 扩展命令 | `pi.registerCommand` | 已有（`get_commands`） | 补全 | 「/」面板 › 扩展 | pi 原生 |

---

## 4. 内置工具

来源：`dist/core/tools/*.js`、`--help`。默认开启 `read`、`bash`、`edit`、`write`（`defaultTools`）。输出截断上限 2000 行或 50 KB（`truncate.js`），截断时给出 `full_output_path`。

| 工具 | 参数 | 显示（pi TUI） | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|---|
| `read` | `path`、`offset?`、`limit?`；图片返回 `image` 块 | 标题 `read path:范围`，折叠后显示前几行 | 已有：`ChatRows.kt` 通用工具卡 | `toolTitle` + `toolOutput` 色，`Ctrl+O` 展开 | 过程行「读取 文件名」，展开看内容和图片 | 核心 |
| `write` | `path`、`content` | 标题 + 内容预览（代码高亮） | 已有（通用） | 同 TUI | 「写入 文件名」+ 代码预览 | 核心 |
| `edit` | `path`、`edits[{oldText,newText}]`（也接受单个 `oldText/newText`） | **diff**（`toolDiffAdded/Removed/Context`） | 部分：`feature/usage/…/DiffView.kt`、`LineDiff.kt` | 红绿 diff | 「编辑 文件名 +3 −1」，展开看 diff | 核心 |
| `bash` | `command`、`timeout?`；结果含 `exit_code`、`wall_time_seconds`、`truncated` | `$ command` + 流式输出 | 已有：`feature/files/…/Ansi.kt` | 原样显示 ANSI | 「运行 命令」+ 等宽输出块 | 核心 |
| `grep`（默认关） | `pattern`、`path?`、`glob?`、`ignoreCase?`、`literal?`、`context?`、`limit?`（默认 100） | 匹配列表 | 已有（通用） | 同 TUI | 「搜索 模式」 | pi 原生 |
| `find`（默认关） | `pattern`、`path?`、`limit?`（默认 1000） | 文件列表 | 已有（通用） | 同 TUI | 「查找 模式」 | pi 原生 |
| `ls`（默认关） | `path?`、`limit?`（默认 500） | 列表 | 已有（通用） | 同 TUI | 「列出 目录」 | pi 原生 |
| `powershell` | 只在 Windows | — | 不适用 | 不适用 | 不适用 | 安卓上没有 |
| `codemode`（内置扩展，`--tools +codemode`） | 原始 JS，在 QuickJS 沙箱里调用其他工具 | 脚本 + 输出 | 没有 | 代码块 + 输出 | 过程行「脚本」，展开看代码 | pi 原生（可选） |
| `tool_search`（内置扩展） | 按需检索工具 | 普通工具行 | 没有 | 同 TUI | 过程行 | pi 原生 |
| MCP 工具 `mcp__服务器__工具` | 由 MCP 服务器定义 | 普通工具行 | 没有 | 同 TUI | 过程行，前面加服务器名 | pi 原生 |

---

## 5. 会话

| 功能 | 要点 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 存储位置 | `~/.pi/agent/sessions/--<cwd 替换>--/<时间戳>_<id>.jsonl`；`sessionDir`、`PI_CODING_AGENT_SESSION_DIR`、`--session-dir` 可改 | 已有：`core/store/…/FileSessionRepository.kt`、`GuestPaths.kt` | `/session` 显示路径 | 会话信息页显示路径 | 底座 |
| 格式 | v3 JSONL：会话头 + 条目树（`id`/`parentId`）；条目类型有 message、model_change、thinking_level_change、usage、compaction、context_edit、branch_summary、custom、custom_message、label、session_info | 已有：`core/store/…/SessionFileParser.kt`（只读头尾各 64 KB） | — | — | 底座 |
| 继续最近 / 恢复 | `-c` / `/resume` | 已有：`ResumePolicy.kt` | 会话选择器 | 侧边栏 | 核心 |
| 新建 | `/new` | 已有 | 命令 | ＋ | 核心 |
| 分叉 / 复制 | `/fork`、`/clone` | 已有 | 命令 | 长按、会话菜单 | 核心 |
| 树导航 | 在同一文件里换分支；选用户消息会把原文放回编辑器；离开分支时可生成分支摘要（`branchSummary.*`） | 部分：只读树 | `/tree`，双击 Esc 也可打开（`doubleEscapeAction`） | 分支树页：点节点切换，弹「是否摘要离开的分支」 | 核心；切换靠桥接扩展 |
| 树筛选 | default、no-tools、user-only、labeled-only、all（`treeFilterMode`） | 没有 | 筛选快捷键映射成按钮 | 树页顶部的筛选胶囊 | pi 原生 |
| 标签 | `LabelEntry`；`Shift+L` 编辑，`Shift+T` 显示时间 | 没有 | 树中编辑 | 节点长按 › 打标签 | pi 原生（`pi.setLabel` 桥接） |
| 会话名 | `/name`、`--name` | 已有 | 页脚 | 标题 | pi 原生 |
| 删除会话 | 选择器里 `Ctrl+D` | 已有，另外加了回收站：`FileSessionRepository.kt` | 选择器里删除 | 侧边栏左滑删除 | pi 原生；回收站是额外加的（见 §12） |
| 选择器选项 | 显示路径 `Ctrl+P`、排序 `Ctrl+S`、只看已命名 `Ctrl+N`、重命名 `Ctrl+R` | 部分：有搜索和分组（`DrawerGrouping.kt`） | 映射成工具栏按钮 | 侧边栏顶部的「排序 / 只看已命名」菜单 | pi 原生 |
| 压缩（手动 / 自动） | 剩余空间低于 `reserveTokens`（16384）时自动压缩，保留最近 `keepRecentTokens`（20000）；溢出时自动恢复；摘要分「目标、约束、进度、决定、下一步、关键上下文」几节 | 已有：`CompactionCard.kt` | 摘要块（`customMessage` 色） | 压缩卡片 + 上下文环 | 核心 |
| 导出 | HTML（自带模板）或 JSONL | 已有 | `/export` | 会话菜单 | pi 原生 |
| 分享 | `/share`（`sessionShareDestination`） | 没有 | 命令 | 会话菜单 | pi 原生 |
| 导入 | `/import` | 没有 | 命令 | 侧边栏 › 导入 | pi 原生 |
| 临时会话 | `--no-session` | 没有 | `/new` 选项 | 新建菜单 | pi 原生 |
| 工作目录 | 会话记录创建时的 cwd，不会再改；目录不存在时询问是否用当前目录 | 部分：`core/workspace/…/RootfsWorkspace.kt` | 页脚显示 `~/路径 (git 分支)` | 顶栏副标题显示目录，点击可换目录 | pi 原生 |

---

## 6. 模型与服务商

| 功能 | 要点 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 内置服务商 | 约 42 个（见下面的列表） | 已有：W2，`core/store/…/ProviderSpecs.kt`、`ProviderPresets.kt`、`BuiltinCatalog.kt` | `/login` 列表 | 设置 › 模型 › 服务商 | 用户要求保留 |
| OAuth / 订阅登录 | Anthropic（Claude Pro/Max）、OpenAI（ChatGPT Plus/Pro，Codex）、GitHub Copilot、Kimi Code、Meta（Muse）、xAI（Grok/X）、OpenRouter OAuth、Radius | 已有：`core/catalog/…/OAuthLoginSession.kt`、`Pkce.kt`、`OAuthProtocol.kt` | 登录对话框：显示 URL，可粘贴回调地址或代码 | 服务商页 › 用账号登录（打开浏览器） | 核心 |
| API key 来源与优先级 | `--api-key` > `auth.json` > `models.json` 里的 `apiKey` > 环境变量；`auth.json` 的 key 可以写成 `!命令`；可附带 `env` | 已有：`core/store/…/KeystoreSecretStore.kt`、`AuthJsonLock.kt` | `/login` › API key | 服务商页 › 填 Key | 核心 |
| 自定义服务商 `models.json` | `providers.<id>`：`baseUrl`、`api`（openai-completions、openai-responses、anthropic-messages、bedrock-converse-stream、mistral-conversations…）、`apiKey`、`headers`、`models[]`、`modelOverrides`、`compat` | 已有：`core/catalog/…/RealCustomProviderStore.kt`、`feature/models/…/CustomAndSettingsScreens.kt`、`ModelDiscovery.kt` | `/settings` › 自定义服务商（表单）；也可以 `!` 编辑文件 | 设置 › 模型 › 添加自定义服务商 | 用户要求保留 |
| 模型切换 | `/model`、`Ctrl+L`、`set_model` | 已有 | 选择器 | 模型胶囊 | 核心 |
| 循环切换 | `Ctrl+P` / `Shift+Ctrl+P`，范围由 `enabledModels` 决定 | 没有入口 | 点页脚模型名或左右滑 | 模型胶囊左右滑 | pi 原生 |
| 作用域模型 | `/scoped-models`，`--models sonnet:high,haiku:low` 可以给每个模型固定思考档位 | 没有 | 多选列表 | 模型面板 › 常用 | pi 原生 |
| 思考强度 | 7 档；`xhigh`、`max` 只有部分模型有；`thinkingBudgets`、`modelThinkingLevels` | 已有 | 编辑器边框颜色（`thinkingOff`…`thinkingMax`） | 思考胶囊 | 核心 |
| 默认模型 | `defaultProvider`、`defaultModel`、`defaultThinkingLevel`，选择器里 `Ctrl+S` 保存 | 已有：`ModelPrefsStore.kt` | 选择器里「设为默认」 | 模型面板 › 设为默认 | pi 原生 |
| 模型目录更新 | 内置目录 + pi.dev 覆盖；`pi update --models` | 部分：`core/catalog/…/CatalogRepository.kt` | `!pi update --models` | 模型面板 › 刷新 | pi 原生 |
| llama.cpp 本地模型 | 内置扩展 `/llama` | 没有 | 扩展 UI | 「/」面板 | pi 原生，可选 |
| 虚拟模型 / 分类器 / 图像模型 | 扩展注册；codemode 里调用 | 没有 | 随扩展出现 | 随扩展出现 | 高级功能，跟着扩展走 |

内置服务商 ID（从 bundle 里提取）：`amazon-bedrock`、`ant-ling`、`anthropic`、`azure`、`baseten`、`cerebras`、`cloudflare-ai-gateway`、`cloudflare-workers-ai`、`deepseek`、`fireworks`、`github-copilot`、`google`、`google-vertex`、`groq`、`huggingface`、`kimi-coding`、`meta`、`minimax`、`minimax-cn`、`mistral`、`moonshotai`、`moonshotai-cn`、`nvidia`、`openai`、`openai-codex`、`opencode`、`opencode-go`、`openrouter`、`qwen-token-plan`、`qwen-token-plan-cn`、`qwen-token-plan-individual`、`together`、`typesafe`、`vercel-ai-gateway`、`xai`、`xiaomi`、`xiaomi-token-plan-ams`、`xiaomi-token-plan-cn`、`xiaomi-token-plan-sgp`、`zai`、`zai-coding-cn`，另外有 `radius`（登录后才出现）和 llama.cpp（扩展）。

---

## 7. 技能、扩展、提示模板、上下文文件、主题、MCP、包

| 功能 | 要点 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 技能 | 目录里放 `SKILL.md`（frontmatter 写 `name`、`description`），按需加载；位置 `~/.pi/agent/skills/`、`.pi/skills/`、`--skill`、包 | 部分：`feature/commands/…/SkillsScreen.kt`、`SkillsController.kt` | `/skill:名`，页眉列出已加载技能 | 「/」面板 › 技能；设置 › 技能（列表、启用、导入） | pi 原生 |
| 提示模板 | `prompts/*.md`，frontmatter 写 `description`、`argument-hint`；参数写法 `$1`、`$@`、`${1:-默认}`、`${@:N}`、`${@:N:L}` | 部分：`SkillTemplateManager.kt` | `/名` 补全，显示参数提示 | 「/」面板 › 模板；选中后弹参数输入框 | pi 原生 |
| 扩展 | TS 模块：事件、`registerTool`、`registerCommand`、`registerShortcut`、`registerFlag`、`sendMessage`、`setLabel`、`appendEntry`、渲染器、`ctx.ui`、`navigateTree`、`reload`… | 部分：扩展 UI 协议已接（`ExtUi.kt`） | 扩展的弹层、状态、组件按 TUI 样式画 | 同一套协议画成原生弹窗 | pi 原生；桥接扩展也靠它 |
| 内置扩展 | `llama.cpp`、`codemode`、`tool-search`、`mcp` | 没有 | 随 pi 自带 | 设置 › 扩展里开关 | pi 原生 |
| 上下文文件 | `AGENTS.override.md`、`AGENTS.md`、`CLAUDE.md`（从 agent 目录、cwd、各级父目录读）；`SYSTEM.md` 替换系统提示，`APPEND_SYSTEM.md` 追加 | 没有 | 页眉列出已加载的上下文文件 | 设置 › 上下文文件：查看和编辑全局 `AGENTS.md`；会话信息页列出已加载的文件 | pi 原生 |
| 主题 | `system`（读终端调色板）、`dark`、`light`；自定义 JSON：`vars` + 56 个 `colors` token + `export` | 没有（pi-go 用 dsh 主题） | **直接用 pi 的 token 和值**，「跟随系统」= 安卓深浅色 | 极简主题：浅色、深色、跟随系统 | 终端形态要像 pi |
| MCP | stdio / HTTP（含 OAuth）；`~/.pi/agent/mcp.json`、`.pi/mcp.json`；`/mcp` 可以登录、重连、开关、调整暴露范围 | 没有 | `/mcp` | 设置 › MCP 服务器 | pi 原生 |
| 包 | npm、git、本地；`pi install/remove/update/list/config` | 没有 | `!pi install …`、`/packages` | 设置 › 扩展与包 | pi 原生 |
| 项目信任 | 项目 `.pi/` 下的设置、扩展、技能需要信任后才加载（`defaultProjectTrust`） | 没有 | `/trust` | 首次打开目录时弹窗 | pi 原生安全机制 |
| 键位文件 | `keybindings.json` | 没有 | 外接键盘按此生效；触屏映射见 §9 | 设置 › 手势与快捷键（只读表格） | pi 原生 |

---

## 8. `settings.json` 全部键（`~/.pi/agent/settings.json`，项目里是 `.pi/settings.json`）

pi-go 现状：`core/store/…/PiProviderConfigStore.kt` 会写一部分；`feature/settings/…/SessionDefaults.kt` 有排队方式、自动压缩、自动重试。用户要求「设置里只保留模型配置」，所以 **UI 形态把下表里非模型的键收进设置页的「高级（pi 设置）」二级页**，用一个通用的键值编辑器呈现，不单独做页面。终端形态用 pi 风格的 `/settings` 列表。

| 分组 | 键（默认值） | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 模型 | `defaultProvider`、`defaultModel`、`defaultThinkingLevel`(medium)、`modelThinkingLevels`、`thinkingBudgets`、`enabledModels` | 已有（部分键） | `/settings` › 模型 | **设置首页：模型** | 用户要求保留的唯一一类 |
| 显示 | `hideThinkingBlock`(false)、`showCacheMissNotices`(false)、`quietStartup`、`theme`(system) | 部分 | `/settings`；`Ctrl+T` 切换思考块 | 高级 › 显示；消息区点思考块折叠 | pi 原生 |
| 缓存 | `cacheWarming`(streaming) | 没有 | `/settings` | 高级 | pi 原生 |
| 队列 | `steeringMode`、`followUpMode`(one-at-a-time) | 已有 | `/settings` | 高级 › 对话 | pi 原生 |
| 编辑器 | `externalEditor`、`doubleEscapeAction`(tree)、`autocompleteMaxVisible`(5)、`editorPaddingX`(0)、`showHardwareCursor` | 没有 | `/settings`；双击 Esc 打开树 | 高级 | 终端形态需要；外部编辑器在手机上不适用 |
| 树 | `treeFilterMode`(default)、`branchSummary.reserveTokens`、`branchSummary.skipPrompt` | 没有 | `/settings` | 高级 › 会话 | pi 原生 |
| 信任 | `defaultProjectTrust`(ask，只能在全局设置里改) | 没有 | `/settings` | 高级 › 安全 | pi 原生 |
| 工具 | `defaultTools`(read,bash,edit,write)、`codemode.mode`、`codemode.inlineBudget` | 没有 | `/settings` › 工具 | 高级 › 工具开关 | pi 原生 |
| 会话 | `sessionDir`、`sessionShareDestination`(auto) | 部分 | `/settings` | 高级 › 会话 | pi 原生 |
| 压缩 | `compaction.enabled`(true)、`reserveTokens`(16384)、`keepRecentTokens`(20000)、`modelOverrides` | 部分（只有开关） | `/settings` | 高级 › 压缩 | pi 原生 |
| 全屏 TUI | `tuiMode`、`fullscreenExitOutput`、`fullscreenScrollbar`、`fullscreenCopyOnSelect`、`fullscreenWheelScrollLines`、`outputPad` | 不适用 | 终端形态内部使用（滚动条、选中即复制） | 不适用 | 只影响终端渲染 |
| 终端 | `terminal.showImages`、`imageWidthCells`、`clearOnShrink`、`showTerminalProgress`、`hyperlinks`、`images`、`trueColor` | 不适用 | 终端形态：内联图片宽度 | 不适用 | 只影响终端渲染 |
| 图片 | `images.autoResize`(true，最大 2000×2000)、`images.blockImages`(false) | 部分：`core/workspace/…/ImagePrep.kt` | `/settings` | 高级 › 图片 | pi 原生 |
| Markdown | `markdown.codeBlockIndent`、`markdown.mermaid`(streaming) | 没有 Mermaid | 终端形态按缩进渲染 | 代码块；Mermaid 渲染成图 | pi 原生 |
| 网络 | `transport`(auto)、`httpProxy`、`httpIdleTimeoutMs`(300000)、`websocketConnectTimeoutMs`(15000) | 部分：`core/store/…/HostProxy.kt` | `/settings` | 高级 › 网络（代理要保留，国内需要） | pi 原生 |
| 重试 | `retry.enabled`、`maxRetries`(3)、`baseDelayMs`(2000)、`maxAgentDelayMs`、`retry.provider.*` | 部分（只有开关） | `/settings` | 高级 › 重试 | pi 原生 |
| Shell | `shellPath`、`shellCommandPrefix` | 没有 | `/settings` | 高级 › Shell | pi 原生 |
| 包与资源 | `npmCommand`、`selfUpdateCommand`、`packages`、`extensions`、`skills`、`prompts`、`themes`、`enableSkillCommands`(true) | 没有 | `/settings` | 高级 › 扩展与包 | pi 原生 |
| 其他 | `collapseChangelog`、`enableInstallTelemetry`(true)、`enableAnalytics`(false)、`warnings.anthropicExtraUsage` | 没有 | `/settings` | 高级 › 隐私 | pi 原生；遥测建议默认关 |

### 8.1 环境变量

| 类别 | 变量 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 服务商 key | `ANTHROPIC_API_KEY`、`OPENAI_API_KEY`、`DEEPSEEK_API_KEY`、`GEMINI_API_KEY`、`OPENROUTER_API_KEY`、`ZAI_*`、`MOONSHOT_API_KEY`、`QWEN_TOKEN_PLAN_*`、`XIAOMI_*`、`AWS_*`、`AZURE_OPENAI_*`、`CLOUDFLARE_*` 等（`--help` 里约 50 个） | 不用（走 `auth.json`） | 不显示 | 不显示 | `auth.json` 优先级更高，也更安全 |
| 进程 | `PI_CODING_AGENT_DIR`、`PI_CODING_AGENT_SESSION_DIR`、`PI_PACKAGE_DIR`、`PI_OFFLINE`、`PI_TELEMETRY`、`PI_SHARE_VIEWER_URL`、`PI_SELF_UPDATE_COMMAND`、`PI_SKIP_VERSION_CHECK`、`PI_CACHE_RETENTION`、`PI_CODEMODE_MAX_WORKERS`、`PI_RADIUS_GATEWAY` | 部分：`core/runtime/…/ProotLauncher.kt` 设置了目录 | 启动参数 | 高级 › 网络 › 离线；隐私 › 遥测 | 由 App 在启动时注入 |
| 渲染 | `PI_TUI_ESC_TIMEOUT`、`PI_HARDWARE_CURSOR`、`PI_HYPERLINKS`、`PI_IMAGE_PROTOCOL`、`PI_TRUE_COLOR`、`PI_CLEAR_ON_SHRINK`、`PI_TUI_WRITE_LOG`、`PI_PROGRAM_STATUS` | 不适用 | 不适用 | 不适用 | 只影响真终端 |
| 给 bash 工具的会话变量 | `PI_SESSION_ID`、`PI_SESSION_FILE`、`PI_PROVIDER`、`PI_MODEL`、`PI_REASONING_LEVEL`；标记 `AI_AGENT=pi`、`PI_CODING_AGENT=true` | pi 自动设置 | — | — | pi 自己处理 |

---

## 9. 快捷键全表与触屏映射

pi 的快捷键由 `keybindings.json` 定义。终端形态在**外接键盘时完全照 pi**，触屏时按下表映射。UI 形态用对应的按钮或手势。

| 动作（id） | 默认键 | pi-go 现状 | 终端形态（触屏） | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 中断 `app.interrupt` | Esc | 已有（停止键） | 编辑器上方的按键条里有 **Esc** | 停止键 | 核心 |
| 清空 / 退出 `app.clear`、`app.exit` | Ctrl+C、Ctrl+D | 没有 | 按键条 **^C**：第一次清空编辑器 | 输入框里的 × 清空 | pi 原生 |
| 挂起 `app.suspend` | Ctrl+Z | 不适用 | 不适用 | 不适用 | 手机上没有作业控制 |
| 外部编辑器 `app.editor.external` | Ctrl+G | 没有 | 按键条 **⤢**：全屏编辑器 | 输入框右上角「展开」 | 长提示需要 |
| 粘贴图片 `app.clipboard.pasteImage` | Ctrl+V | 部分：`feature/files/…/AttachmentSheet.kt` | 长按编辑器 › 粘贴图片 | ＋ › 图片、相机、剪贴板 | 核心 |
| 换行 `tui.input.newLine` | Shift+Enter、Ctrl+J | 已有（软键盘回车） | 软键盘回车 = 换行，按键条 **⏎** = 发送 | 同左 | 手机输入习惯 |
| 补全 `tui.input.tab` | Tab | 部分：`SlashCompletion.kt` | 按键条 **Tab** | 补全列表点选 | 核心 |
| 排队 `app.message.followUp` | Alt+Enter | 部分 | 按键条 **⇣** | 发送键长按 | 核心 |
| 撤回队列 `app.message.dequeue` | Alt+↑ | 已有 | 按键条 **↑队列** | 队列条「撤回」 | 核心 |
| 复制 `app.message.copy` | Ctrl+X | 部分 | 长按消息 | 长按消息 | pi 原生 |
| 选模型 `app.model.select` | Ctrl+L | 已有 | 点页脚模型名 | 模型胶囊 | 核心 |
| 循环模型 `app.model.cycleForward/Backward` | Ctrl+P、Shift+Ctrl+P | 没有 | 页脚模型名左右滑 | 胶囊左右滑 | pi 原生 |
| 循环思考 `app.thinking.cycle` | Shift+Tab | 部分 | 点编辑器边框 | 点思考胶囊 | 核心 |
| 折叠思考 `app.thinking.toggle` | Ctrl+T | 已有：`ChatRows.kt` | 点思考块 | 点思考块 | pi 原生 |
| 展开工具 `app.tools.expand` | Ctrl+O | 已有 | 点工具块（单个）；按键条 **⌃O**（全部） | 点过程行；菜单 › 全部展开 | pi 原生 |
| 保存默认 `app.models.save`、`app.thinking.save` | Ctrl+S | 部分 | 选择器里「设为默认」 | 同左 | pi 原生 |
| 编辑器光标与删除 `tui.editor.*` | ←→、Ctrl+A/E/W/U/K/Y、Alt+B/F… | 不适用（系统输入法负责） | 外接键盘生效；触屏交给输入法 | 输入法 | 手机上由输入法提供 |
| 历史 `tui.editor.cursorUp/Down` | ↑↓（在首行或末行时） | 没有 | 按键条 **↑↓**：翻历史提示 | 输入框为空时上滑看历史 | pi 原生 |
| 消息区滚动和跳转 `tui.altScreen.*` | PgUp/PgDn、Ctrl+Shift+↑/↓ 跳到上一条或下一条提示、Ctrl+Shift+F 搜索、Ctrl+Home/End | 部分（只能滚动） | 手势滚动；按键条 **⌕** 搜索；右侧滚动条拖动 | 顶栏菜单 › 搜索本会话；「回到底部」按钮 | pi 原生 |
| 会话选择器 `app.session.*` | Ctrl+P 路径、Ctrl+S 排序、Ctrl+N 只看已命名、Ctrl+R 重命名、Ctrl+D 删除 | 部分 | 选择器工具栏 | 侧边栏菜单、长按 | pi 原生 |
| 树 `app.tree.*` | Ctrl+←/→ 折叠或展开、Shift+L 标签、Shift+T 时间、Ctrl+D/T/U/L/A/O 筛选 | 没有 | 树页工具栏按钮 | 树页筛选胶囊、长按 | pi 原生 |
| 作用域模型 `app.models.*` | Ctrl+A 全选、Ctrl+X 清空、Ctrl+P 整个服务商、Alt+↑/↓ 排序 | 没有 | 工具栏 + 拖动排序 | 同左 | pi 原生 |
| 未绑定的会话动作 | `app.session.new/tree/fork/resume` 默认无键 | — | 外接键盘可绑定 | — | pi 原生 |

---

## 10. 输入语法

| 语法 | 作用 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| Enter（生成中） | 引导（steer） | 已有 | 同 pi | 发送键在生成中显示「引导」 | 核心 |
| Alt+Enter | 排队（follow-up） | 部分 | 按键条 ⇣ | 长按发送 | 核心 |
| Esc | 中止；队列里的文字回到编辑器 | 已有 | Esc 键 | 停止键；中止后自动把排队文字放回输入框 | 核心 |
| `/命令` | 斜杠命令和补全 | 已有：`SlashCompletion.kt`、`CommandPalette.kt` | pi 风格的补全浮层（最多 `autocompleteMaxVisible` 条） | 「/」面板 | 核心 |
| `@文件` | 搜索文件并插入引用；Tab 补全路径 | **没有** | `@` 弹出文件模糊搜索 | 输入 `@` 或点 ＋ › 文件，弹出工作目录文件搜索 | pi 原生；RPC 的 prompt 不接受 `@` 参数，由 App 把文件内容或路径展开到消息里 |
| 图片 | 粘贴、拖入；`prompt.images[]` 用 base64 | 部分：`feature/files/…/AttachmentController.kt`、`core/workspace/…/Attachments.kt` | 内联缩略图（`terminal.showImages`） | ＋ › 图片，输入框里显示缩略图 | 核心 |
| `!cmd` | 运行命令，输出进上下文（下一次提示时） | 部分 | 编辑器边框变 `bashMode` 色 | 输入以 `!` 开头时输入框变等宽、提示「将运行命令」 | pi 原生 |
| `!!cmd` | 运行命令，输出不进上下文（`excludeFromContext`） | 部分 | 同上 | 同上，提示「不发给模型」 | pi 原生 |
| 双击 Esc（编辑器为空时） | 打开树或分叉（`doubleEscapeAction`） | 没有 | 双击 Esc 键 | 不做手势（入口已在会话菜单），只遵循设置 | pi 原生 |
| 模板参数 | `/review 并发` | 部分 | 补全时显示 `argument-hint` | 选模板后弹参数框 | pi 原生 |

---

## 11. TUI 各界面元素与配色（终端形态要还原的东西）

来源：`modes/interactive/components/*.js`（共 48 个组件）、`footer.js`、`theme/dark.json`。

| 元素 | pi 里是什么样 | pi-go 现状 | 终端形态呈现 | UI 形态入口/呈现 | 理由 |
|---|---|---|---|---|---|
| 页眉（启动区） | pi 标志、版本、按键提示；已加载的上下文文件、技能、扩展、模型范围（`quietStartup` 控制） | 没有 | 照搬，可折叠 | 空会话页：一行「目录 · 模型」，下面「已加载 N 个上下文文件 / 技能」，可点开 | pi 原生 |
| 消息区 | 用户消息有 `userMessageBg` 背景；助手消息是 Markdown；左右留 `outputPad` | 已有（dsh 风格） | 等宽字体，pi 的 token 配色 | 极简：用户消息右侧浅色块，助手消息是无框正文 | 核心 |
| 思考块 | `thinkingText` 色（斜体、偏暗），`Ctrl+T` 折叠 | 已有 | 照搬 | 一行「思考了 Ns」，点开看全文 | 核心 |
| 工具调用块 | 背景 `toolPendingBg` / `toolSuccessBg` / `toolErrorBg`，标题 `toolTitle`，输出 `toolOutput`，diff 三色，默认折叠 | 已有（通用卡片） | 照搬，三态背景 | 过程行 + 状态点 + 时长，展开看详情 | 核心 |
| bash 执行块（`!`） | `bashMode` 色边框 | 部分 | 照搬 | 等宽输出块 | pi 原生 |
| 压缩 / 分支摘要块 | `customMessage*` 色 | 已有 | 照搬 | 压缩卡片 | 核心 |
| 技能调用消息 | `skill-invocation-message` | 没有 | 照搬 | 一行「使用技能 名称」 | pi 原生 |
| 编辑器 | 带边框；**边框颜色表示思考档位**；`bashMode` 时变色；可多行 | 已有（dsh 输入条） | 照搬，下面加一行按键条（Esc、Tab、⇣、↑队列、^C、⌕、⤢、/、@、!） | 圆角输入框 + ＋ + 发送；思考档位用胶囊表示 | 核心 |
| 自动补全 | 编辑器上方浮层，最多 5 条 | 已有：`SlashCompletion.kt` | 照搬 | 面板 | 核心 |
| 队列显示 | 编辑器上方列出排队的引导和后续消息 | 已有 | 照搬 | 队列条 | 核心 |
| 状态行 | loader 加计时、重试倒计时、扩展的 `setStatus` | 部分 | 照搬 | 顶栏副标题 | 核心 |
| **页脚** | 左：`~/路径 (git 分支) • 会话名`；统计：`↑输入 ↓输出 R缓存读 W缓存写 CH缓存命中% $费用 (sub)`、`上下文%/窗口`（自动压缩标记，超阈值变黄或红）；右：`(provider) 模型 • 思考档位` | 部分：数据在 `UsageController.kt`，显示在用量页 | **照搬**，点各段打开对应选择器 | 顶栏只放模型和上下文环；完整统计放会话信息页 | pi 原生；页脚就是 pi 的用量显示 |
| 选择器弹层 | 模型、思考、作用域模型、会话、树、用户消息（分叉）、设置、主题、OAuth、登录、信任、显示图片、配置 | 部分 | 统一做一个「pi 风格选择器」组件：搜索框 + 列表 + 底部提示 | 底部面板 | 核心 |
| 登录对话框 | 显示 URL 和设备码，可以粘贴回调 | 已有：`ProviderScreens.kt` | 照搬 | 打开浏览器，回调后自动完成 | 核心 |
| 首次设置 | `first-time-setup` | 已有：`feature/onboarding`（安装 PRoot 和 pi） | 安装完成后进入终端形态 | 引导页 | 安卓必须先装运行环境 |
| 转录搜索 | 全屏搜索，高亮 `searchMatchBg` | 没有 | 照搬 | 菜单 › 搜索 | pi 原生 |
| Mermaid | 流式渲染 | 没有 | 字符画（pi 也是在终端里画） | 渲染成图 | pi 原生 |
| 彩蛋（armin、3D、earendil 公告） | — | 没有 | 不做 | 不做 | 和功能无关 |

**配色**：pi 的 `dark.json` 用 okhsl 定义 8 个基础色（text、muted、violet、blue、green、red、yellow、blueBg），再派生出 56 个 token：accent、border、borderAccent、borderMuted、success、error、warning、muted、dim、text、thinkingText、selectedBg、scrollbar*、searchMatch*、userMessageBg/Text、customMessage*、toolPending/Success/ErrorBg、toolTitle、toolOutput、md*（标题、链接、代码、引用、分隔线、列表符号）、toolDiffAdded/Removed/Context、syntax*（9 个）、thinkingOff…thinkingMax（7 个）、bashMode。`light.json` 结构相同。终端形态应该**把这两份 JSON 原样打包进 App**（MIT 许可，在 NOTICE 记一行），运行时换算成 Compose `Color`；`system` 主题对应安卓的深浅色。

---

## 12. pi-go 现有但 pi 本身没有的功能（候选删除）

| 功能 | 位置 | pi 有没有 | 建议 | 理由 |
|---|---|---|---|---|
| 吉祥物 | 调度说明里提到已合入；main@527708e 的 `res/` 和 Kotlin 代码里都**没找到**吉祥物资源（NOTICE 第 8 节写的是「未拷贝」） | 没有 | **删**（如果有残留就清掉，并把 NOTICE 里的相关说明一起删） | 用户明确不要 |
| 汇率换算、人民币费用 | `core/store/…/ModelPrefsStore.kt`、`core/catalog/…/RealAppSettingsStore.kt`、`feature/usage/…/UsageController.kt`、`feature/models/…/CustomAndSettingsScreens.kt` | 没有（pi 只显示美元，订阅显示 `(sub)`） | **删汇率**，费用照 pi 显示美元 | pi 没有 |
| 用量页、每轮统计卡 | `feature/usage/…/UsageSheet.kt`、`TurnSummary.kt`、`TurnSummaryRow.kt` | 部分（pi 有 `/session` 和页脚） | **改**：统计只放会话信息页（= `/session`）和页脚，删掉每轮统计卡 | 保留 pi 有的那部分 |
| 文件浏览器、文件预览、语法高亮 | `feature/files/…/FilesBrowserScreen.kt`、`FilePreviewScreen.kt`、`Highlight.kt` | 没有（pi 只有 `@文件` 搜索） | **删浏览器**，把文件搜索改成 `@` 选择器；`AttachmentController`、`Pickers`、`FileShare` 保留给图片和导出用 | pi 没有浏览器；`@` 是 pi 的功能 |
| Git 页面（状态、diff） | `feature/files/…/GitScreens.kt`、`core/workspace/…/GitParse.kt` | 没有（页脚只显示分支名） | **删页面**，只留读取分支名给页脚用 | pi 没有 |
| HTML 导出 | `ExportSheet.kt`、`SessionExport.kt` | **有**（`/export`、`export_html`） | **保留**，改成直接调 rpc | pi 原生 |
| 回收站 | `core/store/…/FileSessionRepository.kt`、`PiSessionIndex.kt` | 没有（pi 直接删） | 删，或保留成「删除后可撤销 5 秒」 | pi 没有；防误删可以用撤销代替 |
| 自动命名会话（调用模型取标题） | `feature/commands/…/AutoNamer.kt`、`AutoTitle.kt` | 没有（只能 `/name` 手动起名） | **删**，未命名的会话用第一条消息当标题（pi 选择器也是这样） | pi 没有，还要额外调用模型 |
| 草稿持久化 | `feature/commands/…/DraftStore.kt` | 没有（pi 进程内保留） | **保留** | §13 的「切换时保留草稿」要用；也防止被系统杀进程 |
| 侧边栏按时间分组 | `feature/sessions/…/DrawerGrouping.kt` | 部分（pi 有排序切换） | 保留成「排序：最近 / 名称」 | 接近 pi 的排序 |
| 网络设置（镜像、代理） | `feature/settings/…`、`core/store/…/HostProxy.kt`、`core/runtime/…/MirrorProbe.kt` | 部分（pi 有 `httpProxy`） | 代理保留在「高级 › 网络」；镜像只在安装页 | 国内必须要 |
| ROM 与后台保活引导 | `feature/settings/…/RomGuide.kt`、`BackgroundGuide.kt` | 没有 | 保留，从设置页移到「引擎被系统杀掉」时的提示里 | 安卓必须；不放在设置首页 |
| 通知（任务完成、前台服务） | `core/engine/…/TaskNotifier.kt`、`AndroidTaskNotifications.kt` | 部分（pi 有 OSC 9;4 终端进度、等待用户时标记） | 保留前台服务；完成通知做成默认关的开关 | 安卓后台运行需要前台服务 |
| 「测试连接」「获取模型列表」 | `core/store/…/ProviderTester.kt`、`ModelDiscovery.kt` | 部分（pi 会自动刷新 OpenRouter 等目录） | 保留在自定义服务商页 | 配模型时必须要，用户要求保留模型配置 |
| dsh-Go 视觉组件（玻璃面、渐变背景、胶囊） | `core/ui/…/DshPalette.kt`、`DshTokens.kt`、`feature/settings/…/DshComponents.kt` | 不适用 | 换成极简主题（UI 形态）和 pi token 主题（终端形态） | 用户要「重新设计、极简」 |

---

## 13. 两种形态无缝切换（06:09 新要求）

**共用的东西**（只有一份）：
- 一个 `pi --mode rpc` 进程（`PiEngine`），切换时不重启。
- 一个会话状态：`ChatReducer` 的消息列表、正在流式生成的那条消息、工具执行状态、队列、`get_state` 快照。
- 一份草稿：输入框文字、光标、已选图片和附件、`@` 引用，都放在 `ChatController`（或 `DraftStore`），两种形态读写同一个 `StateFlow`。
- 同一套「pi-go 自己实现的 TUI 命令」：`/settings`、`/login`、`/resume`、`/tree`、`/scoped-models`……在 Kotlin 里只实现一次。终端形态画成 pi 风格的弹层，UI 形态画成底部面板。

**只换一层**：`ChatScreen` 里放一个 `when (form) { Terminal -> PiTuiSurface(state, actions); Ui -> MinimalChat(state, actions) }`。两个渲染层接收**同一个** `state` 和 `actions`。切换只换渲染，不换数据，所以生成中的回复会继续流式输出，不会中断。

**切换入口**：两种形态的固定位置都有一个按钮（终端形态在页脚右端，UI 形态在顶栏右端），点一下就切。也提供 `/ui`、`/term` 两个本地命令。上次用的形态写进 App 设置。

**还要注意**：
- 滚动位置：两种形态各自记住，但都跟随 `entryId` 定位，切过去后停在同一条消息附近。
- 弹层：切换时如果有弹层（选择器、扩展对话框）开着，把弹层状态（例如 `extension_ui_request` 的 id）交给另一个形态继续显示，不能丢，否则扩展会一直卡在等待回复。
- 测试：加一个仪器测试，覆盖「生成中切换 → 流式继续 → 草稿还在 → 引擎 PID 不变」。

---

## 摘要（大白话）

pi 1.5.0 的功能比 pi-go 现在界面上露出来的多得多。引擎通信这一层（33 个 RPC 命令、全部事件）pi-go 已经基本接齐了，缺的主要在「pi 交互界面自己做的那部分」：设置、登录、会话选择、树和标签、作用域模型、`@文件`、导入和分享、MCP、扩展与包、上下文文件、主题。这些在 RPC 里没有命令，只能由 App 自己读写 pi 的配置文件来做，或者随 App 带一个小扩展来补。

做法建议：只开一个 pi 进程，终端形态和 UI 形态是同一份数据的两种画法，切换时什么都不丢。终端形态照 pi 的配色和布局来画，加一排触屏按键（Esc、Tab、排队等）；UI 形态做成极简聊天页，每个 pi 功能都有入口。

要删的：吉祥物、汇率、文件浏览器、Git 页面、自动起名、每轮统计卡、dsh 视觉风格。HTML 导出 pi 本来就有，保留。
