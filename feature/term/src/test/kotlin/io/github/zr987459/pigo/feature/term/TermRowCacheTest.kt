package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.feature.term.format.FormatOptions
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.format.TermRowCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #18 审查第 1 轮慢帧修复：按行拆分 + 只重排变化的消息 + 不变的行复用同一对象（Compose 跳过重组）。 */
class TermRowCacheTest {
    private val o = FormatOptions(width = 60)
    private fun asst(key: String, text: String, streaming: Boolean = true) =
        ChatItem.Assistant(key, listOf(ContentBlock.Text(0, text)), streaming = streaming, stopReason = if (streaming) null else "stop")

    @Test
    fun rowsEqualFormatterOutputInOrder() {
        val items = listOf(ChatItem.User("u0", "你好"), asst("a0", "第一段\n\n第二段", streaming = false))
        val rows = TermRowCache().rows(items, o)
        assertEquals(items.flatMap { TermFormatter.format(it, o) }, rows.map { it.line })
        assertEquals(rows.map { it.key }.toSet().size, rows.size)
        assertTrue(rows.all { it.key == "${it.itemKey}#${it.lineIndex}" })
    }

    @Test
    fun streamingDeltaOnlyReformatsLastItemAndReusesUnchangedRows() {
        val cache = TermRowCache()
        val head = listOf(ChatItem.User("u0", "问"), asst("a0", "旧回答", streaming = false), ChatItem.User("u1", "再问"))
        var text = "## 标题\n\n- 一"
        val first = cache.rows(head + asst("a1", text), o)
        assertEquals(4, cache.lastFormatted)
        text += "\n- 二"
        val second = cache.rows(head + asst("a1", text), o)
        assertEquals("只重排流式中的最后一条", 1, cache.lastFormatted)
        // 前三条消息的行是同一批对象
        val headCount = first.count { it.itemKey != "a1" }
        for (i in 0 until headCount) assertSame(first[i], second[i])
        // 最后一条里没变的行（标题等）也复用
        val firstA1 = first.filter { it.itemKey == "a1" }
        val secondA1 = second.filter { it.itemKey == "a1" }
        assertTrue(secondA1.size > firstA1.size)
        assertSame(firstA1[0], secondA1[0])
    }

    @Test
    fun optionChangeReformatsEverything() {
        val cache = TermRowCache()
        val items = listOf(ChatItem.User("u0", "你好"), asst("a0", "回答", streaming = false))
        cache.rows(items, o)
        cache.rows(items, o.copy(width = 40))
        assertEquals(2, cache.lastFormatted)
    }
}
