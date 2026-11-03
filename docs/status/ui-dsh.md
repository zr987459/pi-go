# ui/dsh-style 状态（工作者 A：主题 + 聊天主页）

## 进度
- [x] 14:40 已开始
- [x] 阶段 1：主题（`theme:` 提交）——**B 现在可以用了**，API 见下
- [ ] 阶段 2：聊天主页（渐变背景、顶部胶囊、空会话问候 + 启动引擎、底部输入框）
- [ ] 阶段 3：消息气泡、工具卡、Markdown/代码块、提示与动画、抽屉容器
- [ ] 阶段 4：CI 绿、@Preview（浅/深）、差距说明

调度方 14:40 追加要求已执行：**不用吉祥物**（没有入库任何吉祥物资源）；排版尽量一比一照 dsh-Go；顶栏「选择模型▾」/ 输入框「+」留 `onModelClick` / `onAttachClick` 回调。
说明：dsh-Go 的界面是 **Flutter（Dart）**，没有 Compose 代码可直接拷贝，这里按它的组件树、尺寸、配色、动效参数逐项翻译成 Compose。

## 给 B 的主题 API（`io.github.zr987459.pigo.core.ui`，依赖 `:core:ui` 即可）

页面骨架：
```kotlin
PiGoTheme { ... }                       // MainActivity 已包；Preview 里用 PiGoTheme(darkTheme = true/false)
AppBackdrop { /* 页面内容 */ }           // 渐变 + 光晕背景。页面根部用它；Scaffold 请设 containerColor = Color.Transparent
```

颜色：
- M3：`MaterialTheme.colorScheme.*`（primary = 黛青 #0F5E7E；深色 #8CB9D7；surface 系列为雾蓝/深海石板）
- 扩展：`DshTheme.colors.*` —— `backdropTop/Mid/Bottom`、`userBubble/onUserBubble`、`chrome/sheet/card/pill`（`GlassStyle`：fill、hairline、shadow、elevation）、`textFaint`、`textGhost`、`codeWell*`、`success/warning/danger/idle`、`scrim`；`DshTheme.isDark`

形状 / 间距 / 动效 / 字体：
- `AppShape.Xs(6) Sm(10) Md(14) Lg(20) Xl(28) Pill Sheet`；M3 组件已自动套用（`MaterialTheme.shapes`）
- `AppSpace.Xs..Xxxl`、`Gutter`(16)、`FloatInset`(12)、`ReadingWidth`(720)
- `AppMotion.Quick/Standard/Emphasized/SheetIn…`（毫秒）、`Glide/Enter/Exit/SheetRise`（Easing）、`PressScale`
- `MaterialTheme.typography.*`（dsh-Go 字号阶梯）、`MonoStyle`/`AppType.mono()`（JetBrains Mono）、`AppType.Greeting`、`AppType.Eyebrow`、`AppType.Wordmark`

组件：
- `GlassSurface(tier = GlassTier.Chrome|Sheet|Card|Pill, shape, onClick)`：玻璃面（带按压缩放）
- `GlassCard(onClick?) { ... }`：设置分组卡、列表卡
- `ChromePill(onClick) { ... }`、`ChromeIconButton(glyph, contentDescription, onClick)`：白色悬浮胶囊 / 圆按钮（48dp）
- `PrimaryPillButton(text, onClick)`（深青实心，例如「启动引擎」「开始安装」）、`TonalPillButton(text, onClick)`
- `StatusDot(tone)`、`StatusPill(tone, label)`，`StatusTone.Ok|Busy|Warn|Error|Idle`
- `SectionHeader("分组标题")`
- `AppIcon(AppIcons.xxx, contentDescription, size, tint)`：dsh-Go 图标字体（Phosphor）。角色名与 dsh-Go `AppIcons` 一致，例如 `menu`、`newChat`、`add`、`send`、`stopGenerating`、`voice`、`close`、`back`、`settings`、`copy`、`delete`、`search`、`expand`、`disclosure`、`model`；全部字形在 `DshGlyphs`
- 旧 API 仍在：`PlaceholderScreen`、`InfoCard`（换成玻璃卡外观）、`StatusChip`（@Deprecated，转 `StatusPill`）、`PiGoTypography`、`MonoStyle`

注意：`PiGoTheme(dynamicColor)` 默认改为 **false**（与 dsh-Go 一致）。
