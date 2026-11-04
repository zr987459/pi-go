# r3/models 审查第 3 轮修复（PR #19）

- 状态：**完成，PR #19**
- 2026-11-04 09:37 CST：已开始。r3/models 与 main 同步（behind 0），无需 merge。
- 计划：Important（OAuth 表单/选择提交后进度态、防重复提交、失败重试）；Nit1 删除/清除确认；Nit2 思考档多行；Nit3 Key 丢弃提示；CI 全绿；更新 PR 描述。

## 进度 09:58 CST（第一次代码推送）
- RealModelPanels：提交代码/选择 → 「正在验证…」（在途表 oauthSubmitted，登录流任何新步骤清除；60 s 无回应回到原步骤）；在途重复提交忽略；失败文案带「可点重试」，事件 `retry` 重新开始登录。
- 删除自定义服务商 / 清除凭据 / 退出登录 → OverlayModel.Confirm（confirm.remove: / confirm.clear: / confirm.logout:）；删除确认后返回 Done 关闭表单，草稿与 Key 丢弃。
- 改名称或接口地址丢 Key：结果消息追加提示，表单 Key 字段 help 显示提示（KEY_DROPPED）。
- SettingsPanelLogic：所有未到终点的 oauth: 面板持续刷新、关闭时 cancel；guardsSubmit / oauthRetryable / multiline。
- ModelsSettingsPage：OAuth 提交与选择点一次后禁用（remember(model) 复位）；失败显示「重试」；StrList 字段多行；Confirm 键加 testTag。
- 测试：RealModelPanelsFix3Test（8 条）、SettingsPanelLogicTest +2；更新 Fix2/RealModelPanelsTest 的清除与退出登录为先确认。本地 JVM：feature/models 35/35、SettingsPanelLogicTest 15/15。
- 下一步：看 CI（build + 模拟器）；补 SettingsScreenUiTest 防重复提交 UI 用例；更新 PR #19 描述。

## 完成，PR #19（10:20 CST）
- 代码提交 9a0c555（修复）+ 2809568（UI 测试）。CI @2809568：push 41756118584、PR 41756127036 全部 success；模拟器 SettingsScreenUiTest 9/9（含 oauthCodeSubmitIsSentOnceAndFailureOffersRetry）、UiCoverageTest 5/5。
- PR #19 描述已追加「审查第 3 轮修复」表与 CI 证据。
- 未完成：无（本轮范围内）。跟踪项：r3/term 渲染器对 Confirm 面板与 oauth 面板 `retry` 事件的呈现（不归本线）。
