package io.github.zr987459.pigo.feature.term.format

import io.github.zr987459.pigo.feature.term.text.CellWidth

/**
 * pi 风格的 Markdown → 终端行（对照 pi-tui 1.5.0 `Markdown` 组件在主题 `getMarkdownTheme()` 下的输出）：
 * 标题 `mdHeading` 加粗、列表符 `mdListBullet`、引用 `│ `、代码块 ``` 围栏 + 两格缩进、分隔线 `─` 铺满、链接「文字 (地址)」、
 * 行内代码 `mdCode`；块之间空一行；段落内换行保留。折行按列宽（东亚宽字符占 2 列、可在字间断行）。
 */
object Markdown {
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val ORDERED = Regex("""^(\s*)(\d+)([.)])\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val FENCE = Regex("""^\s*(```+|~~~+)\s*(\S*)\s*$""")
    private val HR = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")

    private sealed interface Block
    private data class Para(val lines: List<String>) : Block
    private data class Heading(val level: Int, val text: String) : Block
    private data class ListBlock(val items: List<Triple<Int, String, String>>, val startLine: Int = -1, val endLine: Int = -1) : Block // indent, marker, text；[startLine, endLine) 为源码行号
    private data class Quote(val lines: List<String>) : Block
    private data class Code(val lang: String, val fence: String, val lines: List<String>) : Block
    private data object Rule : Block

    private fun parse(src: String): List<Block> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) { i++; continue }
            FENCE.matchEntire(l)?.let { m ->
                val fence = m.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence.take(3))) { body += lines[i]; i++ }
                i++
                out += Code(m.groupValues[2], fence, body)
                return@let
            } ?: run {
                when {
                    HEADING.matches(l) -> { val m = HEADING.matchEntire(l)!!; out += Heading(m.groupValues[1].length, m.groupValues[2]); i++ }
                    HR.matches(l) -> { out += Rule; i++ }
                    QUOTE.matches(l) -> {
                        val q = mutableListOf<String>()
                        while (i < lines.size && QUOTE.matches(lines[i])) { q += QUOTE.matchEntire(lines[i])!!.groupValues[1]; i++ }
                        out += Quote(q)
                    }
                    ORDERED.matches(l) || BULLET.matches(l) -> {
                        val startLine = i
                        val items = mutableListOf<Triple<Int, String, String>>()
                        while (i < lines.size) {
                            val cur = lines[i]
                            val om = ORDERED.matchEntire(cur)
                            val bm = BULLET.matchEntire(cur)
                            when {
                                om != null -> items += Triple(om.groupValues[1].length, om.groupValues[2] + om.groupValues[3], om.groupValues[4])
                                bm != null && !HR.matches(cur) -> items += Triple(bm.groupValues[1].length, "-", bm.groupValues[3])
                                cur.isNotBlank() && cur.startsWith("  ") && items.isNotEmpty() -> {
                                    val (ind, mk, t) = items.removeAt(items.size - 1); items += Triple(ind, mk, t + "\n" + cur.trim())
                                }
                                else -> break
                            }
                            i++
                        }
                        out += ListBlock(items, startLine, i)
                    }
                    else -> {
                        val p = mutableListOf<String>()
                        while (i < lines.size) {
                            val cur = lines[i]
                            if (cur.isBlank() || FENCE.matches(cur) || HEADING.matches(cur) || QUOTE.matches(cur) || ORDERED.matches(cur) || BULLET.matches(cur) ||
                                (HR.matches(cur) && p.isEmpty())) break
                            p += cur; i++
                        }
                        out += Para(p)
                    }
                }
            }
        }
        // 相邻的列表（有序接无序，中间没空行）合成一个，与 pi 一致不空行。
        val merged = mutableListOf<Block>()
        for (b in out) {
            val last = merged.lastOrNull()
            if (b is ListBlock && last is ListBlock && adjacentLists(last, b)) merged[merged.size - 1] = ListBlock(last.items + b.items, last.startLine, b.endLine) else merged += b
        }
        return merged
    }

    /**
     * 两个列表块在源码里紧挨着（中间没有空行）：按解析时记下的行号判断（PR #18 审查第 3 轮 Nit 3）。
     * 不再按文字 `indexOf` 找位置：同一段文字在前面出现过时，流式前缀与最终全文会得出不同结论。
     */
    private fun adjacentLists(a: ListBlock, b: ListBlock): Boolean = a.endLine >= 0 && b.startLine == a.endLine

    /**
     * 流式增量的切分点（审查第 2 轮慢帧）：最后一个「代码围栏之外的空行」之前那个换行的位置；没有返回 -1。
     * 空行总是结束一个块（段落、列表、引用都在空行处断开，围栏内除外），而 [render] 在块之间固定空一行，所以
     * `render(全文) == render(前缀) + 空行 + render(其余)`。流式时前缀的排版结果可以缓存，每个 delta 只排最后一段。
     */
    fun stableCut(text: String): Int {
        var pos = 0
        var fence: String? = null
        var cut = -1
        var seenContent = false
        val n = text.length
        while (pos <= n) {
            var end = text.indexOf('\n', pos)
            if (end < 0) end = n
            var a = pos
            while (a < end && (text[a] == ' ' || text[a] == '\t')) a++
            val blank = a == end
            if (fence != null) {
                if (text.startsWith(fence.take(3), a)) fence = null
            } else if (blank) {
                if (seenContent && pos > 0) cut = pos - 1
            } else {
                seenContent = true
                if (text.startsWith("```", a) || text.startsWith("~~~", a)) {
                    FENCE.matchEntire(text.substring(pos, end))?.let { fence = it.groupValues[1] }
                }
            }
            if (end == n) break
            pos = end + 1
        }
        // 围栏没闭合：切分点之后在围栏里也没关系（前缀在围栏之前），但切分点本身必须在围栏外——上面只在围栏外记录。
        return cut
    }

    // ---------------- 行内 ----------------

    private val INLINE = Regex("""(\*\*|__)(.+?)\1|(?<![*\w])([*_])(?!\s)(.+?)(?<!\s)\3(?![*\w])|`([^`]+)`|\[([^\]]*)]\(([^)\s]+)(?:\s+"[^"]*")?\)|~~(.+?)~~""")

    fun inline(text: String, base: String?, italic: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        var pos = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > pos) out += Span(unescape(text.substring(pos, m.range.first)), base, italic = italic)
            val g = m.groupValues
            when {
                g[2].isNotEmpty() -> out += inline(g[2], base, italic).map { it.copy(bold = true) }
                g[4].isNotEmpty() -> out += inline(g[4], base, true)
                g[5].isNotEmpty() -> out += Span(g[5], "mdCode", italic = italic)
                g[7].isNotEmpty() -> {
                    val label = g[6]
                    if (label.isEmpty() || label == g[7] || "mailto:$label" == g[7]) out += Span(g[7], "mdLink", italic = italic)
                    else { out += Span(label, "mdLink", italic = italic); out += Span(" (${g[7]})", "mdLinkUrl", italic = italic) }
                }
                g[8].isNotEmpty() -> out += Span(g[8], base, italic = italic)
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) out += Span(unescape(text.substring(pos)), base, italic = italic)
        return out
    }

    private fun unescape(s: String) = s.replace(Regex("""\\([\\`*_{}\[\]()#+\-.!>])"""), "$1")

    // ---------------- 渲染 ----------------

    /** 渲染成最多 [width] 列的行（不含左右内边距）。 */
    fun render(src: String, width: Int, baseToken: String? = null, italic: Boolean = false): List<List<Span>> {
        val out = mutableListOf<List<Span>>()
        val blocks = parse(src)
        blocks.forEachIndexed { bi, b ->
            if (bi > 0) out += emptyList<Span>()
            when (b) {
                is Para -> b.lines.forEach { out += wrap(inline(it.trim(), baseToken, italic), width) }
                is Heading -> {
                    val prefix = if (b.level >= 3) "#".repeat(b.level) + " " else ""
                    out += wrap(listOf(Span(prefix, "mdHeading", bold = true)).filter { it.text.isNotEmpty() } + inline(b.text, "mdHeading").map { it.copy(bold = true) }, width)
                }
                is ListBlock -> b.items.forEach { (indent, marker, text) ->
                    val lead = " ".repeat(indent) + marker + " "
                    val leadW = CellWidth.width(lead)
                    val textLines = text.split("\n")
                    textLines.forEachIndexed { ti, t ->
                        val wrapped = wrap(inline(t.trim(), baseToken, italic), (width - leadW).coerceAtLeast(1))
                        wrapped.forEachIndexed { wi, w ->
                            out += (if (ti == 0 && wi == 0) listOf(Span(" ".repeat(indent)), Span(marker, "mdListBullet"), Span(" ")) else listOf(Span(" ".repeat(leadW)))) + w
                        }
                    }
                }
                is Quote -> b.lines.forEach { q ->
                    wrap(inline(q, "mdQuote", true), width - 2).forEach { w -> out += listOf(Span("│ ", "mdQuoteBorder")) + w }
                }
                is Code -> {
                    out += listOf(Span(b.fence + b.lang, "mdCodeBlockBorder"))
                    b.lines.forEach { cl -> wrap(listOf(Span(cl.replace("\t", "   "), "mdCodeBlock")), width - 2, keepSpaces = true).forEach { w -> out += listOf(Span("  ")) + w } }
                    out += listOf(Span(b.fence, "mdCodeBlockBorder"))
                }
                Rule -> out += listOf(Span("─".repeat(width), "mdHr"))
            }
        }
        return out
    }

    /**
     * 折行（pi-tui `wrapTextWithAnsi` 的规则）：按空格分词贪心填充，断行处的空格丢掉；东亚宽字符之间可以断；
     * 单词比整行还长时按列硬断。片段保持各自样式。
     */
    fun wrap(spans: List<Span>, width: Int, keepSpaces: Boolean = false): List<List<Span>> {
        if (width <= 0) return listOf(spans)
        data class Ch(val s: String, val w: Int, val style: Span)
        val chars = mutableListOf<Ch>()
        for (sp in spans) {
            var i = 0
            while (i < sp.text.length) {
                val cp = sp.text.codePointAt(i)
                val n = Character.charCount(cp)
                chars += Ch(sp.text.substring(i, i + n), CellWidth.of(cp), sp)
                i += n
            }
        }
        if (chars.isEmpty()) return listOf(emptyList())
        // 原子：连续的非空格窄字符为一个词；每个宽字符单独成一个原子；空格单独成原子。
        val atoms = mutableListOf<MutableList<Ch>>()
        for (c in chars) {
            val last = atoms.lastOrNull()
            val isSpace = c.s == " "
            val joinable = last != null && !isSpace && c.w == 1 && last.last().s != " " && last.last().w == 1
            if (joinable) last!!.add(c) else atoms += mutableListOf(c)
        }
        val lines = mutableListOf<MutableList<Ch>>(mutableListOf())
        var cur = 0
        for (a in atoms) {
            val aw = a.sumOf { it.w }
            val isSpace = a.size == 1 && a[0].s == " "
            if (isSpace) {
                if (cur + 1 <= width) { lines.last().add(a[0]); cur += 1 } else if (keepSpaces) { lines += mutableListOf<Ch>(); cur = 0 }
                continue
            }
            if (cur + aw > width && cur > 0) {
                // 去掉行尾空格再换行
                val l = lines.last()
                if (!keepSpaces) while (l.isNotEmpty() && l.last().s == " ") { l.removeAt(l.size - 1) }
                lines += mutableListOf<Ch>(); cur = 0
            }
            if (aw > width) {
                for (c in a) {
                    if (cur + c.w > width) { lines += mutableListOf<Ch>(); cur = 0 }
                    lines.last().add(c); cur += c.w
                }
            } else { lines.last().addAll(a); cur += aw }
        }
        if (!keepSpaces) for (l in lines) while (l.isNotEmpty() && l.last().s == " ") l.removeAt(l.size - 1)
        return lines.map { l ->
            val res = mutableListOf<Span>()
            for (c in l) {
                val last = res.lastOrNull()
                if (last != null && last.token == c.style.token && last.bold == c.style.bold && last.italic == c.style.italic && last.segment == c.style.segment) res[res.size - 1] = last.copy(text = last.text + c.s)
                else res += c.style.copy(text = c.s)
            }
            res
        }
    }
}
