#!/usr/bin/env node
// pi-go：在容器内用 pi 自己的登录流程完成 OAuth / 订阅登录，凭据由 pi 写入 auth.json（权限 600）。
//
// 用法：node pigo-auth.mjs login <provider> [--method oauth|api_key]
//       node pigo-auth.mjs logout <provider>
//       node pigo-auth.mjs status            # 每个服务商一行：是否已登录（不输出凭据）
//
// 协议（JSON Lines）：
//   stdout  {"type":"event","event":{...}}        pi 的 AuthEvent（auth_url / device_code / info / progress）
//           {"type":"prompt","id":"p1","prompt":{type,message,placeholder?,options?}}
//           {"type":"done","provider":"…","credential":"oauth"}     不含任何令牌
//           {"type":"error","message":"…","cancelled":bool}
//   stdin   {"type":"answer","id":"p1","value":"…"}
//           {"type":"cancel"}
//
// 安全：stdout 永远不写 access/refresh token；错误信息里出现的长串令牌形态会被替换为 ***。
import { createInterface } from "node:readline";
import { join } from "node:path";
import { piRoot, importFrom } from "./pigo-common.mjs";

const root = piRoot();
const { ModelRuntime, SettingsManager, getAgentDir } = await importFrom(root, "dist/index.js");

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const redact = (s) => String(s ?? "")
  .replace(/(gh[opsu]_|sk-|eyJ)[A-Za-z0-9._\-]{8,}/g, "***")
  .replace(/(access|refresh)(_token)?["'=:\s]+[A-Za-z0-9._\-]{12,}/gi, "$1=***");

const [cmd, provider, ...rest] = process.argv.slice(2);
const method = rest.includes("--method") ? rest[rest.indexOf("--method") + 1] : "oauth";
const agentDir = getAgentDir();
const authPath = join(agentDir, "auth.json");

const controller = new AbortController();
const pending = new Map();
let seq = 0;
const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  if (msg?.type === "cancel") {
    controller.abort();
    for (const [, p] of pending) p.reject(new Error("Login cancelled"));
    pending.clear();
  } else if (msg?.type === "answer" && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    p.resolve(String(msg.value ?? ""));
  }
});
rl.on("close", () => { if (cmd === "login" && pending.size) controller.abort(); });

const interaction = {
  signal: controller.signal,
  prompt(prompt) {
    const id = `p${++seq}`;
    const { signal: _s, ...p } = prompt;
    out({ type: "prompt", id, prompt: p });
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      prompt.signal?.addEventListener("abort", () => { pending.delete(id); reject(new Error("Login cancelled")); }, { once: true });
    });
  },
  notify(event) { out({ type: "event", event }); },
};

async function runtime() {
  return ModelRuntime.create({ authPath, modelsPath: join(agentDir, "models.json"), refreshOnCreate: false });
}

try {
  if (cmd === "login") {
    if (!provider) throw new Error("缺少服务商 id");
    const rt = await runtime();
    const settings = SettingsManager.create(process.cwd(), agentDir);
    await rt.login(provider, method, interaction, { getDeviceId: () => settings.getOrCreateDeviceId() });
    out({ type: "done", provider, credential: method });
  } else if (cmd === "logout") {
    if (!provider) throw new Error("缺少服务商 id");
    const rt = await runtime();
    await rt.logout(provider);
    out({ type: "done", provider, credential: "none" });
  } else if (cmd === "status") {
    const rt = await runtime();
    const list = await rt.listCredentials();
    out({ type: "status", providers: list.map((c) => ({ provider: c.providerId, credential: c.type })) });
  } else {
    throw new Error(`未知命令：${cmd ?? ""}`);
  }
  rl.close();
  process.exit(0);
} catch (e) {
  const message = redact(e instanceof Error ? e.message : String(e));
  out({ type: "error", message, cancelled: message === "Login cancelled" || controller.signal.aborted });
  rl.close();
  process.exit(1);
}
