# r3/models 修复第 1 轮（PR #19）状态

- 2026-11-04 08:23 CST：**已开始**。在 r3/models 分支上直接修，不开新 PR。

## 计划（对应审查 issuecomment-6618930658）
1. 撤回 app/** 越界改动；NetworkAndCostScreen 改 @Deprecated 保留，交接 r3/app。
2. 设置页按 DESIGN-r3 §6.1–§6.4 重做（常用模型、已连接、拼音搜索 42 服务商、自定义服务商、高级），补界面测试。
3. RealModelPanels：页签中文标签解析崩溃、键帽 actionId 无处理 → 修 + 测试。
4. 编辑自定义服务商保留推理/看图能力；测试连接不落盘 → 修 + 测试。
5. Nit：搜索不重置页签；设为默认原子写含 defaultThinkingLevel；Key 密文/不留 OverlayStack；残留文案列出交接。
6. CI 绿，更新 PR 描述。

## 进度
- [ ] 读审查、DESIGN-r3 §6/§8、前任 status
- [x] 读审查、DESIGN-r3 §6/§8、前任 status（08:35）
- [x] Important 1：WiredPages.kt 恢复为 main 原样（撤回越界）；NetworkAndCostScreen 恢复（只留代理，删汇率部分），标 @Deprecated，交接 r3/app 删除（08:40）
- [x] Important 3/4 + Nit 1–3 代码（08:47）：tabOf 容错、键帽 actionId=面板事件 key（value=高亮项）、搜索保持页签、设为默认三键写入失败回滚、Key 字段 secret=true（契约 FormField 加 secret 默认 false，范围外最小改动）、Key 只存内存 pendingKeys 不进表单值、保存时保留原模型能力（mergeModels + 存储层 capability 未指定语义）、测试连接不落盘（改用 discover 临时探测）。测试：RealModelPanelsFix1Test（10 条）+ RealModelPanelsTest 同步（08:55）
- [ ] **Important 2（设置页按 §6.1 重做 + 界面测试）——未开始，下一位工作者从这里接手。** 具体做法（已核对过约束）：
  - 约束：`feature/settings/build.gradle.kts` 归 r3/core，**不能加依赖**（没有 Robolectric，也不能依赖 `:feature:models`）；只能依赖 `:core:contract`、`:core:ui`。仓库里有 androidTest 的 compose-ui-test，但 CI 模拟器 job 只跑 UiCoverageTest/TermCoverageTest/AppCoverageTest/FormSwitchStreamingTest，其他 androidTest 只编译。
  - 方案：新增 `feature/settings/.../SettingsModel.kt`：纯函数 `suspend fun buildSettingsModel(panels: ModelPanels, piSettings: PiSettings?, query: String): SettingsModel`（契约类型，`contract.fake` 里有 FakeModelCatalog/FakeAuthManager/FakeCustomProviderStore/FakePiSettings 可在 JVM 测）。分区顺序取 `ModelsSettings.root()`：默认模型（defaultProvider/defaultModel/defaultThinkingLevel）、常用模型（`panels.modelPicker(SCOPED)` 条数）、已连接 / 全部服务商（42，拼音搜索）/ 自定义（取 `panels.providerList(query)` 的三个组）、高级（ADVANCED_ITEMS）。
  - `SettingsScreen` 公开签名保留（app 在用），**新增可选参数** `panels: ModelPanels? = null`、`piSettings: PiSettings? = null`；有 panels 时 LazyColumn **逐行渲染 SettingsModel.rows**（testTag `settings.section:<key>`、`settings.row:<key>`，搜索框 `settings.search`），点行 → `panels.onPanelEvent(...)` 返回的 OverlayModel 用本模块一个简单的 Form/SelectList 底部面板画（密文字段用 PasswordVisualTransformation）。panels 为 null 时保持现状（r3/app 装配前不坏）。
  - 测试：JVM `SettingsOnlyModelsTest` 改为对 `buildSettingsModel` + Fakes 断言（根只有「模型」一组、6 个分区按序、全部服务商 42、搜索「qw」只剩 qwen、自定义含「＋ 添加」、已连接含 deepseek）；并新增 androidTest `SettingsScreenUiTest`（createComposeRule，断言 testTag 存在、输入 qw 后只剩 qwen 行）——在 PR 描述写明它目前只在 CI 编译，请 r3/core 把它加进模拟器 job。
  - r3/app 交接（PR 描述）：装配时把 `graph` 的 RealModelPanels / PiSettings 传给 SettingsScreen。
- [x] Nit 4 本线部分（09:02）：`ModelPicker.kt:59` 注释去掉人民币；`BackgroundGuide.kt:220` 改为「以后再说」。剩余；契约 CostDisplaySettings、feature/usage 汇率、「修复运行环境」入口 → PR 描述交 r3/core、r3/app。
- [ ] 更新 PR #19 描述（逐条处理说明 + 交接：NetworkAndCostScreen 由 r3/app 删；FormField.secret 是契约范围外最小改动，r3/calm/r3/term 渲染器需遮挡 secret 字段）。
- [ ] 看 CI：最新推送 bed90a3 的 push/PR run（GET /actions/runs?branch=r3/models）。本地没编译过（feature/models 新测试、RealCustomProviderStore 改动），CI 红先修。
- 08:28 本会话即将被切断：Important 1、3、4 与 Nit 1–4（本线部分）已推送；Important 2 与 PR 描述、CI 核对留给下一位（见上面的具体做法）。

## 接手（第 2 位修复工作者）
- 2026-11-04 08:32 CST：**已开始**，基于 3fccae9。
- 计划：① 看 3fccae9 CI，红了先修；② 普通 merge main（含 r3/core PR#16）；③ Important 2 按上面方案做（SettingsModel 纯函数 + SettingsScreen 逐行渲染 + JVM 界面模型测试 + androidTest 编译）；④ 更新 PR #19 描述；⑤ CI 绿后写完成。
