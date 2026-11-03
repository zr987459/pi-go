# W2 `feat/models` 状态

- 负责：W2（全服务商目录、全模型选择页、OAuth、会话内切模型与思考强度、models.json 完整编辑、代理）
- 依据：`docs/feature-gap.md` §2.1 M1–M5、M7、M9，§3.2 W2，§3.3
- 基线：已 merge `origin/feat/modules`（749d7b2，CI 绿）进本分支（49b3a2a，普通合并，无强推）
- 当前阶段：**修复1 已完成，待复审**（2026-11-03 16:47 CST）。已普通合并 main@cfa3725（a2f8df8，无冲突）；审查第 1 轮 3 个 Important 与 4 个 Nit 全部处理，见下节

## 修复1（审查第 1 轮）
- Important #1 `AuthJsonLock`：残留判定 10s → 45s（pi 1.5.0 `acquireLockAsync` stale 30s、15s 刷新），等待 5s → 50s，删除前复查 mtime；`AuthJsonLockTest` 4 项。
- Important #2 `AuthService.startOAuth` 改 suspend + `withContext(io)`；`RealAuthManager.login` 加 `flowOn(io)`。
- Important #3 `presets()`（旧设置页）改用 `ProviderPresets.SINGLE_KEY`：由 `ProviderSpecs` 静态表生成，去掉要额外字段的 Bedrock/Azure/Cloudflare×2/Vertex 和只能登录的 openai-codex；全量 `ALL` 仍给新页面与 ModelCatalog。顺带修 Nit（旧页不再在主线程解析目录）。
- Nit 可选字段：`ProviderFormLogic.fieldsToSave`，回填过/改过的非密文字段留空提交空串 = 清除；`ProviderConfigScreen(savedFields=…)` 回填，W5 接 `RealAuthManager.savedFields`。
- Nit 代理：`HostProxy` + `discoverDetailed(proxy=…)`，自定义端点「获取模型列表」走 M9 代理（http/socks5、NO_PROXY；带账号密码的代理退回直连）。旧设置页走的 `ModelDiscovery.discover`（`StoreBindings` 调用，非本分支文件）仍直连。
- Nit 范围：`docs/status/featmodels.md` 保留（新文件、各分支各自状态，无冲突）。
- 本地单测（JDK 17 + SDK 35，aarch64）：core/store 66/66、core/catalog 14/14（另 4 项真 pi 集成默认跳过）、feature/models 8/8、core/contract 28/28。

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
