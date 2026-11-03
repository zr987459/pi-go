# W2 `feat/models` 状态

- 负责：W2（全服务商目录、全模型选择页、OAuth、会话内切模型与思考强度、models.json 完整编辑、代理）
- 依据：`docs/feature-gap.md`（feat/gap）§2.1 M1–M5、M7、M9，§3.2 W2，§3.3
- 当前阶段：**0 准备**（2026-11-03 14:5x CST 开始）

## 计划
0. 通读 feature-gap、pi 1.5.0（`rpc-types.d.ts`、`@earendil-works/pi-ai` 的 `./models` 与 `./oauth` 导出、`docs/providers.md`）、Pi-Web 模型/登录相关代码、现有 `core/store`。
1. 等 `origin/feat/modules`（W0）出现 `contract/Catalog.kt` 等且 CI 绿 → 把 W0 合入本分支（merge，不 rebase）开工。
2. `core/catalog`：容器内导出脚本（node 读 pi-ai 模型目录 → JSON，按 pi 版本缓存）、目录解析、`ModelCatalog` 实现（目录 ∪ get_available_models ∪ models.json 自定义）、`AuthManager` 实现（Keystore Key + OAuth：device-code / PKCE，凭据写 auth.json 600，`pi auth check --json` 查状态）。
3. `core/store`：`ProviderPresets` 由目录生成；`PiProviderConfigStore` 完整 provider/model 结构与 4 种 api；`LaunchEnvProvider` 注入全部服务商变量（含多变量服务商）与代理变量。
4. `feature/models`：模型选择底部弹层（分组、搜索、能力与单价、灰显未配凭据、最近 5 个置顶、思考强度分段）、服务商列表页、Key / OAuth 登录页；费用美元/人民币（汇率可配，默认 7.1）。
5. `di/ModelsBindings.kt`、`docs/notices/feat-models.md`、DESIGN §3.10 增补说明（若该文件不在范围内则写入 PR 描述）。
6. 验收：单测、桌面真 pi 集成（导出脚本、DeepSeek 可用组）、Copilot device-code mock、PKCE 单测、Compose 测试；CI 绿；开 PR。

## 进度日志
- 14:5x 已开始，读文档中；W0 尚未出契约提交。
