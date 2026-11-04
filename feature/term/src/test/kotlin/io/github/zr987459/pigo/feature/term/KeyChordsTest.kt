package io.github.zr987459.pigo.feature.term

import androidx.compose.ui.input.key.Key
import io.github.zr987459.pigo.feature.term.keys.KeyChords
import org.junit.Assert.assertEquals
import org.junit.Test

/** PR #18 审查第 1 轮：字母、数字键名（原来只认得 A，Ctrl+L 等组合键在真机与模拟器上都失效）。 */
class KeyChordsTest {
    @Test
    fun lettersAndDigits() {
        assertEquals("a", KeyChords.keyName(Key.A))
        assertEquals("l", KeyChords.keyName(Key.L))
        assertEquals("p", KeyChords.keyName(Key.P))
        assertEquals("z", KeyChords.keyName(Key.Z))
        assertEquals("0", KeyChords.keyName(Key.Zero))
        assertEquals("9", KeyChords.keyName(Key.Nine))
        assertEquals("enter", KeyChords.keyName(Key.Enter))
        assertEquals("ctrl+l", KeyChords.chord(KeyChords.keyName(Key.L)!!, ctrl = true, alt = false, shift = false, meta = false))
    }
}
