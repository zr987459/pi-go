package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.feature.term.format.FormatOptions
import io.github.zr987459.pigo.feature.term.format.Markdown
import io.github.zr987459.pigo.feature.term.format.TermFormatter
import io.github.zr987459.pigo.feature.term.format.TermRowCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #18 审查第 2 轮慢帧：流式时「稳定前缀缓存 + 只排最后一段」与整段排版逐行相同；前缀的行对象跨 delta 复用。
 */
class TermStreamingFormatTest {
    private val o = FormatOptions(width = 48)

    private val sample = buildString {
        append("开头一段，带 **加粗** 与 `code`。\n\n")
        append("## 第一节\n\n")
        append("- 列表一\n- 列表二\n  续行\n1. 有序\n\n")
        append("> 引用一行\n> 引用二行\n\n")
        append("```kotlin\nval a = 1\n\nval b = 2\n```\n\n")
        append("---\n\n")
        append("结尾段落 English words that wrap around the edge of the column 字字字字字字字字字字。\n\n")
        append("~~~\n未闭合的围栏\n\n还在围栏里")
    }

    private fun asst(text: String, streaming: Boolean) =
        ChatItem.Assistant("a", listOf(ContentBlock.Thinking(0, text), ContentBlock.Text(1, text)), streaming = streaming, stopReason = null)

    @Test
    fun streamingEqualsFullRenderAtEveryPrefix() {
        for (n in 1..sample.length) {
            val t = sample.substring(0, n)
            assertEquals("前缀长度 $n", TermFormatter.format(asst(t, false), o), TermFormatter.format(asst(t, true), o))
        }
    }

    @Test
    fun stableCutNeverInsideFence() {
        val t = "a\n\n```\nx\n\ny"
        val cut = Markdown.stableCut(t)
        assertEquals(1, cut) // 只能切在围栏之前
        assertEquals(-1, Markdown.stableCut("只有一段"))
        assertEquals(-1, Markdown.stableCut("\n\n开头空行"))
    }

    @Test
    fun prefixRowsAreReusedAcrossDeltas() {
        val cache = TermRowCache()
        val base = "第一段\n\n第二段\n\n第三"
        val r1 = cache.rows(listOf(ChatItem.Assistant("a", listOf(ContentBlock.Text(0, base)))), o)
        val r2 = cache.rows(listOf(ChatItem.Assistant("a", listOf(ContentBlock.Text(0, base + "段继续")))), o)
        assertEquals(r1.size, r2.size)
        // 除最后一行外全部是同一对象
        for (i in 0 until r1.size - 1) assertSame("行 $i", r1[i], r2[i])
        assertTrue(r1.last() != r2.last())
    }
}
