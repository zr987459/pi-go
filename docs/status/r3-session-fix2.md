# r3/session 修复第 2 轮 状态

- 2026-11-04 09:08 CST 已开始（r3/session head b7548f8）。

## 计划
0. 确认 b7548f8 CI（含模拟器 UiCoverageTest），红则先修；普通 merge 最新 main。
1. I1 外发消息状态（排队/发送中/失败/可重试）经 SessionHost 暴露给两形态，失败不丢内容 + 行为测试。
2. I2 停止后撤回的排队消息与 DraftState.recalled 合一并持久化 + 测试。
3. I3 带图发送被拒：原样保留、返回失败、不重复 + 测试。
4. I4 草稿单一数据源（StateFlow + 来源/版本号防回滚）+ 快速交替输入测试。
5. Nit：transcript.search 真实搜索；问题报告 settings 脱敏；PR 描述写明契约新增与 feature/chat/build.gradle.kts；feature/commands/ui/** 归属按 §8.2 注明。
6. CI 全绿后更新 PR #20 描述。
