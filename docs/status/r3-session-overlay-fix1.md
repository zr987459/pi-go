# r3/session-overlay 审查第 1 轮修复（PR #23）

状态：进行中（12:0x CST）。基线 21ef230。

已写（未编译，测试待补）：I1 路由（SessionHostImpl.modelPanelRoute 规则 1/2 + landModelPanel 确认框收尾）、I2 show/replaceById/syncDialogs 单次 update（新增 OverlayStack.update，core/contract 范围外最小改动）、N1 navInFlight + 按实例关树、N2 失败压回表单、N3 侧键预拒绝+回滚、N4 get_tree 预填。
下一步：OverlayPanelsTest 补用例 → 本地 :core:session:testDebugUnitTest → 推送 → 看 CI → PR 描述。

计划：
1. I1：`modelPanelRoute` 认确认框（lastModelPanel 记 Confirm；先 remove 再 runAction 也能按来源路由到 ModelPanels），加 calm 路径测试（真实 SessionHostImpl）。
2. I2：`show()`/`replaceById` 改为单次 `items.update` 原子重建，保留 ExtDialog 与 editor-text-offer 在栈顶；加交错测试。
3. N1 摘要防重入 + 按实例关树；N2 失败时把带值表单压回（终端先弹栈约定）；N3 saveSettings 先全部校验（侧键拒绝）再写，失败回滚/如实提示；N4 标签表单从 extras.tree() 预填。
4. 每条加测试；CI 全绿（含模拟器）后更新 PR 描述，写「完成」。
