# pi-go 功能差距清单与并行实现计划

> 基线：`main@f0a4a58`。对照对象：Pi-Web `agegr/pi-web`（0.14.2，MIT）、dsh-Go `ccch1mneyyy/dsh-Go`（AGPL-3.0，Flutter）、pi `@earendil-works/pi-coding-agent@1.5.0`（pi-go 锁定版本，RPC 命令以 1.5.0 的 `dist/modes/rpc/rpc-types.d.ts` 为准）。
> 写于 2026-11-03。只写文档，未改代码。
>
> 许可：用户自用、不商用，可直接拷贝或移植代码，每次移植在 `THIRD_PARTY_NOTICES.md` 记一行来源（见 §3.4 的汇总方式）。dsh-Go 的名称、标识和吉祥物（Deepy、鲸娘）**不在其开源授权内**，不得使用；用户 14:40 也已要求先不要吉祥物。REVIEW.md 里“从 dsh-Go 拷代码即 Important”这条与用户最新授权冲突，需所有者改 REVIEW.md 或在 PR 描述里注明“已获授权”。

---

## 0. 结论速览

- pi-go 现在是“一个聊天页 + 会话列表 + 设置页”。引擎协议层做得扎实，但 pi 1.5.0 RPC 已经提供的能力，大约只接了三分之一：`images`、`get_commands`、`fork`/`clone`/`get_tree`、`compact`、`bash`、`export_html`、完整 `get_session_stats` 都没有接。
- 模型侧是最大短板。pi 1.5.0 内置 **42 个服务商**，pi-go 只预置了 9 个，另有百炼、智谱、其他三个“自定义 OpenAI 兼容”入口。**OAuth 类登录（Claude Pro/Max、ChatGPT/Codex、GitHub Copilot、Kimi、xAI、OpenRouter、Meta、Radius）一个都没有**。会话内切模型只能从 `get_available_models` 里选，也就是只能选已经配了 Key 的。
- 想超过 Pi-Web，P0 要补七项：全服务商与 OAuth、会话内模型和思考强度、附件与图片、斜杠命令（含技能、提示模板）、文件浏览、用量与费用、手动压缩。P1 再补：会话树与分叉、终端和 `!` 命令、导出分享、通知、会话搜索与分组、自动命名、Git diff。
- 并行计划：先做一个只加新文件的前置 PR（W0：模块骨架与新契约），再开 4 条文件互不重叠的工作流，最后一步统一接线（W5）。W5 必须等 `ui/dsh-style` 合入 main 后才做。

---

## 1. pi-go 现状盘点（main@f0a4a58）

| 模块 | 已有能力 |
|---|---|
| `core/runtime` | PRoot + Ubuntu + Node + pi 1.5.0 安装（国内镜像、SHA256 校验、续传）；`exec`/`start`；只杀自己的进程树 |
| `core/protocol` + `core/engine` | JSONL 分帧、按 id 关联、`agent_settled`、自动重试横条、压缩提示、扩展 UI（4 种阻塞 + 5 种通知类）、崩溃自动恢复、前台服务与 WakeLock、未送达消息接回（PR #5） |
| `core/store` | Keystore 保存 Key；9 个内置预设 + 3 个自定义 OpenAI 兼容预设；写 `models.json`/`settings.json`；自定义端点“获取模型列表”（`ModelDiscovery`，移植自 Pi-Web）；“测试连接”（`ProviderTester`）；会话文件解析（只读头尾各 64 KB）；回收站 |
| `feature/chat` | 流式 Markdown、思考块、工具卡折叠、排队/插话芯片、停止撤回、失败重试、扩展对话框；顶栏模型/思考芯片（只列 `get_available_models`）、上下文百分比 |
| `feature/sessions` | 会话列表、打开、删除进回收站、恢复 |
| `feature/settings` | Key 配置、默认模型、后台保活与 ROM 引导、运行环境信息与修复 |
| `feature/onboarding` | 首启安装向导 |

明确没有：附件和图片、斜杠命令面板、技能和提示模板、文件浏览、终端、会话树与分叉、手动压缩、用量与费用页、导出分享、回合完成通知、会话搜索与分组、OAuth 登录、全服务商目录、主题与语言、平板布局。

---

## 2. 功能差距表

图例：✅ 有；◐ 部分；❌ 无；— 不适用。“pi RPC”一列写 1.5.0 的命令或事件名，“无”表示 RPC 不提供、需要宿主侧或容器内另行实现。

### 2.1 模型与凭据

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| M1 | **pi 内置全部服务商**可配（42 个：amazon-bedrock、ant-ling、anthropic、azure、baseten、cerebras、cloudflare-ai-gateway、cloudflare-workers-ai、deepseek、fireworks、github-copilot、google、google-vertex、groq、huggingface、kimi-coding、meta、minimax、minimax-cn、mistral、moonshotai、moonshotai-cn、nvidia、openai、openai-codex、opencode、opencode-go、openrouter、qwen-token-plan、qwen-token-plan-cn、qwen-token-plan-individual、radius、together、typesafe、vercel-ai-gateway、xai、xiaomi、xiaomi-token-plan-ams/cn/sgp、zai、zai-coding-cn） | ✅ | ◐（DSH 自家模型为主） | 无列举命令；`get_available_models` 只返回**已有凭据**的模型 | ◐ 9 个预设 | **P0** | 服务商表不要手写。在容器里用 node 读 `@earendil-works/pi-ai@1.5.0` 的模型目录（包导出 `./models`），导出 `{provider, envVar, models[{id,name,reasoning,contextWindow,cost,input}]}` 到 JSON，按 pi 版本缓存在宿主。环境变量名来自 pi `docs/providers.md` 的表；需要额外配置的（Bedrock、Vertex、Azure、Cloudflare）单独给表单字段（region、resource、account id）。仍按 DESIGN §3.10：Key 只放 Keystore 和进程环境 |
| M2 | **全模型可选**（未配 Key 的也能看到，选中时引导去配 Key） | ✅ | ◐ | `get_available_models`、`set_model` | ◐ 只列已配置的 | **P0** | 模型选择页数据 = M1 的目录 ∪ `get_available_models` ∪ `models.json` 自定义项。按服务商分组，支持搜索，显示上下文窗口、是否推理、是否看图、单价。未配凭据的灰显，点击跳到该服务商的 Key 或 OAuth 页 |
| M3 | **OAuth / 订阅登录**（anthropic、openai-codex/chatgpt、github-copilot、kimi-coding、xai、openrouter、meta、radius） | ✅（Models 面板登录） | ❌ | **无**（`/login` 只在交互 TUI 里）；CLI 有 `pi auth check --provider` | ❌ | **P0**（至少 Copilot、Codex、Claude） | 在容器里跑一个小 node 脚本，调用 pi-ai 的 `./oauth` 导出的登录流程：device-code 类（Copilot）回传 user code 和 URL；PKCE 回调类（Claude、Codex）在宿主用 Custom Tab 打开授权页，拦截 redirect 或让用户粘贴 code。凭据只能写 pi 的 `auth.json`（需要刷新），**和 DESIGN §3.10“Key 不落容器磁盘”冲突，需所有者拍板**。建议：仅 OAuth 凭据例外，`auth.json` 设 600 权限、设置页明示。用 `pi auth check --provider <id> --json` 显示登录状态。注意 1.5.0 的确切导出函数名，实现时要先读 `dist/oauth` 核对 |
| M4 | 会话内切模型 | ✅ | ✅ | `set_model`、`cycle_model` | ◐ 顶栏芯片，只能选已配置的 | **P0** | 顶栏“模型▾”打开 M2 的选择页（全屏底部弹层），选中即 `set_model`；生成中允许切换，下一轮生效，并提示。最近使用的 5 个置顶 |
| M5 | 会话内思考强度 | ✅ | ✅ | `get_available_thinking_levels`、`set_thinking_level`、`cycle_thinking_level` | ◐ 芯片菜单 | **P0** | 并入模型选择页底部的分段控件，按模型能力过滤（`reasoning=false` 时隐藏）。中文标签沿用 `thinkingLabel` |
| M6 | 自定义端点：获取模型列表、测试连接 | ✅ | ◐ | 无（宿主 HTTP） | ✅ | — | 已完成，保持 |
| M7 | 编辑 `models.json`（自定义模型的 contextWindow、cost、reasoning、input 图片）、多个自定义端点、Anthropic/Gemini 协议的自定义端点 | ✅ | ❌ | 无 | ◐ 只支持 openai-completions、只填 id | P1 | `PiProviderConfigStore.upsertCustom` 扩成完整的 provider/model 结构，`api` 支持 `openai-completions`/`openai-responses`/`anthropic-messages`/`google-generative-ai`。“获取模型列表”回填 contextWindow 与 reasoning |
| M8 | 队列模式、自动压缩、自动重试开关 | ✅ | ◐ | `set_steering_mode`、`set_follow_up_mode`、`set_auto_compaction`、`set_auto_retry` | ❌ | P1 | 放到“会话设置”页；同时写 `settings.json`，让下次启动也生效 |
| M9 | 代理设置（HTTP_PROXY） | ✅（环境变量） | ◐ | 无 | ❌ | P1 | 设置页填代理地址，engine 启动时注入 `HTTP(S)_PROXY`/`NO_PROXY`。国外服务商在国内基本离不开代理 |

### 2.2 对话与输入

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| C1 | **图片输入** | ✅ | ✅ | `prompt`/`steer`/`follow_up` 的 `images: [{type:"image",data,mimeType}]` | ❌ | **P0** | 输入框“+”：拍照、相册、粘贴。宿主压缩到长边 ≤1568、≤5 MB 后转 base64。只在 `model.input` 含 `image` 时可发，否则提示换模型。乐观气泡显示缩略图；`confirmUser` 匹配要加上图片签名（参考 Pi-Web `prompt-recovery.ts:39-47`） |
| C2 | **文件附件**（非图片） | ✅（上传到工作区） | ✅ | 无（pi 读工作区文件） | ❌ | **P0** | SAF 选文件，复制到 `<cwd>/.pigo/uploads/<日期>/<文件名>`，再在消息里插入 `@路径` 引用。大于 20 MB 时提示 |
| C3 | **斜杠命令面板**（扩展命令、技能 `/skill:name`、提示模板 `/name`） | ✅ | ✅ | `get_commands`；执行即 `prompt` 发 `/name args`（技能和模板由 pi 展开） | ❌ | **P0** | 输入框首字符为 `/` 时弹候选，按 `source` 分组（扩展、技能、模板），显示 description；选中补全为 `/name `。缓存按 cwd 失效（切会话、新建时刷新）。`disposition: handled` 已支持 |
| C4 | 技能与提示模板管理（列表、查看、启用/停用、新建模板） | ✅ | ◐（插件开关） | `get_commands` 只读；文件在 `~/.pi/agent/{skills,prompts}`、`<cwd>/.pi/...` | ❌ | P1 | 列表来自 `get_commands`（含 `sourceInfo.path`、`scope`）；查看和编辑走宿主直读容器文件；新建模板写 `~/.pi/agent/prompts/<name>.md`；改完需要重启引擎或新会话才生效，界面要提示 |
| C5 | 包管理（`pi install` 扩展/技能包） | ✅ | ◐ | 无（CLI `pi install/remove/list`） | ❌ | P2 | 经 `RuntimeManager.exec(["pi","install",…])`，带国内 npm 镜像 |
| C6 | `!` 命令 / 用户直接执行 shell（输出进上下文） | ✅ | ◐ | `bash`、`abort_bash`；事件 `bash_execution_update` | ❌ | P1 | 输入框以 `!` 开头走 `bash`（`!!` 走 `excludeFromContext:true`）；时间线渲染成终端卡片，流式输出，可停止 |
| C7 | 排队消息“立即发送” | ❌ | ✅ | 无原子命令（`clear_queue` 后重排有竞态） | ❌ | P2 | 维持 DESIGN 结论不做；dsh-Go 的做法要等 pi 侧支持 |
| C8 | 复制整条回复 / 最后一条回复 | ✅ | ✅ | `get_last_assistant_text` | ◐（长按选择） | P1 | 消息菜单“复制”“复制 Markdown”；通知栏完成通知带“复制结果” |
| C9 | 语音输入 | ❌ | ✅ | 无 | ❌ | P2 | 系统 `RecognizerIntent`，结果填进输入框 |
| C10 | 轮末汇总：本轮改动文件、耗时、输出速率 | ✅ | ◐ | 由事件推导 | ❌ | P1 | 移植 Pi-Web `turn-written-files.ts`、`turn-duration.ts`、`stream-token-rate.ts`；点文件跳到 F1 的预览或 diff |
| C11 | `edit` 工具 diff 视图 | ✅ | ✅ | `tool_execution_*` 的 args 与 result | ◐ 原文展示 | P1 | 解析 edit 的 `oldText`/`newText`（以 1.5.0 实际 args 为准），做行级 diff 着色 |
| C12 | 扩展 widget/status 的展示 | ✅ | ✅ | `extension_ui_request`：setStatus、setWidget | ◐ 只归类、不渲染 | P2 | 输入框上方的状态条和 widget 区 |

### 2.3 会话

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| S1 | **会话树 / 分叉**：从某条用户消息分出新会话 | ✅（New session from here） | ◐ | `get_fork_messages`、`fork{entryId}`、`clone` | ❌ | P1 | 消息长按“从这里分叉”调 `fork`，返回的 `text` 回填输入框；“复制会话”调 `clone`。树视图用 `get_tree` 渲染缩进树，并标出 `leafId` |
| S2 | 会话内分支（Edit from here，切换到旧分支） | ✅ | ❌ | **1.5.0 无** `navigate_tree` 类命令 | ❌ | P2 | RPC 不支持。用 S1 的 `fork` 代替，等上游加命令再做 |
| S3 | 会话搜索（全文） | ✅ | ✅ | 无（读文件） | ❌ | P1 | 宿主侧流式扫描 jsonl，名字 + 用户消息全文，增量索引（移植 Pi-Web `session-list-scanner.ts` 的 ResumePoint），同时修 G8“只读头尾 64 KB 导致名字读错” |
| S4 | 按项目（cwd）分组、置顶、归档、已读/未读 | ✅ | ◐（按日期分组） | 无 | ❌ | P1 | 置顶、归档状态存宿主 DataStore（不改 pi 文件）；分组用 header 里的 `cwd` |
| S5 | 自动命名 | ✅ | ✅ | `set_session_name`；生成标题要另调模型 | ❌ | P1 | 首轮 `agent_settled` 后，在后台用 `pi --print --no-session` 和 Pi-Web `session-title.ts` 的提示词生成标题，再 `set_session_name`；用户手动改过的不覆盖 |
| S6 | 长会话分页加载 | ✅ | ✅ | `get_entries{since}`（增量游标） | ◐ 一次性 `get_messages` | P2 | 先渲染最后 50 条，往上滚动再取 |
| S7 | 按会话保存草稿 | ✅ | ✅ | 无 | ❌ | P1 | `Map<sessionFile, draft>` 存 DataStore |

### 2.4 工作区、文件、终端

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| F1 | **文件/工作区浏览**（目录树、预览文本/Markdown/图片/代码高亮） | ✅（含 PDF、DOCX、音频） | ✅ | 无 | ❌ | **P0** | 宿主直接读 rootfs（`runtime.hostFile(guestPath)`），不经 pi。范围限定在会话 cwd 与 `/root`；大文件只读前 1 MB；本轮被改动的文件高亮。会话内顶栏“文件”入口 |
| F2 | 切换 / 新建工作目录（cwd） | ✅ | ✅ | `new_session` + 重启（cwd 决定会话目录） | ◐ 有参数、无界面 | **P0** | 在文件页选目录作为新会话的 cwd，复用 `newSession(cwd)` |
| F3 | 上传、下载、分享文件（宿主 ↔ 容器） | ✅（上传） | ✅ | 无 | ❌ | P1 | SAF 导入到 cwd；导出用 FileProvider 分享，或“保存到下载” |
| F4 | Git 状态与 diff | ✅ | ◐ | 无 | ❌ | P1 | `exec(["git","-C",cwd,"status","--porcelain=v2"])`、`git diff`；解析成文件列表与统一 diff 视图 |
| F5 | **终端 / 命令输出查看** | ◐（bash 实时输出） | ✅（内置终端） | `bash` 事件（见 C6）；工具卡 `partialResult` | ◐ 工具卡里的输出 | P1 | 两层：①工具卡与 `!` 命令的输出全屏查看（等宽、可搜索、可复制，ANSI 着色）；②v0.3 的交互式终端（Termux terminal-view，Apache-2.0）仍按 DESIGN 留到二期 |
| F6 | Git worktree 切换 | ✅ | ❌ | 无 | ❌ | P2 | `git worktree list`，切换即换 cwd |
| F7 | 挂载手机共享存储 | ❌ | ✅ | 无 | ❌ | P2 | DESIGN v0.2 已列，proot `-b` 绑定，需要存储权限 |

### 2.5 用量、上下文、压缩

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| U1 | **用量与费用**（本会话 tokens in/out/cache、费用、消息数、工具调用数） | ✅ | ✅ | `get_session_stats`（`tokens{input,output,cacheRead,cacheWrite,total}`、`cost`、`contextUsage`） | ◐ 只显示上下文 % | **P0** | 顶栏百分比点开“用量”底部弹层；每轮 `agent_settled` 后刷新。费用以美元显示，并给“约合人民币”（汇率可配置）。每条 assistant 消息的 `usage` 可在长按详情里看 |
| U2 | 上下文窗口占用条 | ✅ | ✅ | `contextUsage.percent`（压缩后为 null） | ◐（压缩后显示旧值，见 piweb-study G9） | **P0** | 环形进度显示 tokens/contextWindow；`percent == null` 时显示“压缩后待更新” |
| U3 | **手动压缩**（可带指示） | ✅ | ✅ | `compact{customInstructions?}`，响应含 `tokensBefore`/`estimatedTokensAfter`；事件 `compaction_start`/`compaction_end` | ◐ 只显示自动压缩的提示 | **P0** | 用量弹层里放“压缩上下文”按钮和可选输入框；时间线插入“已压缩：150k → 32k”卡片，可展开查看摘要 |
| U4 | 压缩详情（摘要、读/改过的文件） | ✅ | ◐ | 会话条目 `compactionSummary`；`get_entries` | ◐ 只有“已压缩上下文”一行 | P1 | 移植 Pi-Web `compaction-summary.ts:1-34` |
| U5 | 全局用量（跨会话按天、按模型汇总） | ◐ | ✅ | 无（扫会话文件里 assistant 的 `usage`） | ❌ | P2 | 复用 S3 的增量扫描顺带累计 |

### 2.6 导出、分享、通知、其他

| # | 功能 | Pi-Web | dsh-Go | pi RPC | pi-go 现状 | 优先级 | 实现要点 |
|---|---|---|---|---|---|---|---|
| E1 | 导出 HTML | ✅ | ✅ | `export_html{outputPath?}` | ❌ | P1 | 导出到容器临时目录，宿主读出后走系统分享或保存 |
| E2 | 导出 Markdown / JSONL 原文件 | ✅ | ✅ | 无（读文件） | ❌ | P1 | 由 `get_messages` 渲染 Markdown；JSONL 直接复制会话文件 |
| E3 | 单条回复做分享卡（图片） | ❌ | ✅ | 无 | ❌ | P2 | Compose 截图成 PNG 分享，不做 dsh-Go 的加密链接（需要服务器） |
| N1 | **回合完成 / 出错通知**（App 在后台时） | ✅ | ✅ | `agent_settled` + 最后一条 `stopReason` | ◐ 只有扩展对话框通知 | P1 | DEFAULT 级“任务通知”通道；完成、出错各一条，`aborted` 不发；点击回到对应会话；Android 13+ 申请通知权限（piweb-study G7） |
| N2 | 阶段提示行（等待模型 / 正在运行 bash：最后一行） | ✅ | ✅ | 由事件推导 | ✅ PhaseLabel | — | 已有 |
| X1 | 主题、深色、跟随壁纸 | ✅ | ✅ | — | ◐ | 界面改版负责 | 不在功能工作流内 |
| X2 | 英文界面 | ✅ | ✅ | — | ❌ | P2 | strings.xml 抽取；界面改版合入后再做 |
| X3 | 平板 / 横屏双栏 | ✅ | ✅ | — | ❌ | P2 | 界面改版负责 |
| X4 | 远程模式（RPC over SSH 连电脑上的 pi） | ❌ | ✅ | 同一套 RPC | ❌ | P2 | `GuestProcess` 再加一个 SSH 实现（DESIGN v0.5） |
| X5 | 网页模式（内嵌 Pi-Web） | —（本身就是） | ✅ | — | ❌ | P2 | 不建议做，与 DESIGN“不开端口”冲突 |
| X6 | 手机操控（无障碍） | ❌ | ✅ | 扩展 | ❌ | P2 | DESIGN v0.3 `:core:bridge` |
| X7 | MCP 服务器管理 | ✅ | ❌ | 无（1.5.0 自带 `pi-mcp` 扩展，配置文件驱动） | ❌ | P2 | 设置页编辑 MCP 配置；先确认 1.5.0 的配置文件位置 |

### 2.7 超过 Pi-Web 的落点（移动端独有）

Pi-Web 做不到、pi-go 可以做：后台常驻与保活（已有）、系统分享菜单“发给 pi”（Share Target 接收文本、图片、文件，直接作为附件开新会话，**P1**，并入 W3）、拍照直发（C1）、语音输入（C9）、通知栏“完成”直接复制结果（N1+C8）、离线也能浏览历史与文件（F1 宿主直读）。

---

## 3. 并行实现计划

### 3.1 文件所有权的硬约束

- **界面改版（`ui/dsh-style`、`ui/dsh-panels`）合入 main 之前**，界面工作者独占：`core/ui/**`、`feature/chat/src/main/**/ui/**`、`feature/sessions/**`、`feature/settings/**`、`feature/onboarding/**`、`app/src/main/java/**/nav/**`。界面分支还会改 `THIRD_PARTY_NOTICES.md` 和 `docs/status/**`、`docs/ref/**`、`docs/ui-shots/**`。
- 功能工作流**一律不碰上面这些文件**。新界面放在新模块；需要在旧界面里加按钮或回调的地方，统一留到 W5 接线。
- DESIGN §6 规定：`settings.gradle.kts`、`gradle/**`、根和各模块的 `build.gradle.kts`、`core/contract/**` 已有签名、`THIRD_PARTY_NOTICES.md` 都归 scaffold 维护者。下面的 W0 就是这个角色的一次性前置 PR。
- `.github/` 任何工作流都不碰。

### 3.2 工作流总表

依赖关系：

```
W0（前置，只加新文件）──┬─> W1 feat/engine-rpc ───────────┐
                        ├─> W2 feat/models ────────────────┤
                        ├─> W3 feat/files ─────────────────┼─> W5 feat/wiring（等 ui/dsh-style 合入 main）
                        └─> W4 feat/commands ──────────────┘
```

W1–W4 都从“合入 W0 之后的 main”切出，互相不等待。凡是需要 W1 新 RPC 能力的，开发期都用 W0 提供的 Fake，W5 时再切到真实实现。

#### W0 前置：`feat/modules`（scaffold 维护者，预计半天，必须最先合入）

- 内容：
  1. `settings.gradle.kts` 加入 `:core:catalog`、`:core:workspace`、`:feature:models`、`:feature:files`、`:feature:commands`、`:feature:usage`；为每个新模块写好 `build.gradle.kts` 和空的 `Placeholder.kt`。
  2. `gradle/libs.versions.toml` 加入已知要用的依赖：Coil（图片缩略图）、`androidx.browser`（Custom Tab，OAuth 用）、`androidx.exifinterface`。
  3. `core/contract/**` **只新增文件，不改已有签名**（下面是规划时的草案，方法名以合入后的 `EngineExtras.kt`、`Catalog.kt`、`Workspace.kt` 为准）：
     - `contract/EngineExtras.kt`：`interface EngineExtras { sendWithImages(text, images: List<ImageInput>, mode); commands(): Outcome<List<SlashCommand>>; forkMessages(); fork(entryId); clone(); tree(): Outcome<SessionTree>; compact(instructions: String?): Outcome<CompactResult>; setAutoCompaction(Boolean); setAutoRetry(Boolean); setSteeringMode(String); setFollowUpMode(String); bash(command, excludeFromContext): Flow<BashChunk>; abortBash(); exportHtml(): Outcome<String>; lastAssistantText(): Outcome<String?>; fullStats(): Outcome<UsageStats>; val runEvents: SharedFlow<RunOutcome> }`，以及上面用到的数据类。
     - `contract/Catalog.kt`：`ProviderInfo`、`CatalogModel`、`AuthKind { API_KEY, OAUTH_DEVICE, OAUTH_PKCE, CLOUD_ENV }`、`interface ModelCatalog`、`interface AuthManager`（Key 读写、OAuth 开始、轮询、登出、状态）。
     - `contract/Workspace.kt`：`FileNode`、`interface WorkspaceFs`（list、read、stat、importFromUri、exportToUri、gitStatus、gitDiff）。
     - `contract/fake/Fake*.kt`：上述接口各一个 Fake，带夹具。
  4. `docs/notices/` 目录与说明：各工作流把移植来源写到 `docs/notices/<分支名>.md`，W5 时由 scaffold 维护者统一并入 `THIRD_PARTY_NOTICES.md`（避免和界面分支抢这个文件）。
- 验收：`./gradlew assembleDebug testDebugUnitTest` 通过；新模块都能编译；Fake 各有一条单测；`git diff main --stat` 只有新增文件，外加 `settings.gradle.kts` 与 `libs.versions.toml` 两处修改。

#### W1 `feat/engine-rpc`：引擎接上 pi 1.5.0 的全部 RPC

- 功能：C1（引擎侧 `images`）、C3（`get_commands`）、C6（`bash`/`abort_bash`/`bash_execution_update`）、C8（`get_last_assistant_text`）、S1（`get_fork_messages`/`fork`/`clone`/`get_tree`）、S6（`get_entries` 游标）、U1 和 U2（完整 `get_session_stats`，压缩后置空）、U3（`compact` 及事件归约，时间线压缩卡片数据）、M8（四个模式开关）、E1（`export_html`）、N1（任务通知通道与 `RunOutcome` 事件：完成、出错、需要回答）、C10 的数据部分（本轮改动文件、耗时、速率，作为 `ChatState` 的派生字段）。
- 允许改：`core/protocol/src/**`、`core/engine/src/**`（含 `EngineService`、manifest、测试与夹具）、`core/contract/src/main/**/rpc/RpcCommand.kt` 与 `rpc/RpcRecords.kt`（**只追加新的 data class 和事件类型**，W0 之后由 W1 独占）（压缩卡、bash 卡、用户图片、轮末汇总一律通过 W0 契约里的 `EngineExtras.timelineExtras`（`TimelineExtra`）输出，**不新增 `ChatItem` 子类**：`feature/chat` 对 sealed `ChatItem` 的穷尽 `when` 会因此编译失败）、`app/src/main/java/**/di/EngineBindings.kt`、`docs/protocol/**`、`docs/notices/feat-engine-rpc.md`。
- 不许改：`EngineClient` 已有方法签名（新能力都走 `EngineExtras`）。
- 验收（可测）：
  - JVM 单测：每个新命令的 JSON 与 1.5.0 `rpc-types.d.ts` 字段一致（序列化快照）；`images` 往返；`bash_execution_update` 按 id 归到对应 bash 卡；`compact` 响应和事件归约出一张压缩卡（含 tokensBefore/After）；`contextUsage.percent == null` 时 `contextPercent == null`。
  - 桌面真 pi 1.5.0 集成测试（沿用 `realpi/`）：`get_commands` 能列出放在 `~/.pi/agent/prompts/x.md` 的模板；`fork` 后 `get_state.sessionFile` 改变且 `text` 正确；`get_tree` 至少两层；`compact` 成功；`bash("echo hi")` 输出 `hi`、exitCode 0；`export_html` 生成的文件存在；带一张 1×1 PNG 的 prompt 能被 mock 服务器收到 `image_url`（扩展 `MockOpenAiServer`）。
  - 通知：App 在后台时 `agent_settled` 发一条完成通知，`aborted` 不发（Robolectric 或仪器测试）。

#### W2 `feat/models`：全服务商、全模型、OAuth、会话内模型选择页

- 功能：M1、M2、M3、M4 和 M5 的新页面（模型选择底部弹层，在新模块里）、M7、M9，以及服务商与 Key/OAuth 配置的新页面（不改旧设置页，旧设置页的入口在 W5 接线）。
- 允许改：新模块 `core/catalog/src/**`（目录导出脚本、缓存、`ModelCatalog` 与 `AuthManager` 实现、OAuth 流程）、新模块 `feature/models/src/**`（模型选择页、服务商列表页、Key 与 OAuth 登录页）、`core/store/src/**`（`ProviderPresets` 改成由目录生成；`PiProviderConfigStore` 扩展 `models.json` 写法；`LaunchEnvProvider` 注入全部服务商的 Key 与代理变量）、新文件 `app/src/main/java/**/di/ModelsBindings.kt`、`docs/notices/feat-models.md`。
- 与他人的边界：`core/store/src/**` 在 W1–W4 期间**只归 W2**；W4 的会话列表数据增强（S3、S4、S5、S7）不放进 `core/store`，而是放在 W4 的新模块里（见 W4）。
- 验收：
  - 单测：目录 JSON 解析出 42 个服务商、模型数大于 0、每个模型都有 `id` 与 `provider`；`LaunchEnvProvider` 对 Bedrock、Azure 等多变量服务商一次注入全部变量；`models.json` 合并不丢用户已有条目（沿用现有测试并扩充）；Key 与 OAuth token 不出现在日志里（脱敏断言）。
  - 集成（桌面真 pi）：目录导出脚本在 1.5.0 容器里运行并输出 JSON；配了 DeepSeek Key 后 `get_available_models` 返回的模型都出现在选择页的“可用”组。
  - OAuth：GitHub Copilot device-code 流程用 mock 服务器跑通，最后 `pi auth check --provider github-copilot --json` 显示已登录；PKCE 流程至少有单测，覆盖 state 校验和 code 交换请求的构造。
  - 界面（Compose 测试，独立运行新页面）：搜索“qwen”只剩 qwen 相关；未配凭据的模型点击后进入该服务商的配置页；思考强度控件对 `reasoning=false` 的模型隐藏。

#### W3 `feat/files`：工作区、文件、附件选择、终端输出查看、导出分享

- 功能：F1、F2、F3、F4、F5 的第一层（全屏输出查看器）、C1 和 C2 的选择器与预处理（相册、拍照、SAF、图片压缩、导入到 cwd，产出 `ImageInput` 或 `@路径`）、E2（Markdown/JSONL 导出）、E1 的宿主侧分享、§2.7 的系统分享入口（Share Target Activity 放在 `feature/files` 自己的 manifest 里）。
- 允许改：新模块 `core/workspace/src/**`（`WorkspaceFs` 实现：rootfs 路径映射与越界检查、git exec 与解析、大文件截断）、新模块 `feature/files/src/**`（文件树、预览、diff、附件选择底部弹层、输出查看器、分享、Share Target）、新文件 `app/src/main/java/**/di/FilesBindings.kt`、`docs/notices/feat-files.md`。
- 只读依赖：`core/runtime` 已有的 `hostFile`、`exec`（不改 `core/runtime/src/**`；若必须改，单独开一个只改 runtime 的小 PR 并在本表登记）。
- 验收：
  - 单测：路径越界（`../`、符号链接指出 rootfs）被拒绝；`git status --porcelain=v2` 与统一 diff 的解析夹具；图片压缩后长边 ≤1568、体积 ≤5 MB、EXIF 方向已校正；导入文件重名时自动加序号。
  - 仪器测试：从 SAF 导入一个文本文件后，它出现在文件树里，预览内容一致；Share Target 收到图片后产出一个 `ImageInput`。
  - 界面（独立运行新页面）：1 万行日志在输出查看器里滚动不卡（慢帧 < 5%），搜索能定位。

#### W4 `feat/commands`：斜杠命令、技能与模板、会话树与分叉、用量与压缩页、会话数据增强、聊天逻辑接入

- 功能：C3 的候选面板、C4、S1 的树视图与“从这里分叉”流程、U1/U2/U3 的用量弹层与压缩操作、U4、C10 的轮末汇总展示组件、C11 的 diff 组件、S3（全文搜索与增量扫描索引）、S4（置顶、归档、分组的数据层）、S5（自动命名）、S7（按会话草稿），以及 `ChatController` 对附件、`/` 命令、`!` 命令、草稿的逻辑支持。
- 允许改：新模块 `feature/commands/src/**`（命令面板、技能与模板管理页）、新模块 `feature/usage/src/**`（用量弹层、压缩卡展开页、会话树页）、新文件 `feature/commands/src/main/**/data/SessionIndex.kt` 等会话数据增强（放在本工作流的新模块里，不碰 `core/store`）、`feature/chat/src/main/**/ChatController.kt` 与 `feature/chat/src/main/**/logic/**`（**非 ui 目录**，界面工作者不碰）、`feature/chat/src/test/**`、新文件 `app/src/main/java/**/di/CommandsBindings.kt`、`docs/notices/feat-commands.md`。
- 不许改：`feature/chat/src/main/**/ui/**`、`ChatEntry.kt`（如它属于界面层，接线放 W5）。
- 依赖：用 W0 的 `FakeEngineExtras` 开发；W1 合入后，集成测试改用真实实现。
- 验收：
  - `ChatController` 单测：草稿以 `/` 开头时给出按前缀过滤的候选；`!ls` 走 `bash` 而不是 `prompt`；带图片的发送调用 `sendWithImages`，失败后重试保留图片；切会话时草稿换出、换入。
  - 会话索引单测：200 KB 会话在 100 KB 处改名，列表显示新名；追加 10 KB 后只读新增部分；全文搜索命中第 3 条用户消息。
  - 用量页（Compose 测试）：用 Fake 的 stats 显示 tokens、费用、上下文条；点“压缩”调用 `compact`，带指示时把指示一并传入。
  - 会话树：Fake 树（3 个分支）渲染出 3 个叶子，点“从这里分叉”调用 `fork(entryId)`，并把返回的 `text` 写回草稿。

#### W5 `feat/wiring`：接线（`ui/dsh-style` 合入 main 之后才开始）

- 内容：
  - 顶栏“模型▾”`onModelClick` → W2 的模型选择弹层；思考强度芯片并入同一个弹层。
  - 输入框“+”`onAttachClick` → W3 的附件弹层；`/` 候选面板（W4）挂到输入框上方。
  - 顶栏上下文百分比 → W4 的用量弹层；时间线按 `anchorKey` 渲染 `TimelineExtra`（压缩卡、bash 卡、用户图片、轮末汇总）。
  - 消息长按菜单：复制、复制 Markdown、从这里分叉。
  - 抽屉入口：文件、会话树、技能与模板、用量；会话列表接入 W4 的搜索、分组、置顶。
  - 设置页入口：服务商与登录（W2）、代理、会话默认设置（M8）、导出。
  - `app/nav` 增加新路由；`AppGraph.kt` 装配新的 `*Bindings`；`BindingMode` 切到真实实现。
  - 把 `docs/notices/*.md` 合并进 `THIRD_PARTY_NOTICES.md`。
- 允许改：`feature/chat/src/main/**/ui/**`、`feature/chat/.../ChatEntry.kt`、`feature/sessions/src/**`、`feature/settings/src/**`、`app/src/main/java/**`（nav、`AppGraph.kt`、`di/BindingMode.kt`）、`app/src/androidTest/**`、`THIRD_PARTY_NOTICES.md`、`docs/testing/**`。
- 验收：
  - 仪器冒烟：①顶栏选一个未配 Key 的模型 → 跳到配置页 → 填 Key → 回到聊天自动选中该模型；②“+”选一张图发给支持看图的模型，时间线显示缩略图；③输入 `/` 出现至少一个技能或模板；④“从这里分叉”后会话列表多一条，原会话不变；⑤用量弹层的“压缩”跑完后时间线出现压缩卡；⑥文件页打开 pi 刚写的文件，内容一致。
  - `docs/testing/` 下补一份真机验收记录。
  - CI 绿；`THIRD_PARTY_NOTICES.md` 中每一项移植来源都有对应行。

### 3.3 共享文件的归属（W1–W4 并行期间）

| 文件或目录 | 归属 | 其他工作流的做法 |
|---|---|---|
| `settings.gradle.kts`、`gradle/**`、所有 `build.gradle.kts` | W0（之后回到 scaffold 维护者） | 新依赖写在 PR 描述里，由维护者合入 main，各分支 rebase |
| `core/contract/**` 已有文件 | 冻结 | 接口问题写到 PR 描述，W5 之后统一处理 |
| `core/contract/**/rpc/RpcCommand.kt`、`rpc/RpcRecords.kt` | W1（只追加） | 其他工作流不碰 |
| `core/contract/**/Chat.kt` | 冻结（不加 `ChatItem` 子类） | 新时间线内容走 `TimelineExtra`，W4 的渲染组件放自己的模块 |
| `core/contract/**/EngineExtras.kt`、`Catalog.kt`、`Workspace.kt`、`fake/**` | W0 定稿后冻结 | 发现缺方法时：在自己的模块里加扩展函数或适配层，PR 里提出，W5 统一合并 |
| `core/protocol/**`、`core/engine/**` | W1 | — |
| `core/store/**` | W2 | W4 的会话索引放在自己的新模块里 |
| `core/runtime/**` | 无人（冻结） | 确需修改时单独开小 PR |
| `feature/chat/**/ChatController.kt`、`logic/**` | W4 | W1 的新事件经 `ChatState` 和 `EngineExtras` 暴露，不直接改 Controller |
| `feature/chat/**/ui/**`、`feature/sessions/**`、`feature/settings/**`、`feature/onboarding/**`、`core/ui/**`、`app/nav` | 界面工作者 → W5 | 功能工作流全部不碰 |
| `app/**/di/*Bindings.kt` | 各自新增的文件各归各的；已有的 `EngineBindings.kt` 归 W1 | `AppGraph.kt` 的装配留给 W5 |
| `THIRD_PARTY_NOTICES.md` | 界面分支和 W5 | 功能工作流写 `docs/notices/<分支>.md` |

### 3.4 其他约定

- 每条工作流的 PR 描述里列出移植来源，格式同 piweb-study：`- <上游>@<提交>，<许可证> —— <pi-go 文件> ← <上游文件:行号>`。
- 所有新命令都按 1.5.0 的 `rpc-types.d.ts` 核对字段名。pi-mono main 的文档可能比 1.5.0 新，以锁定版本为准。
- 需要所有者拍板的决策（实现前确认）：
  1. OAuth 凭据是否允许写进容器内的 `auth.json`（M3，涉及 DESIGN §3.10）。
  2. REVIEW.md 中 dsh-Go（AGPL）一条是否按最新授权修改。
  3. 费用换算人民币用固定汇率还是可配置汇率。

**已拍板（2026-11-03）**：①允许 OAuth 凭据写入容器内 `auth.json`（权限 600、排除备份/导出/分享、日志脱敏），见 DESIGN §3.10；②REVIEW.md 已改为允许移植 dsh-Go 代码、只要求记来源，名称/标识/吉祥物仍不用；③费用默认显示美元，可选显示人民币，汇率在设置里可配置（默认 7.1），见契约 `CostDisplaySettings`。
- 审查：沿用 REVIEW.md，每条工作流最多审 3 轮。

---

## 附：工作流一行版

- W0 `feat/modules`：新模块骨架、新契约（`EngineExtras`/`Catalog`/`Workspace`）与 Fake、依赖、`docs/notices/`；只加新文件。
- W1 `feat/engine-rpc`：images、get_commands、bash、fork/clone/get_tree/get_entries、compact 与自动开关、完整 stats、export_html、完成通知；改 `core/protocol`、`core/engine`、`rpc/*`（只追加）、`EngineBindings`。
- W2 `feat/models`：42 个服务商目录、全模型选择页、OAuth 登录、会话内切模型和思考强度、`models.json` 完整编辑、代理；改 `core/catalog`、`feature/models`、`core/store`、`ModelsBindings`。
- W3 `feat/files`：文件浏览与预览、cwd 切换、导入导出、Git 状态与 diff、附件选择与图片压缩、输出查看器、导出分享、Share Target；改 `core/workspace`、`feature/files`、`FilesBindings`。
- W4 `feat/commands`：斜杠命令面板、技能与模板管理、会话树与分叉、用量与压缩页、会话搜索/分组/自动命名/草稿、`ChatController` 逻辑；改 `feature/commands`、`feature/usage`、`feature/chat` 非 ui 部分、`CommandsBindings`。
- W5 `feat/wiring`：`ui/dsh-style` 合入后，在顶栏、输入框、抽屉、设置、导航里接线，汇总 NOTICES，跑真机验收。
