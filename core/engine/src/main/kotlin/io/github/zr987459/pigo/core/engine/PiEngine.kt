package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.ExtUiRequestRecord
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import io.github.zr987459.pigo.contract.rpc.RpcRecord
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import io.github.zr987459.pigo.core.protocol.ChatReducer
import io.github.zr987459.pigo.core.protocol.IdGenerator
import io.github.zr987459.pigo.core.protocol.LineRing
import io.github.zr987459.pigo.core.protocol.ProviderErrors
import io.github.zr987459.pigo.core.protocol.Redactor
import io.github.zr987459.pigo.core.protocol.Responses
import io.github.zr987459.pigo.core.protocol.RpcChannel
import io.github.zr987459.pigo.core.protocol.StderrClassifier
import io.github.zr987459.pigo.core.protocol.long
import io.github.zr987459.pigo.core.protocol.str
import io.github.zr987459.pigo.core.protocol.strings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真实引擎：管理一个常驻 `pi --mode rpc` 进程，实现 [EngineClient]（DESIGN.md §3）。
 *
 * 线程模型
 * - 生命周期（启动/停止/重启/恢复）串行化在 [lifecycle] 锁里；普通命令不拿这把锁，可以并发，按 id 关联响应。
 * - 所有对 [ChatState] 的修改（pi 事件 + 客户端侧操作）都经 [inputs] 进入单个归约协程，按到达顺序串行；
 *   每批归约后最多每 [EngineConfig.frameMs] 发布一次 StateFlow。
 * - 读 stdout/stderr 的是 [RpcChannel] 的专用线程，只投递，从不被界面阻塞。
 *
 * 不变量
 * - `running` 只由 `agent_start` / `agent_settled`（或进程消失）改变；
 * - 唤醒锁只在「本轮运行中且进程活着」时持有（N1，见 [WakeLockGuard]）；
 * - 停止 = `clear_queue` → `abort`；`abort` 20 s 不应答 → 只结束本进程树 → 以 `--session` 重启并重建（Q5）；
 * - 异常退出 → 退避 1/3/10 s 自动重启最多 3 次，回到上次的会话文件（N2）。
 */
class PiEngine(
    private val runtime: RuntimeManager,
    private val launchEnv: LaunchEnvProvider,
    private val log: AppLog,
    private val scope: CoroutineScope,
    val config: EngineConfig = EngineConfig(),
    wakeLock: WakeLockPort = InMemoryWakeLock(),
    private val runMarker: RunMarkerStore = InMemoryRunMarker(),
    private val foreground: ForegroundPort = NoForeground,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : EngineClient {

    private class Conn(
        val gen: Int,
        val process: GuestProcess,
        val channel: RpcChannel,
        val cwd: String,
        val bootedAt: Long,
    ) {
        /** 我们主动结束的进程：退出时不触发自动恢复。 */
        @Volatile var intentional = false
        @Volatile var ready = false
        @Volatile var exited = false
    }

    private sealed interface Input {
        class Rec(val gen: Int, val record: RpcRecord) : Input
        class Mut(val f: (ChatState) -> ChatState, val done: CompletableDeferred<Unit>?) : Input
    }

    private val lifecycle = Mutex()
    @Volatile private var conn: Conn? = null
    private val gens = AtomicInteger(0)
    private val ids = IdGenerator()
    private val localSeq = AtomicLong(0)
    private val reducer = ChatReducer()
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val guard = WakeLockGuard(wakeLock)
    @Volatile private var knownSecrets: List<String> = emptyList()
    private val redactor = Redactor { knownSecrets }

    @Volatile private var liveRunning = false
    @Volatile private var lastPiState = PiState()
    @Volatile private var sessionFile: String? = null
    @Volatile private var currentCwd: String = config.defaultCwd
    @Volatile private var closed = false
    @Volatile private var crashCount = 0
    @Volatile private var recoveryJob: Job? = null
    /** 冷启动后第一次重建会话时检查「上一轮被系统中断」。 */
    @Volatile private var coldStartCheck = true

    /** 最近一次连接的 stderr 尾部（诊断页用，已脱敏）。 */
    @Volatile var lastStderr: LineRing = LineRing(500)
        private set

    /** 收到的不带 id 的 parse 错误等协议异常次数（诊断页可见）。 */
    val protocolErrors = AtomicInteger(0)

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _chat = MutableStateFlow(ChatState())
    override val chat: StateFlow<ChatState> = _chat.asStateFlow()

    private val _uiRequests = MutableSharedFlow<ExtUiRequest>(extraBufferCapacity = 32)
    override val uiRequests: SharedFlow<ExtUiRequest> = _uiRequests.asSharedFlow()

    init {
        scope.launch(compute) { actorLoop() }
    }

    // =================================================================== 归约协程

    private suspend fun actorLoop() {
        var state = _chat.value
        val waiters = ArrayList<CompletableDeferred<Unit>>()
        for (first in inputs) {
            state = applyInput(state, first, waiters)
            while (true) {
                val next = inputs.tryReceive().getOrNull() ?: break
                state = applyInput(state, next, waiters)
            }
            _chat.value = state
            afterPublish(state)
            waiters.forEach { it.complete(Unit) }
            waiters.clear()
            if (config.frameMs > 0) delay(config.frameMs)
        }
    }

    private fun applyInput(state: ChatState, input: Input, waiters: MutableList<CompletableDeferred<Unit>>): ChatState {
        val next = when (input) {
            is Input.Mut -> {
                input.done?.let { waiters += it }
                try {
                    input.f(state)
                } catch (e: Exception) {
                    log.w(TAG, "归约操作异常", e)
                    state
                }
            }
            is Input.Rec -> {
                if (input.gen != conn?.gen) return state // 旧进程的残留事件
                val event = input.record as? EngineEvent ?: return state
                try {
                    reducer.reduce(state, event, clock())
                } catch (e: Exception) {
                    // 归约器不应抛异常；万一抛了，丢掉这一条，不影响后续（REVIEW：未知事件不得崩溃）。
                    log.w(TAG, "归约事件异常：${event.raw["type"]}", e)
                    state
                }
            }
        }
        if (!state.running && next.running) onRunStarted()
        if (state.running && !next.running) onRunSettled()
        liveRunning = next.running
        return next
    }

    private fun onRunStarted() {
        runMarker.set(sessionFile)
    }

    private fun onRunSettled() {
        runMarker.clear()
        // 本轮结束后刷新标题、会话文件与上下文占用（顶栏 S4），不阻塞归约。
        scope.launch(io) {
            refreshState()
            stats()
        }
    }

    private fun afterPublish(state: ChatState) {
        val c = conn
        val alive = c != null && !c.exited && c.ready
        guard.sync(state.running, alive)
        if (alive) {
            when (_status.value) {
                is EngineStatus.Idle, is EngineStatus.Running -> _status.value =
                    if (state.running) EngineStatus.Running(lastPiState.copy(isStreaming = true)) else EngineStatus.Idle(lastPiState.copy(isStreaming = false))
                else -> Unit
            }
        }
    }

    /** 投递一个归约操作并等它发布。 */
    private suspend fun mutate(f: (ChatState) -> ChatState) {
        val done = CompletableDeferred<Unit>()
        if (inputs.trySend(Input.Mut(f, done)).isFailure) return
        done.await()
    }

    // =================================================================== 生命周期

    /** 启动引擎（若未启动）。前台服务、App 启动时调用；各命令也会按需调用。 */
    suspend fun start(): Outcome<Unit> = ensureStarted()

    private suspend fun ensureStarted(): Outcome<Unit> {
        if (closed) return Outcome.Err(ErrCode.ENGINE_EXIT, "引擎已关闭")
        if (conn?.ready == true) return Outcome.Ok(Unit)
        return lifecycle.withLock {
            if (conn?.ready == true) Outcome.Ok(Unit)
            else boot(sessionFile, currentCwd, rebuild = sessionFile != null, reason = "按需启动")
        }
    }

    /** 只在 [lifecycle] 锁内调用。 */
    private suspend fun boot(
        session: String?,
        cwd: String,
        rebuild: Boolean,
        reason: String,
        trailingNotice: Pair<NoticeKind, String>? = null,
    ): Outcome<Unit> {
        conn?.let { old -> stopConn(old, graceMs = config.stopGraceMs) }
        val gen = gens.incrementAndGet()
        _status.value = EngineStatus.Starting
        val keyEnv = try {
            launchEnv.env()
        } catch (e: Exception) {
            log.w(TAG, "读取启动环境失败", e)
            emptyMap()
        }
        knownSecrets = keyEnv.filterKeys { it.endsWith("_API_KEY") || it.startsWith("PIGO_KEY_") || it.endsWith("_TOKEN") }
            .values.filter { it.length >= 6 }
        val spec = config.spec(session, cwd, keyEnv)
        // 只记 argv 与 cwd，环境变量里有 Key，不写日志。
        log.i(TAG, "启动 pi（$reason）：argv=${spec.argv.joinToString(" ")} cwd=${spec.cwd}")
        val process = try {
            withContext(io) { runtime.start(spec) }
        } catch (e: Exception) {
            log.w(TAG, "启动 pi 进程失败", e)
            val msg = e.message?.let(redactor::redact)
            val d = StderrClassifier.classify(listOfNotNull(msg), null, beforeReady = true)
            _status.value = EngineStatus.Crashed(null, d.kind, d.hint, listOfNotNull(msg), null)
            return Outcome.Err(d.kind, d.hint, e)
        }
        val ring = LineRing(500)
        lastStderr = ring
        val channel = RpcChannel(
            stdout = process.stdout,
            stdin = process.stdin,
            stderr = process.stderr,
            io = io,
            stderrTail = ring,
            redactor = redactor,
            log = { level, msg -> if (level == RpcChannel.Level.WARN) log.w(TAG, msg) else log.i(TAG, msg) },
            onProtocolError = { protocolErrors.incrementAndGet() },
            name = "pi-$gen",
        )
        val c = Conn(gen, process, channel, cwd, clock())
        conn = c
        foreground.onEngineActive(true)
        channel.start()
        scope.launch(compute) { pump(c) }
        scope.launch(io) { watch(c) }

        val r = channel.request(RpcCommand.GetState("boot-$gen"), config.bootTimeoutMs)
        if (r is Outcome.Err) {
            c.intentional = true
            if (r.code == ErrCode.RPC_TIMEOUT) withContext(io + NonCancellable) { safeStop(process, 0) }
            val exit = withTimeoutOrNull(3_000) { process.awaitExit() }
            delay(50) // 让 stderr 线程把最后几行排空
            val tail = ring.tail(50)
            val d = StderrClassifier.classify(tail, exit, beforeReady = true)
            log.w(TAG, "pi 启动失败（${r.message}）：${d.hint}")
            if (conn === c) conn = null
            foreground.onEngineActive(false)
            _status.value = EngineStatus.Crashed(exit, d.kind, d.hint, tail, null)
            mutate { it } // 让 afterPublish 同步唤醒锁
            return Outcome.Err(d.kind, d.hint)
        }
        val st = Responses.state((r as Outcome.Ok).value.data)
        lastPiState = st
        sessionFile = st.sessionFile ?: session
        currentCwd = cwd
        c.ready = true
        _status.value = if (st.isStreaming) EngineStatus.Running(st) else EngineStatus.Idle(st)
        mutate { reducer.applyPiState(it, st) }
        if (rebuild) rebuildFromMessages(c)
        if (trailingNotice != null) mutate { reducer.addNotice(it, trailingNotice.first, trailingNotice.second) }
        log.i(TAG, "pi 就绪：session=${st.sessionFile} model=${st.model?.provider}/${st.model?.id}")
        return Outcome.Ok(Unit)
    }

    private suspend fun rebuildFromMessages(c: Conn) {
        val r = c.channel.request(RpcCommand.GetMessages(ids.next()), config.getMessagesTimeoutMs)
        if (r is Outcome.Ok) {
            val messages = Responses.messages(r.value.data)
            mutate { reducer.fromMessages(messages, base = it) }
        } else {
            log.w(TAG, "get_messages 失败：${(r as Outcome.Err).message}")
        }
        if (coldStartCheck) {
            coldStartCheck = false
            val marker = runMarker.get()
            val sameSession = marker != null && (marker.isEmpty() || marker == sessionFile)
            val lastIsUnanswered = _chat.value.items.lastOrNull { it !is ChatItem.Notice } is ChatItem.User
            if (sameSession || lastIsUnanswered) {
                mutate { reducer.addNotice(it, NoticeKind.INTERRUPTED, "上一轮被系统中断") }
            }
            runMarker.clear()
        }
    }

    /** stdout 记录 → 归约协程；扩展 UI 请求直接分发。 */
    private suspend fun pump(c: Conn) {
        for (record in c.channel.events) {
            when (record) {
                is ExtUiRequestRecord -> onUiRequest(c, record)
                is EngineEvent.Unknown -> {
                    log.i(TAG, "未知事件：${record.type}")
                    inputs.trySend(Input.Rec(c.gen, record))
                }
                is EngineEvent.ExtensionError -> {
                    log.w(TAG, "扩展出错：${record.extensionPath} ${record.event}: ${record.error?.let(redactor::redact)}")
                    inputs.trySend(Input.Rec(c.gen, record))
                }
                else -> inputs.trySend(Input.Rec(c.gen, record))
            }
        }
    }

    private suspend fun onUiRequest(c: Conn, r: ExtUiRequestRecord) {
        val o = r.raw
        val req = ExtUiRequest(
            id = r.id,
            method = r.method,
            title = o.str("title"),
            message = o.str("message") ?: o.str("text") ?: o.str("statusText"),
            options = o.strings("options"),
            placeholder = o.str("placeholder"),
            prefill = o.str("prefill"),
            timeoutMs = o.long("timeout"),
            raw = o,
        )
        if (r.method == "setStatus") {
            val text = o.str("statusText")
            mutate { it.copy(statusLine = text) }
        }
        // 对话类请求会阻塞 pi：没有界面在听（或缓冲已满）时立即回 cancelled，保证 pi 不会永久挂起（§3.6）。
        val delivered = _uiRequests.subscriptionCount.value > 0 && _uiRequests.tryEmit(req)
        if (!delivered && req.needsResponse) {
            log.w(TAG, "扩展对话框 ${r.method} 无人处理，自动取消")
            c.channel.send(RpcCommand.ExtensionUiResponse(r.id, cancelled = true))
        }
    }

    private suspend fun watch(c: Conn) {
        val code = try {
            c.process.awaitExit()
        } catch (e: Exception) {
            null
        }
        c.exited = true
        log.i(TAG, "pi 进程退出：code=$code intentional=${c.intentional}")
        if (c.intentional || closed) return
        onCrash(c, code)
    }

    private fun onCrash(c: Conn, code: Int?) {
        scope.launch(compute) {
            // 只有「当前连接」的意外退出才触发恢复；启动失败、被我们替换掉的旧连接都在这里被过滤掉。
            val mine = lifecycle.withLock {
                if (conn !== c) {
                    false
                } else {
                    conn = null
                    true
                }
            }
            if (!mine) return@launch
            recoveryJob?.cancel()
            recoveryJob = coroutineContext[Job]
            foreground.onEngineActive(false)
            runMarker.clear()
            delay(100) // 等 stderr 线程把最后几行排空
            var lastCode = code
            var tail = c.channel.stderrTail.tail(50)
            val diag = StderrClassifier.classify(tail, code, beforeReady = false)
            var kind = diag.kind
            var hint = diag.hint
            log.w(TAG, "pi 异常退出：$hint")
            mutate { reducer.interrupt(it, NoticeKind.INTERRUPTED, "引擎意外退出：$hint") }
            if (clock() - c.bootedAt >= config.stableResetMs) crashCount = 0
            while (!closed) {
                crashCount++
                if (crashCount > config.restartBackoffMs.size) {
                    // 3 次仍失败：停在诊断页（stderr 尾部、复制诊断、修复运行环境）。
                    _status.value = EngineStatus.Crashed(lastCode, kind, hint, tail, null)
                    return@launch
                }
                val wait = config.restartBackoffMs[crashCount - 1]
                _status.value = EngineStatus.Crashed(lastCode, kind, hint, tail, wait)
                delay(wait)
                val r = lifecycle.withLock {
                    if (conn?.ready == true) return@launch // 用户操作已经把它拉起来了
                    boot(
                        sessionFile, c.cwd, rebuild = true, reason = "崩溃后自动恢复（第 $crashCount 次）",
                        trailingNotice = NoticeKind.INTERRUPTED to "引擎意外退出（$hint），已自动恢复",
                    )
                }
                if (r is Outcome.Ok) return@launch
                (_status.value as? EngineStatus.Crashed)?.let {
                    lastCode = it.exitCode
                    kind = it.kind
                    hint = it.hint
                    tail = it.stderrTail
                }
            }
        }
    }

    private suspend fun stopConn(c: Conn, graceMs: Long) {
        c.intentional = true
        if (conn === c) conn = null
        withContext(io + NonCancellable) {
            c.channel.closeStdin()
            safeStop(c.process, graceMs)
        }
        foreground.onEngineActive(false)
    }

    private suspend fun safeStop(p: GuestProcess, graceMs: Long) {
        try {
            p.stop(graceMs)
        } catch (e: Exception) {
            log.w(TAG, "停止 pi 进程失败", e)
        }
    }

    /** 强杀当前进程树并以 `--session <当前文件>` 重启、重建时间线（Q5 / 命令超时探活失败）。 */
    private suspend fun forceRestart(c: Conn, reason: String, notice: Pair<NoticeKind, String>): Outcome<Unit> =
        lifecycle.withLock {
            if (conn !== c) return@withLock Outcome.Ok(Unit)
            log.w(TAG, "强制重启：$reason")
            c.intentional = true
            conn = null
            withContext(io + NonCancellable) { safeStop(c.process, 0) }
            mutate { reducer.interrupt(it, notice.first, notice.second) }
            boot(sessionFile, c.cwd, rebuild = true, reason = reason, trailingNotice = notice)
        }

    /** App 退出 / 服务销毁：结束进程、释放唤醒锁。之后本实例不可再用。 */
    suspend fun close() {
        closed = true
        recoveryJob?.cancel()
        lifecycle.withLock { conn?.let { stopConn(it, config.stopGraceMs) } }
        guard.releaseNow()
        _status.value = EngineStatus.Stopped
        inputs.close()
    }

    // =================================================================== 命令

    private suspend fun call(build: (String) -> RpcCommand): Outcome<RpcResponse> {
        val s = ensureStarted()
        if (s is Outcome.Err) return s
        val c = conn ?: return Outcome.Err(ErrCode.ENGINE_EXIT, "引擎未运行")
        val cmd = build(ids.next())
        val r = c.channel.request(cmd, config.timeoutFor(cmd.type))
        if (r is Outcome.Err && r.code == ErrCode.RPC_TIMEOUT && cmd.type != "get_state" && cmd.type != "abort") {
            // §3.9：命令超时 → 先 get_state 探活，探活也超时则强杀重启。
            scope.launch(io) {
                val probe = c.channel.request(RpcCommand.GetState(ids.next()), config.defaultTimeoutMs)
                if (probe is Outcome.Err && probe.code == ErrCode.RPC_TIMEOUT) {
                    forceRestart(c, "${cmd.type} 超时且探活失败", NoticeKind.INTERRUPTED to "引擎无响应，已重启")
                }
            }
        }
        return r
    }

    override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
        val s = ensureStarted()
        if (s is Outcome.Err) return s
        val key = "local-${localSeq.getAndIncrement()}"
        mutate { reducer.addPendingUser(it, key, text) }
        val behavior = if (mode == SendMode.STEER) RpcCommand.STREAMING_STEER else RpcCommand.STREAMING_FOLLOW_UP
        var r = call { RpcCommand.Prompt(it, text, if (liveRunning) behavior else null) }
        if (r is Outcome.Err && r.code == ErrCode.RPC_REJECTED && isBusyError(r.message)) {
            // 本地还没看到 agent_start 但 pi 已在生成：按用户选择的方式重发一次（上一条被拒绝了，不会重复）。
            r = call { RpcCommand.Prompt(it, text, behavior) }
        }
        return when (r) {
            is Outcome.Ok -> {
                val d = Responses.disposition(r.value.data)
                mutate {
                    when (d) {
                        Disposition.STARTED -> reducer.setDelivery(it, key, Delivery.SENT)
                        Disposition.QUEUED -> reducer.setDelivery(it, key, Delivery.QUEUED)
                        Disposition.HANDLED -> reducer.setDelivery(it, key, Delivery.SENT, confirmed = true)
                    }
                }
                Outcome.Ok(d)
            }
            is Outcome.Err -> {
                // 失败标红，不自动重发（防止重复）；文本由界面放回输入框（§3.7、§3.9）。
                mutate { reducer.setDelivery(it, key, Delivery.FAILED) }
                if (r.code == ErrCode.RPC_REJECTED) {
                    val hint = ProviderErrors.hint(r.message)
                    Outcome.Err(if (hint.kind == ErrCode.NO_MODEL) ErrCode.NO_MODEL else ErrCode.RPC_REJECTED, r.message)
                } else {
                    r
                }
            }
        }
    }

    private fun isBusyError(msg: String) = msg.contains("streamingBehavior") || msg.contains("already processing", ignoreCase = true)

    override suspend fun stop(): Outcome<QueueSnapshot> {
        val c = conn
        if (c == null || !c.ready) return Outcome.Ok(QueueSnapshot.EMPTY)
        // 1) 先清队列：abort 不清队列，不先清的话排队消息会在中止后继续发出。
        val q = c.channel.request(RpcCommand.ClearQueue(ids.next()), config.defaultTimeoutMs)
        val withdrawn = if (q is Outcome.Ok) Responses.queue(q.value.data) else QueueSnapshot.EMPTY
        if (q is Outcome.Err) log.w(TAG, "clear_queue 失败：${q.message}")
        // 撤回的文本不会再发出：去掉对应的「已排队」气泡，文本交给界面放回输入框或做成「已撤回」芯片。
        if (!withdrawn.isEmpty) {
            val texts = (withdrawn.steering + withdrawn.followUp).toMutableList()
            mutate { st ->
                var s = st
                st.items.filterIsInstance<ChatItem.User>().filter { it.delivery == Delivery.QUEUED }.forEach { u ->
                    if (texts.remove(u.text)) s = reducer.removeItem(s, u.key)
                }
                s.copy(queue = QueueSnapshot.EMPTY)
            }
        }
        if (!liveRunning) return Outcome.Ok(withdrawn)
        // 2) 再中止；abort 等会话空闲后才响应。
        mutate { it.copy(statusLine = STOPPING) }
        val a = c.channel.request(RpcCommand.Abort(ids.next()), config.abortTimeoutMs)
        mutate { if (it.statusLine == STOPPING) it.copy(statusLine = null) else it }
        if (a is Outcome.Err) {
            if (a.code == ErrCode.RPC_TIMEOUT) {
                // 3) Q5：20 s 未应答 → 只结束本进程树 → 以 --session 重启并重建，最后一条标「已强制停止」。
                val r = forceRestart(c, "abort 超时", NoticeKind.STOPPED to "已强制停止")
                if (r is Outcome.Err) return r
            } else if (a.code != ErrCode.ENGINE_EXIT) {
                log.w(TAG, "abort 失败：${a.message}")
            }
        }
        return Outcome.Ok(withdrawn)
    }

    /** 放弃自动重试（顶部黄条按钮）。EngineClient 接口里还没有这个方法，见 PR 说明。 */
    suspend fun abortRetry(): Outcome<Unit> = call { RpcCommand.AbortRetry(it) }.unit()

    override suspend fun newSession(cwd: String?): Outcome<Unit> {
        if (cwd != null && cwd != currentCwd) {
            // 在其他目录新建：以新 cwd 重启（cwd 决定会话目录与 AGENTS.md）。
            val r = lifecycle.withLock {
                mutate { reducer.cleared(it) }
                sessionFile = null
                boot(null, cwd, rebuild = false, reason = "在 $cwd 新建会话")
            }
            return r
        }
        val r = call { RpcCommand.NewSession(it) }
        if (r is Outcome.Err) return r
        if (Responses.cancelled((r as Outcome.Ok).value.data)) return Outcome.Err(ErrCode.CANCELLED, "新建会话被扩展取消")
        mutate { reducer.cleared(it) }
        refreshState()
        return Outcome.Ok(Unit)
    }

    override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> {
        val c = conn
        if (c == null || !c.ready || cwd != currentCwd) {
            return lifecycle.withLock {
                mutate { reducer.cleared(it) }
                boot(guestPath, cwd, rebuild = true, reason = "打开会话（cwd=$cwd）")
            }
        }
        val r = call { RpcCommand.SwitchSession(it, guestPath) }
        if (r is Outcome.Err) return r
        if (Responses.cancelled((r as Outcome.Ok).value.data)) return Outcome.Err(ErrCode.CANCELLED, "切换会话被扩展取消")
        sessionFile = guestPath
        val m = call { RpcCommand.GetMessages(it) }
        if (m is Outcome.Ok) {
            val messages = Responses.messages(m.value.data)
            mutate { reducer.fromMessages(messages, base = it.copy(sessionName = null)) }
        }
        refreshState()
        return if (m is Outcome.Err) m else Outcome.Ok(Unit)
    }

    override suspend fun rename(name: String): Outcome<Unit> {
        val r = call { RpcCommand.SetSessionName(it, name) }
        if (r is Outcome.Ok) mutate { it.copy(sessionName = name) }
        return r.unit()
    }

    override suspend fun models(): Outcome<List<ModelInfo>> = when (val r = call { RpcCommand.GetAvailableModels(it) }) {
        is Outcome.Ok -> Outcome.Ok(Responses.models(r.value.data))
        is Outcome.Err -> r
    }

    override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> =
        when (val r = call { RpcCommand.SetModel(it, provider, modelId) }) {
            is Outcome.Ok -> {
                val m = Responses.model(r.value.data) ?: ModelInfo(provider, modelId)
                lastPiState = lastPiState.copy(model = m)
                mutate { it.copy(model = m) }
                Outcome.Ok(m)
            }
            is Outcome.Err -> r
        }

    override suspend fun thinkingLevels(): Outcome<List<String>> = when (val r = call { RpcCommand.GetAvailableThinkingLevels(it) }) {
        is Outcome.Ok -> Outcome.Ok(Responses.thinkingLevels(r.value.data))
        is Outcome.Err -> r
    }

    override suspend fun setThinking(level: String): Outcome<Unit> {
        val r = call { RpcCommand.SetThinkingLevel(it, level) }
        if (r is Outcome.Ok) {
            lastPiState = lastPiState.copy(thinkingLevel = level)
            mutate { it.copy(thinkingLevel = level) }
        }
        return r.unit()
    }

    override suspend fun stats(): Outcome<SessionStats> {
        val c = conn
        if (c == null || !c.ready) return Outcome.Err(ErrCode.ENGINE_EXIT, "引擎未运行")
        return when (val r = c.channel.request(RpcCommand.GetSessionStats(ids.next()), config.defaultTimeoutMs)) {
            is Outcome.Ok -> {
                val s = Responses.stats(r.value.data)
                mutate { it.copy(contextPercent = s.contextPercent ?: it.contextPercent) }
                Outcome.Ok(s)
            }
            is Outcome.Err -> r
        }
    }

    override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
        val c = conn ?: return
        c.channel.send(RpcCommand.ExtensionUiResponse(id, value, confirmed, cancelled))
    }

    override suspend fun restart(reason: String): Outcome<Unit> = lifecycle.withLock {
        crashCount = 0
        boot(sessionFile, currentCwd, rebuild = sessionFile != null, reason = "重启：$reason")
    }

    private suspend fun refreshState() {
        val c = conn ?: return
        if (!c.ready) return
        val r = c.channel.request(RpcCommand.GetState(ids.next()), config.defaultTimeoutMs)
        if (r is Outcome.Ok) {
            val st = Responses.state(r.value.data)
            lastPiState = st
            st.sessionFile?.let { sessionFile = it }
            mutate { reducer.applyPiState(it, st) }
        }
    }

    private fun Outcome<RpcResponse>.unit(): Outcome<Unit> = when (this) {
        is Outcome.Ok -> Outcome.Ok(Unit)
        is Outcome.Err -> this
    }

    /** 测试/诊断用：当前唤醒锁是否持有。 */
    val wakeLockHeld: Boolean get() = guard.isHeld

    /** 当前会话文件（容器内路径）。 */
    val currentSessionFile: String? get() = sessionFile

    companion object {
        private const val TAG = "PiEngine"
        const val STOPPING = "正在停止…"
    }
}
