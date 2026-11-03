package io.github.zr987459.pigo.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/*
 * W0（feat/modules）新增的契约，docs/feature-gap.md §3.2。定稿后冻结：W1–W4 只实现或调用，不改签名。
 *
 * - [EngineExtras]：pi 1.5.0 RPC 中 [EngineClient] 尚未覆盖的能力，由 W1 在 `PiEngine` 中实现。
 *   命令名和字段名以 `@earendil-works/pi-coding-agent@1.5.0` 的 `dist/modes/rpc/rpc-types.d.ts` 为准。
 * - 时间线附加项 [TimelineExtra]：压缩卡、bash 卡、用户图片、轮末汇总。不新增 [ChatItem] 子类，
 *   因为 `feature/chat` 里对 sealed [ChatItem] 的穷尽 `when` 会因此无法编译（这些文件属于界面工作者和 W4）。
 *   附加项通过 [TimelineExtra.anchorKey] 挂在某个 [ChatItem.key] 之后，由 W5 接线时渲染。
 * - [SessionIndex]、[DraftStore]：会话列表的数据增强（W4 实现）。
 */

// ---------------- 图片输入（C1） ----------------

/** `prompt`/`steer`/`follow_up` 的 `images[]` 一项：`{type:"image", data, mimeType}`。[base64Data] 不含 `data:` 前缀。 */
data class ImageInput(
    val mimeType: String,
    val base64Data: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    /** 用户看到的文件名，只用于界面，不发给 pi。 */
    val displayName: String? = null,
) {
    /** 解码前的估算字节数。 */
    val approxBytes: Long get() = base64Data.length.toLong() * 3 / 4

    /** 乐观气泡与 pi 回显的 user 消息配对时用（参考 Pi-Web `prompt-recovery.ts` 的图片签名）。 */
    val signature: String get() = "$mimeType:${base64Data.length}:${base64Data.take(32)}"

    override fun toString(): String = "ImageInput($mimeType, ${approxBytes}B, ${widthPx}x$heightPx, $displayName)"
}

// ---------------- 斜杠命令（C3、C4） ----------------

enum class CommandSource { EXTENSION, PROMPT, SKILL, UNKNOWN }

/** `get_commands` 的一项。执行方式：`prompt` 发 `"/$name 参数"`。 */
data class SlashCommand(
    /** 不含 `/`；技能为 `skill:<名>`。 */
    val name: String,
    val description: String? = null,
    val source: CommandSource = CommandSource.UNKNOWN,
    /** `sourceInfo.path`（容器内绝对路径），扩展命令可能为空。 */
    val path: String? = null,
    /** `user` / `project` / `temporary`。 */
    val scope: String? = null,
    /** `top-level` / `package`。 */
    val origin: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val invocation: String get() = "/$name"
}

// ---------------- 会话树与分叉（S1、S2、S6） ----------------

/** `get_fork_messages` 的一项。 */
data class ForkPoint(val entryId: String, val text: String)

/** `fork` 响应；[cancelled] 表示被扩展拦截。[text] 是被分叉的那条用户消息，应回填输入框。 */
data class ForkResult(val text: String?, val cancelled: Boolean)

/** 会话文件中的一条 entry（不含 header）。 */
data class SessionEntry(
    val id: String,
    val parentId: String?,
    /** `message` / `compaction` / `model_change` / `session_info` 等，原样保留。 */
    val type: String,
    val timestamp: String? = null,
    /** `type == "message"` 时的 `message.role`。 */
    val role: String? = null,
    /** 供树视图显示的一行文字（用户消息文本、助手回答开头、压缩摘要开头等）。 */
    val preview: String = "",
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class TreeNode(
    val entry: SessionEntry,
    val children: List<TreeNode> = emptyList(),
    val label: String? = null,
)

/** `get_tree`：可能有多个根；[leafId] 是当前分支的叶子。 */
data class SessionTree(val roots: List<TreeNode>, val leafId: String?) {
    fun find(id: String): TreeNode? {
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.entry.id == id) return n
            stack.addAll(n.children)
        }
        return null
    }

    /** 所有叶子节点（没有子节点的）。 */
    fun leaves(): List<TreeNode> {
        val out = ArrayList<TreeNode>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.children.isEmpty()) out += n else stack.addAll(n.children)
        }
        return out
    }
}

/** `get_entries{since}`：[entries] 为 since 之后（不含）的条目，按追加顺序。 */
data class EntriesPage(val entries: List<SessionEntry>, val leafId: String?)

// ---------------- 用量与压缩（U1–U4） ----------------

data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
)

/** `contextUsage`；压缩后 pi 可能给 `percent == null`，此时界面显示“待更新”，不得回退旧值。 */
data class ContextUsage(val tokens: Long?, val contextWindow: Long?, val percent: Double?)

/** `get_session_stats` 的完整结果。费用单位为美元（pi 原样）。 */
data class UsageStats(
    val sessionFile: String? = null,
    val sessionId: String? = null,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    val toolResults: Int = 0,
    val totalMessages: Int = 0,
    val tokens: TokenUsage = TokenUsage(),
    val costUsd: Double? = null,
    val context: ContextUsage? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** `compact` 响应。 */
data class CompactResult(
    val summary: String,
    val firstKeptEntryId: String?,
    val tokensBefore: Long?,
    val estimatedTokensAfter: Long?,
    val usage: TokenUsage? = null,
    val costUsd: Double? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

// ---------------- 队列模式与自动开关（M8） ----------------

/** pi 的取值：`all` / `one-at-a-time`。 */
enum class QueueMode(val wire: String) {
    ALL("all"),
    ONE_AT_A_TIME("one-at-a-time");

    companion object {
        fun fromWire(s: String?): QueueMode? = entries.firstOrNull { it.wire == s }
    }
}

/** 当前会话的运行设置（来自 `get_state`）。未知时为 null。 */
data class SessionSettings(
    val autoCompaction: Boolean? = null,
    val autoRetry: Boolean? = null,
    val steeringMode: QueueMode? = null,
    val followUpMode: QueueMode? = null,
)

// ---------------- 用户 shell 命令（C6、F5） ----------------

data class BashResult(
    val output: String,
    val exitCode: Int?,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    /** 输出被截断时 pi 保存完整输出的容器内路径。 */
    val fullOutputPath: String? = null,
)

sealed interface BashEvent {
    /** `bash_execution_update` 的增量输出。 */
    data class Output(val chunk: String) : BashEvent
    data class Done(val result: BashResult) : BashEvent
    data class Failed(val error: Outcome.Err) : BashEvent
}

// ---------------- 回合结果与通知（N1） ----------------

enum class RunOutcomeKind { COMPLETED, ERROR, ABORTED, NEEDS_INPUT }

/** 每次 `agent_settled`（或扩展对话框等待用户）发一条，供通知与轮末汇总使用。 */
data class RunOutcome(
    val kind: RunOutcomeKind,
    val sessionFile: String?,
    val sessionName: String?,
    /** 出错时的中文原因（已脱敏）；需要回答时是对话框标题。 */
    val message: String? = null,
    /** 最后一条助手文本的开头（用于通知正文），已脱敏，≤200 字。 */
    val lastTextPreview: String? = null,
    val atMillis: Long = 0,
)

// ---------------- 时间线附加项 ----------------

sealed interface TimelineExtra {
    /** 稳定 key，供 LazyColumn 使用。 */
    val key: String

    /** 渲染在哪个 [ChatItem.key] 之后；null 表示放在时间线末尾。 */
    val anchorKey: String?

    /** 压缩卡：“已压缩：150k → 32k”，可展开摘要与读写文件（U3、U4）。 */
    data class Compaction(
        override val key: String,
        override val anchorKey: String?,
        val tokensBefore: Long?,
        val estimatedTokensAfter: Long?,
        val summary: String?,
        val readFiles: List<String> = emptyList(),
        val modifiedFiles: List<String> = emptyList(),
        /** 是否用户手动触发（`compact`），否则为自动压缩。 */
        val manual: Boolean = false,
        val running: Boolean = false,
    ) : TimelineExtra

    /** 用户 `!命令` 的终端卡（C6）。 */
    data class Bash(
        override val key: String,
        override val anchorKey: String?,
        val command: String,
        val output: String,
        val running: Boolean,
        val exitCode: Int? = null,
        val excludedFromContext: Boolean = false,
        val truncated: Boolean = false,
        val fullOutputPath: String? = null,
    ) : TimelineExtra

    /** 某条用户消息附带的图片（C1）；[anchorKey] 即该 [ChatItem.User.key]。 */
    data class UserImages(
        override val key: String,
        override val anchorKey: String?,
        val images: List<ImageInput>,
    ) : TimelineExtra

    /** 轮末汇总（C10）：本轮改动的文件、耗时、输出速率。 */
    data class TurnSummary(
        override val key: String,
        override val anchorKey: String?,
        /** 只认成功的 write/edit 类工具结果，容器内路径，去重保序。 */
        val writtenFiles: List<String>,
        val durationMs: Long?,
        val outputTokens: Long?,
        val tokensPerSecond: Double?,
    ) : TimelineExtra
}

/** 扩展的非阻塞 UI（C12）：`setStatus`、`setWidget`、`setTitle`。 */
data class ExtensionSurface(
    val statuses: Map<String, String> = emptyMap(),
    /** widgetKey → (placement, lines)；placement 为 `aboveEditor` / `belowEditor`。 */
    val widgets: Map<String, Pair<String, List<String>>> = emptyMap(),
    val title: String? = null,
)

// ---------------- 引擎扩展能力 ----------------

/**
 * [EngineClient] 之外的 pi RPC 能力（W1 实现，`PiEngine` 同时实现两个接口）。
 * 全部方法在引擎未就绪时返回 `Outcome.Err(ENGINE_START, …)`，不抛异常。
 */
interface EngineExtras {
    // --- 输入 ---

    /** 带图片发送；[images] 为空时等同 [EngineClient.send]。空闲时忽略 [mode]。 */
    suspend fun send(text: String, images: List<ImageInput>, mode: SendMode = SendMode.FOLLOW_UP): Outcome<Disposition>

    /** `get_commands`。按 cwd 缓存；[refresh] 为 true 时重新请求。 */
    suspend fun commands(refresh: Boolean = false): Outcome<List<SlashCommand>>

    // --- 会话树 ---

    suspend fun forkMessages(): Outcome<List<ForkPoint>>
    suspend fun fork(entryId: String): Outcome<ForkResult>

    /** `clone`：返回 true 表示被扩展取消。 */
    suspend fun clone(): Outcome<Boolean>
    suspend fun tree(): Outcome<SessionTree>

    /** `get_entries`；[since] 为 null 时取全部。 */
    suspend fun entries(since: String? = null): Outcome<EntriesPage>

    // --- 模型快捷切换 ---

    /** `cycle_model`：返回切换后的模型；没有可切换的返回 null。 */
    suspend fun cycleModel(): Outcome<ModelInfo?>

    /** `cycle_thinking_level`：返回新级别；模型不支持思考时返回 null。 */
    suspend fun cycleThinking(): Outcome<String?>

    // --- 用量与压缩 ---

    suspend fun usage(): Outcome<UsageStats>
    suspend fun compact(customInstructions: String? = null): Outcome<CompactResult>

    // --- 运行设置 ---

    suspend fun sessionSettings(): Outcome<SessionSettings>
    suspend fun setAutoCompaction(enabled: Boolean): Outcome<Unit>
    suspend fun setAutoRetry(enabled: Boolean): Outcome<Unit>
    suspend fun setSteeringMode(mode: QueueMode): Outcome<Unit>
    suspend fun setFollowUpMode(mode: QueueMode): Outcome<Unit>

    // --- shell ---

    /** `bash`：冷流，收集时发命令，以 [BashEvent.Done] 或 [BashEvent.Failed] 结束。 */
    fun bash(command: String, excludeFromContext: Boolean = false): Flow<BashEvent>
    suspend fun abortBash(): Outcome<Unit>

    // --- 导出与复制 ---

    /** `export_html`：返回生成文件的容器内路径。 */
    suspend fun exportHtml(outputPath: String? = null): Outcome<String>

    /** `get_last_assistant_text`；没有时为 null。 */
    suspend fun lastAssistantText(): Outcome<String?>

    // --- 可观察状态 ---

    /** 时间线附加项，随会话切换整体替换。 */
    val timelineExtras: StateFlow<List<TimelineExtra>>

    /** 回合结果事件（通知、轮末汇总用）。 */
    val runOutcomes: SharedFlow<RunOutcome>

    /** 最近一次已知的用量；每轮 `agent_settled` 后刷新。 */
    val usageState: StateFlow<UsageStats?>

    val extensionSurface: StateFlow<ExtensionSurface>
}

// ---------------- 会话数据增强（W4：S3、S4、S5、S7） ----------------

/** 宿主侧保存的会话元数据，不改 pi 的会话文件。 */
data class SessionMeta(
    val guestPath: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val unread: Boolean = false,
    /** 用户手动改过名后为 true，自动命名不再覆盖。 */
    val nameLockedByUser: Boolean = false,
)

data class SessionHit(
    val summary: SessionSummary,
    val meta: SessionMeta,
    /** 命中片段（含前后文），无查询时为 null。 */
    val snippet: String? = null,
    /** 命中位置在第几条用户消息（0 起），名字命中为 -1。 */
    val messageIndex: Int? = null,
)

data class SessionGroup(val cwd: String, val sessions: List<SessionHit>)

interface SessionIndex {
    /** 全量会话，按 cwd 分组；置顶在前，归档的默认不含。 */
    fun observeGroups(includeArchived: Boolean = false): Flow<List<SessionGroup>>

    /** 名字 + 用户消息全文搜索；空查询返回空表。 */
    suspend fun search(query: String, limit: Int = 50): List<SessionHit>

    /** 增量扫描（文件只增长时从上次偏移续读）。 */
    suspend fun rescan(): Outcome<Unit>

    suspend fun meta(guestPath: String): SessionMeta
    suspend fun setPinned(guestPath: String, pinned: Boolean)
    suspend fun setArchived(guestPath: String, archived: Boolean)
    suspend fun setUnread(guestPath: String, unread: Boolean)
    suspend fun markNameLocked(guestPath: String)

    /** 为会话生成标题（不写入）；W4 用 `pi --print --no-session` 实现。 */
    suspend fun suggestTitle(guestPath: String): Outcome<String>
}

/** 按会话保存草稿（S7）。新会话用 `new:<cwd>` 作为 key。 */
interface DraftStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, draft: String)
    suspend fun remove(key: String)

    companion object {
        fun newSessionKey(cwd: String) = "new:$cwd"
    }
}
