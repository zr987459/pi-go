# r3/core-base 状态

状态：首推实现完成，PR #15；CI 绿（@fbae131）；第 1 轮审查 FAIL，修复由 fix1 工作者在本分支进行中（07:18 CST）

## 范围
DESIGN-r3.md §8.3 第 1 步（首推）：core/session、feature/term 模块骨架；core/contract 只新增契约
（SessionHost、DraftState、OverlayModel、UiForm、PiActionSpec/PiActions/ActionResult、ModelPanels、PiSettingsSchema、
actions/PiActionCatalog.kt 第一版）；fake/FakeSessionHost（可编程流式 + 命令日志 + start/restart 计数）；CI（§8.4）。
不做第 2 步（r3/core 实现：桥接、@ 搜索、实测三件事等）。

## 已完成
- 契约、FakeSessionHost（可编程流式 + 命令日志 + start/restart 计数）、PiActionCatalog（75 个 id，与 DESIGN-r3 §2 一一对应）、core/session 与 feature/term 骨架、CI（单测、全部仪器测试编译、模拟器 job、单测汇总）
- PR #15：https://github.com/zr987459/pi-go/pull/15
- CI @fbae131：push https://github.com/zr987459/pi-go/actions/runs/41734382781 success；PR https://github.com/zr987459/pi-go/actions/runs/41734388016 success
- 4f13222（CI 单测汇总）那轮被后续推送取消，未出结论
- 审查第 1 轮（PR 评论 6617726115）：Important 2、Nit 6，结论 FAIL

## 下一步
- 审查修复见 docs/status/r3-core-base-fix1.md（另一工作者负责，本人不再改代码，避免两人同改一处）
- 修复后 CI 绿、复审通过，即为「首推 PR 已就绪」；之后开始第 2 步 r3/core（不在本次范围）

## 移交
- PiActionCatalog.kt 在本 PR 合入 main 后移交 r3/session（之后只归 r3/session）。
