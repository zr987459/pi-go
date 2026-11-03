# ui/dsh-panels 状态（工作者 B：抽屉内容 + 设置 + 安装引导）

**状态：阶段 2 代码已推，等 CI**（2026-11-03 15:20 CST）

## 进度
- [x] 14:40 已开始；14:45 通读 dsh-Go 完成
- [x] 已普通 merge ui/dsh-style（含 A 的 `theme:` 6395c89）→ 9427e8e
- [x] 抽屉会话面板（9eb55db）、设置 + 后台引导 + 安装引导（1827177）按 dsh-Go 改版，均用 A 的 `DshTheme`/`AppIcon`/`GlassSurface`/`AppShape`/`AppMotion`
- [ ] CI 绿：**当前被 ui/dsh-style 自身的编译错误挡住**——`core/ui/.../AppIcons.kt:164`、`:450-451` 用了关键字 `package` 作属性名（需加反引号），`:core:ui:compileDebugKotlin` 失败，B 的模块还没编到。core/ui 归 A，B 不改；A 修好后 B 立刻再 merge。
- [ ] 再次 merge ui/dsh-style → CI 绿 → 合回 ui/dsh-style

## 用户变更（14:40 生效）
1. **不要吉祥物**：不移植、不显示像素吉祥物（本分支未引入任何吉祥物资源）。
2. **照抄 dsh-Go 布局**：布局、组件结构、间距、配色、动画尽量 1:1 移植，不另行设计。注：dsh-Go 的 App 实为 **Flutter/Dart**（`app/lib/ui/**`），不是 Compose，所以这里是把 Flutter 组件逐个翻译成 Compose，尺寸/颜色/动画时长按原值照搬。
3. **只做 UI，不做新功能**：完整模型选择、会话内切模型/思考、文件浏览、附件、斜杠命令由 features 工作流做。本分支界面上若有“选择模型▾”或“+”入口，只留回调参数（如 `onModelClick`/`onAttachClick`），默认保持现有行为或提示“暂不支持”。

## dsh-Go 对照（已通读）
- 抽屉：`app/lib/ui/shell/sessions_drawer.dart`、`shell/drawer/session_tile.dart`、`shell/session_search_page.dart`（品牌行 + 实心“新对话”胶囊 36dp；玻璃胶囊搜索框 40dp；按 今天/昨天/本周/更早 分组的 eyebrow 标题；44dp 会话行、14dp 圆角、当前会话 primary 9%/18% 底 + 加粗；底部身份栏含“设置”；面板右侧 28dp 圆角）。
- 设置：`ui/settings/settings_screen.dart`、`settings_widgets.dart`、`background_guide_page.dart`（分组标题 labelLarge 三级灰 → 20dp 圆角玻璃卡；行高 52/64dp，32dp 圆形彩色图标，15sp 标题，chevron；后台引导=分组卡 + 编号步骤 + 可展开厂商行）。
- 引导：`ui/onboarding/onboarding_scaffold.dart`、`runtime_setup_page.dart`、`local_install.dart`（顶部 120×3dp 分段步骤条；184dp 环形进度 ProgressHalo + 中间百分比；headlineSmall 标题 + 灰字说明 + 元信息行；底部 FilledButton/查看日志；52dp 方圆图标头 + Fact 行）。


## 计划
1. 通读 dsh-Go 的抽屉（会话列表/搜索/分组/新建/当前高亮）、设置页（模型/Key、后台保活、ROM 引导）、首次引导/安装页及其主题与深色配色。
2. 主题提交前：只做与主题无关的结构调整（feature/sessions、feature/settings、feature/onboarding 的 Composable 布局拆分）。
3. ui/dsh-style 出现 `theme:` 提交后普通 merge，用 A 的主题/组件对齐 dsh-Go：卡片分组、列表项、开关/输入框、图标、间距、圆角、动画、深色模式；缺的通用组件放在本模块（feature/settings/ui/DshComponents.kt 等），不改 core/ui。40 分钟内未出现则先用 MaterialTheme token。
4. 每个改过的界面加 @Preview（浅色+深色）；能出截图则放 docs/ui-shots/。
5. 本分支 CI 绿 → 再 merge ui/dsh-style → CI 绿 → 普通 merge 回 ui/dsh-style → 确认 CI 绿 → 两个状态文件写“panels 已合入 ui/dsh-style @<sha>”。

范围：只改 feature/sessions/**、feature/settings/**、feature/onboarding/**（含 res/测试）、本文件、docs/ui-shots/**、THIRD_PARTY_NOTICES.md 一行来源。不改逻辑/ViewModel 状态机/core/**/app nav。
