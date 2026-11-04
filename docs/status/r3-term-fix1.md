# r3/term 审查第 1 轮修复（fix1）

状态：已开始（08:35 CST）

## 计划
1. 普通 merge main（含 r3/core PR#16）进 r3/term
2. Important 1：TermCoverageTest ! 键帽 performScrollTo 异常 → 模拟器 job 绿
3. Important 2：! 命令运行中 Esc 停止（bashRunning 接入）+ 测试真实断言
4. Important 3：流式贴底、切换形态后贴底 + 切换夹具断言
5. Important 4：外接键盘仪器测试、1000 delta 慢帧测试、阅读位置断言
6. Important 5：@ 文件搜索注入 WorkspaceSearch；页眉列上下文文件/技能/扩展/模型范围
7. Nit 逐条
8. CI 全绿后 status 写完成
