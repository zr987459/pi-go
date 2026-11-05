# r5 OpenMinis 对照表

参照：OpenMinis（https://github.com/OpenMinis/OpenMinis，`src/android`，Kotlin / Compose，GPL-3.0）。
pi-go 只换「界面与交互」：引擎仍是 `pi --mode rpc`（SessionHost / PiActions / overlay 栈 / 草稿 / 阅读位置都不动），
所有入口仍经 `host.runAction(<动作 id>)`，动作表 `PiActionCatalog` 的每一项在新界面里都有入口（UiCoverageTest、TermCoverageTest 逐项点到）。

## 页面结构（导航）

| OpenMinis | pi-go r5 | 说明 |
|---|---|---|
| `SessionListScreen`（首页：⚙ 设置、标题、Terminal 图标菜单、日期分节的会话行、右下米色 FAB 新对话） | `CalmSessionList`（`calm:drawer`）整页 | 顶栏 ⚙→设置 › 模型；「终端」图标 + ⋯ 菜单（排序 / 只看已命名 / 显示路径 / 导入 / 终端）；搜索栏；今天 / 昨天 / 7 天内 / 30 天内 / 更早分节；44dp 圆形头像 + 标题 + 预览 + 时间；长按菜单（重命名 / 删除）、左滑删除 + 撤销条；FAB = `session.new`（长按：临时会话 / 在其他目录新建）。数据仍是 `host.sessions`（= pi `/resume`）。 |
| `ChatScreen`（← 返回、居中标题、⋯ 菜单） | `CalmSurface` 聊天页 | ← = `session.resume`（回会话列表）；标题点按 = `session.name`；右侧「终端」图标 = `form.toggle`；⋯ = 新对话 / 分享 / 分支树 / 复制会话 / 导出 / 搜索 / 展开工具 / 会话信息 / 打开终端（`menu:<id>`）。 |
| `TerminalScreen`（黑底、圆形 ✕、等宽标题、绿色 🖌、键盘附件条） | `TermSurface` 终端页（`host.form = TERM`） | 内容是 pi TUI 的 Compose 渲染（同一个 SessionHost）；✕ / 返回键 = 回聊天页；🖌 = 清空输入行；附件条 = 键盘开关、复制全部、Esc、Tab、⇣排队、↑队、^C、⌃O、⌕、⇞⇟、⤢、/、@、!、📎、⏎（`term:key:*`）。入口：聊天顶栏终端图标、⋯「打开终端」、会话列表终端图标 / 菜单、`/term`。 |
| 导航转场（推入 / 推出） | `FormHost` AnimatedContent：终端从右推入（280ms），会话列表从左滑入 | 只换渲染层，不发 RPC、不重启引擎、草稿 / overlay / 阅读位置不丢（FormToggleNoRpcTest、RealHostFormSwitchTest、UiCoverageTest、FormSwitchStreamingTest）。 |
| 设置（`SettingsSection` / `SectionDesign` / `SectionTextField` 分组卡片） | 设置 › 模型（r4 QuickModelSetupPage + 高级） | 逻辑不变（r4 模型配置 + Key 热加载）；外观换成分组卡片：服务商单选行（右侧 ✓）、卡片内无边框输入框、模型列表行、蓝字操作行、「高级设置 ›」。 |

## 主题与组件

| OpenMinis | pi-go r5 |
|---|---|
| `ui/theme/Theme.kt` MinisTheme（去饱和 iOS 蓝 #528AD2 / #6A94CE、iOS 分组底色、MinisShapes 12/12/20/24/28） | `core/ui/calm/CalmTheme.kt` CalmTheme / CalmLight / CalmDark |
| `ui/theme/ChatColors.kt` ChatPalette（userBubble、toolCapsuleBg、inputBg、sendButton、codeBlock*、inlineCode*、thinking、fabAccent…） | `CalmColors` 新增同名字段（userBlock、toolCapsuleBg、inputBg、sendButton、codeBlockBg…、fab、danger） |
| `ui/components/MinisMenu.kt`（20dp 圆角、缩放淡入、自动翻转） | `feature/chat/ui/calm/MinisMenu.kt`（MinisMenu / MinisMenuItem / MinisMenuDivider / MinisDialog） |
| `MinisAlertDialog` | `MinisDialog`（会话重命名） |
| `UserMessageBubble`（右侧、80% 宽、18dp、16.5sp、长按菜单 + 震动；排队 = 虚线框） | `CalmMessages.UserBlock` |
| `AssistantHeader` + `AssistantMessageView`（无气泡全宽） | `AssistantHeader`（✦ 渐变 + pi）+ `AssistantBlock`（animateContentSize） |
| `ThinkingBlock`（浅蓝卡片、转圈 / 结束图标、字数、点按展开） | `ThinkingRow`（`calm:thinking`） |
| `ToolCallPill`（36dp 胶囊、状态图标、跳动三点、扫光、等宽耗时） | `ProcessRow`（`tool:<key>`，点按就地展开输出） |
| `BouncingDots` / TypingIndicator | `BouncingDots`、等待首 token 时的 `calm:typing` |
| `InlineErrorBanner` | `InlineErrorBanner`（助手错误、ERROR 提示） |
| 输入卡片（20dp、手绘对称阴影、＋ / ／ 圆钮、ComposerSendButton / ComposerStopButton、swipe-up-to-send） | `CalmComposer`（`calm:composer`、`calm:plus`、`calm:commands` = ／、`persist:send`；生成中空输入 = 红色停止；上滑发送 / 空输入调历史） |
| 排队气泡 + 红色撤回 | `QueueBar`（`calm:queueBar`、`calm:dequeue` = `queue.dequeue`） |
| ChatSearchBar | `TranscriptSearch`（`transcript.search`） |
| StandardChatSheet（底部面板） | `CalmSheet`（28dp、拖动条、居中标题、圆形 ×、底部滑入；`overlay:<id>`） |
| TerminalScreen / KeyboardAccessoryBar / QuickCommandButton | `feature/term/TermChrome.kt`（TermTopBar、QuickButton）+ `TermSurface.KeyStrip` |

## pi 原生功能入口（不丢）

- 斜杠命令：聊天页输入 `/` 内联补全（`calm:slashPanel`）+ ／ 圆钮打开命令中心（中文 / 斜杠名 / 拼音首字母）；终端页照 pi TUI。
- 扩展面板 / 扩展对话框：overlay 栈两页共用，聊天页画 CalmSheet，终端页画 TermOverlay；切页不丢、对话框仍在栈顶。
- 会话恢复：会话列表（`/resume`）、新建 / 临时 / 其他目录、导入、重命名、删除 + 撤销、分支树、分叉、复制、导出、分享。
- 排队 / 引导：生成中发送键 = 引导，长按 = 排队发送 / 停止；排队气泡可撤回；终端 ⇣ / ↑队。
- 中止：发送键红色停止、长按菜单「停止生成」、终端 Esc / ^C。
- 模型 / 思考：模型胶囊（点按面板、左右滑切换、长按设默认）、思考胶囊（点按循环、长按档位）；配置与 Key 热加载沿用 r4。
- `!` / `!!` bash、`@` 文件引用、图片附件：输入框与 ＋ 菜单；终端页 `!` 键帽与 📎。
