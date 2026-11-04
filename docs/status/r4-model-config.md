# r4/model-config：模型配置极简化 + 修「配好用不了」

状态：进行中（06:22）。根因已定位（见下），QuickModelSetup + 极简页 + 单测已写，本地编译/单测中。

根因：① AppGraph 构造 RealModelPanels 没传 onCredentialsChanged（默认空操作），保存 Key 后不重启 pi，而 Key 只在 pi 启动时经环境变量注入（pi docs/models.md:217）；② 只写 settings.json 默认模型，续接会话仍用会话里的旧模型，没发 set_model。

计划：
1. 阶段 1：追链路（设置保存 → ~/.pi/agent/{models,auth,settings}.json 的路径与 schema → 引擎是否重读 → 实际用的 provider/model），找出根因（文件:行号）；研究 Pi-Web 配模型流程；写 docs/model-config-r4.md。
2. 阶段 2：先修根因，再把「设置 › 模型」改成：选服务商 → 填 Key → 自动拉模型 → 选默认，「测试连接」给中文成败；自定义只需 Base URL + Key；高级项折叠；保存后当前引擎立即可用。删被替代的界面代码。
3. 测试：配置写入格式单测、成败分类单测、模拟器主流程、realpi 用本地假 OpenAI 兼容服务端验证。
4. android.yml nightly 另传 app-debug-latest.apk；THIRD_PARTY_NOTICES 记 Pi-Web。
