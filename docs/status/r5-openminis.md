# r5/openminis 状态

- 2026-11-05 11:01 CST 已开始（分支 r5/openminis，基于 main@4e3e2b7）。
- 2026-11-05 11:02 CST **需求变更**（用户：「这个也有终端呀」）：终端也照 OpenMinis 的内置终端做（交互、入口、样式），
  替换/改造现有终端形态；全 App 统一按 OpenMinis 的方式，**不再保留「我们自己的终端形态 + ⇄ 切换」**（此前「保留终端形态可切换」作废）。
  pi 原生功能仍不能丢：终端侧的 pi 功能经 OpenMinis 式终端/入口可达。形态切换相关测试按新结构如实改写（保留「不重启引擎、草稿/会话不丢」的断言）。
- 2026-11-05 11:20 CST 阶段 1 进行中：已通读 OpenMinis src/android 的 theme、chat、sessions、terminal；对照表写入 docs/r5-openminis-map.md。

## 目标
照 OpenMinis Android 端（src/android，Kotlin/Compose）重做 pi-go 的界面与交互：布局、消息流/气泡、输入框、会话列表、设置与模型配置、动效、**终端**。
底层保留 pi --mode rpc 引擎、模型配置与 Key 热加载；pi 原生功能（命令、扩展面板、会话恢复、排队、中止等）不丢。借鉴代码在 THIRD_PARTY_NOTICES.md 记来源。

## OpenMinis 终端是什么（调研结论）
- `ui/terminal/TerminalScreen.kt`：独立的全屏页面（导航目的地 `terminal`），入口在会话列表右上菜单「终端」（`onTerminalClick`）与设置页；
  纯黑底，顶栏 = 左侧圆形 ✕（关闭返回）· 居中等宽「Terminal」· 右侧绿色 🖌（清屏，先发 Ctrl+U 再清）；
  底部圆角深灰「键盘附件条」：显示/隐藏键盘、复制全部、Esc、Tab、⏎、Ctrl（粘滞，蓝色高亮）、↑↓←→、C-c、C-d、C-z，按钮 = #404040 圆角 6dp + 绿色等宽字；
  不自动弹键盘（点画布才弹）；附件条随 IME 抬起（imePadding）。
- pi-go 的映射：终端页 = 同一个 SessionHost（pi 会话、引擎、草稿、overlay 栈共用），内容区是 pi TUI 的 Compose 渲染（原 TermSurface，pi 斜杠命令、`!` bash、扩展面板、排队、Esc 中止都在），
  外壳与附件条换成 OpenMinis 样式；入口 = 会话列表抽屉「终端」行 + 聊天顶栏菜单「打开终端」；✕/返回键回到聊天。
  底层仍用 `host.form`（CALM=聊天页、TERM=终端页），所以「进出终端不发 RPC、不重启引擎、草稿不丢」的既有断言照样成立；
  去掉的是聊天顶栏的 ⇄ 形态切换按钮、终端页脚的 `[UI]` 与键条里的 ⇄UI 键（改为 ✕ 关闭）。

## 计划
1. 通读 OpenMinis src/android 的界面结构、主题、组件、动效与终端，列对照表（OpenMinis 组件 → pi-go 现有 SessionHost/动作表）→ docs/r5-openminis-map.md。
2. 主题与基础组件移植（ChatPalette 颜色、iOS 式分组背景、圆角 12/20/28、动效规格）。
3. 消息流与气泡（右侧 18dp 圆角用户气泡、80% 宽、长按菜单；助手无气泡全宽 Markdown）、工具调用胶囊卡片、思考行、流式渐显。
4. 输入框（OpenMinis 圆角胶囊输入、＋附件、发送/停止/排队（排队气泡虚线框 + 红色撤回）、斜杠补全）。
5. 会话列表/抽屉（含「终端」入口）、设置与模型配置页（沿用 r4 模型配置逻辑，换 OpenMinis 分组卡片样式）。
6. 终端页按 OpenMinis TerminalScreen 改造（黑底、✕/标题/🖌 顶栏、键盘附件条），接入 pi 原生功能入口；改写形态切换测试。
7. 测试与 CI 全绿（build、单测、模拟器、真 pi 桥），开 PR 到 main。

## 下一步（精确）
- 写 docs/r5-openminis-map.md；装本地构建环境（JDK17 + SDK35 + qemu aapt2，/tmp/ab）；从 core/ui/calm/CalmTheme.kt 开始换 OpenMinis 调色板。
