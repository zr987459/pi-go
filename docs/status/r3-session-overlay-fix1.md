# r3/session-overlay 审查第 1 轮修复（PR #23）

状态：已开始（2026-11-04 11:50 CST）。基线 21ef230。

计划：
1. I1：`modelPanelRoute` 认确认框（lastModelPanel 记 Confirm；先 remove 再 runAction 也能按来源路由到 ModelPanels），加 calm 路径测试（真实 SessionHostImpl）。
2. I2：`show()`/`replaceById` 改为单次 `items.update` 原子重建，保留 ExtDialog 与 editor-text-offer 在栈顶；加交错测试。
3. N1 摘要防重入 + 按实例关树；N2 失败时把带值表单压回（终端先弹栈约定）；N3 saveSettings 先全部校验（侧键拒绝）再写，失败回滚/如实提示；N4 标签表单从 extras.tree() 预填。
4. 每条加测试；CI 全绿（含模拟器）后更新 PR 描述，写「完成」。
