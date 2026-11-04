import fs from "node:fs"; import path from "node:path"; import { mock, sandbox, Rpc } from "./harness.mjs";
const srv = await mock(); const res = {};
const listSessions = sb => { const d = path.join(sb.agent, "sessions"); if (!fs.existsSync(d)) return []; return fs.readdirSync(d, { recursive: true }).filter(f => String(f).endsWith(".jsonl")); };
{ // A: --no-session
  const sb = sandbox(srv.address().port); const r = new Rpc(sb, ["--no-session"]); const o = {};
  try {
    o.state0 = (({ sessionFile, sessionId }) => ({ sessionFile: sessionFile ?? null, sessionId }))((await r.send({ type: "get_state" })).data);
    await r.send({ type: "prompt", message: "eph #text" }); await r.wait(e => e.type === "agent_settled", 20000);
    o.filesAfterPrompt = listSessions(sb).length;
    const ns = await r.send({ type: "new_session" }); o.newSession = ns.success;
    o.state1 = (({ sessionFile, sessionId }) => ({ sessionFile: sessionFile ?? null, sessionId }))((await r.send({ type: "get_state" })).data);
    await r.send({ type: "prompt", message: "eph2 #text" }); await r.waitNew(e => e.type === "agent_settled", 20000);
    o.filesAfterNew = listSessions(sb).length;
    o.commandsMentioningSession = (await r.send({ type: "get_commands" })).data.commands.map(c => c.name).filter(n => /session|ephemeral|save/i.test(n));
    // try switch to a real file
    const sb2files = []; o.note = "RPC 没有切换持久化开关的命令（rpc-types.d.ts 33 条命令里无此项）";
  } catch (e) { o.error = String(e) } finally { r.close() }
  res.A_no_session = o;
}
{ // B: normal session: when is the file written; can it be moved away while running
  const sb = sandbox(srv.address().port); const r = new Rpc(sb); const o = {};
  try {
    const st = (await r.send({ type: "get_state" })).data; o.sessionFile0 = !!st.sessionFile; o.exists0 = fs.existsSync(st.sessionFile);
    await r.send({ type: "prompt", message: "p #text" }); await r.wait(e => e.type === "agent_settled", 20000);
    o.existsAfterFirstTurn = fs.existsSync(st.sessionFile);
    // leave: new_session then move old file to trash dir
    await r.send({ type: "new_session" }); const trash = path.join(sb.root, "trash"); fs.mkdirSync(trash);
    fs.renameSync(st.sessionFile, path.join(trash, path.basename(st.sessionFile)));
    await r.send({ type: "prompt", message: "q #text" }); await r.waitNew(e => e.type === "agent_settled", 20000);
    o.oldFileRecreated = fs.existsSync(st.sessionFile);
    o.filesNow = listSessions(sb).length;
  } catch (e) { o.error = String(e) } finally { r.close() }
  res.B_normal_then_trash = o;
}
srv.close(); console.log(JSON.stringify(res, null, 1));
