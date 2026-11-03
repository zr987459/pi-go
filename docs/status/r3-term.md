# r3/term 状态（工作流 ⑤ 终端形态）

**已开始** 2026-11-04 07:35 CST，基于 r3/core-base@818aa93。

## 计划
1. 读 DESIGN-r3.md 的 §3（pi TUI 还原）、§7、§8.1/8.2/8.5 和派发表 ⑤ 段，以及 pi-features.md。
2. 在 §8.1 规定的终端形态文件范围内，基于 FakeSessionHost 实现：消息流渲染、编辑器/草稿、斜杠命令（PiActionCatalog）、状态栏/页脚、键位、一键切到 UI 形态（共用会话和引擎，保留草稿）。
3. 补单测和截图快照，作为验收证据。
4. PR #15 合入后，把 main merge 进本分支（不 rebase），再开 PR 到 main，盯 CI 变绿。

## 进度（2026-11-04 08:00 CST）
- [x] 读完设计：§3、§5、§7、§8 和派发表 ⑤
- [x] `theme/PiColor.kt`、`theme/PiTheme.kt`：从 pi-tui 移植 okhsl/oklch 转换；`assets/pi-theme/{dark,light}.json` 原样打包；`PiThemeTokensTest`，夹具由 node 跑 pi 包生成
- [x] `text/CellWidth.kt`：格宽计算，宽字符区间表由 node 跑 get-east-asian-width 1.6.0 生成
- [x] `format/Footer.kt`：逐行移植 footer.js；`FooterSnapshotTest` 的 7 组输入与 node 跑 FooterComponent 的输出逐行比对
- [ ] 本地编译与单测（feature:term:testDebugUnitTest 正在跑首轮）
- [ ] `format/TermFormatter.kt`（ChatItem → 行；markdown、工具三态、diff、思考块、截断提示）+ `TermFormatterGoldenTest`
- [ ] `KeyBindings` + `SwitchChordNoConflictTest`（夹具：pi 默认键位表）
- [ ] Compose：`TermSurface`（字符网格、页眉、状态行、编辑器、补全浮层、页脚、键条、`TermSelectList`）
- [ ] `TermCoverageTest`（androidTest）、切换夹具、截图
- [ ] `docs/notices/r3-term.md`

## 下一步（接手者看这里）
1. 跑 `./gradlew --offline :feature:term:testDebugUnitTest`，修编译错误。
2. 写 TermFormatter 和 golden 测试：用 node 跑 pi 的 components（assistant-message、tool-execution 等）在 80 列下的渲染结果作夹具。
