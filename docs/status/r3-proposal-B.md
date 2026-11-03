# r3 重新设计方案 B — 状态

状态：**完成**（2026-11-04 CST）

## 成果
- 方案文档：docs/proposals/r3-B.md（约 1100 行 / 34 KB）。
- 依据：docs/pi-features.md 全条目 + 仓库 main@478941df 现有代码。

## 方案要点
- 架构：一个 `pi --mode rpc` 进程 + 一份会话状态（SessionController）+ 两层渲染（终端形态 / 极简 UI 形态），切换只换渲染层，不重启引擎、不丢上下文。
- 设置只留「模型配置」；其余 pi 键进「高级(pi 设置)」按 settings.json schema 生成控件。
- 删：吉祥物、汇率/人民币、文件浏览器、Git 页、自动起名、每轮统计卡；保留 pi 原生的 HTML 导出等。
- RPC 缺口（settings/login/resume/tree/scoped-models/import/share/trust/mcp/reload…）在 Kotlin 实现一次，两形态共用；原地切分支/标签/热重载用随 App 启动的 `pigo-bridge` 扩展，生成中可执行。
- 逐项对照 pi-features.md §1–§13 全覆盖，异议仅 4 处（均为更安全的收敛，不减功能）。
- 工作流：前置 r3/contract(scaffold) + 6 条并行 r3/{shared,terminal,minimal,models,prune,switch}，文件范围互不重叠；删除集中在 r3/prune 单一工作流，导航入口由 r3/switch 整合期摘除。
- 两条硬性要求落到可执行验收：UiCoverageTest（按 pi-features.md 逐条断言 UI 入口存在）、FormSwitchDuringStreamTest（生成中切换：引擎 PID 不变、流不断、草稿与扩展弹层保留）。
