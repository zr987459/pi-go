package io.github.zr987459.pigo.core.workspace

/**
 * `git status --porcelain=v2 -z --branch` 与统一 diff 的解析。
 * 状态分类参考 Pi-Web `lib/git-status.ts`（MIT，见 docs/notices/feat-files.md），改为解析 v2 格式。
 */
object GitParse {
    enum class Kind { MODIFIED, ADDED, DELETED, RENAMED, COPIED, UNTRACKED, CONFLICT, TYPE_CHANGED, IGNORED }

    data class Entry(
        /** 相对仓库根的路径（`/` 分隔）。 */
        val path: String,
        val originalPath: String? = null,
        val index: Char,
        val worktree: Char,
        val kind: Kind,
    ) {
        val staged: Boolean get() = index != '.' && index != '?' && index != '!'
    }

    data class Status(
        val branch: String? = null,
        val upstream: String? = null,
        val ahead: Int = 0,
        val behind: Int = 0,
        val entries: List<Entry> = emptyList(),
    )

    /** 解析 `-z` 输出：记录以 NUL 分隔，重命名 / 复制记录后面紧跟一条原路径记录。未知记录跳过，不抛异常。 */
    fun porcelainV2(output: String): Status {
        val recs = output.split('\u0000')
        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val entries = ArrayList<Entry>()
        var i = 0
        while (i < recs.size) {
            val r = recs[i]
            i++
            if (r.isEmpty()) continue
            when (r[0]) {
                '#' -> {
                    val parts = r.split(' ')
                    when (parts.getOrNull(1)) {
                        "branch.head" -> branch = parts.getOrNull(2)?.takeIf { it != "(detached)" } ?: "(分离头指针)"
                        "branch.upstream" -> upstream = parts.getOrNull(2)
                        "branch.ab" -> {
                            ahead = parts.getOrNull(2)?.removePrefix("+")?.toIntOrNull() ?: 0
                            behind = parts.getOrNull(3)?.removePrefix("-")?.toIntOrNull() ?: 0
                        }
                    }
                }
                '1' -> {
                    // 1 XY sub mH mI mW hH hI path
                    val f = r.split(' ', limit = 9)
                    if (f.size == 9 && f[1].length == 2) entries += entry(f[8], null, f[1][0], f[1][1])
                }
                '2' -> {
                    // 2 XY sub mH mI mW hH hI Xscore path \0 origPath
                    val f = r.split(' ', limit = 10)
                    val orig = recs.getOrNull(i)
                    i++
                    if (f.size == 10 && f[1].length == 2) entries += entry(f[9], orig?.ifEmpty { null }, f[1][0], f[1][1])
                }
                'u' -> {
                    // u XY sub m1 m2 m3 mW h1 h2 h3 path
                    val f = r.split(' ', limit = 11)
                    if (f.size == 11 && f[1].length == 2) entries += Entry(f[10], null, f[1][0], f[1][1], Kind.CONFLICT)
                }
                '?' -> if (r.length > 2) entries += Entry(r.substring(2), null, '?', '?', Kind.UNTRACKED)
                '!' -> if (r.length > 2) entries += Entry(r.substring(2), null, '!', '!', Kind.IGNORED)
            }
        }
        return Status(branch, upstream, ahead, behind, entries)
    }

    private fun entry(path: String, orig: String?, x: Char, y: Char): Entry {
        val kind = when {
            x == 'R' || y == 'R' -> Kind.RENAMED
            x == 'C' || y == 'C' -> Kind.COPIED
            x == 'A' -> Kind.ADDED
            x == 'D' || y == 'D' -> Kind.DELETED
            x == 'T' || y == 'T' -> Kind.TYPE_CHANGED
            else -> Kind.MODIFIED
        }
        return Entry(path, orig, x, y, kind)
    }

    // ------------------------------------------------------------------ 统一 diff

    enum class LineType { CONTEXT, ADD, DEL, NO_NEWLINE }

    data class Line(val type: LineType, val text: String, val oldNo: Int?, val newNo: Int?)

    data class Hunk(val header: String, val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<Line>)

    data class FileDiff(
        val oldPath: String?,
        val newPath: String?,
        val hunks: List<Hunk>,
        val binary: Boolean = false,
        val newFile: Boolean = false,
        val deletedFile: Boolean = false,
    ) {
        val path: String get() = newPath ?: oldPath ?: ""
        val additions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == LineType.ADD } }
        val deletions: Int get() = hunks.sumOf { h -> h.lines.count { it.type == LineType.DEL } }
    }

    private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")

    /** 解析 `git diff` 的统一 diff（可含多个文件）。不认识的行忽略。 */
    fun unifiedDiff(text: String): List<FileDiff> {
        val files = ArrayList<FileDiff>()
        var oldPath: String? = null
        var newPath: String? = null
        var binary = false
        var newFile = false
        var deleted = false
        var hunks = ArrayList<Hunk>()
        var cur: MutableList<Line>? = null
        var hHeader = ""
        var hOld = 0; var hOldN = 0; var hNew = 0; var hNewN = 0
        var oldNo = 0; var newNo = 0
        var started = false

        fun closeHunk() {
            cur?.let { hunks += Hunk(hHeader, hOld, hOldN, hNew, hNewN, it.toList()) }
            cur = null
        }
        fun closeFile() {
            closeHunk()
            if (started) files += FileDiff(oldPath, newPath, hunks, binary, newFile, deleted)
            oldPath = null; newPath = null; binary = false; newFile = false; deleted = false
            hunks = ArrayList()
            started = false
        }

        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            val c = cur
            if (c != null) {
                // 按 hunk 头里的行数判断是否还在 hunk 内：内容以 "+++ "/"--- " 开头的行也不会被误认成文件头。
                val o = c.count { it.type == LineType.DEL || it.type == LineType.CONTEXT }
                val n = c.count { it.type == LineType.ADD || it.type == LineType.CONTEXT }
                val inside = o < hOldN || n < hNewN
                if (line.startsWith("\\")) { c += Line(LineType.NO_NEWLINE, line, null, null); continue }
                if (inside) {
                    when {
                        line.startsWith("+") && n < hNewN -> { c += Line(LineType.ADD, line.substring(1), null, newNo++); continue }
                        line.startsWith("-") && o < hOldN -> { c += Line(LineType.DEL, line.substring(1), oldNo++, null); continue }
                        (line.startsWith(" ") || line.isEmpty()) && o < hOldN && n < hNewN -> {
                            c += Line(LineType.CONTEXT, line.removePrefix(" "), oldNo++, newNo++); continue
                        }
                    }
                }
                closeHunk()
            }
            when {
                line.startsWith("diff --git ") -> {
                    closeFile(); started = true
                    val rest = line.removePrefix("diff --git ")
                    val sp = rest.indexOf(" b/")
                    if (sp > 0) { oldPath = rest.substring(0, sp).removePrefix("a/"); newPath = rest.substring(sp + 3) }
                }
                line.startsWith("new file mode") -> { newFile = true; oldPath = null }
                line.startsWith("deleted file mode") -> { deleted = true; newPath = null }
                line.startsWith("Binary files ") || line == "GIT binary patch" -> binary = true
                line.startsWith("--- ") -> { started = true; oldPath = pathOf(line.substring(4), "a/") }
                line.startsWith("+++ ") -> { started = true; newPath = pathOf(line.substring(4), "b/") }
                else -> HUNK.matchEntire(line)?.let { m ->
                    started = true
                    closeHunk()
                    hHeader = line
                    hOld = m.groupValues[1].toInt(); hOldN = m.groupValues[2].ifEmpty { "1" }.toInt()
                    hNew = m.groupValues[3].toInt(); hNewN = m.groupValues[4].ifEmpty { "1" }.toInt()
                    oldNo = hOld; newNo = hNew
                    cur = ArrayList()
                }
            }
        }
        closeFile()
        return files
    }

    private fun pathOf(s: String, prefix: String): String? {
        val p = s.substringBefore('\t')
        return if (p == "/dev/null") null else p.removePrefix(prefix)
    }

    /** 未跟踪文件的「全部新增」diff（参考 Pi-Web `createAddedFilePatch`）。 */
    fun addedFilePatch(path: String, content: String): String {
        val trailing = content.endsWith("\n")
        val lines = content.split('\n').toMutableList()
        if (trailing) lines.removeAt(lines.lastIndex)
        val body = lines.joinToString("\n") { "+$it" }
        val marker = if (!trailing && lines.isNotEmpty()) "\n\\ No newline at end of file" else ""
        return listOf("diff --git a/$path b/$path", "new file mode 100644", "--- /dev/null", "+++ b/$path", "@@ -0,0 +1,${lines.size} @@", body + marker).joinToString("\n")
    }
}
