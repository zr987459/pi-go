# r3/session 审查第 1 轮修复（PR #20）

- 状态：**进行中 → 等 CI**（2026-11-04 08:56 CST）
- 发现：PR #16（pigo-bridge、PiBridge 契约）合入的是 `r3/core-base`，**不是 main**；main 上没有 PiBridge。树原地切换走 `SessionOps.treeNavigate`（与 `PiBridge.treeNavigate` 同签名），由 AppGraph（r3/app）用 PiBridge 实现。r3/session 已包含 main@de09f66。
- 已完成（逐条见 PR #20 评论「REVIEW-R1 处理说明」）：I1–I6、N1–N5；N6 见未完成。
- 本地：`:core:contract` `:core:session` `:feature:chat` `:feature:files` `:feature:usage` 单测全过；`feature:commands`、`app` 本机离线缺依赖，交给 CI。
- 未完成 / 交接：
  1. N6：`feature/commands/ui/**`（CommandPalette、SkillsScreen）仍只加 `@Deprecated`，等 r3/app 去掉引用后由本线删除。
  2. AppGraph 装配（r3/app）：`SessionHostImpl(..., chatPort = ChatControllerPort(chatController, imageLoader), actionsFactory = { PiActionsImpl(it, ops, modelPanels) })`；`SessionOps` 用 PiBridge（treeNavigate / setEntryLabel / reloadResources）+ GuestRunner（runGuest）+ 缓存目录（writeReport）+ 引擎重启（newSessionInDir）实现。
  3. 两种形态（r3/calm、r3/term）需收集 `SessionHost.clipboardRequests` 写系统剪贴板，并对 `SessionHostImpl.EDITOR_OFFER_ID` 的 Confirm 调 `answerEditorTextOffer`。
- 下一步：等 CI 绿 → status 写「完成，PR #20」。
