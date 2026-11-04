# r3/session-overlay 状态

- 状态：**进行中**（2026-11-04 11:20 CST），已普通合并 main@d15a637（合并提交 32b834c）
- 任务：修复 SessionHost 产出的 overlay:* 面板（/tree、/resume、会话列表等）选中某项后返回「未知动作」的问题（PR #18 审查附注）

## 计划
1. 找出 core/session 里所有 overlay:* 面板，以及它们的选择和提交回调
2. 逐个实现真实行为，与 docs/pi-features.md 一致：/tree 跳到节点或分叉，/resume 切换会话，其余同类面板同样处理
3. 加单测：用真实的 SessionHostImpl 配 Fake 引擎，逐个断言选中后的效果；扩展覆盖测试，防止再出现「未知动作」
4. 检查 ui/calm 的面板路由，有问题就做最小修复；不改 feature/term
5. 开 PR 到 main，CI 全绿

## 进度 / 下一步
- 已完成：
  - 新增 `core/session/OverlayPanels.kt`，统一 overlay:* 面板的回传约定，表单兼容 JSON 与 `k=v` 两种格式
  - 新增 `PiActionsImpl.onPanel`，逐个面板实现真实行为
  - `SessionHostImpl.runAction` 路由：overlay:* 面板 → onPanel；ModelPanels 面板 → onPanelEvent；同 id 面板改为替换，不再层层叠加
- 进行中：编译、单测、扩展覆盖测试（OverlayPanelsTest）、calm 最小修复（FormView 显示 Form.actions）
- 下一步：本地跑 :core:session 单测，开 PR
