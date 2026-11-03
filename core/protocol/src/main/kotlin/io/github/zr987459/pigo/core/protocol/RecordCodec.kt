package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvents
import io.github.zr987459.pigo.contract.rpc.ExtUiRequestRecord
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import io.github.zr987459.pigo.contract.rpc.RpcRecord
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * stdout 记录 ⇄ 类型化对象（DESIGN.md §3.4、§3.5）。
 *
 * 宽松：未知字段忽略，未知 `type` → [EngineEvent.Unknown]，字段类型不对时取默认值，**从不抛异常**。
 * 只有整行不是 JSON 对象时返回 [Decoded.Malformed]（客户端记日志，不影响后续记录）。
 */
object RecordCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    sealed interface Decoded {
        data class Record(val record: RpcRecord) : Decoded
        data class Malformed(val line: String, val reason: String) : Decoded
    }

    /** 命令序列化为一行（不含 LF）。kotlinx 会把字符串里的 `\n` 转义，保证一条命令就是一行。 */
    fun encode(command: RpcCommand): String = command.toJson().toString()

    fun decode(line: String): Decoded {
        val element = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            return Decoded.Malformed(line, e.message ?: e.javaClass.simpleName)
        } catch (e: StackOverflowError) {
            // 嵌套过深（超大的 get_tree）：当作坏记录丢弃，不能让读线程死掉、整条连接被当成退出。
            return Decoded.Malformed(line.take(200), "JSON 嵌套过深")
        }
        val obj = element as? JsonObject ?: return Decoded.Malformed(line, "not a JSON object")
        return Decoded.Record(decodeObject(obj))
    }

    fun decodeObject(obj: JsonObject): RpcRecord = when (val type = obj.str("type")) {
        "response" -> RpcResponse(
            id = obj.str("id"),
            command = obj.str("command") ?: "",
            success = obj.bool("success") ?: false,
            error = obj.str("error"),
            data = obj["data"]?.takeUnless { it is JsonNull },
            raw = obj,
        )
        "extension_ui_request" -> {
            val id = obj.str("id")
            val method = obj.str("method")
            if (id == null || method == null) EngineEvent.Unknown(type, obj) else ExtUiRequestRecord(id, method, obj)
        }
        else -> decodeEvent(type, obj)
    }

    private fun decodeEvent(type: String?, o: JsonObject): EngineEvent = when (type) {
        "agent_start" -> EngineEvent.AgentStart(o)
        "agent_end" -> EngineEvent.AgentEnd(o.arr("messages"), o.bool("willRetry") ?: false, o)
        "agent_settled" -> EngineEvent.AgentSettled(o.bool("aborted") ?: false, o)
        "turn_start" -> EngineEvent.TurnStart(o)
        "turn_end" -> EngineEvent.TurnEnd(o.obj("message"), o.arr("toolResults"), o)
        "message_start" -> o.obj("message")?.let { EngineEvent.MessageStart(it, o) } ?: EngineEvent.Unknown(type, o)
        "message_update" -> EngineEvent.MessageUpdate(
            message = o.obj("message"),
            event = decodeAssistantEvent(o.obj("assistantMessageEvent")),
            usage = o.obj("usage"),
            raw = o,
        )
        "message_end" -> o.obj("message")?.let { EngineEvent.MessageEnd(it, o) } ?: EngineEvent.Unknown(type, o)
        "tool_execution_start" -> {
            val id = o.str("toolCallId")
            if (id == null) EngineEvent.Unknown(type, o) else EngineEvent.ToolExecutionStart(
                toolCallId = id,
                toolName = o.str("toolName") ?: "",
                args = o.obj("args"),
                parentToolCallId = o.str("parentToolCallId"),
                raw = o,
            )
        }
        "tool_execution_update" -> {
            val id = o.str("toolCallId")
            if (id == null) EngineEvent.Unknown(type, o) else EngineEvent.ToolExecutionUpdate(id, o["partialResult"], o)
        }
        "tool_execution_end" -> {
            val id = o.str("toolCallId")
            if (id == null) EngineEvent.Unknown(type, o) else EngineEvent.ToolExecutionEnd(
                toolCallId = id,
                result = o["result"],
                isError = o.bool("isError") ?: false,
                durationMs = o.long("durationMs"),
                raw = o,
            )
        }
        "queue_update" -> EngineEvent.QueueUpdate(o.strings("steering"), o.strings("followUp"), o)
        "auto_retry_start" -> EngineEvent.AutoRetryStart(
            attempt = o.long("attempt")?.toInt() ?: 0,
            maxAttempts = o.long("maxAttempts")?.toInt() ?: 0,
            delayMs = o.long("delayMs") ?: 0,
            errorMessage = o.str("errorMessage"),
            raw = o,
        )
        "auto_retry_end" -> EngineEvent.AutoRetryEnd(
            success = o.bool("success") ?: false,
            attempt = o.long("attempt")?.toInt() ?: 0,
            finalError = o.str("finalError"),
            raw = o,
        )
        "compaction_start" -> EngineEvent.CompactionStart(o.str("reason"), o)
        "compaction_end" -> EngineEvent.CompactionEnd(
            result = o["result"]?.takeUnless { it is JsonNull },
            aborted = o.bool("aborted") ?: false,
            willRetry = o.bool("willRetry") ?: false,
            errorMessage = o.str("errorMessage"),
            raw = o,
        )
        "session_info_changed" -> EngineEvent.SessionInfoChanged(o.str("name"), o)
        "thinking_level_changed" -> o.str("level")?.let { EngineEvent.ThinkingLevelChanged(it, o) } ?: EngineEvent.Unknown(type, o)
        "extension_error" -> EngineEvent.ExtensionError(o.str("extensionPath"), o.str("event"), o.str("error"), o)
        // ---- pi 1.5.0 其余事件（feat/engine-rpc） ----
        "bash_execution_update" -> EngineEvents.BashExecutionUpdate(o.str("id"), o.str("delta") ?: "", o)
        "entry_appended" -> EngineEvents.EntryAppended(o.obj("entry"), o)
        "summarization_retry_scheduled" -> EngineEvents.SummarizationRetryScheduled(
            attempt = o.long("attempt")?.toInt() ?: 0,
            maxAttempts = o.long("maxAttempts")?.toInt() ?: 0,
            delayMs = o.long("delayMs") ?: 0,
            errorMessage = o.str("errorMessage"),
            raw = o,
        )
        "summarization_retry_attempt_start" -> EngineEvents.SummarizationRetryAttemptStart(o.str("source"), o.str("reason"), o)
        "summarization_retry_finished" -> EngineEvents.SummarizationRetryFinished(o)
        else -> EngineEvent.Unknown(type, o)
    }

    fun decodeAssistantEvent(e: JsonObject?): AssistantMessageEvent {
        if (e == null) return AssistantMessageEvent.Other(null, JsonObject(emptyMap()))
        val idx = e.long("contentIndex")?.toInt() ?: 0
        return when (val t = e.str("type")) {
            "start" -> AssistantMessageEvent.Start
            "text_start" -> AssistantMessageEvent.TextStart(idx)
            "text_delta" -> AssistantMessageEvent.TextDelta(idx, e.str("delta") ?: "")
            "text_end" -> AssistantMessageEvent.TextEnd(idx, e.str("content"))
            "thinking_start" -> AssistantMessageEvent.ThinkingStart(idx)
            "thinking_delta" -> AssistantMessageEvent.ThinkingDelta(idx, e.str("delta") ?: "")
            "thinking_end" -> AssistantMessageEvent.ThinkingEnd(idx, e.str("content"))
            "toolcall_start" -> AssistantMessageEvent.ToolcallStart(idx, e.str("id"), e.str("toolName"))
            "toolcall_delta" -> AssistantMessageEvent.ToolcallDelta(idx, e.str("delta") ?: "")
            "toolcall_end" -> AssistantMessageEvent.ToolcallEnd(idx, e.obj("toolCall"))
            "done" -> AssistantMessageEvent.Done(e.str("reason"))
            "error" -> AssistantMessageEvent.Error(e.str("reason"))
            else -> AssistantMessageEvent.Other(t, e)
        }
    }
}

// ---- 宽松取值：类型不符时返回 null，不抛异常 ----

internal fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.str(key: String): String? = prim(key)?.let { if (it.isString) it.content else it.contentOrNull }

fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.strings(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }.orEmpty()

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
