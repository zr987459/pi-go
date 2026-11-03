package io.github.zr987459.pigo.contract.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * pi → 客户端的 stdout 记录（DESIGN.md §3.5）。三类：`response`、会话事件、`extension_ui_request`。
 * 解码由 `:core:protocol` 负责；这里只定义类型。每个记录都保留 `raw`，未知字段不丢。
 */
sealed interface RpcRecord {
    val raw: JsonObject
}

/**
 * `{"id":"c-1","type":"response","command":"prompt","success":true,"data":{…}}`。
 * 格式错误时 pi 回**不带 id** 的 `command:"parse"`。
 */
data class RpcResponse(
    val id: String?,
    val command: String,
    val success: Boolean,
    val error: String? = null,
    val data: JsonElement? = null,
    override val raw: JsonObject,
) : RpcRecord {
    val isParseError: Boolean get() = id == null && command == "parse"
}

/** `type:"extension_ui_request"`。 */
data class ExtUiRequestRecord(
    val id: String,
    val method: String,
    override val raw: JsonObject,
) : RpcRecord

/** 会话事件。 */
sealed interface EngineEvent : RpcRecord {
    data class AgentStart(override val raw: JsonObject) : EngineEvent
    data class AgentEnd(val messages: JsonArray?, val willRetry: Boolean, override val raw: JsonObject) : EngineEvent

    /** 一轮彻底结束的唯一信号（不是 `agent_end`）。 */
    data class AgentSettled(val aborted: Boolean, override val raw: JsonObject) : EngineEvent

    data class TurnStart(override val raw: JsonObject) : EngineEvent
    data class TurnEnd(val message: JsonObject?, val toolResults: JsonArray?, override val raw: JsonObject) : EngineEvent

    data class MessageStart(val message: JsonObject, override val raw: JsonObject) : EngineEvent
    data class MessageUpdate(
        val message: JsonObject?,
        val event: AssistantMessageEvent,
        val usage: JsonObject?,
        override val raw: JsonObject,
    ) : EngineEvent

    /** 权威消息，整体替换。 */
    data class MessageEnd(val message: JsonObject, override val raw: JsonObject) : EngineEvent

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject?,
        /** 未文档化，按可选处理。 */
        val parentToolCallId: String?,
        override val raw: JsonObject,
    ) : EngineEvent

    data class ToolExecutionUpdate(val toolCallId: String, val partialResult: JsonElement?, override val raw: JsonObject) : EngineEvent

    data class ToolExecutionEnd(
        val toolCallId: String,
        val result: JsonElement?,
        val isError: Boolean,
        val durationMs: Long?,
        override val raw: JsonObject,
    ) : EngineEvent

    /** 每次都是完整队列。 */
    data class QueueUpdate(val steering: List<String>, val followUp: List<String>, override val raw: JsonObject) : EngineEvent

    data class AutoRetryStart(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String?,
        override val raw: JsonObject,
    ) : EngineEvent

    data class AutoRetryEnd(val success: Boolean, val attempt: Int, val finalError: String?, override val raw: JsonObject) : EngineEvent

    data class CompactionStart(val reason: String?, override val raw: JsonObject) : EngineEvent
    data class CompactionEnd(
        val result: JsonElement?,
        val aborted: Boolean,
        val willRetry: Boolean,
        val errorMessage: String?,
        override val raw: JsonObject,
    ) : EngineEvent

    data class SessionInfoChanged(val name: String?, override val raw: JsonObject) : EngineEvent
    data class ThinkingLevelChanged(val level: String, override val raw: JsonObject) : EngineEvent
    data class ExtensionError(val extensionPath: String?, val event: String?, val error: String?, override val raw: JsonObject) : EngineEvent

    /** 未知 `type`：只记日志，不抛异常。 */
    data class Unknown(val type: String?, override val raw: JsonObject) : EngineEvent
}

/** `message_update.assistantMessageEvent`，按 `contentIndex` 维护块。 */
sealed interface AssistantMessageEvent {
    data object Start : AssistantMessageEvent
    data class TextStart(val contentIndex: Int) : AssistantMessageEvent
    data class TextDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent

    /** `*_end` 带权威内容，用来覆盖拼接结果。 */
    data class TextEnd(val contentIndex: Int, val content: String?) : AssistantMessageEvent
    data class ThinkingStart(val contentIndex: Int) : AssistantMessageEvent
    data class ThinkingDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent
    data class ThinkingEnd(val contentIndex: Int, val content: String?) : AssistantMessageEvent
    data class ToolcallStart(val contentIndex: Int, val id: String?, val toolName: String?) : AssistantMessageEvent
    data class ToolcallDelta(val contentIndex: Int, val delta: String) : AssistantMessageEvent
    data class ToolcallEnd(val contentIndex: Int, val toolCall: JsonObject?) : AssistantMessageEvent
    data class Done(val reason: String?) : AssistantMessageEvent
    data class Error(val reason: String?) : AssistantMessageEvent
    data class Other(val type: String?, val raw: JsonObject) : AssistantMessageEvent
}
