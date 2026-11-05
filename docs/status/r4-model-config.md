# r4/model-config：模型配置极简化 + 修「配好用不了」

状态：**完成，PR #29**（07:51）。代码 head 2a87542：CI 全绿（Android debug build 含模拟器 run 41935456748；真 pi 桥接 run 41935456734）；本机真 pi 1.5.0 RealPiBridgeIntegrationTest 7/7（含 hotReloadNewKeyWithoutRestartWhileGenerating）。

审查 I1 由热加载消除：Key 写 ~/.pi/agent/auth.json（600，pigoManaged），pigo-bridge `/pigo:models-refresh` → `ModelRegistry.refresh()`，生成中不重启不打断；凭据只能进环境的（Bedrock 多密文、代理）才退回重启并延后到本轮结束。I2、N1–N6 已修，对应关系见 PR 描述。
待办：等审查/合并（不由本分支自行合并）。已知限制：/resume 旧会话仍用会话里记录的模型。

根因：① AppGraph 构造 RealModelPanels 没传 onCredentialsChanged（默认空操作），保存 Key 后不重启 pi，而 Key 只在 pi 启动时经环境变量注入（pi docs/models.md:217）；② 只写 settings.json 默认模型，续接会话仍用会话里的旧模型，没发 set_model。

计划：
1. 阶段 1：追链路（设置保存 → ~/.pi/agent/{models,auth,settings}.json 的路径与 schema → 引擎是否重读 → 实际用的 provider/model），找出根因（文件:行号）；研究 Pi-Web 配模型流程；写 docs/model-config-r4.md。
2. 阶段 2：先修根因，再把「设置 › 模型」改成：选服务商 → 填 Key → 自动拉模型 → 选默认，「测试连接」给中文成败；自定义只需 Base URL + Key；高级项折叠；保存后当前引擎立即可用。删被替代的界面代码。
3. 测试：配置写入格式单测、成败分类单测、模拟器主流程、realpi 用本地假 OpenAI 兼容服务端验证。
4. android.yml nightly 另传 app-debug-latest.apk；THIRD_PARTY_NOTICES 记 Pi-Web。
