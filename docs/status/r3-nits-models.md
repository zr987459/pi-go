# r3/nits-models 状态

**状态：进行中**（2026-11-04 14:22 CST，基于 main@ccee240）

## 计划（只修已审出的 Nit，每条加测试，不扩功能）
1. [ ] PR #19：服务商表单保存成功后仍提示“请重新填写 Key” → 成功时不出现
2. [ ] PR #19：模型确认框“关闭/取消”不发 cancel、残留已填 Key/待定状态 → 关闭等同取消并清理
3. [ ] PR #19：OAuth 失败后的“重试”按钮 → 核实 UI 与终端 ⌘ 两形态；缺则补在 feature/models 面板模型
4. [ ] PR #24 审查 Nit 1：isFailureToast 文案判成败 → core/contract 加结构化成败标志（默认兼容），RealModelPanels 填，SessionHostImpl.landModelPanel 改用，删文案判断

不碰：app/**、feature/usage/**、feature/files/**（r3/app-2 在改）。

## 进度
- 1 已改：RealModelPanels.onCustomEvent 在处理之后再判断 keyDropped（成功保存 forgetDraft 已清标记）。
- 2 已改：confirm.remove:/confirm.clear: 的 cancel → 丢弃草稿与已填 Key、清该服务商的待选模型，返回清理后的表单；设置页关闭确认框发 cancel（SettingsPanelLogic.dismissEvent）。
- 3 核实：两形态都已有「重试」（ui/calm CalmSheet PanelTextButtons `panel:retry` + CalmPanelLogic.oauthRetryable；终端 TermInput.oauthCaps FAILED → `enter 重试`，TermOverlay Enter=retry）。未改渲染器，加了 feature/models 契约测试。
- 4 已改：core/contract `ActionResult.Toast(text, failed = false)`；RealModelPanels 失败提示全用 fail()；SessionHostImpl.landModelPanel 用 r.failed，删 isFailureToast；测试 fake（OverlayFix1Test、TermPanelRouterRealHostTest）标 failed = true。
- 测试：RealModelPanelsNitsTest（新）、NitsCoreTest（替换文案分类测试）、SettingsPanelLogicTest.dismissingModelConfirmSendsCancel、RealModelPanelsFix3Test 取消断言更新。

## 下一步
- 推送代码 → 开 PR → 看 CI（含模拟器 job），失败读日志修。
