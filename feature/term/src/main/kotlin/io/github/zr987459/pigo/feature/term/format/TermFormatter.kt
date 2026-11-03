package io.github.zr987459.pigo.feature.term.format

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.feature.term.text.CellWidth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一行终端输出：若干带 token 的片段 + 整行底色 token（用户消息 `userMessageBg`、工具块 `toolPendingBg/SuccessBg/ErrorBg` 等）。
 * [action] 非空表示点这一行执行的动作（工具块 → 展开 / 收起、思考块 → 显示 / 隐藏）。
 */
data class TermLine(val spans: List<Span>, val bg: String? = null, val action: String? = null) {
    val text: String get() = spans.joinToString("") { it.text }
}

/** 排版选项（与 pi 设置 / 交互状态对应）。 */
data class FormatOptions(
    val width: Int = 80,
    /** pi `hideThinkingBlock`（Ctrl+T 切换）。 */
    val hideThinking: Boolean = false,
    /** Ctrl+O：全部工具块展开。 */
    val toolsExpanded: Boolean = false,
    /** 单独展开的工具块（点工具块）。 */
    val expandedKeys: Set<String> = emptySet(),
    val cwd: String = "",
    val home: String? = null,
    /** 「… (ctrl+o to expand)」里显示的键名（读 keybindings.json，缺省 ctrl+o）。 */
    val expandKey: String = "ctrl+o",
)

/**
 * 消息区排版（DESIGN-r3 §3.1、§3.5）：纯函数，把 [ChatItem] 转成按列宽折好的 [TermLine]，与 pi 1.5.0 交互模式在同一列宽下的输出一致
 * （`TermFormatterGoldenTest`：夹具由 node 直接调用 pi 包的 UserMessage / AssistantMessage / ToolExecution 组件生成）。
 */
object TermFormatter {
    private const val PAD = 1

    fun format(item: ChatItem, o: FormatOptions): List<TermLine> = when (item) {
        is ChatItem.User -> user(item.text, o)
        is ChatItem.Assistant -> assistant(item, o)
        is ChatItem.Tool -> tool(item, o)
        is ChatItem.Notice -> notice(item, o)
        is ChatItem.Raw -> listOf(blank(), padded(listOf(Span("[${item.role ?: "?"}] ${item.json}", "muted")), o.width))
    }

    // ---------------- 公共 ----------------

    private fun blank() = TermLine(emptyList())

    /** 左右各 [PAD] 列内边距，补满到 [width]（底色铺满整行）。 */
    private fun padded(spans: List<Span>, width: Int, bg: String? = null, action: String? = null): TermLine {
        val used = spans.sumOf { CellWidth.width(it.text) } + PAD
        val rest = (width - used).coerceAtLeast(0)
        return TermLine(listOf(Span(" ".repeat(PAD))) + spans + Span(" ".repeat(rest)), bg, action)
    }

    private fun fill(width: Int, bg: String?) = TermLine(listOf(Span(" ".repeat(width))), bg)

    // ---------------- 用户消息 ----------------

    private fun user(text: String, o: FormatOptions): List<TermLine> {
        val inner = o.width - 2 * PAD
        val out = mutableListOf(fill(o.width, "userMessageBg"))
        for (l in Markdown.render(text, inner, baseToken = "userMessageText")) out += padded(l, o.width, "userMessageBg")
        out += fill(o.width, "userMessageBg")
        return out
    }

    // ---------------- 助手消息 ----------------

    private fun assistant(a: ChatItem.Assistant, o: FormatOptions): List<TermLine> {
        val inner = o.width - 2 * PAD
        val out = mutableListOf<TermLine>()
        val visible = a.blocks.filter { it !is ContentBlock.ToolCall && !(it is ContentBlock.Text && it.text.isBlank()) && !(it is ContentBlock.Thinking && it.text.isBlank()) }
        if (visible.isNotEmpty() || a.stopReason == "error" || a.stopReason == "aborted") out += blank()
        visible.forEachIndexed { idx, b ->
            when (b) {
                is ContentBlock.Thinking -> {
                    if (o.hideThinking) out += padded(listOf(Span("Thinking...", "thinkingText", italic = true)), o.width, action = "thinking.toggle")
                    else for (l in Markdown.render(b.text.trim(), inner, baseToken = "thinkingText", italic = true)) out += padded(l, o.width, action = "thinking.toggle")
                    if (idx < visible.size - 1) out += blank()
                }
                is ContentBlock.Text -> for (l in Markdown.render(b.text.trim(), inner)) out += padded(l, o.width)
                else -> Unit
            }
        }
        when (a.stopReason) {
            "error" -> out += padded(listOf(Span("Error: ${a.errorMessage ?: "Unknown error"}", "error")), o.width)
            "aborted" -> {
                if (visible.isNotEmpty()) out += blank()
                out += padded(listOf(Span(a.errorMessage?.takeIf { it.isNotBlank() && it != "Request was aborted" } ?: "Operation aborted", "error")), o.width)
            }
        }
        return out
    }

    // ---------------- 工具块 ----------------

    private fun JsonObject?.s(vararg keys: String): String? {
        if (this == null) return null
        for (k in keys) (this[k] as? JsonPrimitive)?.contentOrNull?.let { return it }
        return null
    }

    /** pi `render-utils.shortenPath`：家目录换成 `~`。 */
    private fun shorten(p: String, home: String?): String =
        if (!home.isNullOrEmpty() && (p == home || p.startsWith("$home/"))) "~" + p.removePrefix(home) else p

    /** 工具结果里的文本（`content[].text` 拼接）；兼容结果直接是字符串。 */
    fun resultText(r: JsonElement?): String {
        if (r == null) return ""
        if (r is JsonPrimitive) return r.contentOrNull ?: ""
        val obj = r as? JsonObject ?: return r.toString()
        val content = obj["content"] as? JsonArray ?: return obj.s("text", "output") ?: ""
        return content.mapNotNull { (it as? JsonObject)?.let { c -> if (c.s("type") == "text") c.s("text") else null } }.joinToString("\n")
    }

    private fun isError(r: JsonElement?) = ((r as? JsonObject)?.get("isError") as? JsonPrimitive)?.contentOrNull == "true"

    private fun duration(ms: Long): String = "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)

    private fun tool(t: ChatItem.Tool, o: FormatOptions): List<TermLine> {
        val expanded = o.toolsExpanded || t.key in o.expandedKeys
        val result = t.result ?: t.partialResult
        val failed = t.status == ToolStatus.ERROR || isError(t.result)
        val bg = when {
            failed -> "toolErrorBg"
            t.status == ToolStatus.OK -> "toolSuccessBg"
            else -> "toolPendingBg"
        }
        val a = t.args
        val title = mutableListOf<Span>()
        val outLines = mutableListOf<List<Span>>()
        var footer: String? = null
        val text = resultText(result).let { if (t.toolName == "bash") it.trimEnd('\n') else it.trim() }
        val lines = if (text.isEmpty()) emptyList() else text.split("\n")
        fun hint(remaining: Int, extra: String = "") =
            listOf(Span("... ($remaining more lines,$extra", "muted"), Span(" ${o.expandKey}", "dim"), Span(" to expand", "muted"), Span(")", "muted"))
        fun capped(max: Int, extra: String = "") {
            val shown = if (expanded) lines else lines.take(max)
            shown.forEach { outLines += listOf(Span(it.replace("\t", "   "), "toolOutput")) }
            if (lines.size > shown.size) outLines += hint(lines.size - shown.size, extra)
        }
        when (t.toolName) {
            "read" -> {
                val path = a.s("file_path", "path")?.let { shorten(it, o.home) }
                title += Span("read", "toolTitle", bold = true)
                title += Span(" ")
                title += Span(path ?: "[invalid arg]", if (path == null) "error" else "accent")
                val off = a?.get("offset")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val lim = a?.get("limit")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (off != null || lim != null) {
                    val start = off ?: 1
                    title += Span(if (lim != null) ":$start-${start + lim - 1}" else ":$start", "warning")
                }
                if (expanded || failed) {
                    val ls = lines.dropLastWhile { it.isEmpty() }
                    val shown = if (expanded) ls else ls.take(10)
                    shown.forEach { outLines += listOf(Span(it.replace("\t", "   "), if (failed) "error" else "toolOutput")) }
                    if (ls.size > shown.size) outLines += hint(ls.size - shown.size)
                }
            }
            "bash" -> {
                title += Span("$ ${a.s("command") ?: ""}", "toolTitle", bold = true)
                if (lines.isNotEmpty()) {
                    if (expanded || lines.size <= 5) lines.forEach { outLines += listOf(Span(it, "toolOutput")) }
                    else {
                        outLines += listOf(Span("... (${lines.size - 5} earlier lines,", "muted"), Span(" ${o.expandKey}", "dim"), Span(" to expand", "muted"), Span(")", "muted"))
                        lines.takeLast(5).forEach { outLines += listOf(Span(it, "toolOutput")) }
                    }
                }
                if (t.status == ToolStatus.OK || t.status == ToolStatus.ERROR) footer = "Took ${duration(t.durationMs ?: 0)}"
                else if (t.startedAtMillis != null) footer = "Elapsed ${duration((System.currentTimeMillis() - t.startedAtMillis!!).coerceAtLeast(0))}"
            }
            "write" -> {
                val path = a.s("file_path", "path")?.let { shorten(it, o.home) }
                title += Span("write", "toolTitle", bold = true)
                title += Span(" ")
                title += Span(path ?: "[invalid arg]", if (path == null) "error" else "accent")
                val content = a.s("content")
                if (!content.isNullOrEmpty()) {
                    val cl = content.replace("\r\n", "\n").split("\n").dropLastWhile { it.isEmpty() }
                    val shown = if (expanded) cl else cl.take(10)
                    shown.forEach { outLines += listOf(Span(it.replace("\t", "   "), "toolOutput")) }
                    if (cl.size > shown.size) outLines += hint(cl.size - shown.size, " ${cl.size} total,")
                }
                if (failed && lines.isNotEmpty()) lines.forEach { outLines += listOf(Span(it, "error")) }
            }
            "edit" -> {
                val path = a.s("file_path", "path")?.let { shorten(it, o.home) }
                title += Span("edit", "toolTitle", bold = true)
                title += Span(" ")
                title += Span(path ?: "[invalid arg]", if (path == null) "error" else "accent")
                diffOf(t.result)?.let { d ->
                    d.split("\n").forEach { l ->
                        val tok = when { l.startsWith("+") -> "toolDiffAdded"; l.startsWith("-") -> "toolDiffRemoved"; else -> "toolDiffContext" }
                        outLines += listOf(Span(l, tok))
                    }
                }
                if (failed && lines.isNotEmpty()) lines.forEach { outLines += listOf(Span(it, "error")) }
            }
            "grep", "find" -> {
                val pattern = a.s("pattern")
                val path = shorten(a.s("path")?.ifEmpty { "." } ?: ".", o.home)
                title += Span(t.toolName, "toolTitle", bold = true)
                title += Span(" ")
                title += Span(if (t.toolName == "grep") "/${pattern ?: ""}/" else pattern ?: "", "accent")
                title += Span(" in $path", "toolOutput")
                a.s("glob")?.let { title += Span(" ($it)", "toolOutput") }
                a.s("limit")?.let { title += Span(if (t.toolName == "grep") " limit $it" else " (limit $it)", "toolOutput") }
                capped(if (t.toolName == "grep") 15 else 20)
            }
            "ls" -> {
                title += Span("ls", "toolTitle", bold = true)
                title += Span(" ")
                title += Span(shorten(a.s("path")?.ifEmpty { "." } ?: ".", o.home), "accent")
                a.s("limit")?.let { title += Span(" (limit $it)", "toolOutput") }
                capped(20)
            }
            else -> {
                // 未知工具：pi 通用回退（工具名 + 参数摘要 + 输出）。
                title += Span(t.toolName, "toolTitle", bold = true)
                val argText = a?.entries?.joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v}" }.orEmpty()
                if (argText.isNotEmpty()) title += Span(" " + CellWidth.clip(argText, 60).let { if (it.length < argText.length) CellWidth.clip(argText, 57) + "..." else it }, "toolOutput")
                capped(10)
            }
        }
        val inner = o.width - 2 * PAD
        val action = "tool:${t.key}"
        val out = mutableListOf(blank(), fill(o.width, bg))
        for (l in wrapSpans(title, inner)) out += padded(l, o.width, bg, action)
        if (outLines.isNotEmpty()) {
            out += fill(o.width, bg)
            for (ol in outLines) for (l in wrapSpans(ol, inner)) out += padded(l, o.width, bg, action)
        }
        footer?.let {
            out += fill(o.width, bg)
            out += padded(listOf(Span(it, "muted")), o.width, bg, action)
        }
        out += fill(o.width, bg)
        return out
    }

    private fun diffOf(r: JsonElement?): String? = ((r as? JsonObject)?.get("details") as? JsonObject)?.get("diff")?.jsonPrimitive?.contentOrNull

    // ---------------- 提示 ----------------

    private fun notice(n: ChatItem.Notice, o: FormatOptions): List<TermLine> {
        val tok = when (n.kind) {
            NoticeKind.ERROR, NoticeKind.STOPPED, NoticeKind.INTERRUPTED -> "error"
            NoticeKind.TRUNCATED -> "warning"
            NoticeKind.COMPACTION -> "customMessageText"
            NoticeKind.INFO -> "muted"
        }
        return listOf(blank()) + wrapSpans(listOf(Span(n.text, tok)), o.width - 2 * PAD).map { padded(it, o.width) }
    }

    /** 按列宽折行（片段保持各自 token）。 */
    fun wrapSpans(spans: List<Span>, width: Int): List<List<Span>> = Markdown.wrap(spans, width)
}
