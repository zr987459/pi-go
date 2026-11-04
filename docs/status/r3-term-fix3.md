# r3/term 审查第 3 轮修复（fix3）

状态：**已开始**（2026-11-04 11:07 CST），基于 r3/term@f14af8c41daee47673ede275599cb681d6127fd4

## 计划
1. Important 1：ModelPanels 面板的事件按“面板来源”路由（与 calm 一致），不再按 id 交给 runAction；测试用真实 SessionHostImpl + PiActionsImpl（Fake 引擎）：/model 选中后会话模型切换、/login 表单提交到达 ModelPanels。
2. Important 2：表单提交只有成功 / Done 才关闭；失败 Toast 显示在面板内并保留输入。
3. Nit 1：OAuth 失败用结构化状态判断；Nit 2：OAuth 面板关闭时发 cancel、成功时发 done；Nit 4：面板栈约定写进注释或改为按实例判断；Nit 3：能顺手修就修。
4. CI 全绿（含模拟器 job）后，status 写“完成”。
