# feat/models（W2）移植与第三方数据来源

- earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 数据：`core/store/src/main/resources/pigo/catalog-1.5.0.json` 由 `core/catalog/src/main/assets/pigo/pigo-catalog.mjs` 从 `dist/providers/all.js`（`builtinProviders()`）与 `dist/models.js`（`getSupportedThinkingLevels`）导出（42 个服务商、1592 个模型，SHA256 6c1fe7517ded12b37ea08bc9e4caa775c5220e86c7463ac79d67920da8e6a253）
- earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 移植/改写：`core/catalog/.../Pkce.kt` 的 `parseAuthorizationInput` ← `dist/auth/oauth/anthropic.js:20-46`（同样的四种输入形态）
- earendil-works/pi（npm `@earendil-works/pi-ai@1.5.0`），MIT —— 参考（未拷贝代码）：`core/store/.../ProviderSpecs.kt` 的环境变量表 ← `dist/env-api-keys.js:68-120` 与 `pi-coding-agent` `docs/providers.md`
- earendil-works/pi（npm `@earendil-works/pi-coding-agent@1.5.0`），MIT —— 运行时调用（不复制）：`pigo-auth.mjs` 在容器内 import pi 自带的 `ModelRuntime`、`SettingsManager`、`getAgentDir` 完成 OAuth 登录与登出；`AuthJsonLock.kt` 遵循其 `dist/core/auth-storage.js` 的 proper-lockfile 锁目录约定
- agegr/pi-web@025e24e，MIT —— 无新增移植（`ModelDiscovery.kt` 原有移植见 THIRD_PARTY_NOTICES；本分支新增的 `parseDetailed`/`discoverDetailed` 为原创）
- ccch1mneyyy/dsh-Go —— 本分支无移植代码，未使用其名称、标识与吉祥物
