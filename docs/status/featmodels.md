# W2 `feat/models` 状态

- 负责：W2（全服务商目录、全模型选择页、OAuth、会话内切模型与思考强度、models.json 完整编辑、代理）
- 依据：`docs/feature-gap.md` §2.1 M1–M5、M7、M9，§3.2 W2，§3.3
- 基线：已 merge `origin/feat/modules`（749d7b2，CI 绿）进本分支（49b3a2a，普通合并，无强推）
- 当前阶段：**修复1 进行中**（2026-11-03 16:35 CST 开始：merge main@cfa3725，修审查第 1 轮 3 个 Important + Nit）；之前：3 已开 PR #12，待审，更新于 2026-11-03 16:10 CST；已 merge main（W0 合入后，普通合并）

## 已完成并已推送
- `core/store`（本地单测 59 项全过）：
  - `BuiltinCatalog`：pi 1.5.0 目录快照（resources，42 个服务商、1592 个模型）与解析；
  - `ProviderSpecs`：42 个服务商的中文名、主 Key 变量、多变量字段（Bedrock、Azure、Cloudflare×2、Vertex）与校验；
  - `ProviderPresets` 改为由目录生成（41 个 Key 类 + 3 个自定义入口）；
  - `PiProviderConfigStore`：多字段保存/预填/清除；`env()` 一次注入多变量服务商全部变量；共用 Key 变量的服务商（moonshotai/-cn、opencode/-go、qwen-token-plan/-individual、cloudflare×2）在 auth.json 只写 `$PIGO_PK_<ID>` 引用（不含 Key，加 pi 同款锁）；代理（HTTP(S)_PROXY/NO_PROXY/NODE_USE_ENV_PROXY）；models.json 完整结构（4 种 api、name/reasoning/input/contextWindow/maxTokens/cost/headers，合并不丢字段）、删除端点；
  - `ModelDiscovery.discoverDetailed`：获取模型列表回填上下文/推理/看图；`ModelPrefsStore`（人民币开关、汇率默认 7.1、最近 5 个）；`KeyRedaction` 增加 OAuth 令牌形态脱敏。

## 已推送（第二批，c0a0f03）
- `core/catalog`：容器脚本 `pigo-catalog.mjs`、`pigo-auth.mjs`（调用 pi 自己的 OAuth 登录，JSONL 协议，凭据由 pi 写 auth.json 600）；`CatalogRepository`（按 pi 版本缓存）、`ModelIndex`、`OAuthLoginSession`、`Pkce`、`AuthService`、契约实现 `RealModelCatalog`/`RealAuthManager`/`RealCustomProviderStore`/`RealAppSettingsStore`、`CatalogGraph`。
- `feature/models`：模型选择页（分组、搜索、未配置跳配置、思考强度只在推理模型显示）、服务商列表与配置（Key、多字段、OAuth 浏览器/设备码/粘贴）、自定义端点编辑（获取模型列表回填）、代理与费用设置。
- `app/di/ModelsBindings.kt`：app 尚未依赖新模块，暂只给 Fake，真实装配留给 W5。
- 本地单测：core/store 59 项、core/catalog 18 项（4 项真 pi 集成默认跳过）、feature/models 7 项，全部通过；Compose 仪器测试已写（无模拟器，只编译）。

## 验收
- 真 pi 1.5.0 集成 4 项（目录导出、Copilot 设备码、Anthropic PKCE、DeepSeek 可用组）本机全过。
- Compose 仪器测试可编译，未在设备运行。

## 遗留（详见 PR #12）
- app 真实装配留给 W5；契约差异；真机 OAuth 回调与 `$PIGO_PK_*` 端到端未验证。
