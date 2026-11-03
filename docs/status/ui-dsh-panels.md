# ui/dsh-panels 状态（工作者 B：抽屉内容 + 设置 + 安装引导）

**状态：阶段 2 代码已推，等 CI**（2026-11-03 15:20 CST）

## 进度
- [x] 14:40 已开始；14:45 通读 dsh-Go 完成
- [x] 已普通 merge ui/dsh-style（含 A 的 `theme:` 6395c89）→ 9427e8e
- [x] 抽屉会话面板（9eb55db）、设置 + 后台引导 + 安装引导（1827177）按 dsh-Go 改版，均用 A 的 `DshTheme`/`AppIcon`/`GlassSurface`/`AppShape`/`AppMotion`
- [ ] CI 绿：**当前被 ui/dsh-style 自身的编译错误挡住**——`core/ui/.../AppIcons.kt:164`、`:450-451` 用了关键字 `package` 作属性名（需加反引号），`:core:ui:compileDebugKotlin` 失败，B 的模块还没编到。core/ui 归 A，B 不改；A 修好后 B 立刻再 merge。
- [ ] 再次 merge ui/dsh-style → CI 绿 → 合回 ui/dsh-style

## 每个界面改成了什么样（无法本地出图，文字说明；预览见各模块 `*Previews.kt`，浅色 + 深色）

本机是 arm64、没有 Android SDK，跑不了 Paparazzi/Roborazzi/模拟器，所以 `docs/ui-shots/` 暂无截图；每个界面都有 `@Preview`（浅/深），在 Android Studio 打开即可看。

### 抽屉会话面板 `feature/sessions/SessionsScreen.kt`（对照 dsh-Go `shell/sessions_drawer.dart`）
- 顶部品牌行：左「pi-go」字标（AppType.Wordmark），右侧**实心主色「新对话」胶囊**（36dp，铅笔图标 + 文字）；padding 20/14/10/10。
- **搜索胶囊**：40dp 玻璃胶囊（pill 层填充 + 0.5dp 发丝描边），放大镜 + 占位「搜索对话」，就地按标题/预览/目录筛选，有内容时出现清除钮。
- **筛选胶囊**：「全部」「回收站 N」两个 CapsuleChoiceChip（选中=onSurface 实底反白字，未选中=5%/7% 淡底，160ms 颜色过渡）。
- **会话列表**：按 今天 / 昨天 / 本周 / 更早 分组，组标题为 12sp 字距 0.8 的眉题（三级灰）；每行 44dp、14dp 圆角，**当前会话铺 primary 9%（深色 18%）底并加粗**，160ms 淡入；只显示标题（与 dsh-Go 一致）。**长按**弹出底部操作面板（标题 + 目录/时间 + 红色「删除」），删除保护逻辑不变（`SessionFormat.deleteGuard`）。
- 回收站：同样的行，右侧「恢复」。读取中显示 dsh-Go 的骨架列表（2.4s 呼吸）；空列表居中偏上的提示。
- 底部身份栏：薄荷色圆标「本机 / 会话保存在本机」+ 44dp 玻璃圆钮「刷新」「设置」（设置钮需要导航层传 `onOpenSettings`）。
- 本组件不画面板底色：放进抽屉时由 A 的抽屉容器提供玻璃面板和右侧 28dp 圆角。
- 新增可选参数 `onOpenSettings: (() -> Unit)? = null`（默认不显示设置钮，旧调用不受影响）。

### 设置页 `feature/settings/SettingsScreen.kt`（对照 `settings/settings_screen.dart`、`settings_widgets.dart`）
- 渐变背景（AppBackdrop），透明 Scaffold；顶部 dsh-Go 大标题「设置」（headlineLarge，padding 24/6/24/20）。
- 「模型与 Key」：眉题 + 警告色 12% 底的提示卡（盾牌图标）。
- 每类服务商一张 **20dp 圆角玻璃分组卡**（标题 labelLarge 三级灰在卡外，脚注写该类 Key 的说明）；每行 52/64dp：**32dp 彩色圆形图标**（已配置=钥匙/蓝，未配置=划掉的钥匙/湖青，「其他 OpenAI 兼容」=加号）、15sp 名称 + 说明、右侧 Key 末 4 位或「未配置」（警告色）+ 右箭头。点开仍是原来的 Key / 自定义端点对话框，输入框换成 dsh-Go 的 14dp 圆角填充样式，按钮换成 50dp 高、14dp 圆角的实心/描边按钮。
- 「默认模型」卡：「选择默认模型」行（右侧显示当前模型）+「测试连接」行，结果用 ✓/✗ 图标 + 成功/错误色。新增 `onModelClick: (() -> Unit)? = null`，默认打开原来的默认模型对话框，留给 features 工作流接完整模型选择器。
- 「后台运行」卡：一行入口，点开**全屏「后台运行」页**（见下）。
- 「运行环境与关于」卡：InfoRow（状态 / Ubuntu / Node.js / pi，值右对齐）+「修复运行环境」行；脚注写许可证。
- `settingsItemIndex` 随新布局更新（大标题 0、模型与 Key 1、默认模型 5），单测同步更新。

### 后台运行引导 `feature/settings/BackgroundGuide.kt`（对照 `settings/background_guide_page.dart`）
- 全屏页：左上 44dp 玻璃返回钮、大标题「后台运行」+ 副标题。
- 分组卡：「你的手机（厂商名）」→「允许自启动 / 后台活动」行（打开厂商页，失败时脚注提示）；「系统」→「电池优化 已关闭/未关闭」（成功/警告色，未关闭可点）；「保持在线」→ 编号步骤（24dp 主色容器圆里的序号）；「其他品牌」→ 可展开行（箭头旋转 180°、240ms 展开）；Android 12+ 子进程限制说明卡。
- `BackgroundGuideDialog`（首次进入聊天弹出）同样用这个全屏页，底部「设置好了」「以后再说」两个按钮，关闭即记为已提示。

### 安装引导 `feature/onboarding/OnboardingScreen.kt` + `OnboardingUi.kt`（对照 `onboarding/onboarding_scaffold.dart`、`local_install.dart`、`runtime_setup_page.dart`）
- 渐变背景，内容最宽 440dp 居中；顶部 120×3dp **分段步骤条**（检查 → 安装 → 完成，当前段 480ms 填满）。
- 未开始/已中止/未装完：dsh-Go 确认页——52dp 方圆主色容器图标头、headlineMedium 标题、说明；事实行（设备检查三项 ✓/✗、3 GB 空间、只装在本机；中止/损坏时一行警告色说明）；底部 50dp「开始安装/继续安装」。
- 安装中：**184dp 环形进度（ProgressHalo）**：呼吸光晕、14% 底环、圆头渐变进度弧，中间 40sp 细体百分比；标题「正在准备运行环境」、说明=当前步骤消息、元信息行「步骤 · 百分比 · 速度」；底部「可以切到后台或锁屏…」提示 +「中止安装」+「查看日志」。
- 失败：环变红 + 感叹号，标题「安装没有完成」+ 错误原因；底部「查看日志」「重试（换镜像）」并排。
- 完成/已就绪：环满 + 对勾，版本号；「进入聊天」。
- 日志改为底部弹层（代码井底色、等宽字）。ViewModel 状态机未动，只新增纯函数 `setupViewOf`、`overallFraction`（有单测）。

## 与 dsh-Go 还差什么（差距）
- 抽屉：没有置顶、文件夹、归档、多选、重命名、分享、未读/运行中/待确认状态点、工作区切换、DS Bot 行、远程主机切换（pi-go 没有这些状态或功能，按要求不做假功能）；搜索是就地筛选，不是 dsh-Go 的独立搜索页；没有玻璃背景模糊（Compose 版 GlassSurface 不做 blur）。
- 设置：dsh-Go 的设置索引/搜索、概览卡、外观/字号/语言/通知/权限等分组 pi-go 没有对应功能，未做；Key 编辑仍是对话框而不是 dsh-Go 的独立服务商页面。
- 引导：dsh-Go 的欢迎页（品牌动画）、API Key 引导页、外观选择、本地/远程选择没有对应流程（pi-go 只有一页安装向导）；品牌标识与吉祥物按用户要求不用。
- 截图：无法本地生成，见上面文字说明。

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
