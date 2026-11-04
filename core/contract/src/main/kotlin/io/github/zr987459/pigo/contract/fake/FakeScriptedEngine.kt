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
 * [gate]（可选）在第 i 段发出前挂起，由测试控制出段时机（如仪器测试在点击期间暂停、切换完成后才放行剩余分段）。
 */
data class StreamScript(
    val chunks: List<String>,
    val intervalMs: Long = 50,
    val stopReason: String = "stop",
    val gate: (suspend (index: Int) -> Unit)? = null,
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
 *   再次 [startStream] 会先取消正在跑的流（旧回复标 `aborted`），任何时刻只有一段流；
 * - **排队消息按 pi 语义消费**：生成中 `send()` 进队列（记 steer / follow_up）；当前回复结束后，
 *   先投递全部 steer、再投递全部 follow_up，每条作为一条用户消息开新一轮（短回复，或 [program] 设定的脚本），
 *   不再记 RPC（pi 自己投递）；队列清空后才 `chat.running` 置假、[agentSettledCount] 加一。
 *   不会出现 `running=false` 而队列非空；[stop] 清空队列；
 * - 扩展对话框：[pushUiRequest] 发出请求（`replay = 0`，与 [FakeEngineClient] 一致，已应答的不会重放给新订阅者；
 *   对话框以 `OverlayStack` 为准），`answerUi` 记为 `extension_ui_response`。
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

    private val _uiRequests = MutableSharedFlow<ExtUiRequest>(replay = 0, extraBufferCapacity = 8)
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

    /** 带图片发送（`FakeSessionHost.sendDraft` 用）：命令日志多记 `images` 个数。 */
    suspend fun sendWithImages(text: String, imageCount: Int, mode: SendMode): Outcome<Disposition> {
        if (imageCount == 0) return send(text, mode)
        return sendInternal(text, mode, mapOf("images" to imageCount.toString()))
    }

    /** 直接开始一段流式回复（不记命令），返回流任务。正在跑的流会先被取消。 */
    fun startStream(script: StreamScript, userText: String? = null): Job {
        streamJob?.let { old ->
            old.cancel()
            _chat.update { s -> s.copy(items = s.items.map { if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false, stopReason = "aborted") else it }) }
        }
        // 第一轮的消息同步加入（调用方返回时就能看到），后续轮次在流任务里加。
        val firstKey = addTurn(userText)
        _chat.update { it.copy(running = true) }
        _status.value = EngineStatus.Running(state(streaming = true))
        val job = scope.launch {
            streamOne(script, firstKey)
            // 按 pi 语义消费排队消息：先 steer，再 follow_up，一条一轮。
            while (true) {
                val next = dequeue() ?: break
                val s2 = nextScript ?: StreamScript(listOf("好的。"), intervalMs = 10)
                nextScript = null
                streamOne(s2, addTurn(next))
            }
            _chat.update { it.copy(running = false) }
            agentSettledCount++
            _status.value = EngineStatus.Idle(state())
        }
        streamJob = job
        return job
    }

    /** 加入一轮的用户消息（可无）与空的流式回复，返回回复的 key。 */
    private fun addTurn(userText: String?): String {
        val userKey = "u${++seq}"
        val asstKey = "a${++seq}"
        _chat.update { s ->
            val items = s.items.toMutableList()
            if (userText != null) items += ChatItem.User(userKey, userText)
            items += ChatItem.Assistant(asstKey, blocks = emptyList(), streaming = true)
            s.copy(items = items)
        }
        return asstKey
    }

    private suspend fun streamOne(script: StreamScript, asstKey: String) {
        val sb = StringBuilder()
        for ((i, chunk) in script.chunks.withIndex()) {
            script.gate?.invoke(i)
            delay(script.intervalMs)
            sb.append(chunk)
            val text = sb.toString()
            _chat.update { s -> s.copy(items = s.items.map { if (it.key == asstKey) (it as ChatItem.Assistant).copy(blocks = listOf(ContentBlock.Text(0, text))) else it }) }
        }
        _chat.update { s -> s.copy(items = s.items.map { if (it.key == asstKey) (it as ChatItem.Assistant).copy(streaming = false, stopReason = script.stopReason) else it }) }
    }

    /** 取出下一条排队消息（先 steer 后 follow_up）；队列空返回 null。 */
    private fun dequeue(): String? {
        var out: String? = null
        _chat.update { s ->
            val q = s.queue
            when {
                q.steering.isNotEmpty() -> { out = q.steering.first(); s.copy(queue = q.copy(steering = q.steering.drop(1))) }
                q.followUp.isNotEmpty() -> { out = q.followUp.first(); s.copy(queue = q.copy(followUp = q.followUp.drop(1))) }
                else -> s
            }
        }
        return out
    }

    private fun state(streaming: Boolean = false) = PiState(
        sessionFile = _chat.value.sessionFile,
        sessionId = "fake-session-$sessionSeq",
        sessionName = _chat.value.sessionName,
        model = _chat.value.model,
        thinkingLevel = _chat.value.thinkingLevel,
        isStreaming = streaming,
    )

    override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> = sendInternal(text, mode, emptyMap())

    private fun sendInternal(text: String, mode: SendMode, extra: Map<String, String>): Outcome<Disposition> {
        if (_chat.value.running) {
            val type = if (mode == SendMode.STEER) "steer" else "follow_up"
            record(type, mapOf("message" to text) + extra)
            _chat.update { s ->
                val q = s.queue
                s.copy(queue = if (mode == SendMode.STEER) q.copy(steering = q.steering + text) else q.copy(followUp = q.followUp + text))
            }
            return Outcome.Ok(Disposition.QUEUED)
        }
        record("prompt", mapOf("message" to text) + extra)
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
        streamJob = null
        // 进程重启后 pi 的队列随之消失；把队列文字退回草稿是 SessionHost 的事（重启前先读 chat.queue）。
        _chat.update { it.copy(running = false, queue = QueueSnapshot.EMPTY) }
        _status.value = EngineStatus.Idle(state())
        return Outcome.Ok(Unit)
    }

    @Suppress("unused")
    private fun now() = clock()
}
