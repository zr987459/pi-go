package io.github.zr987459.pigo.contract.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayStackTest {
    @Test
    fun pushReplaceTopPopRemove() {
        val s = OverlayStack()
        assertNull(s.pop())
        s.push(OverlayModel.Text("a", "A", "x"))
        s.push(OverlayModel.SelectList("model.select", "选择模型"))
        s.replaceTop(OverlayModel.SelectList("model.select", "选择模型", query = "son"))
        assertEquals("son", (s.top as OverlayModel.SelectList).query)
        assertEquals(2, s.items.value.size)
        s.remove("a")
        assertEquals(listOf("model.select"), s.items.value.map { it.id })
        assertEquals("model.select", s.pop()?.id)
        assertNull(s.top)
    }

    @Test
    fun draftDefaultsPutCursorAtEnd() {
        val d = DraftState(text = "abc")
        assertEquals(3, d.selectionStart)
        assertEquals(3, d.selectionEnd)
        assertEquals("!!", BashMode.EXCLUDE.prefix)
    }
}
