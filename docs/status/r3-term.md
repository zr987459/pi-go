# r3/term 状态（工作流 ⑤ 终端形态）

**已开始** 2026-11-04 07:35 CST，基于 r3/core-base@818aa93。

## 计划
1. 读 DESIGN-r3.md 的 §3（pi TUI 还原）、§7、§8.1/8.2/8.5 和派发表 ⑤ 段，以及 pi-features.md。
2. 在 §8.1 规定的终端形态文件范围内，基于 FakeSessionHost 实现：消息流渲染、编辑器/草稿、斜杠命令（PiActionCatalog）、状态栏/页脚、键位、一键切到 UI 形态（共用会话和引擎，保留草稿）。
3. 补单测和截图快照，作为验收证据。
4. PR #15 合入后，把 main merge 进本分支（不 rebase），再开 PR 到 main，盯 CI 变绿。

## 进度（2026-11-04 08:02 CST）
- [x] 主题：PiColor / PiThemeLoader，dark/light 原样打包；PiThemeTokensTest 通过
- [x] 格宽：CellWidth，宽字符表来自 get-east-asian-width 1.6.0
- [x] 页脚：Footer 移植 footer.js；FooterSnapshotTest 7 组宽度，结果与 node 一致
- [x] 外接键盘：KeyBindings 与 KeyChords；SwitchChordNoConflictTest 通过（Ctrl+Alt+T 未被占用）
- [x] 排版：TermFormatter 与 Markdown；TermFormatterGoldenTest 80 列 0/145 行差异
- [x] Compose：TermSurface（消息区、状态行、队列、widget、编辑器、斜杠补全、页脚、键条、双指缩放、阅读位置）与 TermOverlay（全部 OverlayModel）
- [x] TermInputTest（JVM 静态核对每个动作的终端入口）
- [x] androidTest：TermCoverageTest（两个用例：入口遍历、切换夹具；CI 模拟器 job 按类名只跑 TermCoverageTest，所以切换夹具也放在这个类里）
- [x] 快照：TermScreenSnapshotTest 输出整屏 JSON，`feature/term/snapshots/*.png` 由 render.py 渲染
- [x] 已 merge main（de09f66，PR #15）
- [ ] 开 PR，盯 CI

## 下一步（接手者看这里）
1. CI 若在 assembleDebugAndroidTest 报 androidTest 编译错误，就改 `feature/term/src/androidTest/**`。
2. 遗留：慢帧门槛（1000 条 delta）未做；`fullscreenCopyOnSelect` 选中即复制未做；`@` 文件模糊搜索的候选源没有（SessionHost 没有暴露 workspace），只做了插入 `@`；外接键盘仪器测试未写。

## 修复记录（审查第 1、2 轮；主状态以本文件为准）
- 修复第 1 轮细节：`docs/status/r3-term-fix1.md`。**更正**：其中「全绿」只适用于 7ef0de9；head 9ea2633（代码相同）的模拟器 job 红，原因是慢帧不稳定（run 41757177576、41757183985），见审查第 2 轮。
- 修复第 2 轮细节：`docs/status/r3-term-fix2.md`（调度者指定的文件名）。完成情况、CI 运行链接以那里的最新一节为准。
- 上面「下一步」一节的遗留已经处理：慢帧门槛、`@` 文件模糊搜索、外接键盘仪器测试已完成；`fullscreenCopyOnSelect`、Mermaid 字符画暂缓（调度者已同意）。
