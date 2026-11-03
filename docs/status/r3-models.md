# r3/models 状态

状态：**进行中**（最后更新 2026-11-04 ~08:10 CST）

基线：PR #15 已合入 main；已 POST /merges 把 main(de09f66) 合进 r3/models → d387c26。尚未开 PR。

## 已完成（已推送）
- `core/store/FileSessionRepository`：新增 `deleteWithUndo(guestPath)`（仍移进回收目录，返回契约 `PendingDelete`，5 秒窗口）、`undoLastDelete()`、`undoDelete(pending)`（回到原路径原文件名）、`UNDO_WINDOW_MILLIS`，构造参数加可注入 `clock`。测试 `core/store/src/test/.../UndoDeleteTest.kt`（5 条）。
- 删汇率（X2）：`ModelPrefsStore` 去掉换算字段（旧文件的键读取忽略、保存丢弃）；`RealAppSettingsStore.costDisplay` 恒为默认、`setCostDisplay` 空操作；`NetworkAndCostScreen` 已删；`PickerLogic.priceText(cost)` 只显示美元（新增 `usd()`）；`ModelPicker` 不再读费用设置；`CatalogAndEnvTest`、`PickerLogicTest` 同步。
- 范围外最小改动：`app/.../nav/WiredPages.kt` 的 `NetworkPage` 不再引用已删的 `NetworkAndCostScreen`（改为一行说明文字；路由由 r3/app 删除）。
- `feature/models/ProviderSearch.kt`：id / 显示名 / 拼音首字母搜索（内置常用汉字首字母表）；测试 `ProviderSearchPinyinTest`（42 服务商可见、「qw」只剩 qwen、ty/zp/xm）。

- `feature/settings/ModelsSettings.kt`（设置根节点只有「模型」一组的结构 + 页底指引）；测试 `SettingsOnlyModelsTest`（3 条）。**界面 `SettingsScreen` 尚未改成按此结构渲染。**

- `feature/models/RealModelPanels.kt`：实现契约 `ModelPanels`（模型面板全部/常用/最近、服务商列表含已连接与拼音搜索、服务商表单、OAuth 面板、自定义服务商表单、退出登录）；`onPanelEvent` 处理选择 / 搜索 / 星标（`enabledModels`）/ 设为默认（default*）/ 刷新 / 提交 / 发现 / 测试 / OAuth code。生成中 `set_model` 照发并提示「下一轮生效」；未配置模型点选 → 服务商表单 → 保存 Key 或 OAuth 完成后自动选回（`pending`）。测试 `RealModelPanelsTest`（8 条）、`ModelPanelsRenderFixtureTest`（两种形态参考夹具渲染器）；本地 kotlinc + JDK17 跑过 12/12 绿。

## CI
- 302ac2b（含以上全部代码）run 41740714769：构建 debug APK ✅、W3 仪器测试编译 ✅、单元测试（全部模块）✅；「编译全部仪器测试」在本状态推送时仍在跑（本次推送会取消它，看新 run）。

## 契约变更请求（给 r3/core）
- `contract/Catalog.kt` 的 `CostDisplaySettings`（showCny/usdToCny/DEFAULT_USD_TO_CNY）与 `AppSettingsStore.costDisplay/setCostDisplay` 应删除（全仓 grep cny 验收依赖此项与 r3/app 删 feature/usage）；`FakeCatalog`、`ExtrasFakesTest` 同步。
- `SessionRepository` 接口可加 `deleteWithUndo/undoDelete`（现在只在实现类上，`SessionCatalog` 实现需持有 `FileSessionRepository` 具体类型）。

## 下一步（按顺序）
1. 看 CI（GET /actions/runs?branch=r3/models），修编译 / 测试失败。
2. `feature/settings`：（结构与测试已完成）把 `SettingsScreen` 公开签名保留（app 在用），内部改为只渲染模型设置、Material 默认主题；页底「pi 的其他设置在 ⌘ › pi 设置」。
3. 删 `feature/settings/ui/DshComponents.kt`（先确认 SettingsScreen/RomGuide/BackgroundGuide 不再引用）；回收站浏览页（feature/settings 内若有）。
4. ~~`feature/models/RealModelPanels`~~（已完成）（实现契约 `ModelPanels`，依赖 `ModelCatalog`/`AuthManager`/`CustomProviderStore`/`EngineClient`），产出 SelectList/Form/Text；生成中 set_model 提示「下一轮生效」；未配置模型点选 → providerForm → 配好后自动选回（复用 PendingModel 思路）；单测用契约 Fake。
5. 等 #15 合入 → POST /merges {base:r3/models, head:main} → CI 绿 → 开 PR（标题 r3/models…）。
