package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem

/**
 * 搜索本会话（清单 §9「消息区搜索」，审查第 2 轮 N1）：在 `host.chat.value.items` 里按关键词（不分大小写）
 * 查用户消息、助手正文与思考、工具名与参数、提示条，每条命中给一行摘录。结果项的 key 就是 `ChatItem.key`，
 * 选中后执行 `transcript.jump`（args = key）跳到那条。
 */
object TranscriptSearch {
    data class Hit(val key: String, val role: String, val snippet: String)

    fun search(items: List<ChatItem>, rawQuery: String, max: Int = 200): List<Hit> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Hit>()
        for (item in items) {
            val (role, text) = when (item) {
                is ChatItem.User -> "你" to item.text
                is ChatItem.Assistant -> "pi" to item.blocks.joinToString("\n") { b ->
                    when (b) {
                        is ContentBlock.Text -> b.text
                        is ContentBlock.Thinking -> b.text
                        is ContentBlock.ToolCall -> b.name + " " + b.argsJson
                    }
                }
                is ChatItem.Tool -> "工具" to (item.toolName + " " + (item.args?.toString() ?: "") + " " + (item.result?.toString() ?: ""))
                is ChatItem.Notice -> "提示" to item.text
                is ChatItem.Raw -> (item.role ?: "其他") to item.json.toString()
            }
            val at = text.indexOf(q, ignoreCase = true)
            if (at < 0) continue
            out += Hit(item.key, role, snippet(text, at, q.length))
            if (out.size >= max) break
        }
        return out
    }

    /** 命中处前后各取一段，换行压成空格。 */
    fun snippet(text: String, at: Int, len: Int, context: Int = 24): String {
        val start = (at - context).coerceAtLeast(0)
        val end = (at + len + context).coerceAtMost(text.length)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }

    fun groups(hits: List<Hit>): List<SelectGroup> =
        if (hits.isEmpty()) emptyList() else listOf(SelectGroup("${hits.size} 条结果", hits.map { SelectItem(it.key, it.snippet, it.role) }))
}
