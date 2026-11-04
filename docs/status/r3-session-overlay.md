# r3/session-overlay 状态

- 状态：**已开始**（2026-11-04 11:08 CST），基于 main@a83ff37
- 任务：修复 SessionHost 产出的 overlay:* 面板（/tree、/resume、会话列表等）选中某项后返回「未知动作」的问题（PR #18 审查附注）

## 计划
1. 找出 core/session 里所有 overlay:* 面板，以及它们的选择和提交回调
2. 逐个实现真实行为，与 docs/pi-features.md 一致：/tree 跳到节点或分叉，/resume 切换会话，其余同类面板同样处理
3. 加单测：用真实的 SessionHostImpl 配 Fake 引擎，逐个断言选中后的效果；扩展覆盖测试，防止再出现「未知动作」
4. 检查 ui/calm 的面板路由，有问题就做最小修复；不改 feature/term
5. 开 PR 到 main，CI 全绿

## 进度 / 下一步
- 下一步：定位面板
