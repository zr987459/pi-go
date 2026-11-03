// 真 pi 1.5.0 + 本机假 OpenAI 服务的 RPC 实测工具（DESIGN-r3 §5.7）。
import http from "node:http"; import { spawn } from "node:child_process";
import fs from "node:fs"; import os from "node:os"; import path from "node:path";
export const PI = process.env.PI_BIN || "/workspace/work/r3core/pi/node_modules/.bin/pi";
export const BRIDGE = process.env.BRIDGE || "/workspace/work/r3core/src/core/engine/src/main/assets/pi/pigo-bridge.ts";
export function mock() {
  const srv = http.createServer((req, res) => {
    let b = ""; req.on("data", d => b += d); req.on("end", () => {
      const j = JSON.parse(b || "{}"); const msgs = j.messages || [];
      const last = [...msgs].reverse().find(m => m.role === "user"); let t = typeof last?.content === "string" ? last.content : (last?.content || []).map(c => c.text || "").join("");
      const slow = t.includes("#slow");
      res.writeHead(200, { "content-type": "text/event-stream" });
      const n = slow ? 30 : 3; let i = 0;
      const tick = () => {
        if (i < n) { res.write(`data: ${JSON.stringify({ id: "x", object: "chat.completion.chunk", choices: [{ index: 0, delta: { content: `p${i} ` } }] })}\n\n`); i++; setTimeout(tick, slow ? 100 : 5); }
        else { res.write(`data: ${JSON.stringify({ id: "x", object: "chat.completion.chunk", choices: [{ index: 0, delta: {}, finish_reason: "stop" }], usage: { prompt_tokens: 5, completion_tokens: n, total_tokens: 5 + n } })}\n\ndata: [DONE]\n\n`); res.end(); }
      }; tick();
    });
  });
  return new Promise(r => srv.listen(0, "127.0.0.1", () => r(srv)));
}
export function sandbox(port) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "pigo-m-")); const agent = path.join(root, "agent"); const cwd = path.join(root, "cwd");
  fs.mkdirSync(agent, { recursive: true }); fs.mkdirSync(cwd, { recursive: true });
  fs.writeFileSync(path.join(agent, "models.json"), JSON.stringify({ providers: { mock: { baseUrl: `http://127.0.0.1:${port}/v1`, api: "openai-completions", apiKey: "$PIGO_KEY_MOCK", models: [{ id: "mock-model", name: "Mock", reasoning: false, input: ["text"], contextWindow: 32000, maxTokens: 4096 }] } } }));
  fs.writeFileSync(path.join(agent, "settings.json"), JSON.stringify({ defaultProvider: "mock", defaultModel: "mock-model" }));
  return { root, agent, cwd };
}
export class Rpc {
  constructor(sb, extraArgs = [], useBridge = true) {
    const args = ["--mode", "rpc", ...(useBridge ? ["-e", BRIDGE] : []), ...extraArgs];
    const env = { ...process.env, HOME: sb.root, PI_CODING_AGENT_DIR: sb.agent, PI_OFFLINE: "1", PI_SKIP_VERSION_CHECK: "1", PI_TELEMETRY: "0", PIGO_KEY_MOCK: "sk-mock", NO_PROXY: "127.0.0.1" };
    for (const k of Object.keys(env)) if (k.toLowerCase().endsWith("_proxy") && k.toLowerCase() !== "no_proxy") delete env[k];
    this.p = spawn(PI, args, { cwd: sb.cwd, env }); this.events = []; this.waiters = []; this.n = 0; this.stderr = "";
    let buf = ""; this.p.stdout.on("data", d => { buf += d; let i; while ((i = buf.indexOf("\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); if (!line.trim()) continue; let e; try { e = JSON.parse(line) } catch { continue } e._t = Date.now(); this.events.push(e); this.waiters = this.waiters.filter(w => !w(e)); } });
    this.p.stderr.on("data", d => this.stderr += d);
  }
  wait(pred, ms = 15000) { const hit = this.events.find(pred); if (hit) return Promise.resolve(hit); return new Promise((res, rej) => { const t = setTimeout(() => rej(new Error("timeout waiting; stderr=" + this.stderr.slice(-800))), ms); this.waiters.push(e => { if (pred(e)) { clearTimeout(t); res(e); return true } return false }); }); }
  waitNew(pred, ms) { const start = this.events.length; return new Promise((res, rej) => { const old = this.events.slice(start).find(pred); if (old) return res(old); const t = setTimeout(() => rej(new Error("timeout; stderr=" + this.stderr.slice(-800))), ms || 15000); this.waiters.push(e => { if (pred(e)) { clearTimeout(t); res(e); return true } return false }); }); }
  send(cmd) { const id = "c" + (++this.n); this.p.stdin.write(JSON.stringify({ id, ...cmd }) + "\n"); return this.wait(e => e.type === "response" && e.id === id, 30000); }
  bridge(cmd) { return this.waitNew(e => e.type === "extension_ui_request" && e.method === "notify" && typeof e.message === "string" && e.message.startsWith("pigo-bridge:") && JSON.parse(e.message.slice(12)).cmd === cmd, 15000).then(e => JSON.parse(e.message.slice(12))); }
  ready(reason) { return this.waitNew(e => e.type === "extension_ui_request" && e.method === "notify" && typeof e.message === "string" && e.message.startsWith("pigo-bridge:") && JSON.parse(e.message.slice(12)).event === "ready" && JSON.parse(e.message.slice(12)).reason === reason, 15000).then(e => JSON.parse(e.message.slice(12))); }
  close() { try { this.p.stdin.end(); this.p.kill() } catch { } }
}
