package io.github.zr987459.pigo.contract

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

enum class Disposition { STARTED, QUEUED, HANDLED }

/** 生成中发送的两种方式，默认 FOLLOW_UP（DESIGN.md §3.7）。 */
enum class SendMode { FOLLOW_UP, STEER }

data class QueueSnapshot(val steering: List<String>, val followUp: List<String>) {
    val isEmpty: Boolean get() = steering.isEmpty() && followUp.isEmpty()

    companion object {
        val EMPTY = QueueSnapshot(emptyList(), emptyList())
    }
}

/** `get_state` 的要点；`raw` 保留完整对象，未知字段不丢。 */
data class PiState(
    val sessionFile: String? = null,
    val sessionId: String? = null,
    val sessionName: String? = null,
    val model: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val isStreaming: Boolean = false,
    val steeringMode: String? = null,
    val followUpMode: String? = null,
    val pendingMessageCount: Int = 0,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String? = null,
    val reasoning: Boolean = false,
    val contextWindow: Long? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class SessionStats(
    val contextPercent: Double?,
    val totalTokens: Long?,
    val cost: Double?,
    val raw: JsonObject = JsonObject(emptyMap()),
)

/** 扩展 UI 请求（DESIGN.md §3.6）。`raw` 为完整记录。 */
data class ExtUiRequest(
    val id: String,
    val method: String,
    val title: String? = null,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String? = null,
    val timeoutMs: Long? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** select / confirm / input / editor 会阻塞 pi，必须应答。 */
    val needsResponse: Boolean get() = method in BLOCKING_METHODS

    companion object {
        val BLOCKING_METHODS = setOf("select", "confirm", "input", "editor")
    }
}

sealed interface EngineStatus {
    data object Stopped : EngineStatus
    data object Starting : EngineStatus
    data class Idle(val state: PiState) : EngineStatus
    data class Running(val state: PiState) : EngineStatus
    data class Crashed(
        val exitCode: Int?,
        val kind: ErrCode,
        val hint: String,
        val stderrTail: List<String>,
        val retryInMs: Long?,
    ) : EngineStatus
}

interface EngineClient {
    val status: StateFlow<EngineStatus>

    /** 已归约的界面状态，Activity 重建不丢。 */
    val chat: StateFlow<ChatState>
    val uiRequests: SharedFlow<ExtUiRequest>

    /** 空闲时忽略 mode。 */
    suspend fun send(text: String, mode: SendMode = SendMode.FOLLOW_UP): Outcome<Disposition>

    /** clear_queue → abort；返回撤回的文本。 */
    suspend fun stop(): Outcome<QueueSnapshot>
    suspend fun newSession(cwd: String? = null): Outcome<Unit>

    /** 同 cwd 用 switch_session，否则重启。 */
    suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit>
    suspend fun rename(name: String): Outcome<Unit>
    suspend fun models(): Outcome<List<ModelInfo>>
    suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo>
    suspend fun thinkingLevels(): Outcome<List<String>>
    suspend fun setThinking(level: String): Outcome<Unit>
    suspend fun stats(): Outcome<SessionStats>
    suspend fun answerUi(id: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false)

    /** 重新读取 LaunchEnvProvider。 */
    suspend fun restart(reason: String): Outcome<Unit>
}
