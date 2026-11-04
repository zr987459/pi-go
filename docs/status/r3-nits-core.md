# r3/nits-core：积压 Nit（core/engine + core/session）

状态：进行中（13:1x）。A1–A3、B 代码与测试已写（未编译）；C、D 待做。

计划：
- A1 桥接命令按请求 id 对应，超时后迟到回复丢弃并记日志；A2 tree-nav 迟到成功仍回填原文/刷新状态；A3 switch 对账路径规范化后比较。
- B 查 RealPiBridgeIntegrationTest.treeNavChangesLeafKeepsSessionFile 偶发超时根因并修，CI 重跑 ≥3 次。
- C1 landModelPanel 先弹栈提交：成功关表单、失败才压回；C2 切换中树换实例也能关当次的树（按 id/来源）；C3 停止失败只提示一次；C4 旧路径带图消息退回草稿保留图片。
- D 核实 CI testDebugUnitTest 是否覆盖 core/contract、core/protocol 纯 JVM 单测，缺则最小补。
- 每条加测试，开 PR 到 main。
