# r3/term 第三方来源（并入 THIRD_PARTY_NOTICES.md 由 r3/app 完成）

## pi 主题 JSON
- 文件：`feature/term/src/main/assets/pi-theme/dark.json`、`light.json`
- 来源：`@earendil-works/pi-coding-agent` 1.5.0，`dist/modes/interactive/theme/{dark,light}.json`，原样打包
- 许可：MIT（pi-coding-agent）

## pi 默认键位表
- 文件：`feature/term/src/main/assets/pi-default-keybindings.json`（测试夹具 `src/test/resources/pi/default-keybindings.json` 内容相同）
- 来源：pi-coding-agent 1.5.0 `dist/core/keybindings.js` 的 `KEYBINDINGS`（含 pi-tui `TUI_KEYBINDINGS`）各项 `defaultKeys`，非 Windows 平台，用 node 导出
- 许可：MIT

## 颜色转换（okhsl / oklch → sRGB）
- 文件：`feature/term/src/main/kotlin/.../theme/PiColor.kt`
- 来源：`@earendil-works/pi-tui` 1.5.0 `dist/oklab.js`、`dist/colors.js`，逐函数移植到 Kotlin（数值常量原样）
- 许可：MIT

## 东亚宽度表（wcwidth）
- 文件：`feature/term/src/main/kotlin/.../text/CellWidth.kt` 中的 `WIDE` 区间表
- 来源：pi-tui 依赖的 `get-east-asian-width` 1.6.0（sindresorhus，MIT）：用 node 对 U+0000–U+10FFFF 逐码点调用 `eastAsianWidth()`，宽度为 2 的码点压成 123 个区间
- 格宽规则（ASCII 1 列、制表符 3 列、区域指示符 2 列、组合符号 0 列）对照 pi-tui `utils.js` 的 `graphemeWidth`

## 页脚、消息排版
- 文件：`format/Footer.kt`、`format/TermFormatter.kt`、`format/Markdown.kt`
- 来源：按 pi-coding-agent 1.5.0 `components/footer.js`、`user-message.js`、`assistant-message.js`、`tool-execution.js`、
  `core/tools/renderers/*.js` 与 pi-tui `Markdown` 的行为重新实现（Kotlin 重写，非逐行拷贝；footer 的排版与截断顺序逐行对照）
- 许可：MIT

## 夹具生成方式（可复现）
- `src/test/resources/pi/theme-tokens-golden.json`：node 跑 pi-tui `parseColor` + `colorToHex`，token 表与 fallback 取自 `theme-tokens.js`
- `src/test/resources/pi/footer-golden.json`：node 直接 new pi 的 `FooterComponent`（假 session / footerData），`render(width)` 后去 ANSI
- `src/test/resources/pi/term-format-golden.json`：node 直接 new `UserMessageComponent`、`AssistantMessageComponent`、`ToolExecutionComponent`
  （`createToolDefinition` 内置工具 + `setKeybindings(new KeybindingsManager())`），`render(80)` 后去 ANSI 与行尾空格

## 字体
- JetBrains Mono：只读引用 `core/ui/src/main/res/font/jetbrains_mono_*.ttf`（来源与许可已在 core/ui 的 NOTICE 中）
