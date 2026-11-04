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

    /** 光标处的 `@` 查询：[start] 是 `@` 的位置，[end] 是该词结尾（光标后的非空白也算），[query] 去掉 `@` 与开头引号。 */
    data class AtQuery(val start: Int, val end: Int, val query: String)

    /**
     * pi-tui 的 `@` 前缀补全：光标所在的词以 `@` 开头（`@` 在行首或前面是空白）时返回查询，否则 null。
     * 支持 `@"含 空格` 这种已加引号的写法（引号内的空格不断词）。
     */
    fun atQuery(text: String, cursor: Int): AtQuery? {
        val c = cursor.coerceIn(0, text.length)
        // 已加引号：`@"含 空格`（引号内空格不断词，直到闭合引号）
        val qi = if (c >= 2) text.lastIndexOf("@\"", c - 1) else -1
        if (qi >= 0 && qi + 2 <= c && (qi == 0 || text[qi - 1].isWhitespace()) && '"' !in text.substring(qi + 2, c)) {
            val close = text.indexOf('"', c).let { if (it < 0) text.length else it + 1 }
            return AtQuery(qi, close, text.substring(qi + 2, c))
        }
        var i = c
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        if (i >= text.length || text[i] != '@' || i >= c) return null
        var e = c
        while (e < text.length && !text[e].isWhitespace()) e++
        return AtQuery(i, e, text.substring(i + 1, c))
    }

    /** 重试倒计时剩余秒数（向上取整，不小于 0）。 */
    fun retrySecondsLeft(delayMs: Long, elapsedMs: Long): Long = ((delayMs - elapsedMs).coerceAtLeast(0) + 999) / 1000

    /** 页眉的一段（pi 启动区 `[Context]`、`[Skills]`、`[Prompts]`、`[Extensions]`、`[Models]`）。 */
    data class ResourceSection(val title: String, val items: List<String>)

    /**
     * pi 启动区列出的已加载资源：上下文文件（装配层注入）、`get_commands` 里的技能 / 模板 / 扩展（扩展按文件去重，
     * 不列随包的 pigo-bridge）、`settings.json` 的 `enabledModels`（模型范围）。路径里的家目录缩成 `~`。空段不显示。
     */
    fun startupResources(contextFiles: List<String>, commands: List<SlashCommand>, enabledModels: List<String>, home: String?): List<ResourceSection> {
        fun short(p: String) = if (home != null && (p == home || p.startsWith("$home/"))) "~" + p.removePrefix(home) else p
        val skills = commands.filter { it.source == io.github.zr987459.pigo.contract.CommandSource.SKILL }.map { it.path?.let(::short) ?: it.name.removePrefix("skill:") }.distinct()
        val prompts = commands.filter { it.source == io.github.zr987459.pigo.contract.CommandSource.PROMPT }.map { "/" + it.name }.distinct()
        val exts = commands.filter { it.source == io.github.zr987459.pigo.contract.CommandSource.EXTENSION && !it.name.startsWith("pigo:") }
            .map { it.path?.let(::short) ?: "/" + it.name }.filterNot { it.endsWith("pigo-bridge.ts") }.distinct()
        return listOf(
            ResourceSection("Context", contextFiles.map(::short)),
            ResourceSection("Skills", skills),
            ResourceSection("Prompts", prompts),
            ResourceSection("Extensions", exts),
            ResourceSection("Models", enabledModels),
        ).filter { it.items.isNotEmpty() }
    }
}
