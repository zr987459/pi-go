package io.github.zr987459.pigo.feature.term.keys

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
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
        // 用 Android 原生键码比较：Compose 的 Key.keyCode 是原生键码左移 32 位，`a..a+25` 的区间只覆盖 A 一个键，
        // 导致 Ctrl+L 等字母组合键全部认不出（PR #18 审查第 1 轮，模拟器 externalKeyboardMatchesPi）。
        val code = key.nativeKeyCode
        val a = Key.A.nativeKeyCode
        if (code in a..(a + 25)) return ('a' + (code - a)).toString()
        val zero = Key.Zero.nativeKeyCode
        if (code in zero..(zero + 9)) return ('0' + (code - zero)).toString()
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
