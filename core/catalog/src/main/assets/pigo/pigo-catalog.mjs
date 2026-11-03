#!/usr/bin/env node
// pi-go：导出 pi 内置服务商与模型目录为 JSON（stdout）。只读，不访问网络，不读凭据。
// 输出格式见 core/catalog 的 CatalogJson.kt；schema 版本变化时同步修改。
import { findPackage, importFrom, piVersion } from "./pigo-common.mjs";

const ai = findPackage("@earendil-works/pi-ai");
const { builtinProviders } = await importFrom(ai, "dist/providers/all.js");
const models = await importFrom(ai, "dist/models.js");

const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
function cost(c) {
  if (!c || typeof c !== "object") return undefined;
  return { input: num(c.input) ?? 0, output: num(c.output) ?? 0, cacheRead: num(c.cacheRead) ?? 0, cacheWrite: num(c.cacheWrite) ?? 0 };
}
function levels(m) {
  try {
    return models.getSupportedThinkingLevels ? [...models.getSupportedThinkingLevels(m)] : undefined;
  } catch {
    return undefined;
  }
}

const providers = builtinProviders().map((p) => {
  let list = [];
  try {
    list = p.getModels() ?? [];
  } catch {
    list = [];
  }
  return {
    id: p.id,
    name: p.name,
    baseUrl: p.baseUrl ?? null,
    auth: {
      apiKey: !!p.auth?.apiKey,
      oauth: !!p.auth?.oauth,
      oauthName: p.auth?.oauth?.name ?? null,
      subscription: !!p.auth?.oauth?.isSubscription,
    },
    models: list.map((m) => ({
      id: m.id,
      name: m.name ?? m.id,
      api: m.api ?? null,
      reasoning: !!m.reasoning,
      input: Array.isArray(m.input) ? m.input : ["text"],
      contextWindow: num(m.contextWindow) ?? null,
      maxTokens: num(m.maxTokens) ?? null,
      cost: cost(m.cost) ?? null,
      thinkingLevels: levels(m) ?? null,
    })),
  };
});

process.stdout.write(JSON.stringify({ schema: 1, piVersion: await piVersion(), providers }) + "\n");
