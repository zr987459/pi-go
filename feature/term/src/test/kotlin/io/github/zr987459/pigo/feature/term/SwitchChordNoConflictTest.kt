package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.feature.term.keys.KeyBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN-r3 §3.4 / §7.3 SwitchChordNoConflictTest：形态切换组合键不与 pi 1.5.0 默认键位冲突。
 * 夹具 `pi/default-keybindings.json` 由 node 从 pi 包 `dist/core/keybindings.js` 的 `KEYBINDINGS` 导出（90 项），
 * 与 App 打包的 `assets/pi-default-keybindings.json` 必须一致。
 */
class SwitchChordNoConflictTest {
    private val fixture = javaClass.getResource("/pi/default-keybindings.json")!!.readText()
    private val pi = KeyBindings.parseDefaults(fixture)

    @Test
    fun switchChordIsFree() {
        assertFalse("Ctrl+Alt+T 被 pi 占用：${pi.idsFor(KeyBindings.SWITCH_CHORD)}", pi.isBound(KeyBindings.SWITCH_CHORD))
        assertFalse(pi.isBound(KeyBindings.SWITCH_CHORD_FALLBACK))
    }

    @Test
    fun bundledDefaultsEqualPiFixture() {
        assertEquals(fixture.trim(), File("src/main/assets/pi-default-keybindings.json").readText().trim())
        assertEquals(listOf("escape"), pi.keysOf("app.interrupt"))
        assertEquals(listOf("alt+enter"), pi.keysOf("app.message.followUp"))
        assertEquals(listOf("ctrl+l"), pi.keysOf("app.model.select"))
        assertEquals(listOf("shift+tab"), pi.keysOf("app.thinking.cycle"))
        assertEquals(listOf("enter"), pi.keysOf("tui.input.submit"))
    }

    @Test
    fun chordsNormalizeAndUserOverridesWin() {
        assertTrue(pi.idsFor("ctrl+shift+p").contains("app.model.cycleBackward"))
        val user = pi.withUserOverrides("""{"app.model.select":"ctrl+k","app.interrupt":["escape","ctrl+q"]}""")
        assertEquals(listOf("app.model.select"), user.idsFor("ctrl+k", "app."))
        assertTrue(user.idsFor("ctrl+l", "app.model").isEmpty())
        assertEquals(listOf("escape", "ctrl+q"), user.keysOf("app.interrupt"))
    }
}
