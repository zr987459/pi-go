# r4/model-config：模型配置极简化 + 修「配好用不了」

状态：已开始（2026-11-05 06:15 CST），基线 main@7bdb83d。

计划：
1. 阶段 1：追链路（设置保存 → ~/.pi/agent/{models,auth,settings}.json 的路径与 schema → 引擎是否重读 → 实际用的 provider/model），找出根因（文件:行号）；研究 Pi-Web 配模型流程；写 docs/model-config-r4.md。
2. 阶段 2：先修根因，再把「设置 › 模型」改成：选服务商 → 填 Key → 自动拉模型 → 选默认，「测试连接」给中文成败；自定义只需 Base URL + Key；高级项折叠；保存后当前引擎立即可用。删被替代的界面代码。
3. 测试：配置写入格式单测、成败分类单测、模拟器主流程、realpi 用本地假 OpenAI 兼容服务端验证。
4. android.yml nightly 另传 app-debug-latest.apk；THIRD_PARTY_NOTICES 记 Pi-Web。
