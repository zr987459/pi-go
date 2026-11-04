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

export default function pigoBridge(pi: Api) {
	// 每次扩展实例生效都回 ready：启动（startup）、/pigo:reload 之后（reload）、切换/新建/分叉会话之后（resume/new/fork）。
	// reload 与 switchSession 会让旧实例失效（stale），旧 ctx 上的 notify 不再送达，所以这两条命令的完成信号就是新实例的 ready。
	pi.on("session_start", (event: { reason?: string }, ctx: Ctx) => {
		reply(ctx, {
			event: "ready",
			version: VERSION,
			reason: event?.reason ?? "startup",
			sessionFile: ctx.sessionManager?.getSessionFile?.() ?? null,
		});
	});

	pi.registerCommand("pigo:tree-nav", {
		description: "pi-go 内部：在会话树内切换到某条记录",
		handler: async (args: string, ctx: Ctx) => {
			const parts = splitArgs(args);
			const summarize = parts.includes("--summarize");
			const target = parts.find((p) => !p.startsWith("--"));
			if (!target) return reply(ctx, { cmd: "tree-nav", ok: false, error: "缺少 entryId" });
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
				});
			} catch (e) {
				reply(ctx, { cmd: "tree-nav", ok: false, error: errText(e) });
			}
		},
	});

	pi.registerCommand("pigo:label", {
		description: "pi-go 内部：给会话树记录设置或清除标签",
		handler: async (args: string, ctx: Ctx) => {
			const trimmed = (args ?? "").trim();
			const sp = trimmed.search(/\s/);
			const entryId = sp < 0 ? trimmed : trimmed.slice(0, sp);
			const label = sp < 0 ? "" : trimmed.slice(sp + 1).trim();
			if (!entryId) return reply(ctx, { cmd: "label", ok: false, error: "缺少 entryId" });
			try {
				if (!ctx.sessionManager.getEntry(entryId)) {
					return reply(ctx, { cmd: "label", ok: false, entryId, error: `没有这条记录：${entryId}` });
				}
				pi.setLabel(entryId, label.length > 0 ? label : undefined);
				reply(ctx, { cmd: "label", ok: true, entryId, label: label.length > 0 ? label : null });
			} catch (e) {
				reply(ctx, { cmd: "label", ok: false, entryId, error: errText(e) });
			}
		},
	});

	pi.registerCommand("pigo:reload", {
		description: "pi-go 内部：重新加载扩展、技能、模板、主题与上下文文件",
		handler: async (_args: string, ctx: Ctx) => {
			try {
				await ctx.reload();
				reply(ctx, { cmd: "reload", ok: true });
			} catch (e) {
				reply(ctx, { cmd: "reload", ok: false, error: errText(e) });
			}
		},
	});

	pi.registerCommand("pigo:switch", {
		description: "pi-go 内部：切换到另一个会话文件",
		handler: async (args: string, ctx: Ctx) => {
			const path = (args ?? "").trim();
			if (!path) return reply(ctx, { cmd: "switch", ok: false, error: "缺少会话路径" });
			try {
				const r = await ctx.switchSession(path);
				// switchSession 后旧 ctx 已过期（stale），sessionFile 由 App 再发 get_state 读取。
				reply(ctx, { cmd: "switch", ok: true, cancelled: !!r?.cancelled, sessionFile: path });
			} catch (e) {
				reply(ctx, { cmd: "switch", ok: false, error: errText(e) });
			}
		},
	});
}
