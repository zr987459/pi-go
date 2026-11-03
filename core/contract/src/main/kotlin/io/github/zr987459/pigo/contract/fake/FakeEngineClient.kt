package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 不启动 pi 的引擎：把事件序列逐条归约成 [ChatState]，供界面开发和单测使用。
 *
 * - 空闲时 `send()` 回放 [FakeFixtures.demoTurn]（第一条消息）或 [FakeFixtures.simpleTurn]；
 * - 生成中 `send()` 进入队列（QUEUED），本轮结束后逐条作为下一回合发出，只发一次；
 * - `stop()` 先清队列（返回撤回文本）再中止回放。
 */
class FakeEngineClient(
    private val scope: CoroutineScope,
    private val stepDelayMs: Long = 60,
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineClient {

    private val reducer = FakeChatReducer()
    private val mutex = Mutex()
    private var replayJob: Job? = null
    /** 是否有一轮（含其排队的后续回合）尚未结束。只在 [mutex] 内读写；比 chat.running 更早置位、更晚清除。 */
    private var busy = false
    private var turns = 0
    private val pendingFollowUp = ArrayDeque<String>()
    private val pendingSteer = ArrayDeque<String>()

    private val model = ModelInfo(provider = "fake", id = "fake-model", name = "Fake 模型")
    private val idleState get() = PiState(model = model, thinkingLevel = _chat.value.thinkingLevel)

    private val _chat = MutableStateFlow(ChatState(model = model, thinkingLevel = "medium", contextPercent = 0.0))
    override val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle(PiState(model = model, thinkingLevel = "medium")))
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _uiRequests = MutableSharedFlow<ExtUiRequest>(extraBufferCapacity = 8)
    override val uiRequests: SharedFlow<ExtUiRequest> = _uiRequests.asSharedFlow()

    /** 已回答的扩展对话框，便于测试断言。 */
    val answeredUi = mutableListOf<String>()

    /** 回放任意事件序列（同步完成，不受 stepDelay 影响之外的调度）。 */
    suspend fun replay(events: List<EngineEvent>, delayMs: Long = stepDelayMs) {
        for (e in events) {
            if (delayMs > 0) delay(delayMs)
            apply(e)
        }
    }

    /** 立即归约一条事件。 */
    fun apply(event: EngineEvent) {
        _chat.update { reducer.reduce(it, event, clock()) }
        _status.value = if (_chat.value.running) EngineStatus.Running(idleState.copy(isStreaming = true)) else EngineStatus.Idle(idleState)
    }

    override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> = mutex.withLock {
        if (busy) {
            if (mode == SendMode.STEER) pendingSteer.addLast(text) else pendingFollowUp.addLast(text)
            publishQueue()
            return@withLock Outcome.Ok(Disposition.QUEUED)
        }
        busy = true
        // 乐观气泡：收到 message_start 时由归约器确认为 SENT。
        _chat.update { it.copy(items = it.items + ChatItem.User("pending-${turns}", text, Delivery.SENDING)) }
        startTurn(text)
        Outcome.Ok(Disposition.STARTED)
    }

    /** 只在 [mutex] 内、[busy] 置位后调用。 */
    private fun startTurn(text: String) {
        replayJob?.cancel()
        val events = if (turns++ == 0) FakeFixtures.demoTurn(text) else FakeFixtures.simpleTurn(text)
        replayJob = scope.launch {
            replay(events)
            drainQueue()
        }
    }

    /** 逐条发出排队消息；确认队列为空后才清除 [busy]，与 send() 之间没有空档。 */
    private suspend fun drainQueue() {
        while (true) {
            val next = mutex.withLock {
                val t = pendingSteer.removeFirstOrNull() ?: pendingFollowUp.removeFirstOrNull()
                if (t == null) {
                    busy = false
                    replayJob = null
                } else {
                    publishQueue()
                }
                t
            } ?: return
            replay(FakeFixtures.simpleTurn(next))
        }
    }

    private fun publishQueue() {
        apply(
            EngineEvent.QueueUpdate(
                steering = pendingSteer.toList(),
                followUp = pendingFollowUp.toList(),
                raw = JsonObject(emptyMap()),
            ),
        )
    }

    override suspend fun stop(): Outcome<QueueSnapshot> {
        val (withdrawn, job) = mutex.withLock {
            val snap = QueueSnapshot(pendingSteer.toList(), pendingFollowUp.toList())
            pendingSteer.clear()
            pendingFollowUp.clear()
            val j = replayJob
            replayJob = null
            busy = false
            snap to j
        }
        // 等回放协程真正结束，之后不会再有任何输出。
        job?.cancelAndJoin()
        apply(EngineEvent.QueueUpdate(emptyList(), emptyList(), JsonObject(emptyMap())))
        if (_chat.value.running) {
            _chat.update { s ->
                s.copy(
                    items = s.items.map {
                        if (it is ChatItem.Assistant && it.streaming) it.copy(streaming = false, stopReason = "aborted") else it
                    } + ChatItem.Notice("stopped-${clock()}", NoticeKind.STOPPED, "已停止"),
                )
            }
            apply(EngineEvent.AgentEnd(JsonArray(emptyList()), willRetry = false, raw = JsonObject(emptyMap())))
            apply(EngineEvent.AgentSettled(aborted = true, raw = JsonObject(emptyMap())))
        }
        return Outcome.Ok(withdrawn)
    }

    override suspend fun newSession(cwd: String?): Outcome<Unit> {
        stop()
        turns = 0
        _chat.value = ChatState(model = model, thinkingLevel = _chat.value.thinkingLevel, contextPercent = 0.0)
        return Outcome.Ok(Unit)
    }

    override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> {
        newSession(cwd)
        _chat.update { it.copy(sessionFile = guestPath) }
        return Outcome.Ok(Unit)
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        _chat.update { it.copy(sessionName = name) }
        return Outcome.Ok(Unit)
    }

    override suspend fun models(): Outcome<List<ModelInfo>> = Outcome.Ok(listOf(model))

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> {
        val m = ModelInfo(provider = provider, id = modelId)
        _chat.update { it.copy(model = m) }
        return Outcome.Ok(m)
    }

    override suspend fun thinkingLevels(): Outcome<List<String>> =
        Outcome.Ok(listOf("off", "minimal", "low", "medium", "high"))

    override suspend fun setThinking(level: String): Outcome<Unit> {
        _chat.update { it.copy(thinkingLevel = level) }
        return Outcome.Ok(Unit)
    }

    override suspend fun stats(): Outcome<SessionStats> =
        Outcome.Ok(SessionStats(contextPercent = _chat.value.contextPercent, totalTokens = 0, cost = 0.0))

    override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
        answeredUi += id
    }

    override suspend fun restart(reason: String): Outcome<Unit> {
        stop()
        return Outcome.Ok(Unit)
    }

    /** 测试/演示用：推送一个扩展对话框请求。 */
    suspend fun emitUiRequest(request: ExtUiRequest) = _uiRequests.emit(request)
}
