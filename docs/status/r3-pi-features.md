# r3-pi-features 状态

- 状态：**完成**（2026-11-04）
- 产出：`docs/pi-features.md`
- 依据：`npm pack @earendil-works/pi-coding-agent@1.5.0`（README、docs 48 篇、dist 源码），以及实际运行 `pi --help`；对照 pi-go main@527708e
- 06:09 新要求已落实：表格的「建议形态」改成两列「终端形态呈现」「UI 形态入口/呈现」，每个功能两种形态都覆盖；新增 §13「两种形态无缝切换」（一个 rpc 进程、同一份状态和草稿、只换渲染层）

## 要点
- RPC 33 个命令 pi-go 契约层全部建模；缺口在 pi 交互界面自己实现、RPC 里没有命令的功能（/settings、/login、/resume 列表、/tree 原地切换、标签、/scoped-models、/import、/share、/reload、/mcp、包、上下文文件、主题、@文件）
- 建议随 App 带一个桥接扩展（`-e`），用 navigateTree、setLabel、reload 补齐
- 候选删除见 §12：吉祥物（main 里没找到残留）、汇率、文件浏览器、Git 页面、自动起名、每轮统计卡、dsh 视觉风格；HTML 导出是 pi 原生功能，保留
