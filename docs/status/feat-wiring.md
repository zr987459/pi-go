# feat/wiring（W5）状态

**状态：接线主体已推送，等待 CI**（2026-11-03 16:5x CST，从 main@b54a507 切出）

## 计划（按 docs/feature-gap.md §3.2 W5）
1. 读 §3.2 W5 条目、各模块入口，确定接线点。
2. AppGraph / *Bindings：W2（feature/models、RealAuthManager）、W4（CommandsGraph、UsageController）、UI 改版接入；W1/W3 未合入前 BindingMode 用 Fake（EngineExtras、Workspace）。
3. 顶栏、输入框（/ 候选、+ 附件）、抽屉（文件、会话树、技能与模板、用量）、设置入口换成 W2 新服务商页、会话内切模型/思考强度。
4. 前几轮留下的 Nit：抽屉返回键、外壳只订阅 sessionFile、服务商表单回填 savedFields、旧设置入口替换、setModel/setThinking。
5. docs/notices/*.md 并入 THIRD_PARTY_NOTICES.md。
6. 仪器冒烟用例；docs/testing/ 真机验收清单；单测；CI。
7. 每次推送前 merge origin/main；W1/W3 合入后切真实实现。

## 进度
- 导航：设置首页（服务商与登录 / 代理与费用 / 运行环境与高级 / 导出）、服务商页与配置页（回填 savedFields）、自定义端点、会话树、技能与模板、用量、文件（占位）；模型选择弹层 + 用量弹层；审查 Nit ①–⑤ 已处理。
- THIRD_PARTY_NOTICES.md 已并入 docs/notices（feat-commands、feat-models、feat-modules）。
- 仪器冒烟 WiringSmokeTest（①–⑥，缺条件时 Assume 跳过）；AppSmokeTest 按新入口更新；真机清单 docs/testing/feat-wiring-device-checklist.md。
- 聊天页插槽：时间线附加项（按 anchorKey）、`/` 候选面板、消息长按菜单（复制、复制 Markdown、从这里分叉）、待发图片胶囊、上下文小环 → 用量弹层。
- 依赖装配：app 依赖 core:catalog/core:workspace/feature:models/files/commands/usage；AppGraph 新增 extras（Fake，等 W1）、models（REAL，CatalogGraph）、workspace（Fake，等 W3）、commands（REAL，CommandsGraph，传 launchEnv）；ChatController 带上 extras 与草稿存储。
