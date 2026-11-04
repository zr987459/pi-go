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
- 09:03 已 merge main → e8f7f66（普通 merge，无冲突）。
- 09:38 本地 kotlinc 编译通过（JVM 部分）；新增单测 RealModelPanelsFix2Test、SettingsPanelLogicTest、CapabilityTriStateTest，正在跑。下一步：跑测试 → androidTest 补交互测试并修 SettingsScreenUiTest 滚动 → 模拟器名单（.github）→ CI → PR 描述。
- 09:24 代码进行中：契约 KeyCap 注释 + Form.actions + CustomModel 显式能力；RealCustomProviderStore 三态；RealModelPanels 表单按钮/新建清草稿/Key 绑定名称地址/OAuth submit；设置页 SettingsPanelLogic（选取模式、高亮、Toast 不关、OAuth 轮询、高级表单）。下一步：本地编译 JVM 部分、补单测、androidTest、CI。
- 09:10 读完审查与代码，方案：设置页面板加「选取模式」（默认/常用）与高亮项，键帽回传高亮项；Toast 不关面板（面板内显示消息）；OAuth 面板 1 秒轮询 refresh、OpenUrl 自动打开链接；Form 加 actions（契约最小改动）补「账号登录」「清除」；高级两项在设置页内表单直接写 pi settings；SettingsScreenUiTest 先滚到分区再取行，加进模拟器名单（.github 改动）；CustomModel 加显式能力三态（契约最小改动）。
