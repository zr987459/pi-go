# r3/core-base 状态

状态：进行中（06:52 CST）：代码已推，等 CI

## 范围
DESIGN-r3.md §8.3 第 1 步（首推）：core/session、feature/term 模块骨架；core/contract 只新增契约
（SessionHost、DraftState、OverlayModel、UiForm、PiActionSpec/PiActions/ActionResult、ModelPanels、PiSettingsSchema、
actions/PiActionCatalog.kt 第一版）；fake/FakeSessionHost（可编程流式 + 命令日志 + start/restart 计数）；CI（§8.4）。
不做第 2 步（r3/core 实现：桥接、@ 搜索、实测三件事等）。

## 已完成
- 契约、FakeSessionHost、PiActionCatalog（75 个 id）、core/session 与 feature/term 骨架、CI 改动已推（3bffea5）

## 下一步
- 开 PR，等 CI；红了按日志修

## 移交
- PiActionCatalog.kt 在本 PR 合入 main 后移交 r3/session（之后只归 r3/session）。
