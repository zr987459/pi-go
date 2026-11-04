import fs from "node:fs"; import path from "node:path"; import { mock, sandbox, Rpc } from "./harness.mjs";
const out = {}; const srv = await mock(); const sb = sandbox(srv.address().port);
const r = new Rpc(sb);
try {
  const st0 = (await r.send({ type: "get_state" })).data;
  out.ready = !!r.events.find(e => e.type === "extension_ui_request" && e.message?.startsWith?.("pigo-bridge:") && e.message.includes('"ready"'));
  out.extension_error = r.events.filter(e => e.type === "extension_error");
  const cmds = (await r.send({ type: "get_commands" })).data.commands.map(c => c.name);
  out.bridgeCommandsVisible = cmds.filter(c => c.startsWith("pigo:"));
  await r.send({ type: "prompt", message: "first #text" }); await r.wait(e => e.type === "agent_settled", 20000);
  await r.send({ type: "prompt", message: "second #text" }); await r.waitNew(e => e.type === "agent_settled", 20000).catch(()=>{});
  await new Promise(s => setTimeout(s, 300));
  const st1 = (await r.send({ type: "get_state" })).data;
  const tree = (await r.send({ type: "get_tree" })).data;
  out.treeKeys = Object.keys(tree);
  const entries = (await r.send({ type: "get_entries" })).data;
  const ents = entries.entries || entries;
  const users = ents.filter(e => e.type === "message" && e.message?.role === "user");
  const firstUser = users[0];
  const leafBefore = tree.leafId ?? null;
  out.leafBefore = leafBefore; out.sessionFileBefore = st1.sessionFile;
  // tree-nav to first user message
  const navP = r.bridge("tree-nav"); await r.send({ type: "prompt", message: `/pigo:tree-nav ${firstUser.id}` }); const nav = await navP;
  out.treeNav = nav;
  const st2 = (await r.send({ type: "get_state" })).data; const tree2 = (await r.send({ type: "get_tree" })).data;
  out.leafAfter = tree2.leafId ?? null; out.sessionFileAfter = st2.sessionFile;
  out.treeNavOk = nav.ok && out.leafAfter !== leafBefore && st2.sessionFile === st1.sessionFile;
  // label
  const asst = ents.find(e => e.type === "message" && e.message?.role === "assistant");
  const lp = r.bridge("label"); await r.send({ type: "prompt", message: `/pigo:label ${asst.id} 书签 一` }); out.label = await lp;
  const tree3 = (await r.send({ type: "get_tree" })).data;
  const flat = []; const walk = n => { if (!n) return; if (Array.isArray(n)) return n.forEach(walk); flat.push(n); walk(n.children); }; walk(tree3.tree ?? tree3.roots ?? tree3.nodes ?? tree3);
  const node = flat.find(n => (n.entry?.id ?? n.id) === asst.id);
  out.labelInTree = node ? (node.label ?? node.entry?.label ?? null) : "node-not-found";
  out.treeNodeSample = JSON.stringify(flat[0]).slice(0, 300);
  // reload: add a prompt template in agent dir
  fs.mkdirSync(path.join(sb.agent, "prompts"), { recursive: true }); fs.writeFileSync(path.join(sb.agent, "prompts", "pigotpl.md"), "---\ndescription: test tpl\n---\nhello $1\n");
  const before = (await r.send({ type: "get_commands" })).data.commands.map(c => c.name);
  const rp = r.ready("reload"); await r.send({ type: "prompt", message: "/pigo:reload" }); out.reload = await rp;
  const after = (await r.send({ type: "get_commands" })).data.commands.map(c => c.name);
  out.reloadTemplateBefore = before.includes("pigotpl"); out.reloadTemplateAfter = after.includes("pigotpl");
  // label during streaming
  const st3 = (await r.send({ type: "get_state" })).data;
  await r.send({ type: "prompt", message: "long #slow" });
  await r.waitNew(e => e.type === "message_update", 10000);
  const t0 = Date.now(); const lp2 = r.bridge("label"); await r.send({ type: "prompt", message: `/pigo:label ${asst.id} 流式中` }); const l2 = await lp2; const t1 = Date.now();
  const stStream = (await r.send({ type: "get_state" })).data;
  const settled = await r.waitNew(e => e.type === "agent_settled", 20000);
  const endMsg = [...r.events].reverse().find(e => e.type === "message_end" && e.message?.role === "assistant");
  out.labelDuringStreaming = { reply: l2, latencyMs: t1 - t0, stillStreamingAfterLabel: stStream.isStreaming, settledAfterLabel: settled._t > t1, finalStop: endMsg?.message?.stopReason, finalText: (endMsg?.message?.content||[]).map(c=>c.text||"").join("").slice(-40) };
  const abortCmds = r.events.filter(e=>e.type==="response" && e.command==="abort").length; out.abortSent = abortCmds;
  // switch
  const nsFile = st1.sessionFile;
  await r.send({ type: "new_session" }); const st4 = (await r.send({ type: "get_state" })).data;
  const sp = r.ready("resume"); await r.send({ type: "prompt", message: `/pigo:switch ${nsFile}` }); out.switch = await sp;
  const st5 = (await r.send({ type: "get_state" })).data; out.switchOk = st5.sessionFile === nsFile && st4.sessionFile !== nsFile;
  out.pid = r.p.pid;
} catch (e) { out.error = String(e) } finally { r.close(); srv.close(); }
console.log(JSON.stringify(out, null, 1));
