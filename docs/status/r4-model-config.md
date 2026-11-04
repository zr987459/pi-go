# r4/model-config：模型配置极简化 + 修「配好用不了」

状态：进行中（07:35 WIP 推送）。PR #29 审查 I1/I2/N1–N6 修复中；用户 07:14 改要求：I1 改为**热加载**。

已做（WIP）：
- 核实 pi 1.5.0 可热加载：`AuthStorage.read` 按文件修订号重读 auth.json；扩展 API `ctx.modelRegistry.refresh()` 重读 models.json 并重算可用模型。
- pigo-bridge 新增 `/pigo:models-refresh`；`PiBridge.refreshModels()`；Key 以字面值写 auth.json（600，`pigoManaged` 标记），不再进进程环境；
  `CredentialReloader`：先热加载，做不到（桥接不可用 / 凭据只能进环境）才退回「生成中等本轮结束再重启」。
- I2 `QuickSaveRunner`（App 作用域）；N1–N5；N6 UI 测试移到 app androidTest，走真实 QuickModelSetup。
- realpi 新增 `hotReloadNewKeyWithoutRestartWhileGenerating`。

07:40：本地 `:core:store`、`:core:engine`、`:app`、`:feature:{chat,models,settings}` 单测全过，`:app:compileDebugAndroidTestKotlin` 通过。
旧单测已改为校验 auth.json 字面 Key + `pigoManaged` + 600；日志脱敏改经 `LaunchEnvProvider.knownSecrets()`（含只在 auth.json 的 Key，热加载后立即生效）。

下一步：本地真 pi 跑 RealPiBridgeIntegrationTest（含热加载用例）；等 CI（android.yml 模拟器 + realpi.yml）全绿；更新 PR #29 描述逐条对应 I1/I2/N1–N6。

根因：① AppGraph 构造 RealModelPanels 没传 onCredentialsChanged（默认空操作），保存 Key 后不重启 pi，而 Key 只在 pi 启动时经环境变量注入（pi docs/models.md:217）；② 只写 settings.json 默认模型，续接会话仍用会话里的旧模型，没发 set_model。

计划：
1. 阶段 1：追链路（设置保存 → ~/.pi/agent/{models,auth,settings}.json 的路径与 schema → 引擎是否重读 → 实际用的 provider/model），找出根因（文件:行号）；研究 Pi-Web 配模型流程；写 docs/model-config-r4.md。
2. 阶段 2：先修根因，再把「设置 › 模型」改成：选服务商 → 填 Key → 自动拉模型 → 选默认，「测试连接」给中文成败；自定义只需 Base URL + Key；高级项折叠；保存后当前引擎立即可用。删被替代的界面代码。
3. 测试：配置写入格式单测、成败分类单测、模拟器主流程、realpi 用本地假 OpenAI 兼容服务端验证。
4. android.yml nightly 另传 app-debug-latest.apk；THIRD_PARTY_NOTICES 记 Pi-Web。
