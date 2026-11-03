# ui/dsh-style 状态（工作者 A：主题 + 聊天主页）

## 进度
- [x] 14:40 已开始
- [x] 阶段 1：主题（`theme:` 提交）——**B 现在可以用了**，API 见下
- [x] 阶段 2：聊天主页（渐变背景、顶部胶囊、空会话问候 + 启动引擎、底部输入框）
- [x] 阶段 3：消息气泡、工具行、Markdown/代码块、提示与动画、抽屉容器
- [x] 阶段 4：@Preview（浅/深）、差距说明、CI 绿
- [x] 收尾（2026-11-03 16:00 CST）：**panels 已合入 ui/dsh-style @3714a2b737098a1fe1e828fd101759b3b8d73285**（普通 merge，含 ui/dsh-panels @78b25f7）；抽屉接入 B 的 `SessionsScreen` @4640e2ee93dc62a087d1f2149210e7967cc534b4，CI 绿；已开 PR 到 main

调度方 14:40 追加要求已执行：**不用吉祥物**（没有入库任何吉祥物资源）；排版尽量一比一照 dsh-Go；顶栏「选择模型▾」/ 输入框「+」留 `onModelClick` / `onAttachClick` 回调。
说明：dsh-Go 的界面是 **Flutter（Dart）**，没有 Compose 代码可直接拷贝，这里按它的组件树、尺寸、配色、动效参数逐项翻译成 Compose。

## 给 B 的主题 API（`io.github.zr987459.pigo.core.ui`，依赖 `:core:ui` 即可）

页面骨架：
```kotlin
PiGoTheme { ... }                       // MainActivity 已包；Preview 里用 PiGoTheme(darkTheme = true/false)
AppBackdrop { /* 页面内容 */ }           // 渐变 + 光晕背景。页面根部用它；Scaffold 请设 containerColor = Color.Transparent
```

颜色：
- M3：`MaterialTheme.colorScheme.*`（primary = 黛青 #0F5E7E；深色 #8CB9D7；surface 系列为雾蓝/深海石板）
- 扩展：`DshTheme.colors.*` —— `backdropTop/Mid/Bottom`、`userBubble/onUserBubble`、`chrome/sheet/card/pill`（`GlassStyle`：fill、hairline、shadow、elevation）、`textFaint`、`textGhost`、`codeWell*`、`success/warning/danger/idle`、`scrim`；`DshTheme.isDark`

形状 / 间距 / 动效 / 字体：
- `AppShape.Xs(6) Sm(10) Md(14) Lg(20) Xl(28) Pill Sheet`；M3 组件已自动套用（`MaterialTheme.shapes`）
- `AppSpace.Xs..Xxxl`、`Gutter`(16)、`FloatInset`(12)、`ReadingWidth`(720)
- `AppMotion.Quick/Standard/Emphasized/SheetIn…`（毫秒）、`Glide/Enter/Exit/SheetRise`（Easing）、`PressScale`
- `MaterialTheme.typography.*`（dsh-Go 字号阶梯）、`MonoStyle`/`AppType.mono()`（JetBrains Mono）、`AppType.Greeting`、`AppType.Eyebrow`、`AppType.Wordmark`

组件：
- `GlassSurface(tier = GlassTier.Chrome|Sheet|Card|Pill, shape, onClick)`：玻璃面（带按压缩放）
- `GlassCard(onClick?) { ... }`：设置分组卡、列表卡
- `ChromePill(onClick) { ... }`、`ChromeIconButton(glyph, contentDescription, onClick)`：白色悬浮胶囊 / 圆按钮（48dp）
- `PrimaryPillButton(text, onClick)`（深青实心，例如「启动引擎」「开始安装」）、`TonalPillButton(text, onClick)`
- `StatusDot(tone)`、`StatusPill(tone, label)`，`StatusTone.Ok|Busy|Warn|Error|Idle`
- `SectionHeader("分组标题")`
- `AppIcon(AppIcons.xxx, contentDescription, size, tint)`：dsh-Go 图标字体（Phosphor）。角色名与 dsh-Go `AppIcons` 一致，例如 `menu`、`newChat`、`add`、`send`、`stopGenerating`、`voice`、`close`、`back`、`settings`、`copy`、`delete`、`search`、`expand`、`disclosure`、`model`；全部字形在 `DshGlyphs`
- 旧 API 仍在：`PlaceholderScreen`、`InfoCard`（换成玻璃卡外观）、`StatusChip`（@Deprecated，转 `StatusPill`）、`PiGoTypography`、`MonoStyle`

注意：`PiGoTheme(dynamicColor)` 默认改为 **false**（与 dsh-Go 一致）。

## 改了什么（逐界面；没有截图，`@Preview` 在各文件里，浅色 + 深色各一）

**主题（core/ui）**：dsh-Go 晨雾/深海石板配色、字号阶梯、圆角、渐变 + 光晕背景（`AppBackdrop`）、玻璃面、胶囊按钮、状态点、图标字体（Phosphor，337 个角色名与 dsh-Go 一致）。Preview：`ThemePreviews.kt`。

**聊天主页（feature/chat/ui/ChatScreen.kt）**：整页铺 `AppBackdrop`；顶栏和输入条悬浮，消息列表从两者下面滚过，列表滚动后顶栏下铺一层背景色渐隐。空会话居中：问候（早上好/中午好/下午好/晚上好/夜深了，displaySmall）→ 状态灰字（「引擎未运行」「正在启动引擎…」「没能连上引擎」「今天想做点什么？」）→ 引擎停止时主色胶囊「启动引擎」/ 崩溃时「重新连接」（都接 `ChatController.restartEngine`）→ 引擎就绪时 2×2 建议胶囊（点了只往输入框填字）。各元素按 dsh-Go `FadeSlideIn` 逐个淡入上浮。新消息行出现时淡入上浮。回到底部为浮在输入条右上的圆键。Preview：`ChatPreviews.kt`「聊天主页」。

**顶栏（TopBar.kt）**：12dp 边距、56dp 栏高；左 44dp 白色圆键「≡」开抽屉；会话名胶囊（15sp 标题 + 6dp 状态点 + 11sp 状态：就绪/生成中/正在启动/已停止/已退出）；「✦ 模型名 · 思考强度 ▾」胶囊；右 44dp 圆键「新建对话」。参数 `onModelClick`：为 null 时点胶囊弹出内置下拉（模型列表 + 思考强度 + 上下文占用，沿用原逻辑），传入后改走外部入口。

**输入条（Composer.kt）**：Chrome 玻璃 + 28dp 圆角；左 40dp「+」（`onAttachClick`，为 null 时提示「暂不支持添加附件」）；无边框多行输入（最多 6 行），占位随状态变：「给 pi 发消息…」「生成中 · 发送会排队，长按可插话」「正在停止…」「连接引擎后即可发送」；上下文小环；40dp 发送键：发送 ↑ / 停止 ■ / 排队（带字胶囊，长按选插话）/ 空闲无字时为麦克风（点了提示「暂不支持语音输入」）。按下缩到 0.9。排队/插话/已撤回/状态行为输入条上方的安静胶囊；重试与引擎横幅为输入条上方的白色胶囊。

**消息行（Messages.kt、MarkdownText.kt）**：用户气泡右对齐、最宽 82%、userBubble 色、20dp 圆角，下方小字送达状态；思考/工具/折叠摘要/未知消息统一成 dsh-Go 过程行（24dp 色块 + 标签 + 灰色摘要 + 旋转 ▾），工具色块按 dsh-Go 的 8 种工具色调；展开内容为代码井（发丝描边、眉题「参数 / 输出 / 错误信息」）；流式等待为三点起伏；错误卡为淡红圆角卡 + 安静胶囊按钮 + 主色「重试」；提示行居中灰字两侧发丝线；代码块 36dp 标题条（语言名 + 复制/已复制）。ExtDialogs 的对话框换成 Sheet 底色。Preview：`ChatPreviews.kt`「消息」。

**外壳（app/nav）**：去掉底部导航栏，改为 `ModalNavigationDrawer`：宽 min(320, 屏宽-56)，Sheet 玻璃、右侧 28dp 圆角，scrim 用 `DshTheme.colors.scrim`，左边缘手势拉出；顶部「pi-go」字标 + 主色「新对话」；行：聊天、全部会话；底部「系统」：安装与运行环境（带引擎状态点）、设置。没配模型的提示改为输入条上方的胶囊（`chat:goSettings` 不变）。`AppSmokeTest` 改为先拉出抽屉再点 `nav:*`。

**抽屉接线（收尾，app/nav/PiGoDrawer.kt、PiGoNavHost.kt）**：抽屉里直接放 B 的会话面板 `SessionsScreen`（品牌行 +「新对话」、搜索胶囊、全部/回收站、按天分组的会话、长按删除、身份栏「刷新」「设置」），替换原来的「聊天 / 全部会话」占位行。打开/新建会话、删除当前会话前先新建（`onBeforeDeleteCurrent`）、`currentGuestPath` 高亮都沿用原 `SessionsPage` 的引擎与会话仓库接线，成功后关抽屉回聊天页；身份栏设置钮跳设置页（设置页 `onModelClick` 仍用默认对话框）。「安装与运行环境」（带引擎状态点）通过 `SessionsScreen` 新增的可选 `footer` 槽放在列表与身份栏之间。`sessions` 路由保留为整页版。`AppSmokeTest` 改为从身份栏 `sessions:settings` 进设置、`nav:onboarding` 进安装向导。

## 与 dsh-Go 的差距
- 吉祥物、鲸鱼尾、品牌标识：按要求不用。
- 玻璃面无背景模糊（dsh-Go 用 BackdropFilter）；只做了半透明填充 + 描边 + 阴影。
- 抽屉已嵌入会话列表与就地搜索；仍没有工作区切换、远程主机、置顶/文件夹/归档/多选/重命名、会话状态点，搜索不是独立搜索页。「安装与运行环境」入口是 pi-go 自有的，dsh-Go 抽屉里没有。
- 顶栏「选择模型▾」点开仍是下拉菜单，不是 dsh-Go 的模型底部面板（功能工作流接 `onModelClick`）。
- 「+」附件面板、斜杠命令面板、@ 提及、语音输入：未做，入口为提示「暂不支持」。
- 代码块无语法高亮、无换行切换、无 Mermaid 预览；Markdown 仍用 mikepenz 渲染器，只换了配色与字号。
- 消息长按菜单、引用回复、复制整条、翻译思考：未做（缺状态/功能）。
- 工具行没有 diff 统计、审批锁、子代理分组线；回合折叠摘要只有一行文字。
- 列表顶部渐隐是简单渐变，不随滚动量渐变。
- 强调色：dsh-Go 默认是「雾蓝」seed #4B67B4；这里按参考截图用了它的「黛青」配色（主色 #0F5E7E），其余中性色照雾蓝。
- 本地无法跑 AAPT2，`:app` 只在 CI 编译；没有截图（无模拟器），以 Preview 代替。

## CI
- 4640e2e（抽屉接线）：https://github.com/zr987459/pi-go/actions/runs/41597702241 —— success（assembleDebug）
- 78b25f7（ui/dsh-panels 合入 ui/dsh-style 后的分支头）：https://github.com/zr987459/pi-go/actions/runs/41597170045 —— success
- 本地（arm64 沙箱，无 AAPT2）：`:feature:sessions/settings/onboarding/chat:testDebugUnitTest` 全部通过（12/14/7/65 个用例）；`:app` 只在 CI 编译
- 05e72f2（界面改完）：https://github.com/zr987459/pi-go/actions/runs/41595071530 —— success（assembleDebug）
- af52ea3（主题修复）：https://github.com/zr987459/pi-go/actions/runs/41593713759 —— success
- 6395c89（第一个 theme 提交）：失败（AppIcons 里 `package` 撞关键字），已在 af52ea3 修复
- 仪器测试（androidTest）CI 不跑；`StopKeepsDraftTest` / `StreamingJankTest` 用到的 tag（chat:input / chat:send / chat:stopAlt / chip:withdrawn）都保留，发送键的「发送/排队/停止」文字放进了语义 text。
