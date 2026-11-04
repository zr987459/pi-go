/**
 * pigo-bridge — pi-go 随包扩展（DESIGN-r3 §5.6）。由 App 以 `pi --mode rpc -e <本文件>` 加载。
 *
 * 作用：补 RPC 协议缺的四个能力。四条隐藏命令经 RPC `prompt` 发送（扩展命令在 pi 里「立即执行，生成中也一样」），
 * 结果用 `extension_ui_request` 的 `notify` 回传，消息以 `pigo-bridge:` 开头、后接一行 JSON：
 *
 *   /pigo:tree-nav <entryId> [--summarize]   → ctx.navigateTree      {"cmd":"tree-nav","ok":true,"leafId":"…","sessionFile":"…","cancelled":false}
 *   /pigo:label <entryId> [text…]            → pi.setLabel（text 为空 = 清除）{"cmd":"label","ok":true,"entryId":"…","label":"…"}
 *   /pigo:reload                              → ctx.reload()          完成信号：新实例的 {"event":"ready","reason":"reload"}
 *   /pigo:switch <sessionPath>                → ctx.switchSession     完成信号：新实例的 {"event":"ready","reason":"resume","sessionFile":"…"}
 *
 * 失败一律回 {"cmd":…,"ok":false,"error":"…"}（label 另带 entryId）；App 侧每种命令串行（同一时刻最多一个在途），
 * 按 `cmd` 匹配，label 再按 entryId 关联。tree-nav 的成功回复带 editorText（选中用户消息时的原文）。
 *
 * 请求序号：App 在参数最前面加 `--req=<序号>`，本扩展在该命令的每条回复里原样带回 `"req":"<序号>"`；reload / switch 的完成信号
 * （新实例的 ready）经 `globalThis` 带回发起它的序号（旧实例失效、模块重新求值，模块内变量不保留）。App 按序号认领回复，
 * 超时之后迟到的回复只交给发起它的那次请求，不会被下一次同类命令认领。
 * 每次扩展实例生效（session_start）回 {"event":"ready","version":1,"reason":…,"sessionFile":…}，App 用它判断桥接可用；收不到或收到 `extension_error`
 * 就走 §5.6 的退路。
 *
 * 只用 pi 1.5.0 公开的扩展 API（`ExtensionAPI.registerCommand`、`ExtensionCommandContext.navigateTree/switchSession/reload`、
 * `ExtensionAPI.setLabel`）。不 import 任何包：pi 用 jiti 加载 .ts，类型注解在运行时被剥掉。
 */

const PREFIX = "pigo-bridge:";
const VERSION = 1;

// biome-ignore lint: 不 import 类型，避免在容器里解析模块
type Ctx = any;
// biome-ignore lint: 同上
type Api = any;

function reply(ctx: Ctx, payload: Record<string, unknown>): void {
	try {
		ctx.ui.notify(PREFIX + JSON.stringify(payload), "info");
	} catch {
		// UI 不可用（print 模式）时静默：pi-go 只在 rpc 模式加载本扩展。
	}
}

function errText(e: unknown): string {
	return e instanceof Error ? e.message : String(e);
}

/** 用户消息 entry 的纯文本（字符串内容，或 content 数组里 text 块的拼接）；不是用户消息返回 null。 */
function userText(entry: any): string | null {
	const msg = entry?.type === "message" ? entry.message : null;
	if (!msg || msg.role !== "user") return null;
	if (typeof msg.content === "string") return msg.content;
	if (!Array.isArray(msg.content)) return null;
	const parts = msg.content.filter((c: any) => c?.type === "text" && typeof c.text === "string").map((c: any) => c.text);
	return parts.length > 0 ? parts.join("") : null;
}

function splitArgs(args: string): string[] {
	return (args ?? "").trim().split(/\s+/).filter((s) => s.length > 0);
}

/** 拆出最前面的 `--req=<序号>`：返回 [序号或 null, 其余参数原文]。 */
function takeReq(args: string): [string | null, string] {
	const t = (args ?? "").trim();
	const m = /^--req=([A-Za-z0-9_-]{1,32})(?:\s+|$)/.exec(t);
	return m ? [m[1], t.slice(m[0].length)] : [null, t];
}

/** reload / switch 发起时记下序号，新实例的 session_start 取走（按 reason 区分）。 */
const PENDING_KEY = "__pigoBridgePendingReq";
function setPending(reason: string, req: string | null): void {
	const g = globalThis as any;
	g[PENDING_KEY] = { ...(g[PENDING_KEY] ?? {}), [reason]: req };
}
function takePending(reason: string): string | null {
	const g = globalThis as any;
	const all = g[PENDING_KEY];
	if (!all || !(reason in all)) return null;
	const req = all[reason] ?? null;
	delete all[reason];
	return req;
}

export default function pigoBridge(pi: Api) {
	// 每次扩展实例生效都回 ready：启动（startup）、/pigo:reload 之后（reload）、切换/新建/分叉会话之后（resume/new/fork）。
	// reload 与 switchSession 会让旧实例失效（stale），旧 ctx 上的 notify 不再送达，所以这两条命令的完成信号就是新实例的 ready。
	pi.on("session_start", (event: { reason?: string }, ctx: Ctx) => {
		const reason = event?.reason ?? "startup";
		const req = reason === "reload" || reason === "resume" ? takePending(reason) : null;
		reply(ctx, {
			event: "ready",
			version: VERSION,
			reason,
			sessionFile: ctx.sessionManager?.getSessionFile?.() ?? null,
			...(req ? { req } : {}),
		});
	});

	pi.registerCommand("pigo:tree-nav", {
		description: "pi-go 内部：在会话树内切换到某条记录",
		handler: async (rawArgs: string, ctx: Ctx) => {
			const [req, args] = takeReq(rawArgs);
			const r0 = req ? { req } : {};
			const parts = splitArgs(args);
			const summarize = parts.includes("--summarize");
			const target = parts.find((p) => !p.startsWith("--"));
			if (!target) return reply(ctx, { cmd: "tree-nav", ok: false, error: "缺少 entryId", ...r0 });
			try {
				// pi 的 /tree 选中用户消息时把原文放回编辑器；RPC 模式下 navigateTree 的返回里去掉了 editorText，
				// 所以在切换前从 entry 取原文一并回传（App 写进共享草稿）。
				const editorText = userText(ctx.sessionManager.getEntry?.(target));
				const r = await ctx.navigateTree(target, { summarize });
				reply(ctx, {
					cmd: "tree-nav",
					ok: true,
					cancelled: !!r?.cancelled,
					leafId: ctx.sessionManager.getLeafId(),
					sessionFile: ctx.sessionManager.getSessionFile() ?? null,
					editorText: r?.cancelled ? null : (r?.editorText ?? editorText),
					...r0,
				});
			} catch (e) {
				reply(ctx, { cmd: "tree-nav", ok: false, error: errText(e), ...r0 });
			}
		},
	});

	pi.registerCommand("pigo:label", {
		description: "pi-go 内部：给会话树记录设置或清除标签",
		handler: async (rawArgs: string, ctx: Ctx) => {
			const [req, trimmed] = takeReq(rawArgs);
			const r0 = req ? { req } : {};
			const sp = trimmed.search(/\s/);
			const entryId = sp < 0 ? trimmed : trimmed.slice(0, sp);
			const label = sp < 0 ? "" : trimmed.slice(sp + 1).trim();
			if (!entryId) return reply(ctx, { cmd: "label", ok: false, error: "缺少 entryId", ...r0 });
			try {
				if (!ctx.sessionManager.getEntry(entryId)) {
					return reply(ctx, { cmd: "label", ok: false, entryId, error: `没有这条记录：${entryId}`, ...r0 });
				}
				pi.setLabel(entryId, label.length > 0 ? label : undefined);
				reply(ctx, { cmd: "label", ok: true, entryId, label: label.length > 0 ? label : null, ...r0 });
			} catch (e) {
				reply(ctx, { cmd: "label", ok: false, entryId, error: errText(e), ...r0 });
			}
		},
	});

	pi.registerCommand("pigo:reload", {
		description: "pi-go 内部：重新加载扩展、技能、模板、主题与上下文文件",
		handler: async (rawArgs: string, ctx: Ctx) => {
			const [req] = takeReq(rawArgs);
			const r0 = req ? { req } : {};
			try {
				setPending("reload", req);
				await ctx.reload();
				reply(ctx, { cmd: "reload", ok: true, ...r0 });
			} catch (e) {
				takePending("reload");
				reply(ctx, { cmd: "reload", ok: false, error: errText(e), ...r0 });
			}
		},
	});

	pi.registerCommand("pigo:switch", {
		description: "pi-go 内部：切换到另一个会话文件",
		handler: async (rawArgs: string, ctx: Ctx) => {
			const [req, path] = takeReq(rawArgs);
			const r0 = req ? { req } : {};
			if (!path) return reply(ctx, { cmd: "switch", ok: false, error: "缺少会话路径", ...r0 });
			try {
				setPending("resume", req);
				const r = await ctx.switchSession(path);
				if (r?.cancelled) takePending("resume");
				// switchSession 后旧 ctx 已过期（stale），sessionFile 由 App 再发 get_state 读取。
				reply(ctx, { cmd: "switch", ok: true, cancelled: !!r?.cancelled, sessionFile: path, ...r0 });
			} catch (e) {
				takePending("resume");
				reply(ctx, { cmd: "switch", ok: false, error: errText(e), ...r0 });
			}
		},
	});
}
