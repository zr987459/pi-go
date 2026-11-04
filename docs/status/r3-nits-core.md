# r3/nits-core：积压 Nit（core/engine + core/session）

状态：完成，PR #24（代码 c327509，CI 绿 @61aa3ad：Android 含模拟器 + 真 pi 连续 5 次）。

## 已做
- A1 桥接回复按请求序号认领：命令带 `--req=<序号>`，扩展在回复里带回；reload/switch 的完成信号（新实例 ready）经 globalThis 带回序号。无人认领的回复丢弃并记日志（`桥接回复无人认领…`）。
- A2 tree-nav 迟到成功：重建时间线 + 原文按 `set_editor_text` 交回聊天层（草稿为空才写入，否则询问）。
- A3 switch 对账：`PigoBridge.normalizePath` 规范化后比较；发给 pi 的路径也规范化。
- B realpi `turn()` 根因：等瞬时的 `running=true`，`#text` 回合太快被两次轮询漏掉 → 改等「助手消息数增加且不在生成」。
- C1 弹栈提交：ModelPanels 表单只在失败提示时压回（`SessionHostImpl.isFailureToast`）。
- C2 树按「打开序号」关闭（`panelOpenSeq` / `Result.closeOpened`），期间筛选/折叠不影响，重开过不关。
- C3 停止失败：端口已发 notices，`abort` 动作返回 Done，只提示一次。
- C4 旧入口带图消息退回：图片回到控制器附件（范围外最小改动：feature/chat/ChatControllerPort.kt）。
- D 核实：core/contract、core/protocol 的 build.gradle.kts 已自带 `testDebugUnitTest → test`，CI 已覆盖，无需改动。

## 下一步
- 无。等审查。
