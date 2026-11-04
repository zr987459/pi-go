# r3/term 审查第 1 轮修复（fix1）

状态：进行中（最近更新见下方时间）

## 进度
- [x] merge main：r3/term 已包含 main（POST /merges 返回 204，main 领先 0）
- [~] 本地改动（未推送，正在编译验证）：
  - TermEnv 注入：fileSearch（@ 搜索）、bashRunning、contextFiles、availableProviderCount、latestCacheHitRate
  - Esc：bashActive（注入的 bashRunning 或本形态发出的 `!` 命令在途）→ bash.abort
  - 贴底：scrollToItem(last, Int.MAX_VALUE)；followBottom 只在用户手势结束时改写
  - 页眉：[Context]/[Skills]/[Prompts]/[Extensions]/[Models]，可折叠，受 quietStartup 控制
  - @ 补全浮层（term:at:<path>），Tab/Enter 选第一项
  - Nit：Shift+Ctrl+P / 右滑反向循环；↑/↓ 键帽拆分并支持外接键盘；⇞/⇟ 拆分并带 prev/next；页脚 provider 数、`?`、CH%；重试倒计时；tick 移入 StatusLine；overlay 下 Enter（overlayEnter）
- [ ] androidTest：修 performScrollTo、Esc 三态断言、外接键盘五键、贴底与阅读位置、1000 delta 慢帧、@ 搜索
- [ ] 推送并让 CI（含模拟器 job）变绿

## 接手须知
- 工作副本：/workspace/work/term/src（tarball + 本地 git）；推送用 Git Data API，不 force
