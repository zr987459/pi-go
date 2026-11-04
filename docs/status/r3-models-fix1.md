# r3/models 修复第 1 轮（PR #19）状态

- 2026-11-04 08:23 CST：**已开始**。在 r3/models 分支上直接修，不开新 PR。

## 计划（对应审查 issuecomment-6618930658）
1. 撤回 app/** 越界改动；NetworkAndCostScreen 改 @Deprecated 保留，交接 r3/app。
2. 设置页按 DESIGN-r3 §6.1–§6.4 重做（常用模型、已连接、拼音搜索 42 服务商、自定义服务商、高级），补界面测试。
3. RealModelPanels：页签中文标签解析崩溃、键帽 actionId 无处理 → 修 + 测试。
4. 编辑自定义服务商保留推理/看图能力；测试连接不落盘 → 修 + 测试。
5. Nit：搜索不重置页签；设为默认原子写含 defaultThinkingLevel；Key 密文/不留 OverlayStack；残留文案列出交接。
6. CI 绿，更新 PR 描述。

## 进度
- [ ] 读审查、DESIGN-r3 §6/§8、前任 status
- [x] 读审查、DESIGN-r3 §6/§8、前任 status（08:35）
- [x] Important 1：WiredPages.kt 恢复为 main 原样（撤回越界）；NetworkAndCostScreen 恢复（只留代理，删汇率部分），标 @Deprecated，交接 r3/app 删除（08:40）
- [x] Important 3/4 + Nit 1–3 代码（08:47）：tabOf 容错、键帽 actionId=面板事件 key（value=高亮项）、搜索保持页签、设为默认三键写入失败回滚、Key 字段 secret=true（契约 FormField 加 secret 默认 false，范围外最小改动）、Key 只存内存 pendingKeys 不进表单值、保存时保留原模型能力（mergeModels + 存储层 capability 未指定语义）、测试连接不落盘（改用 discover 临时探测）。测试：RealModelPanelsFix1Test（10 条）+ RealModelPanelsTest 同步（08:55）
- [ ] Important 2（设置页按 §6.1 重做 + 界面测试）
- [ ] Nit 4 残留文案、PR 描述
