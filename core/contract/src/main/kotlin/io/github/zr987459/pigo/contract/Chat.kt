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

    // ---- feat/engine-rpc（W1）追加的子类：只新增，不改上面已有子类 ----

    /**
     * 时间线上的压缩卡（U3/U4）：`compaction_start` 时出现（[CompactionPhase.RUNNING]），`compaction_end` / `compact` 响应后定稿。
     * 历史重建时由 `compactionSummary` 消息生成（[CompactionPhase.DONE]）。
     * [tokensAfter] 为 pi 的 `estimatedTokensAfter`（估算值，可能为 null）。
     */
    data class Compaction(
        override val key: String,
        val phase: CompactionPhase,
        /** `manual` / `threshold` / `overflow`；历史重建时未知为 null。 */
        val reason: String? = null,
        val tokensBefore: Long? = null,
        val tokensAfter: Long? = null,
        /** 摘要正文（已去掉末尾的 `<read-files>`/`<modified-files>` 段）。 */
        val summary: String? = null,
        val readFiles: List<String> = emptyList(),
        val modifiedFiles: List<String> = emptyList(),
        val errorMessage: String? = null,
    ) : ChatItem

    /**
     * 用户直接执行的 shell 命令卡（C6，`!cmd` / `!!cmd`）。[output] 随 `bash_execution_update` 按 [bashId] 累加，
     * `bash` 响应到达后以 pi 的 `output` 为准（可能被截断，完整输出在 [fullOutputPath]）。历史重建时由 `bashExecution` 消息生成。
     */
    data class Bash(
        override val key: String,
        /** 发出 `bash` 命令时的 RPC id；历史重建的卡为 null。 */
        val bashId: String?,
        val command: String,
        val excludeFromContext: Boolean = false,
        val output: String = "",
        val running: Boolean = true,
        /** 被中止时为 null。 */
        val exitCode: Int? = null,
        val cancelled: Boolean = false,
        val truncated: Boolean = false,
        val fullOutputPath: String? = null,
        val errorMessage: String? = null,
        val startedAtMillis: Long? = null,
        val durationMs: Long? = null,
    ) : ChatItem
}

sealed interface ContentBlock {
    val index: Int

    data class Text(override val index: Int, val text: String) : ContentBlock
    data class Thinking(override val index: Int, val text: String, val durationMs: Long? = null) : ContentBlock
    data class ToolCall(override val index: Int, val id: String, val name: String, val argsJson: String) : ContentBlock
}

/** 压缩卡的阶段（feat/engine-rpc 追加）。 */
enum class CompactionPhase { RUNNING, DONE }
