package io.github.zr987459.pigo.feature.term.format

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.ChatItem

/**
 * 终端时间线的一行（LazyColumn 的一个条目）。审查第 1 轮（PR #18）慢帧修复：原来一条消息是一个条目，
 * 流式时最后一条回复的**所有行**每个 delta 都重组、重新测量；拆成按行的条目后，只有变化的末尾几行重组，
 * 屏幕外的行不组合。
 *
 * @param key LazyColumn key：`<itemKey>#<行号>`
 * @param toolKey 工具块的 key（点行展开 / 收起），其他为 null
 */
@Immutable
data class TermRow(val key: String, val itemKey: String, val lineIndex: Int, val line: TermLine, val toolKey: String?)

/**
 * 按消息缓存排版结果：消息没变（同一实例或相等）就复用上次的行，只重排变化的消息（流式时只有最后一条）。
 * 排版选项变了（宽度、折叠状态）全部重排。只在主线程（组合）里用。
 */
class TermRowCache {
    private val cache = HashMap<String, Pair<ChatItem, List<TermRow>>>()
    private var opts: FormatOptions? = null

    /** 本次调用重排了几条消息（测试用）。 */
    var lastFormatted: Int = 0
        private set

    fun rows(items: List<ChatItem>, o: FormatOptions): List<TermRow> {
        if (o != opts) {
            cache.clear()
            opts = o
        }
        lastFormatted = 0
        val live = HashSet<String>(items.size * 2)
        val out = ArrayList<TermRow>(items.size * 4)
        for (item in items) {
            live += item.key
            val c = cache[item.key]
            val rows = if (c != null && (c.first === item || c.first == item)) {
                c.second
            } else {
                lastFormatted++
                val toolKey = (item as? ChatItem.Tool)?.key
                val prev = c?.second
                TermFormatter.format(item, o).mapIndexed { i, l ->
                    // 行内容没变就复用旧对象：Compose 以相等跳过重组，AnnotatedString 也不重建。
                    val old = prev?.getOrNull(i)
                    if (old != null && old.line == l && old.toolKey == toolKey) old else TermRow("${item.key}#$i", item.key, i, l, toolKey)
                }.also { cache[item.key] = item to it }
            }
            out.addAll(rows)
        }
        cache.keys.retainAll(live)
        return out
    }
}
