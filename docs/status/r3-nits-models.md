# r3/nits-models 状态

**状态：review r1 修复 代码已推（0e3a2b8），等 CI**（2026-11-04 15:20 CST）

审查评论：https://github.com/zr987459/pi-go/pull/25#issuecomment-6624614325

## 第 1 轮审查修复计划
- [x] Important 1：终端/UI 删除、清除确认框点“取消”只关确认框、返回 Done，不动仍开着的表单（未保存修改 / 已填 Key / 待选模型保留）；只有设置页关闭整个面板时才清理残留。两形态一致（RealModelPanels.kt:325-337、TermPanels.kt:99-103/137-142）。
- [x] Nit 1：新建未保存服务商的确认框 id 与草稿 key 对齐；终端不再多压一层不存在服务商的表单。
- [x] Nit 2：RealModelPanelsFix3Test 改回正确预期。
- [x] Nit 3：core/contract 注释写清 ActionResult.Toast.failed 含义。
- 每条加/调测试；CI 全绿（含模拟器）后更新 PR 描述。

## 进度（r1 修复，代码提交 0e3a2b8）
- Important 1：RealModelPanels 确认框 `cancel` → Done，不清任何状态；新增 `dismiss`（RealModelPanels.DISMISS）才清理；设置页 SettingsPanelLogic.dismissEvent 对 confirm.* 改发 `dismiss`（CONFIRM_DISMISS）。终端 TermPanelRouter 不需改：Done 只关确认框。
- Nit 1：remove 的确认框 id 用 providerId（= draftKey）；providerId == null（新建）返回失败提示，不造确认框。
- Nit 2：RealModelPanelsFix3Test.removeCustomNeedsConfirmAndClosesForm 取消断言改回 Done。
- Nit 3：core/contract PiActions.kt ActionResult.Toast 注释写清 failed。
- 测试：RealModelPanelsNitsTest（cancelRemoveConfirmKeepsTypedKeyAndEdits、dismissRemoveConfirmDropsTypedKeyAndDraft、cancelClearConfirmKeepsPendingModel、dismissClearConfirmDropsPendingModel、newUnsavedProviderHasNoRemoveConfirm）、SettingsPanelLogicTest.dismissingModelConfirmSendsDismiss、TermPanelRouterRealHostTest.cancelInRemoveConfirmKeepsOpenFormUntouched。

## 之前（首版，已完成）
首版 4 条 Nit 已在 143909c 完成，CI 全绿（见 PR #25 描述）。

## 下一步（若被切断，接手者）
- 盯最新 SHA 的 CI（build / 查找仪器测试类 / 仪器测试（模拟器）/ realpi）；红了读日志修；绿了更新 PR #25 描述的 r1 证据，status 写“完成，PR #25”。
