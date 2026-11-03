package io.github.zr987459.pigo.contract.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStateTest {
    @Test fun selectionIsClampedAfterShorterCopy() {
        val d = DraftState(text = "hello world").copy(text = "hi")
        assertEquals(11, d.selectionStart) // 原始值可能越界
        assertEquals(2, d.safeSelectionStart)
        assertEquals(2, d.safeSelectionEnd)
        assertEquals(DraftState(text = "hi", selectionStart = 2, selectionEnd = 2), d.normalized())
    }

    @Test fun reversedAndNegativeSelectionIsOrdered() {
        val d = DraftState(text = "abcdef", selectionStart = 5, selectionEnd = -3)
        assertEquals(0, d.safeSelectionStart)
        assertEquals(5, d.safeSelectionEnd)
    }

    @Test fun withTextMovesCursorToEnd() {
        val d = DraftState(text = "abc", selectionStart = 1, selectionEnd = 2, fileRefs = listOf("a.kt")).withText("x")
        assertEquals(1, d.selectionStart)
        assertEquals(1, d.selectionEnd)
        assertEquals(listOf("a.kt"), d.fileRefs)
    }

    @Test fun isEmptyCountsRecalledAndBashMode() {
        assertTrue(DraftState.EMPTY.isEmpty)
        assertFalse(DraftState(recalled = listOf("x")).isEmpty)
        assertFalse(DraftState(bashMode = BashMode.INCLUDE).isEmpty)
        assertFalse(DraftState(recalled = listOf("x")).hasSendableContent)
        assertFalse(DraftState(text = "  ").hasSendableContent)
        assertTrue(DraftState(images = listOf(DraftImage("content://a"))).hasSendableContent)
    }
}
