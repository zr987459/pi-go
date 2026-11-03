# feat/wiring（W5）状态

> **审查第 1 轮修复进行中**（2026-11-03 17:42 CST，接手续做）：修复1（extras 非 REAL 时传 null、隐藏依赖 extras 的入口）进行中；之后依次修 2（长按菜单旧值）、3（ModelPicker 回传 ModelRef）、4（M8 开关 + 会话导出）及 Nit。

> **等待 W1**（2026-11-03 17:3x CST）：PR #13 feat/engine-rpc 仍未合入 main（main@f9097eb）。合入后：merge origin/main → `ExtrasBindings.real()` 返回 W1 的 `EngineBindings.extras(engine)`（PiEngine 本身实现 EngineExtras）→ `BindingMode.extras = REAL` → `WorkspaceBindings` 当前会话 HTML 导出改走 `extras.exportHtml` → 「从这里分叉」随 messageMeta 可用 → 全量单测。

**状态：W2/W3/W4/UI 接线完成，会话面板已接 W4 SessionIndex；CI 绿（run 41610482453，2401c0e，含 `:app:assembleDebugAndroidTest`）；等 W1（PR #13）合入后切 extras 真实实现**（2026-11-03 17:3x CST，从 main@b54a507 切出）

## 计划（按 docs/feature-gap.md §3.2 W5）
1. 读 §3.2 W5 条目、各模块入口，确定接线点。
2. AppGraph / *Bindings：W2（feature/models、RealAuthManager）、W4（CommandsGraph、UsageController）、UI 改版接入；W1/W3 未合入前 BindingMode 用 Fake（EngineExtras、Workspace）。
3. 顶栏、输入框（/ 候选、+ 附件）、抽屉（文件、会话树、技能与模板、用量）、设置入口换成 W2 新服务商页、会话内切模型/思考强度。
4. 前几轮留下的 Nit：抽屉返回键、外壳只订阅 sessionFile、服务商表单回填 savedFields、旧设置入口替换、setModel/setThinking。
5. docs/notices/*.md 并入 THIRD_PARTY_NOTICES.md。
6. 仪器冒烟用例；docs/testing/ 真机验收清单；单测；CI。
7. 每次推送前 merge origin/main；W1/W3 合入后切真实实现。

## 进度
- 17:3x 续做：抽屉会话面板（feature/sessions `SessionsScreen(index = graph.commands.sessionIndex)`）接 W4 SessionIndex——搜索框走全文搜索（名字 + 用户消息，停顿 250ms，结果带命中片段，索引未返回时先用本地筛选），无查询时「置顶」节 + 按工作目录分组，长按菜单「置顶 / 取消置顶」，刷新、删除、恢复后 `rescan`；不传 index 时保持原来的按日期分组。纯函数 `DrawerGrouping.indexed` / `mergeSearch` 加单测。
- 17:2x CI：「编译 W3 仪器测试」一步追加 `:app:assembleDebugAndroidTest`，`WiringSmokeTest` / `AppSmokeTest` 编译通过（run 41610482453 success）。
- ModelPicker 自动选回用户点的模型：W2 契约只回传服务商 id，不改 feature/models；提议（`Click.Configure(provider, ref)` + 可选 `onConfigureModel`）已追加到 PR #14 描述，目前仍选该服务商第一个可用模型。
- 已 merge origin/main（f9097eb，W3 feat/files PR #9）→ 74dc68b；BindingMode.workspace 切 REAL（WorkspaceServices），抽屉「文件」→ FilesHost，「+」→ AttachmentSheet，轮末汇总点文件 → 文件预览，FilesRuntime 在 Application 启动时登记。
- 导航：设置首页（服务商与登录 / 代理与费用 / 运行环境与高级 / 导出）、服务商页与配置页（回填 savedFields）、自定义端点、会话树、技能与模板、用量、文件（占位）；模型选择弹层 + 用量弹层；审查 Nit ①–⑤ 已处理。
- THIRD_PARTY_NOTICES.md 已并入 docs/notices（feat-commands、feat-models、feat-modules）。
- 仪器冒烟 WiringSmokeTest（①–⑥，缺条件时 Assume 跳过）；AppSmokeTest 按新入口更新；真机清单 docs/testing/feat-wiring-device-checklist.md。
- 聊天页插槽：时间线附加项（按 anchorKey）、`/` 候选面板、消息长按菜单（复制、复制 Markdown、从这里分叉）、待发图片胶囊、上下文小环 → 用量弹层。
- 依赖装配：app 依赖 core:catalog/core:workspace/feature:models/files/commands/usage；AppGraph 新增 extras（Fake，等 W1）、models（REAL，CatalogGraph）、workspace（Fake，等 W3）、commands（REAL，CommandsGraph，传 launchEnv）；ChatController 带上 extras 与草稿存储。

## 遗留（2026-11-03 17:3x CST）
- **等待 W1（PR #13）**：合入后切 extras REAL、HTML 导出走 `extras.exportHtml`、「从这里分叉」可用，WiringSmokeTest ②④⑤ 才真正运行；PR #13 也改了 `app/di/EngineBindings.kt`，merge 时注意。
- **W2 缺口**：ModelPicker 只回传服务商 id，自动选回的是该服务商第一个可用模型（提议见 PR 描述）。
- 会话归档（`setArchived`）、未读标记、自动命名结果显示尚未接界面。
- 「会话默认设置（M8）」W2 没有独立页面，未接。
- 本地只能跑 JVM 单测（aarch64 沙箱，无 Android SDK）：本轮 feature/sessions DrawerGroupingTest + SessionFormatTest 16/16 通过；Android 编译与 androidTest 编译靠 CI。

