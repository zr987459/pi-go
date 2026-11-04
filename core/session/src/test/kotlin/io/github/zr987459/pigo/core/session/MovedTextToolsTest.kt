package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.core.session.text.Ansi
import io.github.zr987459.pigo.core.session.text.DiffKind
import io.github.zr987459.pigo.core.session.text.LineDiff
import io.github.zr987459.pigo.core.session.text.OutputSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 从 feature/usage、feature/files 移入的 LineDiff / Ansi 在新位置可用（DESIGN-r3 §8.2 第 3 条）。 */
class MovedTextToolsTest {
    @Test
    fun lineDiff() {
        val d = LineDiff.diff("a\nb\nc\n", "a\nB\nc\nd\n")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.CONTEXT, DiffKind.ADDED), d.map { it.kind })
        assertEquals(2 to 1, LineDiff.stats(d))
    }

    @Test
    fun ansi() {
        val l = Ansi.parse("ok \u001B[1;31merror\u001B[0m done").single()
        assertEquals("ok error done", l.text)
        assertTrue(l.spans.single().style.bold)
        assertEquals(1, OutputSearch.find(listOf("Error one", "fine"), "error").size)
    }
}
