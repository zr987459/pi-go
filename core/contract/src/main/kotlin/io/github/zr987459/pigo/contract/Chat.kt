package io.github.zr987459.pigo.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 引擎归约后的聊天界面状态（DESIGN.md §3.5）。由 `:core:protocol` 的 ChatReducer 产出，
 * 界面只读。所有列表项都带稳定 `key`，供 LazyColumn 使用。
 */
data class ChatState(
    val items: List<ChatItem> = emptyList(),
    /** `agent_start` 置真，只有 `agent_settled` 才置假。 */
    val running: Boolean = false,
    /** 以 `queue_update` 为唯一真相，整体替换。 */
    val queue: QueueSnapshot = QueueSnapshot.EMPTY,
    val retry: RetryBanner? = null,
    val sessionName: String? = null,
    val sessionFile: String? = null,
    val model: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val contextPercent: Double? = null,
    val statusLine: String? = null,
)

data class RetryBanner(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val errorMessage: String?)

enum class Delivery { SENDING, SENT, QUEUED, FAILED }

enum class ToolStatus { PENDING, RUNNING, OK, ERROR }

enum class NoticeKind { INFO, STOPPED, TRUNCATED, COMPACTION, INTERRUPTED, ERROR }

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val delivery: Delivery = Delivery.SENT,
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val blocks: List<ContentBlock> = emptyList(),
        val streaming: Boolean = true,
        /** `stop` / `toolUse` / `error` / `aborted` / `length` 等，原样保留。 */
        val stopReason: String? = null,
        val errorMessage: String? = null,
    ) : ChatItem

    data class Tool(
        override val key: String,
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject? = null,
        val status: ToolStatus = ToolStatus.PENDING,
        val partialResult: JsonElement? = null,
        val result: JsonElement? = null,
        val startedAtMillis: Long? = null,
        val durationMs: Long? = null,
    ) : ChatItem

    data class Notice(
        override val key: String,
        val kind: NoticeKind,
        val text: String,
    ) : ChatItem

    /** 未知 role，渲染为可展开的原始 JSON（DESIGN.md §3.8）。 */
    data class Raw(
        override val key: String,
        val role: String?,
        val json: JsonObject,
    ) : ChatItem
}

sealed interface ContentBlock {
    val index: Int

    data class Text(override val index: Int, val text: String) : ContentBlock
    data class Thinking(override val index: Int, val text: String, val durationMs: Long? = null) : ContentBlock
    data class ToolCall(override val index: Int, val id: String, val name: String, val argsJson: String) : ContentBlock
}
