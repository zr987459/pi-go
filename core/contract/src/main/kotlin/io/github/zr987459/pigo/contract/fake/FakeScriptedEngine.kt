package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条 RPC 命令记录（只记类型与关键参数，供断言）。 */
data class RpcCall(val type: String, val args: Map<String, String> = emptyMap())

/**
 * 可编程流式回复：[chunks] 每隔 [intervalMs] 追加一段；[stopReason] 为结束原因。
 * 例：`StreamScript.counting(200, 50)` = 每 50ms 一段，共 200 段（SessionHostFormSwitchTest 用）。
 */
data class StreamScript(
    val chunks: List<String>,
    val intervalMs: Long = 50,
    val stopReason: String = "stop",
) {
    val fullText: String get() = chunks.joinToString("")

    companion object {
        fun counting(count: Int, intervalMs: Long = 50): StreamScript =
            StreamScript((1..count).map { "$it\n" }, intervalMs)
    }
}

/**
 * 给 `FakeSessionHost` 用的引擎替身（DESIGN-r3 §7.2）：
 * - **命令日志** [commands]：每个会发 RPC 的调用都记一条（类型名与 pi RPC 一致：prompt / steer / follow_up / abort / …）；
 * - **start / restart 计数** [startCount]、[restartCount]；
 * - **可编程流式**：[program] 设定下一次 `send()` 的回复，或 [startStream] 直接开始一段流（不记命令）；
 *   结束时 [agentSettledCount] 加一、`chat.running` 置假；
 * - 扩展对话框：[pushUiRequest] 发出请求，`answerUi` 记为 `extension_ui_response`。
 *
 * 不做真实协议归约；完整事件回放用现有的 [FakeEngineClient]。
 */
class FakeScriptedEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineClient {

    private val model = ModelInfo(provider = "fake", id = "fake-model", name = "Fake 模型", reasoning = true)
    private val models = listOf(model, ModelInfo(provider = "fake", id = "fake-mini", name = "Fake 小模型"))

    private val _commands = MutableStateFlow<List<RpcCall>>(emptyList())
    val commands: StateFlow<List<RpcCall>> = _commands.asStateFlow()

    var startCount: Int = 0
        private set
    var restartCount: Int = 0
        private set
    var agentSettledCount: Int = 0
        private set

    private val _chat = MutableStateFlow(ChatState(model = model, thinkingLevel = "medium", contextPercent = 0.0, sessionFile = "/fake/session-1.jsonl"))
    override val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _uiRequests = MutableSharedFlow<ExtUiRequest>(replay = 8, extraBufferCapacity = 8)
    override val uiRequests: SharedFlow<ExtUiRequest> = _uiRequests.asSharedFlow()

    private var nextScript: StreamScript? = null
    private var streamJob: Job? = null
    private var seq = 0
    private var sessionSeq = 1

    /** 启动引擎（只计数，不记命令）。 */
    fun start() {
        startCount++
        _status.value = EngineStatus.Idle(state())
    }

    /** 设定下一次空闲 `send()` 的流式回复；不设时回一段短文本。 */
    fun program(script: StreamScript) { nextScript = script }

    /** 记一条任意 RPC（供 Fake 动作表记录 abort_retry、compact 等）。 */
    fun record(type: String, args: Map<String, String> = emptyMap()) {
        _commands.update { it + RpcCall(type, args) }
    }

    fun clearCommands() { _commands.value = emptyList() }

    fun pushUiRequest(request: ExtUiRequest) { _uiRequests.tryEmit(request) }

    /** 直接开始一段流式回复（不记命令），返回流任务。 */
    fun startStream(script: StreamScript, userText: String? = null): Job {
        val userKey = "u${++seq}"
        val asstKey = "a${++seq}"
        _chat.update { s ->
            val items = s.items.toMutableList()
            if (userText != null) items += ChatItem.User(userKey, userText)
            items += ChatItem.Assistant(asstKey, blocks = emptyList(), streaming = true)
            s.copy(items = items, running = true)
        }
        _status.value = EngineStatus.Running(state(streaming = true))
        val job = scope.launch {
            val sb = StringBuilder()
            for (chunk in script.chunks) {
                delay(script.intervalMs)
                sb.append(chunk)
                val text = sb.toString()
                _chat.update { s -> s.copy(items = s.items.map { if (it.key == asstKey) (it as ChatItem.Assistant).copy(blocks = listOf(ContentBlock.Text(0, text))) else it }) }
            }
            _chat.update { s ->
                s.copy(
                    items = s.items.map { if (it.key == asstKey) (it as ChatItem.Assistant).copy(streaming = false, stopReason = script.stopReason) else it },
                    running = false,
                )
            }
            agentSettledCount++
            _status.value = EngineStatus.Idle(state())
        }
        streamJob = job
        return job
    }

    private fun state(streaming: Boolean = false) = PiState(
        sessionFile = _chat.value.sessionFile,
        sessionId = "fake-session-$sessionSeq",
        sessionName = _chat.value.sessionName,
        model = _chat.value.model,
        thinkingLevel = _chat.value.thinkingLevel,
        isStreaming = streaming,
    )

    override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
        if (_chat.value.running) {
            val type = if (mode == SendMode.STEER) "steer" else "follow_up"
            record(type, mapOf("message" to text))
            _chat.update { s ->
                val q = s.queue
                s.copy(queue = if (mode == SendMode.STEER) q.copy(steering = q.steering + text) else q.copy(followUp = q.followUp + text))
            }
            return Outcome.Ok(Disposition.QUEUED)
        }
        record("prompt", mapOf("message" to text))
        val script = nextScript ?: StreamScript(listOf("好的。"), intervalMs = 10)
        nextScript = null
        startStream(script, userText = text)
        return Outcome.Ok(Disposition.STARTED)
    }

    override suspend fun stop(): Outcome<QueueSnapshot> {
        val q = _chat.value.queue
        record("clear_queue")
        record("abort")
        streamJob?.cancel()
        streamJob = null
        _chat.update { s ->
            s.copy(
                running = false,
                queue = QueueSnapshot.EMPTY,
                items = s.items.map { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false, stopReason = "aborted") else it },
            )
        }
        _status.value = EngineStatus.Idle(state())
        return Outcome.Ok(q)
    }

    override suspend fun newSession(cwd: String?): Outcome<Unit> {
        record("new_session", cwd?.let { mapOf("cwd" to it) } ?: emptyMap())
        sessionSeq++
        _chat.update { ChatState(model = it.model, thinkingLevel = it.thinkingLevel, contextPercent = 0.0, sessionFile = "/fake/session-$sessionSeq.jsonl") }
        return Outcome.Ok(Unit)
    }

    override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> {
        record("switch_session", mapOf("sessionPath" to guestPath, "cwd" to cwd))
        _chat.update { ChatState(model = it.model, thinkingLevel = it.thinkingLevel, sessionFile = guestPath) }
        return Outcome.Ok(Unit)
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        record("set_session_name", mapOf("name" to name))
        _chat.update { it.copy(sessionName = name) }
        return Outcome.Ok(Unit)
    }

    override suspend fun models(): Outcome<List<ModelInfo>> {
        record("get_available_models")
        return Outcome.Ok(models)
    }

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> {
        record("set_model", mapOf("provider" to provider, "modelId" to modelId))
        val m = models.firstOrNull { it.provider == provider && it.id == modelId } ?: ModelInfo(provider, modelId)
        _chat.update { it.copy(model = m) }
        return Outcome.Ok(m)
    }

    override suspend fun thinkingLevels(): Outcome<List<String>> {
        record("get_available_thinking_levels")
        return Outcome.Ok(listOf("off", "minimal", "low", "medium", "high", "xhigh"))
    }

    override suspend fun setThinking(level: String): Outcome<Unit> {
        record("set_thinking_level", mapOf("level" to level))
        _chat.update { it.copy(thinkingLevel = level) }
        return Outcome.Ok(Unit)
    }

    override suspend fun stats(): Outcome<SessionStats> {
        record("get_session_stats")
        return Outcome.Ok(SessionStats(contextPercent = _chat.value.contextPercent, totalTokens = 0, cost = 0.0))
    }

    override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
        val args = buildMap {
            put("id", id)
            value?.let { put("value", it) }
            confirmed?.let { put("confirmed", it.toString()) }
            if (cancelled) put("cancelled", "true")
        }
        record("extension_ui_response", args)
    }

    override suspend fun restart(reason: String): Outcome<Unit> {
        restartCount++
        record("restart", mapOf("reason" to reason))
        streamJob?.cancel()
        _chat.update { it.copy(running = false) }
        _status.value = EngineStatus.Idle(state())
        return Outcome.Ok(Unit)
    }

    @Suppress("unused")
    private fun now() = clock()
}
