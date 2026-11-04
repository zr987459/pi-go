package io.github.zr987459.pigo.feature.chat.ui.calm

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.actions.ActionGroup
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.TreeNode
import io.github.zr987459.pigo.core.ui.calm.CommandSearch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** UI 形态的纯逻辑（无 Compose），供 CalmSurface 与测试共用。 */

/** 发送键三态（DESIGN-r3 §4.4）。[actionId] 是点按执行的动作。 */
sealed interface SendKey {
    val actionId: String
    val label: String

    /** 空闲：↑ 发送；`!` 模式文字为「运行」；没内容时禁用。 */
    data class Send(val enabled: Boolean, val bash: Boolean) : SendKey {
        override val actionId = "send"
        override val label = if (bash) "运行" else "发送"
    }

    /** 生成中且输入框空：■ 停止（重试中 → `retry.abort`；bash 运行中 → `bash.abort`；否则 `abort`）。 */
    data class Stop(override val actionId: String) : SendKey {
        override val label = "停止"
    }

    /** 生成中且有字：「引导」（`steer`）；长按菜单：排队（`send.followUp`）/ 停止（`abort`）。 */
    data object Steer : SendKey {
        override val actionId = "send"
        override val label = "引导"
    }
}

fun sendKeyOf(chat: ChatState, draft: DraftState): SendKey {
    val bashRunning = chat.items.lastOrNull { it is ChatItem.Tool }
        .let { it is ChatItem.Tool && it.toolName == "bash" && it.status == ToolStatus.RUNNING }
    val busy = chat.running || bashRunning || chat.retry != null
    if (!busy) return SendKey.Send(enabled = draft.hasSendableContent, bash = draft.bashMode != BashMode.NONE)
    if (draft.hasSendableContent) return SendKey.Steer
    return SendKey.Stop(
        when {
            chat.retry != null -> "retry.abort"
            bashRunning -> "bash.abort"
            else -> "abort"
        },
    )
}

/** ⌘ 命令中心的分组顺序与中文名（§4.3）。 */
val GROUP_ORDER: List<Pair<ActionGroup, String>> = listOf(
    ActionGroup.SESSION to "会话",
    ActionGroup.MODEL to "模型",
    ActionGroup.SETTINGS to "pi 设置",
    ActionGroup.RESOURCES to "资源",
    ActionGroup.EXT to "扩展命令",
    ActionGroup.HELP to "帮助",
)

/** 命令中心里出现的动作：全部动作（含常驻 / 长按 / 说明条目也可搜到，§2.0「按组列出全部动作」）。 */
fun commandEntry(spec: PiActionSpec): CommandSearch.Entry = CommandSearch.Entry(spec.id, spec.title, spec.slash)

/** 搜索并分组；空查询按 [GROUP_ORDER] 分组，非空查询仍按组但组内按分数。 */
fun groupedCommands(all: List<PiActionSpec>, query: String): List<Pair<String, List<PiActionSpec>>> {
    val hits = CommandSearch.filter(all, query, ::commandEntry)
    return GROUP_ORDER.mapNotNull { (g, name) -> hits.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { name to it } }
}

/** 斜杠补全：输入框以 `/` 开头且还没有空格时，按斜杠别名过滤（§4.3「同一列表变内联补全面板」）。 */
fun slashQuery(draft: DraftState): String? {
    val t = draft.text
    if (draft.bashMode != BashMode.NONE || !t.startsWith("/") || t.contains(' ') || t.contains('\n')) return null
    return t.removePrefix("/")
}

fun slashCompletions(all: List<PiActionSpec>, query: String): List<PiActionSpec> =
    CommandSearch.filter(all.filter { it.slash.isNotEmpty() }, query, ::commandEntry)

/** `@` 引用：光标前最后一个以 `@` 开头、不含空白的词。 */
fun atQuery(draft: DraftState): String? {
    val before = draft.text.substring(0, draft.safeSelectionStart)
    val i = before.lastIndexOf('@')
    if (i < 0) return null
    if (i > 0 && !before[i - 1].isWhitespace()) return null
    val word = before.substring(i + 1)
    return if (word.any { it.isWhitespace() }) null else word
}

/** 把 `@词` 换成 fileRefs 里的一条引用（去掉正文里的 `@词`）。 */
fun acceptAtRef(draft: DraftState, path: String): DraftState {
    val start = draft.safeSelectionStart
    val before = draft.text.substring(0, start)
    val i = before.lastIndexOf('@')
    if (i < 0 || path.isBlank()) return draft
    val text = draft.text.substring(0, i) + draft.text.substring(start)
    return draft.copy(text = text, selectionStart = i, selectionEnd = i, fileRefs = (draft.fileRefs + path).distinct())
}

/**
 * 输入 `!` 进入命令模式（§4.4、清单 §10）：空草稿里敲 `!` → INCLUDE，再敲一次 → EXCLUDE；
 * 命令模式下空文字退格 → 退出。返回 null 表示按普通文字处理。
 */
fun bashTransition(old: DraftState, newText: String): DraftState? {
    if (old.text.isEmpty() && newText == "!") {
        return when (old.bashMode) {
            BashMode.NONE -> old.copy(bashMode = BashMode.INCLUDE, text = "", selectionStart = 0, selectionEnd = 0)
            BashMode.INCLUDE -> old.copy(bashMode = BashMode.EXCLUDE, text = "", selectionStart = 0, selectionEnd = 0)
            BashMode.EXCLUDE -> null
        }
    }
    return null
}

/** 历史翻找（空输入上滑，§4.3 / 清单 §9）：从新到旧循环。[cursor] = 已翻到的倒数第几条（-1 = 未翻）。 */
fun historyAt(history: List<String>, cursor: Int): Pair<String, Int>? {
    if (history.isEmpty()) return null
    val next = (cursor + 1).coerceAtMost(history.size - 1)
    return history[history.size - 1 - next] to next
}

/** 工具过程行的一句话摘要（§4.3「● 读取 src/auth/Login.kt」）。 */
fun toolSummary(item: ChatItem.Tool): String {
    val a = item.args
    fun s(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
    val verb = when (item.toolName) {
        "read" -> "读取"
        "write" -> "写入"
        "edit" -> "编辑"
        "bash" -> "运行"
        "grep" -> "搜索"
        "find" -> "查找"
        "ls" -> "列出"
        else -> item.toolName
    }
    val target = s("path") ?: s("file_path") ?: s("command") ?: s("pattern") ?: a?.entries?.firstOrNull()?.value?.let(::plain) ?: ""
    return listOf(verb, target.lineSequence().firstOrNull().orEmpty().take(80)).filter { it.isNotBlank() }.joinToString(" ")
}

/** `edit` 的增删行数（§4.3「编辑 Login.kt +24 −9」）。 */
fun editStats(item: ChatItem.Tool): Pair<Int, Int>? {
    if (item.toolName != "edit") return null
    val a = item.args ?: return null
    val old = (a["oldText"] ?: a["old_string"])?.let(::plain) ?: return null
    val new = (a["newText"] ?: a["new_string"])?.let(::plain) ?: return null
    return new.lines().size to old.lines().size
}

/** 工具结果的纯文本（content[].text 拼接；否则原样）。 */
fun toolOutput(item: ChatItem.Tool): String {
    val r = item.result ?: item.partialResult ?: return ""
    return plain(r)
}

fun plain(e: JsonElement): String = when (e) {
    is JsonPrimitive -> e.contentOrNull ?: ""
    is JsonArray -> e.joinToString("\n") { plain(it) }
    is JsonObject -> {
        val content = e["content"]
        val text = e["text"]
        when {
            content != null -> plain(content)
            text != null -> plain(text)
            else -> e.toString()
        }
    }
}

/** 被截断的完整输出路径（`details.fullOutputPath` / `full_output_path`，清单 §4）。 */
fun fullOutputPath(item: ChatItem.Tool): String? {
    val r = item.result as? JsonObject ?: return null
    val d = r["details"] as? JsonObject
    return listOfNotNull(d?.get("fullOutputPath"), d?.get("full_output_path"), r["full_output_path"])
        .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
}

/** 一段 Markdown 切成正文与代码块（围栏 ```lang）。 */
sealed interface MdSegment {
    data class Prose(val text: String) : MdSegment
    data class Code(val lang: String, val code: String) : MdSegment
}

fun splitFences(text: String): List<MdSegment> {
    val out = mutableListOf<MdSegment>()
    val lines = text.split('\n')
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in lines) {
        val t = line.trimStart()
        if (t.startsWith("```")) {
            if (!inCode) {
                if (buf.isNotEmpty()) out += MdSegment.Prose(buf.toString().trimEnd('\n'))
                buf.clear(); inCode = true; lang = t.removePrefix("```").trim()
            } else {
                out += MdSegment.Code(lang, buf.toString().trimEnd('\n'))
                buf.clear(); inCode = false; lang = ""
            }
            continue
        }
        buf.append(line).append('\n')
    }
    if (buf.isNotEmpty()) out += if (inCode) MdSegment.Code(lang, buf.toString().trimEnd('\n')) else MdSegment.Prose(buf.toString().trimEnd('\n'))
    return out.filterNot { it is MdSegment.Prose && it.text.isBlank() }
}

/** 树节点的缩进层级（按 parentId 链）；折叠节点的子孙不显示。 */
fun visibleTree(nodes: List<TreeNode>): List<Pair<TreeNode, Int>> {
    val byId = nodes.associateBy { it.id }
    fun depth(n: TreeNode): Int {
        var d = 0; var p = n.parentId; var guard = 0
        while (p != null && guard++ < 10_000) { d++; p = byId[p]?.parentId }
        return d
    }
    fun hidden(n: TreeNode): Boolean {
        var p = n.parentId; var guard = 0
        while (p != null && guard++ < 10_000) { val pn = byId[p] ?: return false; if (pn.collapsed) return true; p = pn.parentId }
        return false
    }
    return nodes.filterNot(::hidden).map { it to depth(it) }
}

/** 常驻 / 长按入口的 testTag（覆盖测试按它找控件）。 */
fun persistentTag(id: String): String? = (io.github.zr987459.pigo.contract.actions.PiActionCatalog[id]?.ui as? UiEntry.Persistent)?.tag
fun longPressTag(id: String): String? = (io.github.zr987459.pigo.contract.actions.PiActionCatalog[id]?.ui as? UiEntry.LongPress)?.tag

/** ⌘ 面板在 overlay 栈里的 id（存成 SelectList，切换形态时搜索词保留，终端也能按通用列表画）。 */
const val COMMANDS_OVERLAY_ID = "calm.commands"

/** 只影响 UI 视图的动作：先照常 `runAction`（真实实现返回 Done），再在本形态做视图上的事。 */
val VIEW_LOCAL_ACTIONS = setOf("transcript.search", "tools.expandAll", "thinking.toggle", "editor.expand", "editor.clear", "editor.history", "transcript.jump")
