# r3/term 审查第 1 轮修复（fix1）

状态：进行中（最近更新见下方时间）

## 进度
- [x] merge main：r3/term 已包含 main（POST /merges 返回 204，main 领先 0）
- [x] 实现改动已推送（70ee7a6 起），本地 :feature:term:compileDebugKotlin 与 testDebugUnitTest（21 用例）通过：
  - TermEnv 注入：fileSearch（@ 搜索）、bashRunning、contextFiles、availableProviderCount、latestCacheHitRate
  - Esc：bashActive（注入的 bashRunning 或本形态发出的 `!` 命令在途）→ bash.abort
  - 贴底：scrollToItem(last, Int.MAX_VALUE)；followBottom 只在用户手势结束时改写
  - 页眉：[Context]/[Skills]/[Prompts]/[Extensions]/[Models]，可折叠，受 quietStartup 控制
  - @ 补全浮层（term:at:<path>），Tab/Enter 选第一项
  - Nit：Shift+Ctrl+P / 右滑反向循环；↑/↓ 键帽拆分并支持外接键盘；⇞/⇟ 拆分并带 prev/next；页脚 provider 数、`?`、CH%；重试倒计时；tick 移入 StatusLine；overlay 下 Enter（overlayEnter）
- [x] androidTest 重写（TermCoverageTest 9 个用例）：tap() 先 runCatching 滚动；Esc 三态真实断言；外接键盘五键；模型方向与拆分键；切换夹具；阅读位置；流式贴底；@ 搜索；页眉资源；1000 delta 慢帧（Choreographer，>2 刷新周期计慢帧）
  - 本机 AAPT2 起不来，androidTest 只能靠 CI 编译和运行
- [ ] 盯 CI（含模拟器 job），红了就读日志修
- [ ] PR #18 描述补逐条处理说明与证据
- 暂缓：fullscreenCopyOnSelect（Compose SelectionContainer 没有公开的选区回调）、Mermaid 字符画

## 接手须知
- 工作副本：/workspace/work/term/src（tarball + 本地 git）；推送用 Git Data API，不 force

## 09:10 CST
- 6a9a43a：并入调度者裁定（面板键帽→面板事件，TermEnv.onPanelEvent，测试 panelKeyCapsEmitPanelEvents）。
- CI run 41751100860：build 绿；模拟器 job 剩 2 个失败（escPicksActionByState 的 bash 分支、externalKeyboardMatchesPi），修复中。

## 09:25 CST（进行中）
- 3e8489f：run 41752332211 build 绿；模拟器剩 2 个失败：externalKeyboardMatchesPi（Ctrl+L，清面板后编辑器失焦，已补重新聚焦）、slowFramesUnder5PercentWith1000Deltas（CI 模拟器慢帧 17.49%，184/1052，未解决）。

## 接手（第 3 位修复工作者）09:26 CST
- 已开始。计划：①确认 externalKeyboardMatchesPi 的重新聚焦修复；②终端流式渲染降重组（只重组最后一条、文本增量追加、避免整列表重算与每 delta 重新测量），再测 slowFramesUnder5PercentWith1000Deltas；仍因软件渲染超标则按调度者裁定加同模拟器基线；③CI 全绿后补 PR #18 描述。

## 09:46 CST
- 推送“慢帧优化 + 焦点修复”：
  - 终端时间线按行拆成 LazyColumn 条目（TermRow / TermRowCache）：只重排变化的消息，不变的行复用同一对象（TermLine、Span、TermRow 标 @Immutable，Compose 以相等跳过）；TermLineText 记住 AnnotatedString；屏幕外的行不组合；贴底只看行数和末行。
  - 阅读位置锚点仍是“消息 key + 消息内像素偏移”（行号 × 行高 + 行内偏移），与 calm 通用。
  - 面板栈由非空变空时，编辑器重新请求焦点（externalKeyboardMatchesPi 的根因：面板拿走焦点）。
  - slowFrames 测试按调度者裁定加同模拟器基线（纯 Text 列表，同样 1000 delta）：<5% 直接过，否则 ≤ 基线 + 5pp 且 ≤ 25%；日志打印两者。
  - 新 JVM 测试 TermRowCacheTest；readingPositionSurvivesSwitch 改为按行查 term:item:<key>。
- 下一步：看 CI（build + 模拟器），绿了补 PR #18 描述。

## 09:50 CST（上一节标的“09:46”实为 09:30）
- 7fb0ab9：run 41754527644，build 绿；模拟器上 slowFramesUnder5PercentWith1000Deltas **通过**。剩 2 个失败：
  - readingPositionSurvivesSwitch：偏移 46 → 52（空行与文字行高度不同，按行号 × 行高还原有误差）→ 每行固定为行高。
  - externalKeyboardMatchesPi：Ctrl+L 无反应。根因是 KeyChords.keyName 用 Compose `Key.keyCode`（原生键码左移 32 位）做 A–Z 区间，结果只认得 A，**所有 Ctrl+字母组合键都失效**（真机同样）→ 改用 nativeKeyCode，新增 JVM 测试 KeyChordsTest。
- 下一步：等这次 CI；绿了补 PR #18 描述。

## 09:55 CST — 完成，PR #18
- 7ef0de9：run 41756143567（pull_request）与 41756136675（push）全绿；模拟器 11 个仪器用例失败 0（含 slowFrames、externalKeyboard、readingPosition）。
- PR #18 描述已补“慢帧与外接键盘”处理表、暂缓项原因、Form.actions 说明、CI 链接；未改 .github。
- 更正：上两节标题时间“09:46”“09:50”实为 09:30、09:44。
- 未完成：Form.actions（等 r3/models 修复 2 合入 main 后另派）；fullscreenCopyOnSelect、Mermaid 字符画暂缓；真机慢帧 <5% 列入真机清单。
