package io.github.zr987459.pigo.feature.term.keys

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 外接键盘键位（DESIGN-r3 §3.4）：缺省表 = pi 1.5.0 `KEYBINDINGS` 的 defaultKeys（`assets/pi-default-keybindings.json`，
 * 由 node 从 pi 包导出，非 Windows 平台），再叠加 `~/.pi/agent/keybindings.json` 的用户覆盖（与 pi 相同：值为字符串或数组，覆盖整项）。
 * 形态切换组合键 [SWITCH_CHORD] 不在 pi 的表里（SwitchChordNoConflictTest）。
 */
class KeyBindings(private val bindings: Map<String, List<String>>) {

    /** 组合键 → pi 绑定 id（同一组合键可对应多个上下文，如 `ctrl+d` 在编辑器里是退出、在会话选择器里是删除）。 */
    private val byChord: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((id, keys) in bindings) for (k in keys) getOrPut(normalize(k)) { mutableListOf() }.add(id)
    }

    fun keysOf(id: String): List<String> = bindings[id].orEmpty()

    /** [chord] 对应的绑定 id；[prefix] 限定上下文，如 `app.tree.`、`tui.editor.`。 */
    fun idsFor(chord: String, prefix: String = ""): List<String> = byChord[normalize(chord)].orEmpty().filter { it.startsWith(prefix) }

    fun isBound(chord: String): Boolean = byChord.containsKey(normalize(chord))

    fun withUserOverrides(userJson: String?): KeyBindings {
        if (userJson.isNullOrBlank()) return this
        val obj = runCatching { Json.parseToJsonElement(userJson.removePrefix("\uFEFF")).jsonObject }.getOrNull() ?: return this
        val merged = bindings.toMutableMap()
        for ((k, v) in obj) {
            merged[MIGRATIONS[k] ?: k] = when (v) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(v.content)
                else -> continue
            }
        }
        return KeyBindings(merged)
    }

    companion object {
        /** 形态切换（§3.4）：Ctrl+Alt+T；被 pi 占用时改 Ctrl+Shift+U（由测试保证）。 */
        const val SWITCH_CHORD = "ctrl+alt+t"
        const val SWITCH_CHORD_FALLBACK = "ctrl+shift+u"

        /** pi 旧名到新名（keybindings.js `KEYBINDING_NAME_MIGRATIONS` 的常用项）。 */
        private val MIGRATIONS = mapOf("interrupt" to "app.interrupt", "clear" to "app.clear", "exit" to "app.exit")

        private val MOD_ORDER = listOf("ctrl", "alt", "shift", "meta")

        /** 统一成「修饰键按 ctrl,alt,shift,meta 排序 + 键名」，`shift+ctrl+p` 与 `ctrl+shift+p` 视为同一组合。 */
        fun normalize(chord: String): String {
            val parts = chord.lowercase().split('+').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return chord
            val key = parts.last()
            val mods = parts.dropLast(1).sortedBy { MOD_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
            return (mods + key).joinToString("+")
        }

        fun parseDefaults(json: String): KeyBindings {
            val obj = Json.parseToJsonElement(json).jsonObject
            return KeyBindings(obj.mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } })
        }

        /** pi 绑定 id → 动作 id（PiActionCatalog），编辑器上下文里外接键盘按下时执行。 */
        val ACTION_FOR: Map<String, String> = mapOf(
            "app.interrupt" to "abort",
            "app.clear" to "editor.clear",
            "app.thinking.cycle" to "thinking.cycle",
            "app.model.cycleForward" to "model.cycle",
            "app.model.cycleBackward" to "model.cycle",
            "app.model.select" to "model.select",
            "app.tools.expand" to "tools.expandAll",
            "app.thinking.toggle" to "thinking.toggle",
            "app.editor.external" to "editor.expand",
            "app.message.copy" to "copy.last",
            "app.message.followUp" to "send.followUp",
            "app.message.dequeue" to "queue.dequeue",
            "app.clipboard.pasteImage" to "attach.image",
            "tui.altScreen.search" to "transcript.search",
            "tui.altScreen.previousPrompt" to "transcript.jump",
            "tui.altScreen.nextPrompt" to "transcript.jump",
        )

        /** 同一动作带方向参数的绑定（Shift+Ctrl+P 反向循环模型；上一条 / 下一条提示，审查第 1 轮 Nit 1、3）。 */
        val ACTION_ARGS: Map<String, String> = mapOf(
            "app.model.cycleBackward" to "backward",
            "tui.altScreen.previousPrompt" to "prev",
            "tui.altScreen.nextPrompt" to "next",
        )
    }
}
