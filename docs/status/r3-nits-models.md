# r3/nits-models 状态

**状态：已开始**（2026-11-04 14:10 CST，基于 main@ccee240）

## 计划（只修已审出的 Nit，每条加测试，不扩功能）
1. [ ] PR #19：服务商表单保存成功后仍提示“请重新填写 Key” → 成功时不出现
2. [ ] PR #19：模型确认框“关闭/取消”不发 cancel、残留已填 Key/待定状态 → 关闭等同取消并清理
3. [ ] PR #19：OAuth 失败后的“重试”按钮 → 核实 UI 与终端 ⌘ 两形态；缺则补在 feature/models 面板模型
4. [ ] PR #24 审查 Nit 1：isFailureToast 文案判成败 → core/contract 加结构化成败标志（默认兼容），RealModelPanels 填，SessionHostImpl.landModelPanel 改用，删文案判断

不碰：app/**、feature/usage/**、feature/files/**（r3/app-2 在改）。

## 进度
- 刚建分支，正在读代码。

## 下一步
- 读 feature/models、core/contract、core/session 相关代码，逐条修。
