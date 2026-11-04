# DESIGN-r3 §5.7 实测记录（r3/core）

- 时间：2026-11-04（CST）
- pi：`@earendil-works/pi-coding-agent@1.5.0`（npm 安装，`pi --mode rpc`），Node v22，Linux x86_64
- 模型：本机假 OpenAI 兼容服务（127.0.0.1，SSE 流式；`#slow` 约 3 s 30 段），不访问外网
- 隔离：独立 `HOME` 与 `PI_CODING_AGENT_DIR`，`PI_OFFLINE=1`，与 `EngineConfig.spec` 相同的环境变量
- 脚本：`docs/protocol/r3-measure/`（`harness.mjs` + `m1_bridge.mjs`、`m2_trust.mjs`、`m3_nosession.mjs`），
  运行：`PI_BIN=$(which pi) BRIDGE=core/engine/src/main/assets/pi/pigo-bridge.ts node docs/protocol/r3-measure/m1_bridge.mjs`
- 同样的桥接四项另有 Kotlin 集成测试 `core/engine/src/test/…/realpi/RealPiBridgeIntegrationTest.kt`（走真实 `PiEngine`），
  CI 的「真 pi 桥接集成测试」步骤会 `npm i` pi 1.5.0 后设置 `PIGO_PI_BIN` 运行。

## 1. `navigateTree` / `setLabel` / `reload`（及 `switchSession`）在 RPC 模式下 —— **可行，不需要退路**

pi 1.5.0 的 `modes/rpc/rpc-mode.js` 在 `bindExtensions` 时给扩展命令传了完整的 `commandContextActions`
（`navigateTree`、`switchSession`、`reload`、`fork`、`newSession`、`waitForIdle`），`agent-session.js` 的 `prompt()`
对扩展命令「立即执行，生成中也一样」。实测（`m1_bridge.mjs`）：

| 项 | 结果 |
|---|---|
| 桥接加载 | `-e pigo-bridge.ts` 加载成功，无 `extension_error`；启动后收到 `pigo-bridge:{"event":"ready","reason":"startup"}` |
| `/pigo:tree-nav <第一条 user 记录>` | 回 `{"cmd":"tree-nav","ok":true,"cancelled":false,"leafId":"…"}`；`get_tree.leafId` 由 `16d0edcc` 变为 `1ce20f1d`；`get_state.sessionFile` **不变** |
| `/pigo:label <assistant 记录> 书签 一` | 回 `{"ok":true}`；`get_tree` 里该节点 `label == "书签 一"`（中文与空格原样） |
| `/pigo:reload` | 写入 `<agentDir>/prompts/pigotpl.md` 后：reload 前 `get_commands` 无 `pigotpl`，reload 后有 |
| 生成中发 `/pigo:label` | 1 ms 内回 `ok`；回复后 `get_state.isStreaming == true`（未打断）；之后才 `agent_settled`；最终 `stopReason == "stop"`、30 段全部到齐；全程没有 `abort` |
| `/pigo:switch <旧会话文件>` | 先 `new_session` 再 switch 回来：`get_state.sessionFile` 回到旧文件，收到 `ready`（`reason":"resume"`） |

实测中发现并已处理的两点：
- `reload()` 与 `switchSession()` 会让旧扩展实例失效（stale），旧 `ctx.ui.notify` 不再送达。所以这两条命令的
  **完成信号是新实例在 `session_start` 回的 `ready`**（带 `reason`：`reload` / `resume`），`PiEngine` 按此等待。
- 四条 `pigo:*` 命令会出现在 `get_commands` 里（pi 没有隐藏扩展命令的 API）。`PiEngine.commands()` 已把
  `pigo:` 前缀的命令过滤掉，界面的 `/` 补全不会看到它们。

退路（§5.6）仍实现：桥接没回 `ready` 或报 `extension_error` 时，树内切换退化为 `fork`，reload 返回
`ErrCode.ENGINE_START` + 「需重启引擎」提示（由界面确认后走唯一允许的重启路径），标签返回不可用原因。

## 2. `/trust` 在 RPC 模式下 —— **项目 `.pi/` 默认不加载；改由 App 写 `trust.json`（首选）或 `-a`**

`main.js` 的信任判定：有项目资源（`.pi/settings.json`、`prompts/`、`skills/`、`extensions/`、`SYSTEM.md` 等）时，
RPC 模式 `hasUI=false`，`defaultProjectTrust` 默认 `ask` → 直接判为**不信任**，也**不会**发 `extension_ui_request` 询问。
实测（`m2_trust.mjs`，cwd 下放 `.pi/prompts/projtpl.md`）：

| 情形 | 项目模板是否加载 |
|---|---|
| A 默认 | 否（无任何询问请求） |
| B 启动前 `<agentDir>/trust.json` 写 `{"<cwd 实际路径>": true}` | **是** |
| C 启动参数 `-a`（`--approve`） | **是**（但对本次运行的任何目录都生效） |
| D 先以不信任启动，再写 `trust.json` + `/pigo:reload` | **否**（pi 在进程内按 cwd 缓存了「不信任」的判定，reload 不重新判定） |
| E `settings.json` 设 `defaultProjectTrust: "always"` | 是（全局放开，不采用） |

结论与实现：
- 采用 **B**：用户在 App 里确认信任某目录后，`ProjectTrustFile`（`core/engine`）按 pi 自己的格式写 `trust.json`
  （键 = 规范化绝对路径，值 `true/false/null`，按键排序、2 空格缩进、末尾换行；锁目录 `trust.json.lock` 与 pi 的
  proper-lockfile 互斥；保留已有的其他键）。
- 由于 D：对**正在运行**的会话目录改信任，需要重启引擎才生效 → 走 §5.5 唯一允许的重启路径（先确认）。
  新建到另一个目录的会话（新进程或 `new_session` 后再 `/pigo:switch`）在启动前写好即可。
- `-a` 只作为 `EngineConfig.approveProject` 开关保留（默认关），不默认使用：它对整次运行的所有目录生效，
  不满足「仅对用户已确认信任的目录」。

## 3. `--no-session` 运行中不能切换 —— **确认；按设计用「临时会话」近似**

实测（`m3_nosession.mjs`）：
- `--no-session` 启动：`get_state.sessionFile == null`，对话后不写任何会话文件；`new_session` 后仍是 null（整个进程期间都是临时的）。
- RPC 33 条命令里没有切换持久化的命令；进程运行中无法从持久会话变成临时会话，反之亦然（只能重启进程）。
- 普通会话：`get_state` 一开始就给出 `sessionFile`，但文件在**第一轮回复后**才落盘；离开（`new_session`）后把旧文件
  移走，pi 不会再写回它（`oldFileRecreated == false`），新会话照常写自己的文件。

结论：采用设计的近似方案——`SessionHost`（r3/session）把当前会话标记为临时；离开（新建、切换、App 退出）时把会话文件
**移进回收目录**（D1），列表不显示；首轮回复前离开则没有文件可移。这样不需要重启引擎。
`EngineConfig` 不加 `--no-session`（加了就无法在同一进程里回到持久会话，违反「不重启引擎」）。
