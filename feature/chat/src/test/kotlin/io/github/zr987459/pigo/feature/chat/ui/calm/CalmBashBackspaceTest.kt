package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PR #17 遗留 Nit 3：命令模式下空输入退格退出（与 bashTransition 的注释一致）。 */
class CalmBashBackspaceTest {
    @Test
    fun emptyBackspaceLeavesBashMode() {
        for (m in listOf(BashMode.INCLUDE, BashMode.EXCLUDE)) {
            val r = bashBackspace(DraftState(text = "", bashMode = m))
            assertEquals(BashMode.NONE, r?.bashMode)
            assertEquals("", r?.text)
        }
    }

    @Test
    fun backspaceWithTextOrNormalModeIsUntouched() {
        assertNull(bashBackspace(DraftState(text = "ls", bashMode = BashMode.INCLUDE)))
        assertNull(bashBackspace(DraftState(text = "", bashMode = BashMode.NONE)))
    }

    @Test
    fun enterThenBackspaceRoundTrip() {
        val inBash = bashTransition(DraftState(), "!")!!
        assertEquals(BashMode.INCLUDE, inBash.bashMode)
        assertEquals(DraftState().bashMode, bashBackspace(inBash)!!.bashMode)
    }
}
