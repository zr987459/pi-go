package io.github.zr987459.pigo.feature.chat.logic

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.ToolStatus

/**
 * 界面上的一行。由 [ChatRows.build] 从引擎的 `ChatState.items` 纯函数地算出，
 * 实时显示和 `get_messages` 重建后的显示走同一条路径（T4）。
 * 每行都有稳定 key，供 LazyColumn 使用（§3.5 性能）。
 */
@Immutable
sealed interface ChatRow {
    val key: String

    /** 引擎里的用户消息，或本地尚未被引擎确认的乐观气泡（[localId] 非空）。 */
    @Immutable
    data class User(
        override val key: String,
        val text: String,
        val delivery: Delivery,
        val localId: String? = null,
        val error: String? = null,
    ) : ChatRow

    /**
     * 一条助手消息。回合折叠后，过程部分与最终回答分开显示：
     * [showText] 为假时只显示思考，[showThinking] 为假时只显示正文。
     */
    @Immutable
    data class Assistant(
        override val key: String,
        val item: ChatItem.Assistant,
        val showText: Boolean = true,
        val showThinking: Boolean = true,
        /** 每个思考块的耗时（毫秒）；进行中的块为 null。 */
        val thinkingMs: Map<Int, Long?> = emptyMap(),
    ) : ChatRow

    @Immutable
    data class Tool(override val key: String, val tool: ChatItem.Tool) : ChatRow

    @Immutable
    data class Notice(override val key: String, val notice: ChatItem.Notice) : ChatRow

    @Immutable
    data class Raw(override val key: String, val raw: ChatItem.Raw) : ChatRow

    /** 模型/网络失败的错误卡（§3.9）。[retryText] 为该回合的用户原文，点「重试」时重发。 */
    @Immutable
    data class Error(
        override val key: String,
        val info: ErrorInfo,
        val retryText: String?,
    ) : ChatRow

    /** 回合结束后的折叠摘要（T3），如「思考 12 秒 · 3 个工具 · 1 失败」。 */
    @Immutable
    data class TurnSummary(
        override val key: String,
        val thinkingMs: Long,
        val thinkingBlocks: Int,
        val toolCount: Int,
        val failedTools: Int,
        val steps: Int,
        val expanded: Boolean,
    ) : ChatRow {
        val label: String
            get() = buildList {
                if (thinkingBlocks > 0) add(if (thinkingMs > 0) "思考 ${formatSeconds(thinkingMs)}" else "已思考")
                if (toolCount > 0) add("$toolCount 个工具")
                if (failedTools > 0) add("$failedTools 失败")
                if (isEmpty() && steps > 0) add("$steps 步过程")
            }.joinToString(" · ")
    }
}

/** 本地乐观气泡（§3.7：收到响应前显示「发送中」，失败标红给「重试」）。 */
@Immutable
data class LocalSend(
    val id: String,
    val text: String,
    val status: Delivery,
    val error: String? = null,
    /** 发出时引擎里同文本 user 消息的条数；引擎条数超过它即视为已被引擎接管。 */
    val baselineUserCount: Int = 0,
    /** 已在 `queue_update` 里见到过本条（排队中由芯片显示）。 */
    val seenInQueue: Boolean = false,
    /** 发出后见到过 running=true。 */
    val sawRunning: Boolean = false,
)

fun formatSeconds(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s < 60) "$s 秒" else "${s / 60} 分 ${s % 60} 秒"
}

object ChatRows {

    /**
     * @param running 引擎是否仍在生成（以 `agent_settled` 为准）；最后一个回合在生成中时不折叠。
     * @param expanded 用户手动展开的折叠摘要 key。
     * @param thinkingMs 查询某条助手消息某个思考块的耗时；块自带 `durationMs` 时优先用它。
     */
    fun build(
        items: List<ChatItem>,
        running: Boolean,
        expanded: Set<String> = emptySet(),
        local: List<LocalSend> = emptyList(),
        thinkingMs: (assistantKey: String, index: Int) -> Long? = { _, _ -> null },
    ): List<ChatRow> {
        val out = ArrayList<ChatRow>(items.size + 4)
        val segments = split(items)
        segments.forEachIndexed { i, seg ->
            seg.user?.let { out += ChatRow.User(it.key, it.text, it.delivery) }
            val live = running && i == segments.lastIndex
            emitBody(out, seg, live, expanded, thinkingMs)
        }
        // 乐观气泡总在最后：它们是引擎还没确认的发送。
        local.forEach { l ->
            out += ChatRow.User("local-${l.id}", l.text, l.status, localId = l.id, error = l.error)
        }
        return out
    }

    private class Segment(val user: ChatItem.User?, val body: List<ChatItem>)

    private fun split(items: List<ChatItem>): List<Segment> {
        val segs = ArrayList<Segment>()
        var user: ChatItem.User? = null
        var body = ArrayList<ChatItem>()
        var started = false
        for (it in items) {
            if (it is ChatItem.User) {
                if (started) segs += Segment(user, body)
                user = it
                body = ArrayList()
                started = true
            } else {
                body += it
                started = true
            }
        }
        if (started) segs += Segment(user, body)
        return segs
    }

    private fun emitBody(
        out: MutableList<ChatRow>,
        seg: Segment,
        live: Boolean,
        expanded: Set<String>,
        thinkingMs: (String, Int) -> Long?,
    ) {
        val body = seg.body
        if (body.isEmpty()) return
        val retryText = seg.user?.text

        fun durations(a: ChatItem.Assistant): Map<Int, Long?> =
            a.blocks.filterIsInstance<ContentBlock.Thinking>().associate { b -> b.index to (b.durationMs ?: thinkingMs(a.key, b.index)) }

        fun emitTail(it: ChatItem) {
            when (it) {
                is ChatItem.Notice -> out += ChatRow.Notice(it.key, it)
                is ChatItem.Raw -> out += ChatRow.Raw(it.key, it)
                else -> Unit
            }
        }

        fun emitError(a: ChatItem.Assistant) {
            if (a.stopReason == "error") {
                out += ChatRow.Error("err-${a.key}", ErrorText.classify(a.errorMessage), retryText)
            }
        }

        if (live) {
            for (it in body) {
                when (it) {
                    is ChatItem.Assistant -> {
                        if (hasVisibleContent(it)) out += ChatRow.Assistant(it.key, it, thinkingMs = durations(it))
                        emitError(it)
                    }
                    is ChatItem.Tool -> out += ChatRow.Tool(it.key, it)
                    else -> emitTail(it)
                }
            }
            return
        }

        val assistants = body.filterIsInstance<ChatItem.Assistant>()
        val final = assistants.lastOrNull()?.takeIf { a ->
            a.stopReason != "toolUse" && a.stopReason != "error" && hasText(a) &&
                // 最终回答之后不能再有工具卡（否则它不是最终回答）
                body.indexOfFirst { it === a } > body.indexOfLast { it is ChatItem.Tool }
        }

        val tools = body.filterIsInstance<ChatItem.Tool>()
        val processAssistants = assistants.filter { it !== final }
        val thinkingBlocks = assistants.flatMap { a -> a.blocks.filterIsInstance<ContentBlock.Thinking>().map { a to it } }
        val processSteps = processAssistants.count { hasVisibleContent(it) } + (if (final != null && hasThinking(final)) 1 else 0)

        val hasProcess = tools.isNotEmpty() || thinkingBlocks.isNotEmpty() || processAssistants.any { hasVisibleContent(it) }
        if (!hasProcess) {
            for (it in body) {
                when (it) {
                    is ChatItem.Assistant -> {
                        if (hasVisibleContent(it)) out += ChatRow.Assistant(it.key, it, thinkingMs = durations(it))
                        emitError(it)
                    }
                    else -> emitTail(it)
                }
            }
            return
        }

        val summaryKey = "turn-${body.first().key}"
        val isExpanded = summaryKey in expanded
        out += ChatRow.TurnSummary(
            key = summaryKey,
            thinkingMs = thinkingBlocks.sumOf { (a, b) -> b.durationMs ?: thinkingMs(a.key, b.index) ?: 0L },
            thinkingBlocks = thinkingBlocks.size,
            toolCount = tools.size,
            failedTools = tools.count { it.status == ToolStatus.ERROR },
            steps = processSteps,
            expanded = isExpanded,
        )
        if (isExpanded) {
            for (it in body) {
                when {
                    it === final -> if (hasThinking(it as ChatItem.Assistant)) {
                        out += ChatRow.Assistant("${it.key}-thinking", it, showText = false, thinkingMs = durations(it))
                    }
                    it is ChatItem.Assistant -> if (hasVisibleContent(it)) {
                        out += ChatRow.Assistant(it.key, it, thinkingMs = durations(it))
                    }
                    it is ChatItem.Tool -> out += ChatRow.Tool(it.key, it)
                }
            }
        }
        if (final != null) out += ChatRow.Assistant(final.key, final, showThinking = false, thinkingMs = durations(final))
        // 错误卡、提示行、未知 role 永远在折叠区之外，按原顺序显示。
        for (it in body) {
            when (it) {
                is ChatItem.Assistant -> emitError(it)
                is ChatItem.Notice, is ChatItem.Raw -> emitTail(it)
                else -> Unit
            }
        }
    }

    private fun hasText(a: ChatItem.Assistant) =
        a.blocks.any { it is ContentBlock.Text && it.text.isNotBlank() }

    private fun hasThinking(a: ChatItem.Assistant) = a.blocks.any { it is ContentBlock.Thinking }

    private fun hasVisibleContent(a: ChatItem.Assistant) =
        hasText(a) || hasThinking(a) || (a.streaming && a.blocks.isEmpty()) ||
            a.stopReason == "aborted" || a.stopReason == "length"
}
