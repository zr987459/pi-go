package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fake 引擎专用的最小归约器，只覆盖夹具里出现的事件。
 * 真正的 ChatReducer（含 `*_end` 校正、重试、压缩、未知 role 等）由 `:core:protocol`（mvp/engine）实现。
 */
class FakeChatReducer {
    private var seq = 0

    fun reduce(state: ChatState, event: EngineEvent, nowMillis: Long = 0L): ChatState = when (event) {
        is EngineEvent.AgentStart -> state.copy(running = true)
        is EngineEvent.AgentSettled -> state.copy(
            running = false,
            items = state.items.map { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false) else it },
        )
        is EngineEvent.MessageStart -> onMessageStart(state, event.message)
        is EngineEvent.MessageUpdate -> onUpdate(state, event.event)
        is EngineEvent.MessageEnd -> onMessageEnd(state, event.message)
        is EngineEvent.ToolExecutionStart -> state.copy(
            items = state.items + ChatItem.Tool(
                key = "tool-${event.toolCallId}",
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                args = event.args,
                status = ToolStatus.RUNNING,
                startedAtMillis = nowMillis,
            ),
        )
        is EngineEvent.ToolExecutionUpdate -> state.updateTool(event.toolCallId) { it.copy(partialResult = event.partialResult) }
        is EngineEvent.ToolExecutionEnd -> state.updateTool(event.toolCallId) {
            it.copy(
                status = if (event.isError) ToolStatus.ERROR else ToolStatus.OK,
                result = event.result,
                durationMs = event.durationMs,
            )
        }
        is EngineEvent.QueueUpdate -> state.copy(queue = QueueSnapshot(event.steering, event.followUp))
        is EngineEvent.SessionInfoChanged -> state.copy(sessionName = event.name)
        is EngineEvent.ThinkingLevelChanged -> state.copy(thinkingLevel = event.level)
        else -> state
    }

    private fun onMessageStart(state: ChatState, message: JsonObject): ChatState =
        when (message["role"]?.jsonPrimitive?.contentOrNull) {
            "user" -> {
                val text = textOf(message)
                val idx = state.items.indexOfLast {
                    it is ChatItem.User && it.text == text && it.delivery != Delivery.SENT
                }
                if (idx >= 0) {
                    // 确认乐观气泡
                    state.copy(items = state.items.toMutableList().also {
                        it[idx] = (it[idx] as ChatItem.User).copy(delivery = Delivery.SENT)
                    })
                } else {
                    state.copy(items = state.items + ChatItem.User("user-${seq++}", text, Delivery.SENT))
                }
            }
            "assistant" -> state.copy(items = state.items + ChatItem.Assistant("assistant-${seq++}"))
            else -> state
        }

    private fun onUpdate(state: ChatState, e: AssistantMessageEvent): ChatState = state.updateLastAssistant { a ->
        when (e) {
            is AssistantMessageEvent.TextStart -> a.withBlock(ContentBlock.Text(e.contentIndex, ""))
            is AssistantMessageEvent.TextDelta -> {
                val prev = a.blocks.firstOrNull { it.index == e.contentIndex } as? ContentBlock.Text
                a.withBlock(ContentBlock.Text(e.contentIndex, (prev?.text ?: "") + e.delta))
            }
            is AssistantMessageEvent.TextEnd ->
                if (e.content != null) a.withBlock(ContentBlock.Text(e.contentIndex, e.content)) else a
            is AssistantMessageEvent.ToolcallEnd -> {
                val tc = e.toolCall
                if (tc == null) a else a.withBlock(
                    ContentBlock.ToolCall(
                        index = e.contentIndex,
                        id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = tc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        argsJson = tc["arguments"]?.toString() ?: "{}",
                    ),
                )
            }
            else -> a
        }
    }

    private fun onMessageEnd(state: ChatState, message: JsonObject): ChatState {
        if (message["role"]?.jsonPrimitive?.contentOrNull != "assistant") return state
        val stop = message["stopReason"]?.jsonPrimitive?.contentOrNull
        return state.updateLastAssistant { it.copy(streaming = false, stopReason = stop) }
    }

    private fun ChatState.updateLastAssistant(f: (ChatItem.Assistant) -> ChatItem.Assistant): ChatState {
        val idx = items.indexOfLast { it is ChatItem.Assistant }
        if (idx < 0) return this
        return copy(items = items.toMutableList().also { it[idx] = f(it[idx] as ChatItem.Assistant) })
    }

    private fun ChatState.updateTool(id: String, f: (ChatItem.Tool) -> ChatItem.Tool): ChatState =
        copy(items = items.map { if (it is ChatItem.Tool && it.toolCallId == id) f(it) else it })

    private fun ChatItem.Assistant.withBlock(block: ContentBlock): ChatItem.Assistant {
        val others = blocks.filter { it.index != block.index }
        return copy(blocks = (others + block).sortedBy { it.index })
    }

    private fun textOf(message: JsonObject): String {
        val content = message["content"]
        if (content is JsonPrimitive) return content.contentOrNull ?: ""
        return (content as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull ?: "" }
    }
}
