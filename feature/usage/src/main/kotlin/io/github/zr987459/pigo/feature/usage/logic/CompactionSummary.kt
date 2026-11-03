package io.github.zr987459.pigo.feature.usage.logic

/**
 * 压缩摘要的解析（U4）：把 pi 写在摘要末尾的 `<read-files>` / `<modified-files>` 段落拆出来，
 * 正文单独显示。
 *
 * 移植自 agegr/pi-web@025e24e `lib/compaction-summary.ts:1-34`，MIT。
 */
data class ParsedCompactionSummary(
    val body: String,
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

object CompactionSummary {
    private val FILE_SECTION = Regex("<(read-files|modified-files)>\\s*([\\s\\S]*?)\\s*</\\1>")
    private val TRAILING_FILE_SECTIONS = Regex(
        "(?:\\r?\\n){2,}((?:[ \\t]*<(?:read-files|modified-files)>[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n[ \\t]*</(?:read-files|modified-files)>[ \\t]*(?:\\r?\\n)?)+)\\s*$",
    )

    fun parse(summary: String): ParsedCompactionSummary {
        val read = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val m = TRAILING_FILE_SECTIONS.find(summary)
        val meta = m?.groupValues?.get(1).orEmpty()
        val body = if (m == null) summary else summary.substring(0, m.range.first)
        for (s in FILE_SECTION.findAll(meta)) {
            val files = s.groupValues[2].split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            if (s.groupValues[1] == "read-files") read += files else modified += files
        }
        return ParsedCompactionSummary(body.trim(), read, modified)
    }
}
