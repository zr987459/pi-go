# r3/models 状态

状态：**进行中**（最后更新 2026-11-04 ~08:05 CST）

基线：r3/core-base 818aa93（PR #15 仍未合入 main；main = 0258e9d）。尚未 merge main，尚未开 PR。

## 已完成（已推送）
- `core/store/FileSessionRepository`：新增 `deleteWithUndo(guestPath)`（仍移进回收目录，返回契约 `PendingDelete`，5 秒窗口）、`undoLastDelete()`、`undoDelete(pending)`（回到原路径原文件名）、`UNDO_WINDOW_MILLIS`，构造参数加可注入 `clock`。测试 `core/store/src/test/.../UndoDeleteTest.kt`（5 条）。
- 删汇率（X2）：`ModelPrefsStore` 去掉换算字段（旧文件的键读取忽略、保存丢弃）；`RealAppSettingsStore.costDisplay` 恒为默认、`setCostDisplay` 空操作；`NetworkAndCostScreen` 已删；`PickerLogic.priceText(cost)` 只显示美元（新增 `usd()`）；`ModelPicker` 不再读费用设置；`CatalogAndEnvTest`、`PickerLogicTest` 同步。
- 范围外最小改动：`app/.../nav/WiredPages.kt` 的 `NetworkPage` 不再引用已删的 `NetworkAndCostScreen`（改为一行说明文字；路由由 r3/app 删除）。
- `feature/models/ProviderSearch.kt`：id / 显示名 / 拼音首字母搜索（内置常用汉字首字母表）；测试 `ProviderSearchPinyinTest`（42 服务商可见、「qw」只剩 qwen、ty/zp/xm）。

## 契约变更请求（给 r3/core）
- `contract/Catalog.kt` 的 `CostDisplaySettings`（showCny/usdToCny/DEFAULT_USD_TO_CNY）与 `AppSettingsStore.costDisplay/setCostDisplay` 应删除（全仓 grep cny 验收依赖此项与 r3/app 删 feature/usage）；`FakeCatalog`、`ExtrasFakesTest` 同步。
- `SessionRepository` 接口可加 `deleteWithUndo/undoDelete`（现在只在实现类上，`SessionCatalog` 实现需持有 `FileSessionRepository` 具体类型）。

## 下一步（按顺序）
1. 看 CI（GET /actions/runs?branch=r3/models），修编译 / 测试失败。
2. `feature/settings`：新 `ModelsSettingsSections`（纯逻辑：根节点只有「模型」一组：默认模型、常用模型、已连接、全部服务商(42)+搜索、自定义、高级）+ `SettingsOnlyModelsTest`；把 `SettingsScreen` 公开签名保留（app 在用），内部改为只渲染模型设置、Material 默认主题；页底「pi 的其他设置在 ⌘ › pi 设置」。
3. 删 `feature/settings/ui/DshComponents.kt`（先确认 SettingsScreen/RomGuide/BackgroundGuide 不再引用）；回收站浏览页（feature/settings 内若有）。
4. `feature/models/RealModelPanels`（实现契约 `ModelPanels`，依赖 `ModelCatalog`/`AuthManager`/`CustomProviderStore`/`EngineClient`），产出 SelectList/Form/Text；生成中 set_model 提示「下一轮生效」；未配置模型点选 → providerForm → 配好后自动选回（复用 PendingModel 思路）；单测用契约 Fake。
5. 等 #15 合入 → POST /merges {base:r3/models, head:main} → CI 绿 → 开 PR（标题 r3/models…）。
