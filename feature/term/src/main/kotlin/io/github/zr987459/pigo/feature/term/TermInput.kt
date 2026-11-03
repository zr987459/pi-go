package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.TermEntry

/**
 * 终端形态的输入逻辑（纯函数，可单测）：触屏键条、斜杠补全、斜杠命令解析。
 * 动作 id、斜杠名、键条键都取自 `PiActionCatalog`（两种形态同一张表），这里不另起一套。
 */
object TermInput {
    /** 键条一行（DESIGN-r3 §3.4 顺序）；[actionIds] 为空的键是纯输入键（Tab、/、@、!）或形态切换。 */
    data class StripKey(val key: String, val label: String = key)

    /** 本地键（不对应动作）。 */
    const val KEY_TAB = "Tab"
    const val KEY_SLASH = "/"
    const val KEY_AT = "@"
    const val KEY_BANG = "!"
    const val KEY_FORM = "⇄UI"

    /** §3.4 的顺序；目录里出现但不在顺序表里的键条键（如 ⇧Tab、↑↓）追加在 ⏎ 之前，保证目录里每个键条键都有按钮。 */
    fun stripKeys(catalog: List<PiActionSpec>): List<StripKey> {
        val order = listOf("Esc", KEY_TAB, "⇣", "↑队", "^C", "⌃O", "⌕", "⇞⇟", "⤢", KEY_SLASH, KEY_AT, KEY_BANG, "📎", KEY_FORM, "⏎")
        val fromCatalog = catalog.mapNotNull { (it.term as? TermEntry.KeyStrip)?.key }.distinct()
        val extra = fromCatalog.filter { it !in order }
        val keys = order.dropLast(1) + extra + order.last()
        return keys.map { StripKey(it) }
    }

    /**
     * 键条键 → 要执行的动作 id。同一个键对应多个动作时按状态选（pi 的 Esc：重试倒计时中 = 放弃重试；`!` 命令运行中 = 停止命令；生成中 = 中止）。
     * 返回 null 表示这是本地键或此刻无事可做。
     */
    fun actionForKey(key: String, catalog: List<PiActionSpec>, chat: ChatState, bashRunning: Boolean = false): String? {
        val ids = catalog.filter { (it.term as? TermEntry.KeyStrip)?.key == key }.map { it.id }
        if (ids.isEmpty()) return null
        if (key == "Esc") {
            return when {
                chat.retry != null && "retry.abort" in ids -> "retry.abort"
                bashRunning && "bash.abort" in ids -> "bash.abort"
                "abort" in ids -> "abort"
                else -> ids.first()
            }
        }
        return ids.first()
    }

    data class Candidate(val name: String, val description: String?, val actionId: String?)

    /**
     * 斜杠补全候选（§3.3）：动作表的斜杠别名 + `get_commands`（扩展命令、模板、技能），按前缀过滤；动作在前。
     * [query] 是 `/` 之后、第一个空格之前的文字。
     */
    fun slashCandidates(query: String, catalog: List<PiActionSpec>, commands: List<SlashCommand>): List<Candidate> {
        val q = query.lowercase()
        val fromActions = catalog.flatMap { spec -> spec.slash.map { Candidate(it, spec.title, spec.id) } }
        val names = fromActions.map { it.name }.toSet()
        val fromPi = commands.filter { it.name !in names }.map { Candidate(it.name, it.description, null) }
        val all = fromActions + fromPi
        val prefix = all.filter { it.name.lowercase().startsWith(q) }
        val contains = all.filter { it !in prefix && q.isNotEmpty() && it.name.lowercase().contains(q) }
        return prefix + contains
    }

    /** 草稿是否处于斜杠补全状态，是则返回 `/` 后的查询词。 */
    fun slashQuery(text: String): String? = if (text.startsWith("/") && !text.contains(' ') && !text.contains('\n')) text.drop(1) else null

    /**
     * 回车时草稿是不是一条动作表里的斜杠命令（如 `/tree`、`/name 新名字`）：是则返回 (动作 id, 参数)，
     * 否则返回 null（照常发给 pi：扩展命令、模板、技能由 pi 自己处理）。
     */
    fun resolveSlash(text: String, catalog: List<PiActionSpec>): Pair<String, String?>? {
        if (!text.startsWith("/")) return null
        val body = text.drop(1).trim()
        val name = body.substringBefore(' ')
        val args = body.substringAfter(' ', "").trim().ifEmpty { null }
        val spec = catalog.firstOrNull { name in it.slash } ?: return null
        return spec.id to args
    }

    /** 页脚段 → 动作。 */
    fun actionForFooter(segment: String, catalog: List<PiActionSpec>): String? =
        catalog.firstOrNull { (it.term as? TermEntry.Footer)?.segment == segment }?.id

    /** 某个选择器底部的键帽（§3.4 最后一行：选择器提示行里的可点键帽）。 */
    fun keyCapsFor(selector: String, catalog: List<PiActionSpec>): List<Pair<String, String>> =
        catalog.mapNotNull { s -> (s.term as? TermEntry.KeyCap)?.takeIf { it.selector == selector }?.let { it.key to s.id } }

    /** 键帽所属选择器 → 打开它的面板 id（overlay 栈顶是这个面板时，在它底部画这些键帽）。 */
    val SELECTOR_OVERLAY: Map<String, String> = mapOf(
        "settings" to "settings",
        "model" to "model.select",
        "session" to "session.resume",
        "tree" to "session.tree",
    )

    /** 页眉、编辑器、补全浮层、消息里常驻的键帽所属选择器。 */
    val INLINE_SELECTORS = setOf("header", "editor", "slash", "message")
}
