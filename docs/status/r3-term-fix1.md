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
