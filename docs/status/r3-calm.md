# r3/calm（工作流 ④ UI 形态）状态

**状态：进行中**（最近更新 2026-11-04 07:50 CST）

## 已完成
- `core/ui/.../calm/CalmTheme.kt`：§4.2 配色（浅/深）、字体（系统 Sans 16sp/1.55、JetBrains Mono 14sp）、圆角与动效常量。
- `core/ui/.../calm/CommandSearch.kt` + `PinyinTable.kt`：⌘ 搜索（中文 / 斜杠名 / 拼音首字母）；JVM 单测 `CommandSearchTest`（本地 kotlinc 跑过 5/5）。

## 下一步（接手者照做）
1. `feature/chat/src/main/kotlin/.../ui/calm/`：CalmSurface（顶栏 / 消息区 / 输入区 / 发送键三态）、CommandCenter、CalmSheet（全部 OverlayModel）、会话列表、Previews。
2. `feature/chat/src/androidTest/.../UiCoverageTest.kt`（CI 模拟器 job 只跑这个类，切换夹具与三态测试也放这个类里）。
3. 删 `SuggestionText.kt` 与其测试；dsh 文件被他线引用的先 `@Deprecated("r3: r3/calm 删除")`。
4. 开 PR 到 main（PR #15 是否合入要先查）。

## 约定
- 只用 Git Data API 提交（git clone 经中转 403）。
