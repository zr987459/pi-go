package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** r5/openminis：OpenMinis 式终端外壳——「复制全部」的文本、附件条仍覆盖动作表里的每个键条键。 */
class TermChromeTest {
    @Test
    fun copyAllTextKeepsOrder() {
        val items = listOf(
            ChatItem.User("u", "列出文件"),
            ChatItem.Assistant("a", listOf(ContentBlock.Thinking(0, "想想"), ContentBlock.Text(1, "好的")), streaming = false),
            ChatItem.Tool("t", "c1", "bash"),
        )
        assertEquals("> 列出文件\n想想\n好的\n$ bash", TermChrome.transcriptText(items))
    }

    @Test
    fun accessoryBarStillHasEveryStripKeyExceptCloseWhichIsInTopBar() {
        val keys = TermInput.stripKeys(PiActionCatalog.all).map { it.key }
        assertTrue(TermInput.KEY_FORM in keys) // 键条表里仍登记；界面上由顶栏 ✕ 承担
        for (k in listOf("Esc", TermInput.KEY_TAB, "⏎", "^C")) assertTrue(k in keys)
    }

    /** PR #31 审查 I2：🖌 清屏只隐藏清屏时已有的行与本地块；之后新出现的行（含流式消息接着长出的行）照常显示。 */
    @Test
    fun clearScreenHidesExistingLinesOnlyAndKeepsNewOnes() {
        val line = io.github.zr987459.pigo.feature.term.format.TermLine(emptyList())
        fun row(item: String, i: Int) = io.github.zr987459.pigo.feature.term.format.TermRow("$item#$i", item, i, line, null)
        val before = listOf(row("u0", 0), row("a0", 0), row("a0", 1))
        assertEquals(before, TermClear.visible(before, TermClearMark())) // 未清屏：原样
        val mark = TermClear.mark(before, listOf("loc1"))
        assertTrue(mark.cleared)
        assertTrue(TermClear.visible(before, mark).isEmpty())
        // a0 流式又长出第 2 行，来了新消息 u1：只显示新行
        val after = before + row("a0", 2) + row("u1", 0)
        assertEquals(listOf("a0#2", "u1#0"), TermClear.visible(after, mark).map { it.key })
        assertEquals(setOf("loc1"), mark.localKeys)
    }
}
