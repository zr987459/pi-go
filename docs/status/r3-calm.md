# r3/calm（工作流 ④ UI 形态）状态

**状态：完成，PR #17**（r3/calm → main，2026-11-04 08:25 CST）

## 已完成
- `core/ui/.../calm/CalmTheme.kt`：§4.2 配色（浅/深）、字体（系统 Sans 16sp/1.55、JetBrains Mono 14sp）、圆角与动效常量。
- `core/ui/.../calm/CommandSearch.kt` + `PinyinTable.kt`：⌘ 搜索（中文 / 斜杠名 / 拼音首字母）；JVM 单测 `CommandSearchTest`（本地 kotlinc 跑过 5/5）。

- `feature/chat/src/main/kotlin/.../ui/calm/`：CalmSurface（顶栏 ☰/标题改名/⌘/⋯/⇄）、CalmMessages（用户块、助手正文、思考折叠、过程行、压缩卡、本地块、Mermaid 字符画）、CalmComposer（队列条、重试条、`/` 与 `@` 面板、`!` 模式、⤢/×、＋、思考胶囊、模型胶囊、上下文环、发送键三态）、CalmSheet（全部 OverlayModel + ⌘ 命令中心，⌘ 存在 overlay 栈里，搜索词切形态不丢）、CalmSessionList（☰ 会话列表）、MermaidAscii、CalmPreviews（6 个 @Preview）。assembleDebug 与单测已在 CI 通过（run 41742209911 的 build 前三步）。
- 删除 `SuggestionText.kt` 与 `SuggestionTextTest.kt`，`ChatScreen.kt` 去掉建议胶囊（X9）。

- `feature/chat/src/androidTest/.../UiCoverageTest.kt`：覆盖测试（遍历 PiActionCatalog.all）、⌘ 三种搜索、发送键三态、切换夹具（草稿 / overlay 搜索词 / 扩展对话框 / 阅读位置）、⌘ 搜索词跨形态保留。CI 模拟器 job 跑这个类。
- dsh（X8）与旧聊天界面（X13）还被 app / settings / models 引用，先加 `@Deprecated("r3: r3/calm 删除 …")`，未删文件。
- `docs/notices/r3-calm.md`。
- onboarding 换 CalmTheme 极简视觉：纯色背景（去渐变 / 玻璃 / 光晕）、CalmIcons 替换 dsh 字形、事实行改圆点、进度环纯色；testTag 不变。

## CI
- run 41744770297（PR，dc03f36）全绿：build（assembleDebug、全部模块单测 644 例 0 失败 / 24 跳过（他线）、全部仪器测试编译）＋ 模拟器 UiCoverageTest 5/5 通过。

## 遗留（后续接手）
1. dsh 文件与 `dsh_icons.ttf`、旧聊天界面（ChatScreen 等）物理删除：等 r3/app、r3/models、feature/sessions 去掉引用（已 `@Deprecated`）。
2. `feature/sessions` 旧页面：新会话列表在 `feature/chat/ui/calm/CalmSessionList.kt`，r3/app 改接后删除旧页面。
3. 1000 条 delta 慢帧测试、截图测试产物（仓库未配 Paparazzi/Roborazzi，CI 无截图上传）。
4. 扩展 widget 区：契约无 setWidget 状态流。

## 约定
- 只用 Git Data API 提交（git clone 经中转 403）。
