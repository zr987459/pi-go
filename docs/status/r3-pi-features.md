# r3-pi-features 状态

- 状态：已开始（2026-11-04 06:08 CST）
- 任务：梳理 @earendil-works/pi-coding-agent 1.5.0 全部功能 → docs/pi-features.md

## 计划
1. npm pack 1.5.0，读 README/docs、dist 源码，跑 `pi --help`
2. 按章节写 pi-features.md：CLI/模式、RPC 命令与事件、斜杠命令、内置工具、会话、模型/服务商、技能/扩展/模板/上下文/主题、settings.json/环境变量、快捷键与输入语法、TUI 元素与配色
3. 每项标注 pi-go 现状 / 建议形态 / 理由；单列 pi-go 独有功能（候选删除）；文末摘要
4. 每 15 分钟推送一次部分成果
