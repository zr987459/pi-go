# r3/term 审查第 3 轮修复（fix3）

- 更新：2026-11-04 12:10 CST
- 状态：完成。代码提交 43df823：push 触发的 Android debug build（build + 模拟器）与真 pi 桥接集成测试全绿（run 41768928035、41768928350）
- 已普通 merge main@d15a637（PR #22 r3/core），合并提交 f8858db，无冲突

| 条目 | 改动 | 测试 |
|---|---|---|
| Important 1 | 新增 `PanelSourceTracker`（包住 ModelPanels，记录它产出的面板）与 `TermPanelRouter`：按**来源**判定是否走面板事件，不再按 id 是否在动作表里；`TermEnv.panelSource` | `TermPanelRouterRealHostTest`：真实 SessionHostImpl + PiActionsImpl + Fake 引擎，/model 选中后 `set_model` 发出、会话模型切换；/login 选服务商与表单提交到达 ModelPanels |
| Important 2 | 只有 Done 关面板；Toast 留在面板内（`overlay:<id>:notice`），已填值保留 | 同上（失败 Key 保留、提示在面板内）；`TermCoverageTest.formActionsConfirmAndOAuthRetryEmitPanelEvents` 改为断言不关 |
| Nit 1 | 契约 `OverlayModel.Text.flow: FlowState?`（RUNNING/FAILED/DONE，默认 null）；RealModelPanels 的 OAuth 步骤填上；`TermInput.oauthCaps` 只看 flow | `TermInputTest.oauthPanelOffersRetryOnlyWhenFailed` |
| Nit 2 | Esc / 关闭键帽 → `TermPanelRouter.close`：OAuth DONE 发 `done`，其余发 `cancel`；provider.custom 发 `cancel` | `TermPanelRouterRealHostTest.oauthCloseSendsCancelWhileRunningAndDoneAfterSuccess` |
| Nit 4 | “已在栈里则弹出到它”写进 `ModelPanels.onPanelEvent` 契约注释 | — |
| Nit 3 | `ListBlock` 记录源码行号，相邻判断按行号 | `TermStreamingFormatTest.adjacentListsUseLinePositionsNotTextSearch` |

装配注意（r3/app）：把交给 `PiActionsImpl` 的 ModelPanels 包成 `PanelSourceTracker`，同一实例传 `TermEnv(panelSource = …)`。未注入时退回旧规则（仅 Fake 开发用）。
