package io.github.zr987459.pigo.core.protocol

/**
 * 压缩摘要拆分（U4）：pi 在摘要末尾追加 `<read-files>…</read-files>`、`<modified-files>…</modified-files>` 段，
 * 拆成正文 + 读过的文件 + 改过的文件。
 *
 * 移植自 Pi-Web `lib/compaction-summary.ts:1-34`（agegr/pi-web@025e24e，MIT）。
 */
object CompactionSummary {
    data class Parsed(val body: String, val readFiles: List<String>, val modifiedFiles: List<String>)

    private val FILE_SECTION = Regex("<(read-files|modified-files)>\\s*([\\s\\S]*?)\\s*</\\1>")
    private val TRAILING_FILE_SECTIONS = Regex(
        "(?:\\r?\\n){2,}((?:[ \\t]*<(?:read-files|modified-files)>[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n[ \\t]*</(?:read-files|modified-files)>[ \\t]*(?:\\r?\\n)?)+)\\s*$",
    )

    fun parse(summary: String): Parsed {
        val read = ArrayList<String>()
        val modified = ArrayList<String>()
        val m = TRAILING_FILE_SECTIONS.find(summary)
        val metadata = m?.groupValues?.get(1) ?: ""
        val body = if (m == null) summary else summary.substring(0, m.range.first)
        for (sec in FILE_SECTION.findAll(metadata)) {
            val files = sec.groupValues[2].split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            if (sec.groupValues[1] == "read-files") read += files else modified += files
        }
        return Parsed(body.trim(), read, modified)
    }
}
