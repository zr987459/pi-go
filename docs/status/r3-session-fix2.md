# r3/session 修复第 2 轮 状态（PR #20，审查 REVIEW-R2 = issuecomment-6619709504）

- 2026-11-04 09:08 CST 已开始（r3/session head b7548f8）。
- 2026-11-04 09:27 CST 进度更新（见下）。

## 计划
0. 9f6bc22 CI：模拟器 job 仍红，`UiCoverageTest.everyActionHasUiEntry` 报 `help.env（Explain）`（调度补充）。先修。普通 merge 最新 main。
1. I1 外发消息状态（发送中/已排队/失败/可重试）经 SessionHost 暴露给两形态，失败不丢内容 + 行为测试。
2. I2 停止后撤回的排队消息与 DraftState.recalled 合一并持久化 + 测试。
3. I3 带图发送被拒：原样保留、返回失败、不重复 + 测试。
4. I4 草稿单一数据源 + 快速交替输入测试。
5. Nit：N1 transcript.search 真实搜索；N2 问题报告 settings 脱敏；N3 PR 描述写明契约新增与 feature/chat/build.gradle.kts；N4 feature/commands/ui/** 归属。
6. CI 全绿后更新 PR #20 描述；状态末行写「完成，PR #20」。

## 进度
- [x] 09:20 help.env 根因：说明结果块追加在 LazyColumn 末尾、可能在视口外，不被组合，`waitFor("local:explain:<id>")` 找不到。
  修法（不放宽）：`checkExplain` 先等结果块真的进 `host.localBlocks`，收起 ⌘，再在 `calm:messages` 上 `performScrollToNode(hasTestTag(tag))` 并 `assertIsDisplayed()`；
  失败时把原因（⌘ 找不到 / 动作没执行 / 结果块没进 localBlocks / 不在列表里）写进断言信息。范围外最小改动（androidTest 属 r3/calm），PR 描述注明。
- [x] 09:25 代码（本地 JDK17 + Android SDK 35 编译，`:core:session` `:feature:chat` 现有单测全过）：
  - 契约新增文件 `core/contract/.../session/OutgoingMessage.kt`；`SessionHost` 新增有默认实现的 `outgoing`、`retryOutgoing`、`editOutgoing`、`discardOutgoing`。
  - `SessionChatPort` 增 `outgoing/retry/edit/discard/stop`；`SessionHostImpl` 转发；`abort` 动作改走 `chatPort.stop()`。
  - `ChatController`：草稿只存在 `sharedDraft`（删掉两条同步协程），`composer.draft/withdrawn` 从它派生，`withdrawn` = `DraftState.recalled`；
    revision 按「打字写入的对象引用」判定；`submitContent` 明确返回 Accepted/Rejected；异步失败发 notice 并留在 `outgoing`；`stopAndWait()`。
  - `ChatControllerPort.send`：图片随本次提交传入、不进控制器附件；被拒原样返回 Err。
- [ ] 下一步：写行为测试（I1–I4、N1、N2），做 N1/N2，推送，看 CI，merge main，更新 PR 描述。
