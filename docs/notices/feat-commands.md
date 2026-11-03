# feat/commands（W4）移植来源

- agegr/pi-web@025e24e（npm @agegr/pi-web 0.14.2），MIT，Copyright (c) 2026 agegr —— 移植/改写：feature/usage/.../logic/CompactionSummary.kt ← lib/compaction-summary.ts:1-34
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 移植/改写：feature/commands/.../data/AutoTitle.kt（SessionTitle：提示词、对话摘录截断、结果清洗） ← lib/session-title.ts:13-48、:131-210、:225-270
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 思路移植（续读点 + 头部/采样哈希校验）：feature/commands/.../data/SessionScanner.kt ← lib/session-list-scanner.ts:44-66、:140-260
- agegr/pi-web@025e24e，MIT，Copyright (c) 2026 agegr —— 思路移植（只认成功的 write/edit 结果、路径去重保序；耗时格式）：feature/usage/.../logic/TurnSummary.kt ← lib/turn-written-files.ts:83-127、lib/turn-duration.ts:56-64
- earendil-works/pi @earendil-works/pi-coding-agent 1.5.0，MIT —— 对照接口（未拷代码）：edit 工具参数与 details.patch（dist/core/tools/edit.d.ts）、提示模板格式（docs/prompt-templates.md）、settings.json 资源排除语法（docs/settings.md「Resources」）、CLI 参数（docs/cli.md）
- 未使用 dsh-Go 的代码、名称、标识或吉祥物。
