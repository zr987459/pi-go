# mvp/integration 进度

状态：**已开始**（2026-11-03 12:5x CST）

## 计划
1. 从 main 切出 mvp/integration，普通 merge origin/mvp/engine（PR #5 未合入）。
2. 通读 DESIGN.md §2.3 / §5 / §6、REVIEW.md、docs/piweb-study.md。
3. BindingMode 全部切为真实实现，修各模块接线（必要时最小改动 di/*Bindings.kt）。
4. 用户追加项：聊天页接会话/设置；错误卡“去设置模型”；Pi-Web 缺口 a–e。
5. app/src/androidTest 端到端冒烟（CI 只编译）。
6. docs/testing/mvp-acceptance.md 逐条 §5 验收记录。
7. 推送前再 fetch：engine 合入则 merge main，否则再 merge mvp/engine。开 PR，CI 绿。
