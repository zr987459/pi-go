# r3/models 审查第 2 轮修复（fix2）

状态：**已开始**（2026-11-04 09:00 CST）

审查评论：https://github.com/zr987459/pi-go/pull/19#issuecomment-6619560179

## 计划
1. 普通 merge 最新 main（含 PR #16 r3/core、PR #17 r3/calm），不 rebase。
2. Important 1：ModelsSettingsPage 默认/常用面板点选写 default*/enabledModels 而不切会话模型；键帽作用于选中项；服务商表单补「账号登录」「清除」；OAuth 面板显示真实进度/链接/结果/失败。+ 单测。
3. Nit 1：core/contract KeyCap 注释（键帽点击 = 面板事件，由面板宿主分发）。
4. Nit 2：新建自定义服务商草稿（含 Key）每次新建/关闭清空。
5. Nit 3：Toast 不关闭表单。
6. Nit 4：思考预算/每模型思考档在设置页内可改或改文案。
7. Nit 5：SettingsScreenUiTest 加进模拟器 job 名单并通过。
8. Nit 6：RealCustomProviderStore 能力三态。

## 进度
- （待更新）
