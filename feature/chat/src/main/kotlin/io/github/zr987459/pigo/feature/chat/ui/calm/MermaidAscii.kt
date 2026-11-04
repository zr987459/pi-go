package io.github.zr987459.pigo.feature.chat.ui.calm

/**
 * Mermaid 字符画（DESIGN-r3 §0 取舍：UI 形态点「图示」用与终端形态相同的字符画，不打包 mermaid.js）。
 * 支持 `graph` / `flowchart` 的 `A --> B`、`A -- 文字 --> B`、`A[标签]`、`A(标签)`、`A{标签}` 写法，按边的出现顺序画成
 * 自上而下的框线图；其他图类型原样返回代码并加一行提示。纯函数。
 */
object MermaidAscii {
    private val node = Regex("""([A-Za-z0-9_\u4e00-\u9fff]+)\s*(?:\[([^\]]*)]|\(([^)]*)\)|\{([^}]*)})?""")
    private val edge = Regex("""^\s*(.+?)\s*(?:--\s*([^-][^>]*?)\s*-->|-->|---|-\.->|==>)\s*(?:\|([^|]*)\|\s*)?(.+?)\s*;?\s*$""")

    fun render(code: String): String {
        val lines = code.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
        val head = lines.firstOrNull() ?: return code
        if (!(head.startsWith("graph") || head.startsWith("flowchart"))) return "（字符画只支持 graph / flowchart，以下为原始代码）\n$code"
        val labels = linkedMapOf<String, String>()
        val edges = mutableListOf<Triple<String, String, String?>>()
        fun parseNode(s: String): String? {
            val m = node.matchEntire(s.trim()) ?: return null
            val id = m.groupValues[1]
            val label = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4]).firstOrNull { it.isNotEmpty() }
            if (label != null) labels[id] = label else labels.putIfAbsent(id, id)
            return id
        }
        for (l in lines.drop(1)) {
            val e = edge.matchEntire(l)
            if (e != null) {
                val a = parseNode(e.groupValues[1]) ?: continue
                val b = parseNode(e.groupValues[4]) ?: continue
                val text = e.groupValues[2].ifBlank { e.groupValues[3] }.trim().ifEmpty { null }
                edges += Triple(a, b, text)
            } else parseNode(l)
        }
        if (labels.isEmpty()) return code
        val sb = StringBuilder()
        fun box(t: String) {
            val w = displayWidth(t) + 2
            sb.append("┌").append("─".repeat(w)).append("┐\n")
            sb.append("│ ").append(t).append(" │\n")
            sb.append("└").append("─".repeat(w)).append("┘\n")
        }
        val drawn = mutableSetOf<String>()
        var last: String? = null
        for ((a, b, t) in edges) {
            if (last != a) {
                if (a in drawn) sb.append("  ↺ ").append(labels[a]).append('\n') else { box(labels[a] ?: a); drawn += a }
            }
            sb.append("   │").append(t?.let { " $it" } ?: "").append('\n')
            sb.append("   ▼\n")
            if (b in drawn) sb.append("  ↺ ").append(labels[b]).append('\n') else { box(labels[b] ?: b); drawn += b }
            last = b
        }
        for ((id, l) in labels) if (id !in drawn) box(l)
        return sb.toString().trimEnd()
    }

    /** 中日韩字符按 2 列算（与终端形态一致的宽度规则的简化版）。 */
    internal fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) w += if (ch.code in 0x1100..0xFFEF && ch.code !in 0x2000..0x303E) 2 else 1
        return w
    }
}
