# r3/core-base 审查第 1 轮修复（PR #15）

- 状态：**已开始**（2026-11-04 07:05 CST）
- 依据：https://github.com/zr987459/pi-go/pull/15#issuecomment-6617726115
- 只改 r3/core-base，不碰 r3/core。

## 计划
1. Important 1：`.github/workflows/android.yml` 模拟器 job 生成的 instr.sh 加 `set -euo pipefail`；没有测试类时显式跳过并打印说明，不报“通过”。
2. Important 2：契约写死 `ActionResult.Overlay` 由共享 overlay 状态/宿主压栈一次，`run` 只返回结果；Fake 对齐；单测断言栈深为 1。
3. Nit：FakeScriptedEngine 排队 steer/follow_up 在流结束后投递或清空、再次 startStream 取消旧流、uiRequests 不 replay 已应答对话框；FakeSessionHost.sendDraft 保留图片/@引用/!模式且不记空串；DraftState 选区越界与 isEmpty；SessionHost 按 DESIGN §5.1 补齐 EngineExtras、PiSettings、SessionCatalog（只加契约+Fake）。
