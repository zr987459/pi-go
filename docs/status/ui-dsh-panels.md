# ui/dsh-panels 状态（工作者 B：抽屉内容 + 设置 + 安装引导）

**状态：已开始**（2026-11-03 14:40 CST）

## 计划
1. 通读 dsh-Go 的抽屉（会话列表/搜索/分组/新建/当前高亮）、设置页（模型/Key、后台保活、ROM 引导）、首次引导/安装页及其主题与深色配色。
2. 主题提交前：只做与主题无关的结构调整（feature/sessions、feature/settings、feature/onboarding 的 Composable 布局拆分）。
3. ui/dsh-style 出现 `theme:` 提交后普通 merge，用 A 的主题/组件对齐 dsh-Go：卡片分组、列表项、开关/输入框、图标、间距、圆角、动画、深色模式；缺的通用组件放在本模块（feature/settings/ui/DshComponents.kt 等），不改 core/ui。40 分钟内未出现则先用 MaterialTheme token。
4. 每个改过的界面加 @Preview（浅色+深色）；能出截图则放 docs/ui-shots/。
5. 本分支 CI 绿 → 再 merge ui/dsh-style → CI 绿 → 普通 merge 回 ui/dsh-style → 确认 CI 绿 → 两个状态文件写“panels 已合入 ui/dsh-style @<sha>”。

范围：只改 feature/sessions/**、feature/settings/**、feature/onboarding/**（含 res/测试）、本文件、docs/ui-shots/**、THIRD_PARTY_NOTICES.md 一行来源。不改逻辑/ViewModel 状态机/core/**/app nav。
