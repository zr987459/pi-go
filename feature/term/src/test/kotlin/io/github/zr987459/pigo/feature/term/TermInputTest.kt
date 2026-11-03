package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.TermEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端形态入口的 JVM 部分（硬性要求 ① 终端侧的静态核对；界面点击由 androidTest `TermCoverageTest` 做）：
 * 动作表里每个动作的终端入口在 TermSurface 里都有对应的东西可点 / 可输入。
 */
class TermInputTest {
    private val catalog = PiActionCatalog.all

    @Test
    fun everyTermEntryIsReachable() {
        val strip = TermInput.stripKeys(catalog).map { it.key }.toSet()
        val missing = mutableListOf<String>()
        for (spec in catalog) {
            val ok = when (val t = spec.term) {
                TermEntry.Slash -> spec.slash.isNotEmpty() && TermInput.resolveSlash("/${spec.slash.first()}", catalog)?.first == spec.id
                is TermEntry.KeyStrip -> t.key in strip
                is TermEntry.Footer -> t.segment in setOf("cwd", "model") && (t.segment != "cwd" || TermInput.actionForFooter("cwd", catalog) == spec.id)
                is TermEntry.KeyCap -> t.selector in TermInput.SELECTOR_OVERLAY || t.selector in TermInput.INLINE_SELECTORS
                is TermEntry.Explain -> t.text.isNotBlank()
            }
            if (!ok) missing += "${spec.id} ${spec.term}"
        }
        assertTrue("终端入口缺失：$missing", missing.isEmpty())
    }

    @Test
    fun stripOrderFollowsDesign() {
        val keys = TermInput.stripKeys(catalog).map { it.key }
        assertEquals("Esc", keys.first())
        assertEquals("⏎", keys.last())
        assertTrue(keys.containsAll(listOf("Tab", "⇣", "↑队", "^C", "⌃O", "⌕", "⇞⇟", "⤢", "/", "@", "!", "📎", "⇄UI")))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun escPicksByState() {
        assertEquals("abort", TermInput.actionForKey("Esc", catalog, ChatState(running = true)))
        assertEquals("retry.abort", TermInput.actionForKey("Esc", catalog, ChatState(running = true, retry = RetryBanner(1, 3, 2000, "overloaded"))))
        assertEquals("bash.abort", TermInput.actionForKey("Esc", catalog, ChatState(), bashRunning = true))
        assertNull(TermInput.actionForKey("Tab", catalog, ChatState()))
    }

    @Test
    fun slashCompletionAndResolve() {
        val cmds = listOf(SlashCommand("skill:review", "Review code"), SlashCommand("tree", "dup"))
        val c = TermInput.slashCandidates("tr", catalog, cmds)
        assertEquals("tree", c.first().name)
        assertEquals("session.tree", c.first().actionId)
        assertEquals(1, c.count { it.name == "tree" })
        assertTrue(TermInput.slashCandidates("skill", catalog, cmds).any { it.name == "skill:review" && it.actionId == null })
        assertEquals("session.name" to "新名字", TermInput.resolveSlash("/name 新名字", catalog))
        assertNull(TermInput.resolveSlash("/skill:review", catalog))
        assertNull(TermInput.resolveSlash("hello", catalog))
        assertEquals("tr", TermInput.slashQuery("/tr"))
        assertNull(TermInput.slashQuery("/tree x"))
        assertNotNull(TermInput.resolveSlash("/ui", catalog))
    }
}
