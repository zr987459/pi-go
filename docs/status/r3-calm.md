# r3/calm（工作流 ④ UI 形态）状态

**状态：进行中**（最近更新 2026-11-04 07:58 CST）

## 已完成
- `core/ui/.../calm/CalmTheme.kt`：§4.2 配色（浅/深）、字体（系统 Sans 16sp/1.55、JetBrains Mono 14sp）、圆角与动效常量。
- `core/ui/.../calm/CommandSearch.kt` + `PinyinTable.kt`：⌘ 搜索（中文 / 斜杠名 / 拼音首字母）；JVM 单测 `CommandSearchTest`（本地 kotlinc 跑过 5/5）。

- `feature/chat/src/main/kotlin/.../ui/calm/`：CalmSurface（顶栏 ☰/标题改名/⌘/⋯/⇄）、CalmMessages（用户块、助手正文、思考折叠、过程行、压缩卡、本地块、Mermaid 字符画）、CalmComposer（队列条、重试条、`/` 与 `@` 面板、`!` 模式、⤢/×、＋、思考胶囊、模型胶囊、上下文环、发送键三态）、CalmSheet（全部 OverlayModel + ⌘ 命令中心，⌘ 存在 overlay 栈里，搜索词切形态不丢）、CalmSessionList（☰ 会话列表）、MermaidAscii、CalmPreviews（6 个 @Preview）。尚未经 CI 编译。
- 删除 `SuggestionText.kt` 与 `SuggestionTextTest.kt`，`ChatScreen.kt` 去掉建议胶囊（X9）。

## 下一步（接手者照做）
1. 看 CI（/actions/runs?branch=r3/calm）把 calm/ 编译修绿。
2. `feature/chat/src/androidTest/.../UiCoverageTest.kt`（CI 模拟器 job 只跑这个类，切换夹具与三态测试也放这个类里）。
3. dsh 文件被他线引用的先 `@Deprecated("r3: r3/calm 删除")`。
4. 开 PR 到 main（PR #15 是否合入要先查）。

## 约定
- 只用 Git Data API 提交（git clone 经中转 403）。
