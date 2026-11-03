# r3/models 状态

状态：**已开始**（2026-11-04 07:36 CST）

基线：r3/core-base 818aa93（PR #15 未合入 main）

## 计划
1. 读 DESIGN-r3.md（③、§8.1/8.2/8.5/§7）、pi-features.md、REVIEW.md
2. 设置只剩「模型」：删旧 SettingsScreen 非模型项、回收站浏览页（X7）、DshComponents（X8）、NetworkAndCostScreen（X14/X2）
3. 删汇率 / 人民币（X2）：core/store ModelPrefsStore 汇率字段、core/catalog RealAppSettingsStore CostDisplay
4. 服务商搜索（拼音）、42 服务商可见可配置；ModelPanels 实现 → OverlayModel
5. FileSessionRepository 删除进回收目录 + 5 秒撤销
6. 单测：SettingsOnlyModelsTest、ProviderSearchPinyinTest、UndoDeleteTest 等
7. CI 绿 → 等 #15 合入后 merge main → 开 PR
