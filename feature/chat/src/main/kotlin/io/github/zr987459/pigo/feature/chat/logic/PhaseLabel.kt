package io.github.zr987459.pigo.feature.chat.logic

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从点发送到出第一个字（以及长时间跑工具）期间的阶段提示，显示在输入框上方的状态区，
 * 让用户知道没有卡死（piweb-study G5）。纯函数，不改契约，只从已有状态推导。
 *
 * 移植自 agegr/pi-web@025e24e `lib/chat-phase-label.ts:11-43`（阶段 → 文案，压缩优先）与
 * `lib/tool-execution-progress.ts:1-29`（partialResult 取最后一行非空输出、截断），MIT。
 */
object PhaseLabel {
    const val STARTING_ENGINE = "正在启动引擎…"
    const val CONNECTING = "正在连接模型…"
    const val WAITING = "等待回复…"
    const val THINKING = "正在思考…"

    /** 工具输出最后一行最多显示的字数。 */
    const val MAX_TOOL_LINE = 80

    /**
     * @param stopping 正在停止时不显示（停止有自己的「正在停止…」）。
     * @return 当前阶段文案；正在流式输出正文、或空闲时为 null。
     */
    fun of(status: EngineStatus, chat: ChatState, sends: List<LocalSend>, stopping: Boolean): String? {
        if (stopping) return null
        val sending = sends.any { it.status == Delivery.SENDING }
        if (status is EngineStatus.Starting) {
            // 引擎（重）启动中：有消息在等或正在生成时才提示，空闲时顶部已有引擎状态。
            return if (sending || chat.running) STARTING_ENGINE else null
        }
        val statusLine = chat.statusLine?.takeIf { it.isNotBlank() }
        if (statusLine != null) {
            // 压缩等引擎状态行已经显示；压缩中还有排队消息时补一句排队数，避免看起来像卡住。
            val queued = chat.queue.steering.size + chat.queue.followUp.size
            return if (queued > 0 && "压缩" in statusLine) "正在压缩上下文（$queued 条排队）" else null
        }
        if (!chat.running) return if (sending) CONNECTING else null

        val turn = currentTurn(chat.items)
        val runningTool = turn.lastOrNull { it is ChatItem.Tool && (it.status == ToolStatus.RUNNING || it.status == ToolStatus.PENDING) } as ChatItem.Tool?
        if (runningTool != null) {
            val line = lastOutputLine(runningTool.partialResult)
            return "正在运行 ${runningTool.toolName}" + (line?.let { "：$it" } ?: "…")
        }
        val last = turn.lastOrNull()
        return when {
            last == null || last is ChatItem.User -> WAITING
            last is ChatItem.Tool -> WAITING // 工具刚结束，模型还没开始下一段
            last is ChatItem.Assistant && last.streaming -> when {
                last.blocks.any { it is ContentBlock.Text && it.text.isNotBlank() } -> null
                last.blocks.any { it is ContentBlock.Thinking } -> THINKING
                else -> WAITING
            }
            else -> null
        }
    }

    /** 最后一条用户消息之后的条目（当前回合）；没有用户消息时为全部。 */
    private fun currentTurn(items: List<ChatItem>): List<ChatItem> {
        val i = items.indexOfLast { it is ChatItem.User }
        return if (i < 0) items else items.subList(i, items.size)
    }

    /** `partialResult.content[].text`（或纯字符串）的最后一行非空输出，超长截断。 */
    fun lastOutputLine(partial: JsonElement?): String? {
        val text = when (partial) {
            null -> return null
            is JsonPrimitive -> partial.contentOrNull
            is JsonObject -> (partial["content"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                ?.joinToString("\n")
                ?: (partial["output"] as? JsonPrimitive)?.contentOrNull
            is JsonArray -> partial.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
        } ?: return null
        val line = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        return if (line.length > MAX_TOOL_LINE) line.take(MAX_TOOL_LINE - 1) + "…" else line
    }
}

/**
 * 自动重试黄条何时隐藏（piweb-study G4）：pi 的 `auto_retry_end{success:true}` 要等重试那一轮完整回复后才来，
 * 黄条会在整段流式期间残留。Pi-Web 在 `agent_start` 时就清掉（agegr/pi-web@025e24e
 * `hooks/useAgentSession.ts:1778-1789`，MIT）；pi-go 的归约器在 `:core:protocol`（本次不改），
 * 所以在界面层等价处理：黄条出现后，一旦出现**新的**助手消息开始输出内容（重试已在出字），或回合已结束，就不再显示。
 * 重试最终失败仍由 `auto_retry_end{success:false}` 产生的错误提示告知，不受影响。
 */
object RetryVisibility {
    /** @param anchorKey 黄条出现时最后一条助手消息的 key（没有则为 null）。 */
    fun visible(chat: ChatState, anchorKey: String?): Boolean {
        if (chat.retry == null) return false
        if (!chat.running) return false
        val last = chat.items.lastOrNull { it is ChatItem.Assistant } as ChatItem.Assistant? ?: return true
        if (last.key == anchorKey) return true
        val producing = last.blocks.any {
            (it is ContentBlock.Text && it.text.isNotBlank()) || it is ContentBlock.Thinking || it is ContentBlock.ToolCall
        }
        return !producing
    }

    fun lastAssistantKey(chat: ChatState): String? = chat.items.lastOrNull { it is ChatItem.Assistant }?.key
}

/**
 * 黄条锚点：每一次重试（attempt 变化）开始时重新记下最后一条助手消息。
 * 只在黄条第一次出现时记锚点不够：第 1 次重试已流出部分文字后失败，第 2 次重试等待期间
 * 「最后一条助手消息」就是那条带字的失败消息，会被误判为重试已在出字而提前隐藏黄条。
 */
class RetryAnchor {
    private var shown: RetryBanner? = null
    var anchorKey: String? = null
        private set

    /** 每次合成时调用；返回要显示的黄条（不显示为 null）。 */
    fun update(chat: ChatState): RetryBanner? {
        val retry = chat.retry
        if (retry == null) {
            shown = null
            anchorKey = null
            return null
        }
        val prev = shown
        if (prev == null || prev.attempt != retry.attempt) {
            shown = retry
            anchorKey = RetryVisibility.lastAssistantKey(chat)
        }
        return retry.takeIf { RetryVisibility.visible(chat, anchorKey) }
    }
}
