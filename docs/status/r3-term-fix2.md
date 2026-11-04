# r3/term 修复第 2 轮（PR #18）状态

- 2026-11-04 10:14 CST：**已开始**。直接在 r3/term 上修；已普通 merge 最新 main（889615e，含 r3/models PR#19），未 rebase、未 force。

## 计划（对应审查 issuecomment-6620605523）
1. Important 1 慢帧：定位每个 delta 的重组 / 测量热点，流式渲染只动最后一行；tick 下沉；auto-scroll 不再每个 delta 都 scrollToItem。模拟器 job 连续 2 次绿。
2. Important 2 ↑↓ 翻历史：开始翻时保存草稿；在调出的历史上一编辑就退出浏览；↓ 越过最新一条时恢复草稿。加单测。
3. Nit 1–5：@ 补全防抖期间的 Enter/Tab；autocompleteMaxVisible；面板键帽一律发面板事件；/tree 与 FullOutput 的外接 Enter；交接 r3/app（bashRunning）写进 PR 描述；status 命名与 CI 如实。
4. 契约新项：Form.actions、确认框、OAuth 重试，经面板事件回传，加测试。
5. CI 全绿，更新 PR 描述，status 写「完成」。

## 进度
- [x] merge main（10:13）
- [x] 读审查评论与前任 status（10:16）
- [x] 10:30 推送 Important 1 第一批与 Important 2（本地没有 JDK 和 Android SDK，靠 CI 编译）：
  - 慢帧：整屏只订阅去掉 items 的 ChatState（derivedStateOf），流式 delta 只让时间线子组合重组；贴底只在行数变化时滚动（行高固定）；流式 Markdown 用「稳定前缀缓存 + 只排最后一段」（Markdown.stableCut、TermFormatter.mdLines），前缀的行对象跨 delta 复用。测试 TermStreamingFormatTest（每个前缀长度都与整段排版逐行相同、行对象复用）。
  - ↑↓：HistoryNav（开始翻时保存草稿、编辑即退出浏览、↓ 越过最新一条恢复草稿），TermSurface 接入；测试 HistoryNavTest（3 条）。
- [ ] 下一步：看 CI；Nit 1–3、Form.actions、确认框、OAuth 重试
