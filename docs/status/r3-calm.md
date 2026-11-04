# r3/calm（工作流 ④ UI 形态）状态

**状态：进行中，PR #17 已开（r3/calm → main）**（最近更新 2026-11-04 08:13 CST）

## 已完成
- `core/ui/.../calm/CalmTheme.kt`：§4.2 配色（浅/深）、字体（系统 Sans 16sp/1.55、JetBrains Mono 14sp）、圆角与动效常量。
- `core/ui/.../calm/CommandSearch.kt` + `PinyinTable.kt`：⌘ 搜索（中文 / 斜杠名 / 拼音首字母）；JVM 单测 `CommandSearchTest`（本地 kotlinc 跑过 5/5）。

- `feature/chat/src/main/kotlin/.../ui/calm/`：CalmSurface（顶栏 ☰/标题改名/⌘/⋯/⇄）、CalmMessages（用户块、助手正文、思考折叠、过程行、压缩卡、本地块、Mermaid 字符画）、CalmComposer（队列条、重试条、`/` 与 `@` 面板、`!` 模式、⤢/×、＋、思考胶囊、模型胶囊、上下文环、发送键三态）、CalmSheet（全部 OverlayModel + ⌘ 命令中心，⌘ 存在 overlay 栈里，搜索词切形态不丢）、CalmSessionList（☰ 会话列表）、MermaidAscii、CalmPreviews（6 个 @Preview）。assembleDebug 与单测已在 CI 通过（run 41742209911 的 build 前三步）。
- 删除 `SuggestionText.kt` 与 `SuggestionTextTest.kt`，`ChatScreen.kt` 去掉建议胶囊（X9）。

- `feature/chat/src/androidTest/.../UiCoverageTest.kt`：覆盖测试（遍历 PiActionCatalog.all）、⌘ 三种搜索、发送键三态、切换夹具（草稿 / overlay 搜索词 / 扩展对话框 / 阅读位置）、⌘ 搜索词跨形态保留。CI 模拟器 job 跑这个类。
- dsh（X8）与旧聊天界面（X13）还被 app / settings / models 引用，先加 `@Deprecated("r3: r3/calm 删除 …")`，未删文件。
- `docs/notices/r3-calm.md`。

## 下一步（接手者照做）
1. 看 CI（/actions/runs?branch=r3/calm）：run 41743112979（87626d9）build 全绿；模拟器 UiCoverageTest 5 例中 3 例过，2 例失败（tree.label 夹具消息不在屏上、离开时 followBottom），已在 97f543e 修，等新 run 确认。
2. main 已普通合并进本分支（87626d9）；PR #17 已开，CI 绿后更新 PR 描述里的 CI 一节，状态改「完成，PR #17」。
3. 未做：onboarding 重画（仍是 dsh 视觉）、feature/sessions 旧页面删除（待 r3/app 改接 CalmSessionList）、慢帧测试、截图产物。

## 约定
- 只用 Git Data API 提交（git clone 经中转 403）。
