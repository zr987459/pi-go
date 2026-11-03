package io.github.zr987459.pigo.feature.chat.logic

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.SlashCommand

/** 斜杠命令候选（C3）：按来源分组，组内按匹配程度排序。 */
@Immutable
data class SlashCandidates(
    /** `/` 之后用户已经输入的部分（不含 `/`）。 */
    val query: String,
    val groups: List<SlashGroup>,
    /** 命令列表还在读取。 */
    val loading: Boolean = false,
) {
    val isEmpty: Boolean get() = groups.all { it.commands.isEmpty() }
    val all: List<SlashCommand> get() = groups.flatMap { it.commands }
}

@Immutable
data class SlashGroup(val source: CommandSource, val title: String, val commands: List<SlashCommand>)

/**
 * 输入框里斜杠命令的识别与过滤，纯函数。
 *
 * - 只有草稿**第一个字符**是 `/`、且还没输入空白（还在写命令名）时才弹候选；
 *   `/name 参数` 已经补全完成，不再弹。
 * - 匹配：名字前缀 > 名字里某个 `:`/`-`/`_` 分段的前缀 > 名字包含 > 描述包含。大小写不敏感。
 * - 分组顺序：扩展、技能、模板、其他（feature-gap C3）。
 */
object SlashCompletion {
    private val ORDER = listOf(CommandSource.EXTENSION, CommandSource.SKILL, CommandSource.PROMPT, CommandSource.UNKNOWN)

    fun title(source: CommandSource): String = when (source) {
        CommandSource.EXTENSION -> "扩展命令"
        CommandSource.SKILL -> "技能"
        CommandSource.PROMPT -> "提示模板"
        CommandSource.UNKNOWN -> "其他"
    }

    /** 草稿处在「正在输入命令名」状态时返回已输入部分，否则返回 null。 */
    fun queryOf(draft: String): String? {
        if (!draft.startsWith("/")) return null
        val rest = draft.substring(1)
        if (rest.any { it.isWhitespace() }) return null
        if (rest.startsWith("/")) return null // `//` 当作普通文本
        return rest
    }

    fun filter(commands: List<SlashCommand>, query: String): List<SlashGroup> {
        val q = query.lowercase()
        val scored = commands.mapNotNull { c -> score(c, q)?.let { c to it } }
        return ORDER.mapNotNull { src ->
            val list = scored.filter { it.first.source == src }
                .sortedWith(compareBy<Pair<SlashCommand, Int>> { it.second }.thenBy { it.first.name.length }.thenBy { it.first.name })
                .map { it.first }
            if (list.isEmpty()) null else SlashGroup(src, title(src), list)
        }
    }

    fun candidates(draft: String, commands: List<SlashCommand>?, loading: Boolean): SlashCandidates? {
        val q = queryOf(draft) ?: return null
        if (commands == null) return SlashCandidates(q, emptyList(), loading = true)
        return SlashCandidates(q, filter(commands, q), loading = loading)
    }

    /** 选中候选后的草稿：`/name `（技能为 `/skill:name `），光标停在参数位置。 */
    fun complete(command: SlashCommand): String = command.invocation + " "

    private fun score(c: SlashCommand, q: String): Int? {
        if (q.isEmpty()) return 0
        val name = c.name.lowercase()
        return when {
            name.startsWith(q) -> 0
            name.split(':', '-', '_').any { it.startsWith(q) } -> 1
            name.contains(q) -> 2
            c.description?.lowercase()?.contains(q) == true -> 3
            else -> null
        }
    }
}

/** 输入框文本的去向：普通消息、`!` 命令（输出进上下文）、`!!` 命令（不进上下文）。 */
sealed interface InputRoute {
    data class Message(val text: String) : InputRoute
    data class Bash(val command: String, val excludeFromContext: Boolean) : InputRoute
}

object InputRouting {
    /** 已 trim 的草稿。`!` / `!!` 后面没有命令时当普通文本发送。 */
    fun route(text: String): InputRoute {
        if (text.startsWith("!!")) {
            val cmd = text.substring(2).trim()
            if (cmd.isNotEmpty()) return InputRoute.Bash(cmd, excludeFromContext = true)
        } else if (text.startsWith("!")) {
            val cmd = text.substring(1).trim()
            if (cmd.isNotEmpty()) return InputRoute.Bash(cmd, excludeFromContext = false)
        }
        return InputRoute.Message(text)
    }
}
