# r3/nits-models 状态

**状态：review r1 修复 已开始**（2026-11-04 14:58 CST，基于 d0b89db / 代码 143909c）

审查评论：https://github.com/zr987459/pi-go/pull/25#issuecomment-6624614325

## 第 1 轮审查修复计划
- [ ] Important 1：终端/UI 删除、清除确认框点“取消”只关确认框、返回 Done，不动仍开着的表单（未保存修改 / 已填 Key / 待选模型保留）；只有设置页关闭整个面板时才清理残留。两形态一致（RealModelPanels.kt:325-337、TermPanels.kt:99-103/137-142）。
- [ ] Nit 1：新建未保存服务商的确认框 id 与草稿 key 对齐；终端不再多压一层不存在服务商的表单。
- [ ] Nit 2：RealModelPanelsFix3Test 改回正确预期。
- [ ] Nit 3：core/contract 注释写清 ActionResult.Toast.failed 含义。
- 每条加/调测试；CI 全绿（含模拟器）后更新 PR 描述。

## 之前（首版，已完成）
首版 4 条 Nit 已在 143909c 完成，CI 全绿（见 PR #25 描述）。

## 下一步（若被切断，接手者）
- 按上面清单逐条修，看本文件“进度”。
