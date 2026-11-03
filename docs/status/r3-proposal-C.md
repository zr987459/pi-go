# r3 方案 C 状态

**状态：完成**（2026-11-04 06:3x CST）

- 方案文档：docs/proposals/r3-C.md（方案提交 c339ff3）。
- 内容：§1 保留/删除/改造清单；§2 对照 docs/pi-features.md §1–§13 逐项覆盖（终端 / UI 入口和动作 id）；§3 终端形态；§4 UI 形态（极简，含线框）；§5 切换与共享层（SessionHost + PiActions + pigo-bridge）；§6 模型配置；§7 五条并行工作流（r3/host、r3/term、r3/calm、r3/models、r3/app）、删除规则、两项硬性要求的可执行验收；§8 复用与风险。
- 与清单不同的取舍：非模型的 pi 设置放进 ⌘「pi 设置」（即 pi 的 /settings），设置页只留模型；UI 形态的 Mermaid 图形渲染放 P2；回收站改为 5 秒撤销。
