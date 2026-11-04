package io.github.zr987459.pigo.core.session.text

/**
 * ANSI 转义序列解析（终端输出查看器，feature-gap F5 第一层）。
 *
 * 只认 SGR（`ESC[…m`）：粗体、暗淡、斜体、下划线、反显、16 色 / 256 色 / 真彩色前景与背景、重置；
 * 其他 CSI、OSC（含 pi 的 `ESC_pi:c BEL` 光标标记）、单字符转义一律丢弃。`\r` 回到行首覆盖（进度条只留最后一次）。
 * 去转义的正则参考 Pi-Web `lib/ansi.ts`（MIT，见 docs/notices/feat-files.md）。
 */
object Ansi {
    data class Style(
        val fg: Int? = null,
        val bg: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    ) {
        val isPlain: Boolean get() = this == PLAIN
    }

    data class Span(val start: Int, val end: Int, val style: Style)

    /** 一行：纯文本与样式区间（区间按 start 排序、不重叠，只含非默认样式）。 */
    data class Line(val text: String, val spans: List<Span>)

    val PLAIN = Style()

    // 先匹配带结束符的串（OSC `ESC]`、DCS `ESCP`、APC `ESC_` 如 pi 的光标标记 `ESC_pi:c BEL`），再匹配 CSI，最后是单字符转义；
    // 单字符的区间 `@-Z\\-_` 本身包含 `]`、`_`，放在前面会把 OSC 只吃掉两个字符。
    private val ESCAPE = Regex("""\u001B(?:[\]P_^X][^\u0007\u001B]*(?:\u0007|\u001B\\)|\[[0-?]*[ -/]*[@-~]|[@-Z\\-_])""")

    fun strip(text: String): String = ESCAPE.replace(text, "")

    /** 解析整段输出为行。样式跨行延续（与终端一致）。 */
    fun parse(text: String): List<Line> {
        val out = ArrayList<Line>()
        var style = PLAIN
        val raw = text.split('\n')
        for ((idx, r) in raw.withIndex()) {
            if (idx == raw.lastIndex && r.isEmpty() && raw.size > 1) break
            val (line, next) = parseLine(r, style)
            out += line
            style = next
        }
        return out
    }

    /** 解析一行（不含 `\n`），返回该行与行末的样式。 */
    fun parseLine(raw: String, start: Style = PLAIN): Pair<Line, Style> {
        // 每个字符带样式，处理 \r 覆盖
        val chars = StringBuilder()
        val styles = ArrayList<Style>()
        var col = 0
        var style = start
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\u001B') {
                val m = ESCAPE.matchAt(raw, i)
                if (m != null) {
                    val s = m.value
                    if (s.length >= 3 && s[1] == '[' && s.last() == 'm') style = applySgr(style, s.substring(2, s.length - 1))
                    i += s.length
                    continue
                }
                i++
                continue
            }
            when (c) {
                '\r' -> { col = 0; i++; continue }
                '\b' -> { if (col > 0) col--; i++; continue }
                '\t' -> {
                    val spaces = 8 - (col % 8)
                    repeat(spaces) { put(chars, styles, col++, ' ', style) }
                    i++; continue
                }
            }
            if (c < ' ' && c != '\t') { i++; continue }
            put(chars, styles, col++, c, style)
            i++
        }
        // 合并区间
        val spans = ArrayList<Span>()
        var s0 = 0
        for (k in 1..chars.length) {
            if (k == chars.length || styles[k] != styles[s0]) {
                if (!styles[s0].isPlain) spans += Span(s0, k, styles[s0])
                s0 = k
            }
        }
        return Line(chars.toString(), spans) to style
    }

    private fun put(chars: StringBuilder, styles: MutableList<Style>, col: Int, c: Char, s: Style) {
        if (col < chars.length) { chars.setCharAt(col, c); styles[col] = s }
        else {
            while (chars.length < col) { chars.append(' '); styles += PLAIN }
            chars.append(c); styles += s
        }
    }

    internal fun applySgr(cur: Style, params: String): Style {
        val ps = if (params.isEmpty()) listOf(0) else params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = cur
        var i = 0
        while (i < ps.size) {
            when (val p = ps[i]) {
                0 -> s = PLAIN
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                in 30..37 -> s = s.copy(fg = BASIC[p - 30])
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = BASIC[p - 40])
                49 -> s = s.copy(bg = null)
                in 90..97 -> s = s.copy(fg = BRIGHT[p - 90])
                in 100..107 -> s = s.copy(bg = BRIGHT[p - 100])
                38, 48 -> {
                    val mode = ps.getOrNull(i + 1)
                    var color: Int? = null
                    if (mode == 5) { color = ps.getOrNull(i + 2)?.let(::xterm256); i += 2 }
                    else if (mode == 2) {
                        val r = ps.getOrNull(i + 2) ?: 0; val g = ps.getOrNull(i + 3) ?: 0; val b = ps.getOrNull(i + 4) ?: 0
                        color = argb(r, g, b); i += 4
                    }
                    s = if (p == 38) s.copy(fg = color) else s.copy(bg = color)
                }
            }
            i++
        }
        return s
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // 对比度在深浅背景上都可读的 16 色（接近 VS Code 终端配色）
    private val BASIC = intArrayOf(
        argb(0x55, 0x55, 0x55), argb(0xCD, 0x31, 0x31), argb(0x0D, 0xBC, 0x79), argb(0xC4, 0xA0, 0x00),
        argb(0x24, 0x72, 0xC8), argb(0xBC, 0x3F, 0xBC), argb(0x11, 0xA8, 0xCD), argb(0xA0, 0xA0, 0xA0),
    )
    private val BRIGHT = intArrayOf(
        argb(0x76, 0x76, 0x76), argb(0xF1, 0x4C, 0x4C), argb(0x23, 0xD1, 0x8B), argb(0xE5, 0xC0, 0x10),
        argb(0x3B, 0x8E, 0xEA), argb(0xD6, 0x70, 0xD6), argb(0x29, 0xB8, 0xDB), argb(0xE5, 0xE5, 0xE5),
    )

    fun xterm256(n: Int): Int {
        val c = n.coerceIn(0, 255)
        return when {
            c < 8 -> BASIC[c]
            c < 16 -> BRIGHT[c - 8]
            c < 232 -> {
                val k = c - 16
                val lv = intArrayOf(0, 95, 135, 175, 215, 255)
                argb(lv[k / 36], lv[(k / 6) % 6], lv[k % 6])
            }
            else -> { val v = 8 + (c - 232) * 10; argb(v, v, v) }
        }
    }
}

/** 输出查看器的搜索：不区分大小写，返回 (行号, 列) 列表。 */
object OutputSearch {
    data class Hit(val line: Int, val start: Int, val end: Int)

    fun find(lines: List<String>, query: String, limit: Int = 10_000): List<Hit> {
        if (query.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for ((i, l) in lines.withIndex()) {
            var from = 0
            while (true) {
                val k = l.indexOf(query, from, ignoreCase = true)
                if (k < 0) break
                hits += Hit(i, k, k + query.length)
                if (hits.size >= limit) return hits
                from = k + maxOf(1, query.length)
            }
        }
        return hits
    }
}
