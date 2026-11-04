package io.github.zr987459.pigo.core.session.text

/**
 * `edit` 工具的行级 diff（C11）。
 *
 * 优先用 pi 结果里的 `details.patch`（标准 unified diff，见 1.5.0 `dist/core/tools/edit.d.ts` 的 EditToolDetails）；
 * 没有结果（还在执行、被拒绝）时，按参数 `edits[].oldText/newText` 逐段做 LCS 行 diff。
 */
enum class DiffKind { CONTEXT, ADDED, REMOVED, HUNK, FILE }

data class DiffLine(val kind: DiffKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

object LineDiff {
    /** 单段太大时不做 O(n·m) 的 LCS，直接整段「删 + 增」。 */
    private const val MAX_CELLS = 400_000

    fun diff(old: String, new: String): List<DiffLine> {
        val a = splitLines(old)
        val b = splitLines(new)
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return a.mapIndexed { i, t -> DiffLine(DiffKind.REMOVED, t, oldNo = i + 1) } +
                b.mapIndexed { i, t -> DiffLine(DiffKind.ADDED, t, newNo = i + 1) }
        }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffLine>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffKind.CONTEXT, a[i], i + 1, j + 1); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffKind.REMOVED, a[i], oldNo = i + 1); i++ }
                else -> { out += DiffLine(DiffKind.ADDED, b[j], newNo = j + 1); j++ }
            }
        }
        while (i < a.size) { out += DiffLine(DiffKind.REMOVED, a[i], oldNo = i + 1); i++ }
        while (j < b.size) { out += DiffLine(DiffKind.ADDED, b[j], newNo = j + 1); j++ }
        return out
    }

    /** 多段 edits：每段之间插一行分隔（HUNK）。 */
    fun diffEdits(edits: List<Pair<String, String>>): List<DiffLine> = buildList {
        edits.forEachIndexed { k, (o, n) ->
            if (edits.size > 1) add(DiffLine(DiffKind.HUNK, "第 ${k + 1} 处修改"))
            addAll(diff(o, n))
        }
    }

    /** 解析 unified diff（`---`/`+++` 文件头、`@@ -a,b +c,d @@` 段头）。 */
    fun parseUnified(patch: String): List<DiffLine> {
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@(.*)$")
        for (raw in splitLines(patch)) {
            when {
                raw.startsWith("--- ") || raw.startsWith("+++ ") -> out += DiffLine(DiffKind.FILE, raw)
                raw.startsWith("diff ") || raw.startsWith("index ") || raw.startsWith("Index: ") || raw.startsWith("===") -> Unit
                raw.startsWith("@@") -> {
                    val m = hunk.find(raw)
                    if (m != null) {
                        oldNo = m.groupValues[1].toInt()
                        newNo = m.groupValues[2].toInt()
                    }
                    out += DiffLine(DiffKind.HUNK, raw)
                }
                raw.startsWith("+") -> { out += DiffLine(DiffKind.ADDED, raw.substring(1), newNo = newNo); newNo++ }
                raw.startsWith("-") -> { out += DiffLine(DiffKind.REMOVED, raw.substring(1), oldNo = oldNo); oldNo++ }
                raw.startsWith("\\") -> Unit // "\ No newline at end of file"
                else -> {
                    out += DiffLine(DiffKind.CONTEXT, raw.removePrefix(" "), oldNo, newNo)
                    oldNo++; newNo++
                }
            }
        }
        return out
    }

    fun stats(lines: List<DiffLine>): Pair<Int, Int> =
        lines.count { it.kind == DiffKind.ADDED } to lines.count { it.kind == DiffKind.REMOVED }

    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val l = s.split('\n').map { it.removeSuffix("\r") }
        return if (l.last().isEmpty()) l.dropLast(1) else l
    }
}
