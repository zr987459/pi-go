# r3/models 审查第 3 轮修复（PR #19）

- 2026-11-04 09:37 CST：已开始。
- 计划：
  1. 普通 merge 最新 main 进 r3/models。
  2. Important：OAuth 表单/选择面板提交后进入「正在验证…」进度态并持续刷新；在途禁用提交；成功显示已登录、失败显示原因可重试（SettingsPanelLogic.kt、RealModelPanels.kt）+ 测试。
  3. Nit1 删除自定义服务商/清除凭据二次确认，删除后关闭表单；Nit2 思考档输入框多行/提示一致；Nit3 改接口地址丢 Key 时提示。
  4. CI 全绿（含模拟器），更新 PR #19 描述。
