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

// =====================================================================================================
// 以下为 feat/engine-rpc（W1）追加：pi 1.5.0 `AgentSessionEvent` 中其余事件，以及新命令响应 `data` 的类型。
// 只追加，不改上面已有类型。字段名以 1.5.0 的 `rpc-types.d.ts` / `agent-session.d.ts` 为准；每个都保留 `raw`。
// =====================================================================================================

/** 追加的会话事件（仍是 [EngineEvent]，由 `:core:protocol` 的 RecordCodec 解码）。 */
object EngineEvents {
    /** `bash` 命令的流式输出：`id` 是发出 `bash` 时的命令 id（扩展处理的 user_bash 可能没有 id）。 */
    data class BashExecutionUpdate(val id: String?, val delta: String, override val raw: JsonObject) : EngineEvent

    /** 会话文件追加了一条条目（`entry` 为 SessionEntry）。 */
    data class EntryAppended(val entry: JsonObject?, override val raw: JsonObject) : EngineEvent

    data class SummarizationRetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val errorMessage: String?,
        override val raw: JsonObject,
    ) : EngineEvent

    /** `source`：`compaction` 或 `branchSummary`；compaction 时带 `reason`。 */
    data class SummarizationRetryAttemptStart(val source: String?, val reason: String?, override val raw: JsonObject) : EngineEvent

    data class SummarizationRetryFinished(override val raw: JsonObject) : EngineEvent
}

/** `get_commands` 的一项（`RpcSlashCommand`）。`source`：`extension` / `prompt` / `skill`。 */
data class SlashCommandRecord(
    val name: String,
    val description: String?,
    val source: String,
    /** `sourceInfo.path`：模板/技能文件路径，扩展为扩展路径。 */
    val path: String?,
    /** `sourceInfo.scope`：`user` / `project` / `temporary`。 */
    val scope: String?,
    val raw: JsonObject,
)

/** `get_fork_messages` 的一项。 */
data class ForkMessageRecord(val entryId: String, val text: String)

/** `fork` 的响应。 */
data class ForkResultRecord(val text: String, val cancelled: Boolean)

/** `bash` 的响应（`BashResult`）。`exitCode` 被中止时为 null。 */
data class BashResultRecord(
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean,
    val truncated: Boolean,
    val fullOutputPath: String?,
    val raw: JsonObject,
)

/** `compact` 的响应 / `compaction_end.result`（`CompactionResult`）。 */
data class CompactionResultRecord(
    val summary: String,
    val firstKeptEntryId: String?,
    val tokensBefore: Long?,
    val estimatedTokensAfter: Long?,
    val raw: JsonObject,
)

/** `get_session_stats` 的 `tokens`。 */
data class TokenTotalsRecord(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val total: Long)

/** `get_session_stats` 的 `contextUsage`：压缩后到下一次模型回复之前 `tokens`/`percent` 为 null。 */
data class ContextUsageRecord(val tokens: Long?, val contextWindow: Long?, val percent: Double?)

/** `get_session_stats` 的完整响应（`SessionStats`）。 */
data class SessionStatsRecord(
    val sessionFile: String?,
    val sessionId: String?,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val toolResults: Int,
    val totalMessages: Int,
    val tokens: TokenTotalsRecord,
    /** 美元。 */
    val cost: Double,
    val contextUsage: ContextUsageRecord?,
    val raw: JsonObject,
)

/** 会话条目（`SessionEntry`）的要点；`raw` 为完整条目。 */
data class SessionEntryRecord(
    val id: String,
    val parentId: String?,
    /** `message` / `compaction` / `branch_summary` / `model_change` / `thinking_level_change` / `session_info` / `label` / `custom` / … */
    val type: String,
    val timestamp: String?,
    /** `type == "message"` 时的 `message.role`。 */
    val role: String?,
    /** 用于树视图的一行预览（用户/助手文本、摘要等，已截断）。 */
    val preview: String,
    val raw: JsonObject,
)

/** `get_tree` 的节点（`SessionTreeNode`）。 */
data class SessionTreeNodeRecord(
    val entry: SessionEntryRecord,
    val children: List<SessionTreeNodeRecord>,
    val label: String?,
)

/** `get_tree` 的响应。 */
data class SessionTreeRecord(val roots: List<SessionTreeNodeRecord>, val leafId: String?)

/** `get_entries` 的响应。 */
data class SessionEntriesRecord(val entries: List<SessionEntryRecord>, val leafId: String?)
