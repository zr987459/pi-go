package io.github.zr987459.pigo.core.ui.calm

/**
 * ⌘ 命令中心与 `/` 补全的搜索（DESIGN-r3 §2.0、§4.3）：按中文标题、斜杠名、拼音首字母三种方式匹配。
 * 纯函数，不依赖动作表类型，方便在 `core/ui` 做 JVM 单测。
 */
object CommandSearch {
    /** 一个可搜索项：[key] 原样返回；[title] 中文名；[aliases] 斜杠名（不带 `/`）与 pi 原命令名。 */
    data class Entry(val key: String, val title: String, val aliases: List<String> = emptyList())

    /** 标题的拼音首字母串（非汉字原样小写保留，空白与标点去掉），如「分支树」→ "fzs"，「MCP 服务器」→ "mcpfwq"。 */
    fun initials(title: String): String = buildString {
        for (c in title) {
            val i = PinyinTable.initial(c)
            when {
                i != null -> append(i)
                c.isLetterOrDigit() -> append(c.lowercaseChar())
            }
        }
    }

    /**
     * 匹配分数，越大越靠前；0 = 不匹配。空查询全部匹配（分数 1）。
     * 顺序：斜杠名完全相等 > 斜杠名前缀 > 标题包含 > 拼音首字母前缀 > 拼音首字母包含 > 斜杠名包含。
     */
    fun score(entry: Entry, rawQuery: String): Int {
        val q = rawQuery.trim().removePrefix("/").lowercase()
        if (q.isEmpty()) return 1
        val aliases = entry.aliases.map { it.lowercase().removePrefix("/") }
        if (aliases.any { it == q }) return 100
        if (aliases.any { it.startsWith(q) }) return 80
        if (entry.title.lowercase().contains(q)) return 70
        val ini = initials(entry.title)
        if (ini.startsWith(q)) return 60
        if (ini.contains(q)) return 50
        if (aliases.any { it.contains(q) }) return 40
        return 0
    }

    /** 过滤并按分数排序（同分保持原顺序）。 */
    fun <T> filter(items: List<T>, query: String, entryOf: (T) -> Entry): List<T> =
        items.map { it to score(entryOf(it), query) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
}
