package io.github.zr987459.pigo.feature.term.keys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** 外接键盘事件 → pi 键名写法的组合键（`ctrl+shift+p`、`alt+enter`、`escape`）。只处理按下事件。 */
object KeyChords {
    private val NAMED = mapOf(
        Key.Enter to "enter", Key.NumPadEnter to "enter", Key.Escape to "escape", Key.Tab to "tab", Key.Backspace to "backspace",
        Key.Delete to "delete", Key.DirectionUp to "up", Key.DirectionDown to "down", Key.DirectionLeft to "left", Key.DirectionRight to "right",
        Key.PageUp to "pageUp", Key.PageDown to "pageDown", Key.MoveHome to "home", Key.MoveEnd to "end", Key.Spacebar to "space",
    )

    fun keyName(key: Key): String? {
        NAMED[key]?.let { return it }
        val code = key.keyCode
        // A–Z：Key.A..Key.Z 的 keyCode 连续
        val a = Key.A.keyCode
        if (code in a..(a + 25)) return ('a' + (code - a).toInt()).toString()
        val zero = Key.Zero.keyCode
        if (code in zero..(zero + 9)) return ('0' + (code - zero).toInt()).toString()
        return when (key) {
            Key.LeftBracket -> "["; Key.RightBracket -> "]"; Key.Minus -> "-"; Key.Equals -> "="; Key.Slash -> "/"
            Key.Comma -> ","; Key.Period -> "."; Key.Semicolon -> ";"; Key.Backslash -> "\\"; Key.Grave -> "`"; Key.Apostrophe -> "'"
            else -> null
        }
    }

    fun chord(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): String =
        buildList { if (ctrl) add("ctrl"); if (alt) add("alt"); if (shift) add("shift"); if (meta) add("meta"); add(name) }.joinToString("+")

    fun chordOf(ev: KeyEvent): String? {
        if (ev.type != KeyEventType.KeyDown) return null
        val name = keyName(ev.key) ?: return null
        // 普通字符不带修饰键时交给输入框
        if (!ev.isCtrlPressed && !ev.isAltPressed && !ev.isMetaPressed && name.length == 1) return null
        if (!ev.isCtrlPressed && !ev.isAltPressed && !ev.isMetaPressed && ev.isShiftPressed && name != "tab" && name != "enter") return null
        return chord(name, ev.isCtrlPressed, ev.isAltPressed, ev.isShiftPressed, ev.isMetaPressed)
    }
}
