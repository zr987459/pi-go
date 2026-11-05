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
}
