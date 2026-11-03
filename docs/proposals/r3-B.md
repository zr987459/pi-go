# r3 重新设计方案 B — 双形态同引擎，极简为纲

> 设计者：B（独立完成，未参考 A/C）。依据：`docs/pi-features.md`（pi 1.5.0 全功能清单）与仓库现有代码（`main@478941df`）。
> 一句话主张：**一个 `pi --mode rpc` 进程 + 一份会话状态 + 两层渲染（终端形态 / 极简 UI 形态）**。设置里只留模型配置，其余 pi 设置收进一个通用键值编辑器；凡 pi 没有的附加功能一律删。
> 写于 2026-11-04。

---

## 设计总纲（先立三条铁律）

1. **视图是函数，状态是唯一真相。** 终端形态和 UI 形态都是 `render(state, actions)` 的纯函数式渲染层，背后是**同一个** `SessionController`（持有 `PiEngine` 句柄、`ChatReducer` 状态、草稿、队列、`get_state` 快照）。切换形态只换 `when(form){…}` 里的那一个 Composable，不碰引擎、不碰 reducer、不动草稿。这是满足 06:09「无缝切换」唯一不丢上下文的架构（pi-features.md §0.1、§13 已论证，我采纳并把它定为模块边界）。
2. **RPC 只有 33 个命令，pi 的交互功能一半在 RPC 外。** `/settings /login /resume /tree /scoped-models /import /share /trust /mcp /reload /hotkeys /changelog /copy` 等 RPC 都没有。这些**在 Kotlin 里只实现一次**（读写 `~/.pi/agent/` 文件，或走桥接扩展），两种形态共用，各自只负责「长什么样」。
3. **补 RPC 缺口用一个随 App 启动的桥接扩展 `pigo-bridge`**（`-e` 加载）。它用扩展 API 暴露 `ctx.navigateTree() / pi.setLabel() / ctx.reload() / ctx.switchSession() / ctx.compact()`，注册的命令经 `prompt` 发送**生成中也能执行**，回包走 `extension_ui_request` 的 `notify/setStatus`。原地切分支、打标签、热重载都不用重启引擎。

> 这三条直接决定了模块怎么拆（见 §7）：一个「共享层」拥有状态与命令实现，两个「形态层」只拥有渲染，彼此文件范围不重叠。

---

## 1. 功能保留 / 删除 / 改造清单（逐模块）

判定口径：**pi 1.5.0 原生有没有**（以 pi-features.md 为准）。pi 有→保留（可改成双形态）；pi 没有但安卓运行必需（运行环境、前台服务、代理）→保留但移出设置首页；pi 没有且是额外花活→删。

| 模块 / 功能 | 位置 | pi 原生？ | 判定 | 理由 |
|---|---|---|---|---|
| 引擎通道（rpc、分帧、归约、事件） | `core/engine`、`core/protocol` | 是（底座） | **保留** | 唯一引擎通道，r3 不动协议 |
| 契约层（33 命令 + 事件建模、Fake） | `core/contract` | 是 | **保留 + 扩**（加 `ChatSurface` 视图契约、桥接记录），由 scaffold 改 | 两形态共享契约的落点 |
| 运行环境（PRoot 安装、启动、停止） | `core/runtime`、`feature/onboarding` | 安卓必需 | **保留** | 手机上必须先装 pi；首启引导保留 |
| 聊天控制器 / 归约驱动 | `feature/chat` logic | 是 | **改造为 `SessionController`**（形态无关） | 成为共享层核心，UI 从中剥离 |
| 聊天 UI（气泡、工具卡、思考块、编辑器） | `feature/chat` ui | 是 | **拆**：逻辑留共享层，渲染搬进两个形态模块 | 一套渲染变两套 |
| 斜杠命令 / 补全 / 命令面板 | `feature/chat` `SlashCompletion`、`feature/commands` `CommandPalette` | 是 | **保留**（合入共享层，实现一次） | `/` 补全核心 |
| 会话扫描 / 索引 / 元数据 / 草稿 | `feature/commands` data/* | 草稿=安卓必需，扫描/索引=补 RPC 缺口 | **保留** | `/resume` 列表、切换保草稿要用 |
| 自动命名会话（调模型取标题） | `feature/commands` `AutoNamer`、`AutoTitle` | 否 | **删** | pi 只有手动 `/name`；未命名用首条消息当标题 |
| 技能 / 模板管理 | `feature/commands` skills/* | 是 | **保留**（改造为双形态入口） | `/skill: /模板` pi 原生 |
| 模型选择 / 思考档位 / 服务商登录 | `feature/models` | 是 | **保留 + 补**（循环切换、作用域模型） | 用户点名要留的唯一设置 |
| 自定义服务商 / 测连 / 取模型列表 | `feature/models`、`core/store` `ProviderTester`、`ModelDiscovery` | 是（`models.json`） | **保留** | 配模型必需 |
| 设置页（项目比 pi 多） | `feature/settings` | 部分 | **精简**：首页只放模型；其余 pi 键进「高级（pi 设置）」通用键值编辑器 | 用户要求「只保留配置模型」 |
| 汇率换算 / 人民币费用 | `core/store` `ModelPrefsStore`、`RealAppSettingsStore`、`feature/usage` `UsageController`、`feature/models` `CustomAndSettingsScreens` | 否 | **删** | pi 只显示美元 + `(sub)` |
| 用量弹层 / 每轮统计卡 | `feature/usage` `UsageSheet`、`TurnSummary(Row)` | 部分（pi 有 `/session`、页脚） | **改造**：统计只进「会话信息页」(= `/session`) 与页脚；删每轮卡 | 保留 pi 有的那部分 |
| 压缩卡 / 摘要解析 / diff / 会话树（逻辑） | `feature/usage` `CompactionCard`、`CompactionSummary`、`DiffView`、`LineDiff`、`SessionTreeRows`、`SessionTreeScreen` | 是（`/compact`、edit diff、`/tree`） | **保留逻辑**；渲染并入两形态；树补「原地切换 + 标签」（桥接） | pi 原生；现状树只读，要补齐 |
| 文件浏览器 / 预览 / 高亮 | `feature/files` `FilesBrowserScreen`、`FilePreviewScreen`、`Highlight`、`browser/FilesController` | 否 | **删** | pi 只有 `@文件` 搜索，没有浏览器 |
| Git 页面（状态、diff） | `feature/files` `GitScreens`、`core/workspace` `GitParse` | 否（页脚只显示分支名） | **删页面**，保留分支名读取 | pi 没有 Git 页 |
| `@文件` 引用 | （现状**没有**） | 是 | **新增** | pi 原生输入语法，必补 |
| 附件 / 图片 | `feature/files` attach/*、`core/workspace` `Attachments`、`ImagePrep` | 是 | **保留** | 粘贴/拖入图片 pi 原生 |
| HTML / JSONL 导出 | `feature/files` `ExportSheet`、`core/workspace` `SessionExport` | 是（`/export`） | **保留**（直接调 rpc `export_html`） | pi 原生 |
| 分享 / 导入会话 | （现状没有） | 是（`/share`、`/import`） | **新增**（给入口；`/share` 需登录时提示） | pi 原生 |
| 回收站（删后可恢复） | `core/store` `FileSessionRepository`、`PiSessionIndex` | 否（pi 直接删） | **改造**为「删除后 5 秒撤销」 | 防误删，但不保留常驻回收站 |
| 侧边栏时间分组 / 排序 | `feature/sessions` `DrawerGrouping` | 部分（pi 有排序切换） | **保留**为「排序：最近 / 名称」+「只看已命名」 | 贴近 pi 选择器 |
| MCP 管理 | （现状没有） | 是（`/mcp`、内置扩展） | **新增入口** | pi 原生 |
| 扩展与包（install/remove/update/list） | `core/runtime` 只装 pi 本体 | 是（`pi install …`、`/packages`） | **新增入口**（用 `GuestRunner` 跑 CLI） | pi 原生 |
| 上下文文件（AGENTS/CLAUDE/SYSTEM） | （现状没有） | 是 | **新增**（查看/编辑全局 `AGENTS.md`，列已加载） | pi 原生 |
| 主题 | `core/ui` `DshPalette`、`DshTokens`、`feature/settings` `DshComponents` | pi 有 dark/light/system | **删 dsh 视觉**；终端形态用 pi 的 token，UI 形态用极简浅/深/跟随系统 | 用户要「重新设计、极简、无吉祥物」 |
| 吉祥物 | NOTICE 第 8 节记「未拷贝」；代码/res 未见残留 | 否 | **删**（若有残留清掉，并删 NOTICE 相应说明） | 用户明确不要 |
| 网络（镜像、代理） | `feature/settings`、`core/store` `HostProxy`、`core/runtime` `MirrorProbe` | 部分（pi 有 `httpProxy`） | **保留**：代理进「高级 › 网络」，镜像只在安装页 | 国内必需 |
| ROM / 后台保活引导、通知 | `feature/settings` `RomGuide`、`BackgroundGuide`、`core/engine` `TaskNotifier`、`AndroidTaskNotifications` | 否（安卓必需） | **保留**：引导移到「引擎被杀」提示；完成通知做成默认关开关；前台服务保留 | 安卓后台运行必需，但不放首页 |
| dsh-Go 相关名称/标识/吉祥物 | — | — | **禁用**（红线） | 不得使用 |

---

## 2. 对照 docs/pi-features.md 逐项覆盖（两形态都要有）

> 规则：每项给「终端形态怎么呈现 / UI 形态入口」。我同意 pi-features.md 绝大多数建议；**有异议处单独标「★B 调整」并说明理由**。编号沿用 pi-features.md 章节。

### §1 CLI 参数与运行模式
- **1.1 运行模式**：交互 TUI→终端形态重画；`--mode rpc`→底座（两形态共用）。`-p`/`--mode json`→不单列，`!` 一次性执行已覆盖（同意）。`pi install/remove/update/list/config`→终端 `!pi …` + `/packages`；UI「设置›扩展与包」。`auth print-*`→只保留 `!pi auth …`，不做按钮（同意，避免把密钥画到屏上）。`mcp add/list/remove`→`!pi mcp` + `/mcp`；UI「设置›MCP」。
- **1.2 CLI 选项**：逐项按 pi-features.md 表覆盖。要点：`--model[:thinking]`→页脚/顶栏模型胶囊；`--system-prompt/--append`→写 `SYSTEM.md`/`APPEND_SYSTEM.md`；`-c/-r/--session`→`/resume`/侧栏；`--fork`→`/fork`/长按分叉；`--models`→`/scoped-models`/「常用」星标；`-t/-xt`→设置›工具开关；`--thinking`→思考胶囊；`-e/-ne`→扩展开关（启动默认带 `pigo-bridge`）；`--theme`→直接用 pi token；`--export`→`/export`/会话菜单；`-a/--approve`→`/trust`/首开目录弹窗；`--offline`→网络›离线。
  - **★B 调整**：`--api-key`（本次运行 key）pi-features.md 标「不做」，我同意不做输入入口，但在「高级›网络」加一个只读说明「本 App 用 auth.json 管理凭据」，避免用户找不到而误以为缺功能。

### §2 RPC 协议
- **2.1 命令（33）**：全部有界面落点。`prompt/steer/follow_up/abort/clear_queue`→编辑器回车(引导)/长按排队/停止键/队列条撤回；`new_session/switch_session/fork/clone/get_fork_messages`→新建/侧栏/长按/会话菜单；`get_state/get_messages/get_entries`→状态来源(不单独显示)；`set_model/cycle_model/get_available_models`→模型胶囊(左右滑循环)；`set_thinking_level/cycle_thinking_level/get_available_thinking_levels`→思考胶囊；`set_steering_mode/set_follow_up_mode/set_auto_compaction/set_auto_retry/abort_retry`→高级›对话；`compact`→上下文环›立即压缩；`bash/abort_bash`→`!`/`!!`；`get_session_stats`→会话信息页；`export_html`→会话菜单；`get_tree/get_last_assistant_text/set_session_name/get_commands`→分支树/复制/改名/`/`面板。**现状缺入口的 `cycle_model`、`cycle_thinking_level`、`get_last_assistant_text` 由 §3/§4 两形态补上。**
- **RPC 外缺口**：会话列表(读 `sessions/`)、原地切分支(`ctx.navigateTree`)、标签(`pi.setLabel`)、热重载(`ctx.reload`)、登录(写 `auth.json`)、导入(复制 JSONL + `switch_session`)——全部由共享层实现一次。
- **2.2 事件**：`agent_*`→状态行/发送键态；`message_*`/`message_update`→流式文字+思考块+工具块；`tool_execution_*`→三态工具卡；`queue_update`→队列条；`entry_appended`→系统提示行；`session_info_changed/thinking_level_changed`→标题/胶囊；`compaction_*`→压缩卡；`auto_retry_*`/`summarization_retry_*`→重试横条；`bash_execution_update`→终端输出块；`extension_error`→吐司+诊断；`extension_ui_request`→两形态各画一套弹层（见 §5 弹层交接）。

### §3 斜杠命令
- 内置 23 + `/mcp /llama` + 资源命令，全部覆盖：`/settings`（终端 pi 风格列表 / UI 精简设置页）、`/model /thinking /scoped-models /login /logout /new /resume /name /session /tree /fork /clone /compact /import /copy /export /share /bug /trust /mcp /reload /hotkeys /changelog /debug /quit`，`/模板名`、`/skill:名`、扩展命令。彩蛋 `/arminsayshi /dementedelves` 不做（同意）。
  - **★B 调整**：`/quit` 在手机上=「停止引擎进程」，但我把它从 `/` 补全里**降级为设置›停止引擎**，避免误触杀掉正在生成的会话；终端形态保留命令但执行前确认。

### §4 内置工具
- `read/write/edit/bash` 默认开；`grep/find/ls` 默认关可开；`codemode/tool_search/mcp__*` 跟扩展。两形态渲染：终端=pi 三态背景 + `Ctrl+O` 展开；UI=过程行 + 状态点 + 时长，点开详情。`edit`→红绿 diff（优先 `details.patch`，否则按 `edits` 行级 diff，复用 `LineDiff`）。`powershell` 不适用（同意）。截断给 `full_output_path`，点开读全文。

### §5 会话
- 存储/格式/继续/新建/分叉/复制/树导航/树筛选/标签/会话名/删除/压缩/导出/分享/导入/临时会话/工作目录——逐项覆盖。树筛选(default/no-tools/user-only/labeled-only/all)→终端快捷键、UI 筛选胶囊；标签→`pi.setLabel` 桥接；临时会话→新建菜单。
  - **★B 调整**：回收站改「删除后 5 秒撤销」（pi 无常驻回收站）。

### §6 模型与服务商
- 42 内置服务商 + OAuth/订阅登录 + API key 优先级 + 自定义 `models.json` + 切换/循环/作用域/思考 7 档/默认模型/目录更新/llama/虚拟模型——全覆盖，详见 §6 本方案。

### §7 技能/扩展/模板/上下文/主题/MCP/包/信任/键位
- 全部给入口：`/skill:`、`/模板`、扩展弹层、上下文文件查看编辑、pi 主题 token、`/mcp`、`!pi install`/`/packages`、首开目录信任弹窗、键位表（设置›手势与快捷键只读）。

### §8 settings.json 全键
- 模型类键→设置首页；其余（显示/缓存/队列/编辑器/树/信任/工具/会话/压缩/全屏/终端/图片/markdown/网络/重试/shell/包/隐私）→**高级(pi 设置) 通用键值编辑器**（按分组折叠，读写 `settings.json`）。全屏/终端类键只影响终端形态渲染，在终端形态内生效，UI 形态标「不适用」。§8.1 环境变量由 App 启动注入，不做输入框。
  - **★B 调整**：pi-features.md 把非模型键塞进 UI 的「高级」二级页用通用键值编辑器——我同意，并补一条：**高级页按 `settings.json` 的真实 schema 生成**（开关/枚举/数字/字符串四种控件），而非自由文本，降低写坏配置的风险。

### §9 快捷键全表与触屏映射
- 外接键盘照 pi；触屏按 pi-features.md 映射（按键条 Esc/Tab/⇣/↑队列/^C/⌕/⤢/`/`/`@`/`!`）。UI 形态用按钮/手势。全部覆盖；`Ctrl+Z` 挂起不适用（同意）。

### §10 输入语法
- 生成中 Enter=引导、Alt+Enter=排队、Esc=中止并回填、`/命令`、`@文件`、图片、`!`/`!!`、双击 Esc、模板参数——全覆盖。`@文件`：RPC 的 prompt 不收 `@`，由共享层把引用展开进消息（路径或内容），两形态都弹工作目录模糊搜索。

### §11 TUI 元素与配色
- 页眉/消息区/思考块/工具块/bash 块/压缩块/技能消息/编辑器/补全/队列/状态行/页脚/选择器/登录对话框/首次设置/转录搜索/Mermaid——终端形态逐一还原；UI 形态给对应极简呈现。页脚统计=pi 的用量显示，终端照搬，UI 收进顶栏(模型+上下文环)+会话信息页。彩蛋不做。配色：把 pi `dark.json`/`light.json` **原样打包**（MIT，NOTICE 记一行），运行时换算 Compose Color；system=安卓深浅。

### §12 pi 没有的功能
- 见 §1 删除清单，与 pi-features.md §12 一致（吉祥物、汇率、文件浏览器、Git 页、自动起名、每轮统计卡删；HTML 导出保留）。

### §13 无缝切换
- 见本方案 §5，与 pi-features.md §13 一致并给出可执行测试。

> **结论：pi-features.md 全部条目在两形态均有落点，无遗漏。** 异议仅 4 处（★B），均为「更安全/更稳」的收敛，不减功能。

---

## 3. 终端形态设计（忠实还原 pi TUI）

**定位**：不是伪终端里另开 `pi`（那会变两进程两状态），而是**用 Compose 画出 pi TUI 的样子**，数据来自同一 `SessionController`。

- **渲染方式**：等宽字体（打包 JetBrains Mono / 思源等宽），按行渲染；消息区是一个 `LazyColumn`，每条目映射成 pi 组件的字符布局（页眉、用户块、助手 Markdown、思考块、工具块、bash 块、压缩块、状态行、页脚）。Markdown 用现有渲染器但套 pi 的 `md*` token 配色。
- **配色**：直接读打包的 `dark.json`/`light.json` 的 56 个 token（accent/border/muted/text/thinkingText/toolPending|Success|ErrorBg/toolTitle/toolOutput/toolDiffAdded|Removed|Context/syntax*/thinkingOff…Max/bashMode/userMessageBg…）。`system` 跟随安卓深浅。
- **输入编辑器**：带边框，**边框颜色=思考档位**（`thinkingOff…thinkingMax`）；输入以 `!` 开头→边框变 `bashMode` 色并提示「将运行命令」。多行。
- **按键条**（编辑器下方一行，替代物理键）：`Esc`(中止/回填) `Tab`(补全) `⏎`(发送) `⇣`(排队=Alt+Enter) `↑队列`(Alt+↑ 撤回队列) `^C`(清空) `⌕`(转录搜索) `⤢`(全屏编辑器) `/` `@` `!`。外接键盘时所有 pi 默认键照常生效。
- **补全**：编辑器上方浮层，最多 `autocompleteMaxVisible`(5) 条，pi 风格。
- **工具/思考块显示**：工具块三态背景色，默认折叠，点块或 `⌃O` 展开；思考块 `thinkingText` 斜体暗色，点或 `Ctrl+T` 折叠。
- **页脚**（照搬 pi）：左 `~/路径 (git 分支) • 会话名`；中 `↑入 ↓出 R缓存读 W缓存写 CH% $费用 (sub) · 上下文%/窗口`（超阈值变黄/红）；右 `(provider) 模型 • 思考档位`。**各段可点**→打开对应选择器（模型/思考/会话/上下文）。
- **快捷键→触屏映射**：按 §9 表；`Ctrl+P/Shift+Ctrl+P` 循环模型=页脚模型名左右滑；`Shift+Tab` 循环思考=点编辑器边框；`PgUp/Ctrl+Shift+↑↓/Ctrl+Shift+F` 滚动/跳转/搜索=手势+`⌕`+右侧滚动条；双击 Esc=打开树/分叉（遵循 `doubleEscapeAction`）。
- **选择器**：统一「pi 风格选择器」组件（搜索框+列表+底部提示键），供模型/思考/作用域/会话/树/分叉/设置/主题/登录/信任复用。
- **ASCII 线框（终端形态）**：
```
┌ pi · v1.5.0 · ~/proj (main) ─────────────── [UI] ┐   ← 页眉(可折叠) + 右上切换
│ › 用户消息                                        │
│ 助手 Markdown 正文……                              │
│ ▸ thinking 2.3s   (Ctrl+T 展开)                   │
│ ▸ edit  src/App.kt  +3 −1   (Ctrl+O)              │
│   + 新行   - 旧行                                  │
│ ◼ running…  12s                                   │
├───────────────────────────────────────────────────┤
│ 排队: 1) 先跑测试   2) 再提交                       │
│ ┌───────────────────────────────────────────────┐ │
│ │ 输入…（边框色=思考档位）                        │ │
│ └───────────────────────────────────────────────┘ │
│ [Esc][Tab][⏎][⇣][↑队列][^C][⌕][⤢][/][@][!]        │
├ ↑1.2k ↓3.4k R800 W0 CH62% $0.03 · 48%/200k ─────── │
│ (anthropic) sonnet • high                          │  ← 页脚各段可点
└───────────────────────────────────────────────────┘
```

---

## 4. UI 形态设计（极简好看的原生界面）

**定位**：极简、留白、无边框正文、无吉祥物。信息密度低于终端形态，功能一个不少（都在菜单/面板后）。

- **页面结构**：顶栏（返回/抽屉 · 会话标题 · 模型胶囊 · 上下文环 · 切换「终端」按钮 · 菜单）→ 消息流 → 输入区（`+` · 圆角输入框 · 思考胶囊 · 发送/停止键）。侧抽屉=会话列表+搜索+新建+导入。
- **配色字体**：极简浅/深/跟随系统三套，单一强调色，正文系统字体，代码/工具输出用等宽。动效原则：只用 150–200ms 的淡入+位移，流式文字不做逐字弹跳（省电、稳），切换形态做一次 120ms 交叉淡化。
- **关键组件**：
  - 用户消息=右侧浅色圆角块；助手=无框正文；思考=一行「思考了 Ns」可展开；工具=过程行(图标+「编辑 文件名 +3 −1」+时长)可展开看 diff/输出；压缩=卡片可展开摘要；bash=等宽输出块。
  - 顶栏**模型胶囊**点开底部面板（见 §6）；**上下文环**（圆环显示 %）点开→立即压缩/自动压缩开关；左右滑胶囊=循环模型。
  - 输入区 `+`→图片/相机/剪贴板/`@文件`/`!`终端模式/模板/技能；发送键长按=排队；生成中发送=「引导」，发送键变停止键；队列条在输入框上方，可撤回。
  - 底部面板统一组件（搜索+列表），复用共享层命令实现：模型、思考、作用域、会话、树、分叉、登录、MCP、扩展与包。
- **设置页**（精简）：首页只有「模型」；其余 pi 键在「高级(pi 设置)」里按 schema 生成控件；安卓相关（网络代理、后台保活、通知、关于/手势表/更新日志/诊断）各占一节。
- **ASCII 线框（UI 形态）**：
```
┌ ☰  项目名 · sonnet▾  ◔48%          [终端] ⋮ ┐   ← 顶栏(模型胶囊/上下文环/切换)
│                                             │
│                      用户消息  ▏            │
│  助手正文……                                 │
│  › 思考了 2.3s                              │
│  › 编辑 App.kt  +3 −1                       │
│                                             │
├─────────────────────────────────────────────┤
│ 排队: 先跑测试 ✕                             │
│ ⊕  [ 输入…              ] high▾   ➤          │   ← +附件 / 思考胶囊 / 发送
└─────────────────────────────────────────────┘
```

---

## 5. 两形态切换机制与共享层

- **共享层（只有一份）**：
  - `SessionController`：持有 `PiEngine` 句柄、`ChatReducer` 状态流、`get_state` 快照、队列、`SessionController` 作用于**同一进程**。
  - `DraftState`：输入文字、光标、已选图片/附件、`@`引用，一个 `StateFlow`，两形态读写同一份（落 `DraftStore` 防杀进程）。
  - **TUI 命令实现**：`/settings /login /logout /resume /tree /scoped-models /import /share /trust /mcp /reload /hotkeys /changelog /copy /name …` 在 Kotlin 实现一次（读写 `~/.pi/agent/` 或桥接扩展）。
  - `pigo-bridge` 扩展资产。
- **只换一层**：`ChatRoot` 内 `when(form){ Terminal -> PiTuiSurface(state, actions); Minimal -> MinimalSurface(state, actions) }`。两个 Surface 接收**同一** `state`/`actions`。切换=重组渲染层，引擎与 reducer 不动。
- **切换入口**：终端页脚右端 `[UI]`、UI 顶栏右端 `[终端]`，一点即切；另有本地命令 `/ui`、`/term`。上次形态写入 App 设置，启动恢复。
- **交接三件事**（否则会丢）：
  1. **滚动位置**：各形态自存，但都以 `entryId` 定位，切过去停在同一条附近。
  2. **弹层/扩展对话**：若有 `extension_ui_request`（阻塞类 select/confirm/input/editor）开着，把其 `id` 与载荷交给新形态继续显示并回包，否则扩展永久卡住。
  3. **生成中流式**：因为只换渲染，`message_update` 流继续写入同一 state，切过去无缝续播。
- **验收（可执行测试，写进 §7）**：仪器测试 `FormSwitchDuringStreamTest`：
  - 用 Fake 引擎发一段跨 50 个 delta 的流 → 在第 20 个 delta 时触发切换 → 断言：(a) `PiEngine` 实例/子进程 PID 不变、无 `new_session`/重启调用；(b) 流继续到第 50 个 delta，最终 `agent_settled`；(c) `DraftState` 文字+图片+`@`引用不变；(d) 预置一个 `extension_ui_request(id=X)` 开着，切换后仍可对 id=X 回 `select`。

---

## 6. 模型配置（唯一的设置项）

- **呈现**：设置首页=「模型」。两形态共用同一控制器，终端=pi 风格选择器，UI=底部面板。
- **全部服务商**：42 个内置（`ProviderSpecs`/`ProviderPresets`/`BuiltinCatalog`）分「已登录 / 未登录」两组；每个可 `/login`（OAuth 订阅或 API key，优先级 `--api-key > auth.json > models.json > env`）或 `/logout`。
- **自定义服务商**：按 `models.json` schema 的表单（`baseUrl`/`api` 枚举/`apiKey`/`headers`/`models[]`/`modelOverrides`/`compat`）；配好可「测试连接」「获取模型列表」（复用 `ProviderTester`/`ModelDiscovery`）。也允许 `!` 直接编辑文件。
- **会话内切模型**：模型胶囊/`/model`/`Ctrl+L`→`set_model`；左右滑/`Ctrl+P`→`cycle_model`（范围由 `enabledModels`）；`/scoped-models` 编「常用」并可给每模型固定思考档。
- **会话内切思考**：思考胶囊/`/thinking`→`set_thinking_level`（7 档，`xhigh/max` 按模型能力过滤，用 `get_available_thinking_levels`）；点胶囊/`Shift+Tab`→`cycle_thinking_level`。
- **默认**：选择器「设为默认」→`defaultProvider/defaultModel/defaultThinkingLevel`。
- **目录更新**：`!pi update --models` / 面板「刷新」。llama.cpp 走 `/llama` 扩展 UI。

---

## 7. 模块拆分与实现工作流（并行、文件范围不重叠）

**前置（scaffold 负责，一次性）——`r3/contract`**：在 `core/contract/**` 加 `ChatSurface`（`ChatSurfaceState` + `ChatSurfaceActions`，两形态共享的视图契约）与桥接记录类型；按需调整 `gradle/libs.versions.toml`、`settings.gradle.kts`、新模块 `build.gradle.kts`。**所有 r3 分支从含此前置的 main 切出。** 这是唯一允许改契约/构建文件的入口（沿用 DESIGN §6 规则）。

> 删除安全总原则：**每个现有模块在 r3 期间只归一个工作流**，删除只发生在本模块内；跨模块引用由「新形态改为经 `ChatSurface` 契约取数」消除，旧屏幕的导航入口由 `r3/switch` 在整合期统一摘除。顺序见下依赖列。

| 分支 | 文件范围（互不重叠） | 依赖 | 验收标准 |
|---|---|---|---|
| **r3/shared** | `feature/chat/src/**`（改造为 `SessionController`+共享逻辑，剥离 UI 渲染）、`feature/commands/src/**`（会话扫描/索引/草稿/技能模板/`@`展开/TUI 命令实现；**删 `AutoNamer`/`AutoTitle`**）、`core/catalog` 与 `core/store` 中登录/凭据读写复用（只读调用，不改签名）、`app/**/di/{ChatBindings,CommandsBindings}.kt`、新增 `app/**/di/ShellBindings.kt`、`core/runtime/src/main/assets/pigo/pigo-bridge.*`（桥接扩展） | r3/contract | 共享层单测：`@`引用展开、草稿读写/恢复、会话索引增量扫描、TUI 命令调度（settings/login/resume/tree/scoped-models/import/share/trust/reload）各一条；Fake 引擎下 `SessionController` 能驱动流式+队列+压缩；删 AutoNamer 后未命名会话以首条消息为标题（单测） |
| **r3/terminal** | 新模块 `feature/terminal/src/**`（pi TUI 渲染：页眉/消息区/三态工具块/思考块/bash 块/压缩块/编辑器+边框色/按键条/补全/页脚/pi 风格选择器/转录搜索）、打包 `feature/terminal/src/main/assets/theme/{dark,light}.json`、`app/**/di/TerminalBindings.kt` | r3/contract（渲染靠 `ChatSurface`，开发期用 Fake）；整合期需 r3/shared | Compose 测试：回放 1000 delta 慢帧 <5%；思考档位→边框色映射表；页脚各段点击打开对应选择器；主题 token 换算快照；外接键盘 pi 默认键全生效（键位映射单测） |
| **r3/minimal** | 新模块 `feature/minimalui/src/**`（极简：顶栏+模型胶囊+上下文环+消息流+输入区+`+`菜单+底部面板+队列条+切换按钮）、`app/**/di/MinimalBindings.kt` | r3/contract（Fake）；整合期需 r3/shared | Compose 测试：生成中发送键显示「引导」、长按=排队；`+`菜单覆盖 图片/相机/剪贴板/`@`/`!`/模板/技能；上下文环%渲染；**UI 覆盖矩阵测试**（见下「硬性要求 1」） |
| **r3/models** | `feature/models/src/**`、`feature/settings/src/**`（精简：首页只模型 + 高级键值编辑器 + 安卓节）、`core/store/src/**` 中模型/凭据/设置存储（**删汇率相关字段与读写**）、`app/**/di/{ModelsBindings,StoreBindings}.kt` | r3/contract | 单测：42 内置服务商分组、登录优先级、`models.json` 合并不丢条目、自定义服务商表单校验、作用域模型保存、思考档位按模型过滤、**高级页按 settings.json schema 生成控件**；删汇率后费用只显示美元+`(sub)`（单测） |
| **r3/prune** | `feature/files/src/**`（**删** 文件浏览器/预览/高亮/Git 页；**留** attach、export、output/Ansi、platform、share）、`feature/usage/src/**`（**删** 每轮统计卡/用量弹层页；**留并下沉** 压缩/diff/树/摘要逻辑给两形态）、`feature/sessions/src/**`（侧栏排序/只看已命名；回收站→5秒撤销）、`core/ui/src/**`（**删** dsh 视觉组件/吉祥物残留；留极简主题 + pi token 换算工具）、`docs/notices/*` 与 `THIRD_PARTY_NOTICES.md` 中吉祥物/被删项的说明（NOTICE 由 scaffold 合） | r3/contract | 删除后 `./gradlew testDebugUnitTest` 全绿；保留逻辑（`LineDiff`/`CompactionSummary`/`SessionTreeRows`）单测不回归；`checkLicenseHygiene` 通过；grep 全仓无吉祥物名称/标识/dsh-Go 标识 |
| **r3/switch**（整合，最后） | `app/**/nav/**`（`PiGoNavHost`/`PiGoDrawer`/`WiredPages`：装 `when(form)` 切换、摘除已删屏幕路由）、`app/**/di/BindingMode.kt`、`app/**/{AppGraph,PiGoApp,MainActivity}.kt`（接线）、`app/src/androidTest/**`、`docs/testing/r3-coverage.md`、`docs/testing/r3-acceptance.md` | r3/shared、terminal、minimal、models、prune 全部合入 | **硬性要求 2** 的 `FormSwitchDuringStreamTest`（见 §5）；端到端冒烟：两形态各走通 §2 一条龙；BindingMode 全为真实实现；真机验收记录 |

**两条硬性要求落到验收（可执行）**：
- **要求 1（UI 形态覆盖 pi-features.md 全部功能）**：产出 `docs/testing/r3-coverage.md` 覆盖矩阵——按 pi-features.md 的**每个条目编号/名称**列一行，填「UI 入口路径」。配套参数化 Compose 测试 `UiCoverageTest`：数据源=该矩阵，逐条断言对应入口 Composable 存在且可达（`onNodeWithTag(featureId).assertExists()`）。缺一条测试即红。
- **要求 2（生成中无缝切换）**：`FormSwitchDuringStreamTest`（§5 四断言）+ 真机手测脚本写进 `r3-acceptance.md`：发长回复→切换→观察不中断、引擎日志无重启、草稿与排队仍在。

**删除如何安全进行**：删除集中在 `r3/prune` 一个工作流内，且只删其所属模块的文件；别的工作流**不引用被删屏幕**（新形态只认 `ChatSurface` 契约）。导航层对被删屏幕的路由由 `r3/switch` 在最后统一摘除，因此 `r3/prune` 合入到 `r3/switch` 整合之间，App 暂时保留「空路由」不会编译失败（占位 Composable 返回空）。契约/构建/NOTICE 的改动只走 scaffold。

---

## 8. 复用的现有代码、风险与取舍

**可直接复用**：
- 全部引擎与协议（`core/engine`、`core/protocol`：rpc、分帧、归约、事件、`CompactionSummary`、`LineDiff`、`TurnStats`）——r3 不动。
- 运行环境与首启（`core/runtime`、`feature/onboarding`）。
- 模型/凭据/自定义服务商（`core/catalog`、`core/store`、`feature/models` 的逻辑），去掉汇率。
- 会话存储与扫描（`core/store` 会话仓、`feature/commands` 的 `SessionScanner`/`PiSessionIndex`/`DraftStore`/`SessionMeta`/`SkillTemplateManager`）。
- 压缩卡/diff/会话树/附件/导出/ANSI 的**逻辑**，渲染搬进两形态。
- 契约层 Fake（`core/contract/fake/*`）——两形态开发期不依赖真引擎。

**风险与取舍**：
1. **工作量翻倍**（两套渲染）。取舍：共享层吃掉全部状态与命令逻辑，形态层只剩纯渲染，把重复降到最低；先交付 UI 形态（用户日常用），终端形态随后。
2. **桥接扩展 `pigo-bridge`** 是补 RPC 缺口的关键，若扩展 API 版本变动会影响切分支/标签/热重载。取舍：这些功能都有「读写文件 + 必要时重启引擎」的退路，扩展只是「不重启」的优化路径。
3. **`@文件` 展开策略**（路径 vs 内容注入）可能与 pi 行为有细微差异。取舍：默认注入路径（和 pi 一致），大文件给摘要+路径。
4. **终端形态触屏还原度** 不可能 100% 等于物理终端。取舍：按键条 + 手势映射覆盖高频键，冷门键留给外接键盘。
5. **设置「只留模型」与 pi 键众多** 的矛盾。取舍：高级页按 schema 生成，既不丢功能又不喧宾夺主。
6. **删除与并行冲突**。取舍：单一删除工作流 + 模块独占 + 导航最后摘除（§7）。

**不做**：彩蛋、Windows powershell、`--api-key` 输入、常驻回收站、汇率、文件浏览器、Git 页、自动起名、每轮统计卡、吉祥物、dsh-Go 任何标识。
