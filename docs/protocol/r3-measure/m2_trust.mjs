import fs from "node:fs"; import path from "node:path"; import { mock, sandbox, Rpc } from "./harness.mjs";
const srv = await mock(); const res = {};
async function run(label, prep, args = [], after) {
  const sb = sandbox(srv.address().port);
  fs.mkdirSync(path.join(sb.cwd, ".pi", "prompts"), { recursive: true });
  fs.writeFileSync(path.join(sb.cwd, ".pi", "prompts", "projtpl.md"), "---\ndescription: project tpl\n---\nproj $1\n");
  prep?.(sb);
  const r = new Rpc(sb, args); const o = {};
  try {
    await r.send({ type: "get_state" });
    o.uiRequests = r.events.filter(e => e.type === "extension_ui_request" && !["notify","setStatus"].includes(e.method)).map(e => e.method + ":" + (e.title || "").slice(0, 40));
    o.projectTemplateLoaded = (await r.send({ type: "get_commands" })).data.commands.some(c => c.name === "projtpl");
    if (after) { await after(sb, r); o.afterAction = true; o.projectTemplateAfter = (await r.send({ type: "get_commands" })).data.commands.some(c => c.name === "projtpl"); }
    o.trustJson = fs.existsSync(path.join(sb.agent, "trust.json")) ? fs.readFileSync(path.join(sb.agent, "trust.json"), "utf8") : null;
  } catch (e) { o.error = String(e) } finally { r.close() }
  res[label] = o;
}
const fsCwd = sb => fs.realpathSync(sb.cwd);
await run("A_untrusted_default", null);
await run("B_trust_json_preset", sb => fs.writeFileSync(path.join(sb.agent, "trust.json"), JSON.stringify({ [fsCwd(sb)]: true })));
await run("C_flag_-a", null, ["-a"]);
await run("D_untrusted_then_write_trust_and_reload", null, [], async (sb, r) => {
  fs.writeFileSync(path.join(sb.agent, "trust.json"), JSON.stringify({ [fsCwd(sb)]: true }));
  const p = r.ready("reload"); await r.send({ type: "prompt", message: "/pigo:reload" }); await p;
});
await run("E_defaultProjectTrust_always", sb => fs.writeFileSync(path.join(sb.agent, "settings.json"), JSON.stringify({ defaultProvider: "mock", defaultModel: "mock-model", defaultProjectTrust: "always" })));
srv.close(); console.log(JSON.stringify(res, null, 1));
