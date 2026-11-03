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
import io.github.zr987459.pigo.core.protocol.RecordCodec
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
import kotlinx.coroutines.flow.first
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
import kotlinx.serialization.json.JsonObject

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
 * - 异常退出 → 退避 1/3/10 s 自动重启最多 3 次，回到上次的会话文件（N2）；
 * - 前台服务跟随「希望引擎在运行」而不是每次 boot：首次启动时打开，[close]、非恢复中的启动失败、
 *   自动恢复放弃时才关闭；崩溃恢复期间不反复启停（见 [ForegroundGate]）；
 * - 冷启动检查「上一轮被系统中断」只在本实例第一次成功启动时做一次（强制重启、崩溃恢复不做）。
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
    /** 本实例还没成功启动过：第一次成功启动时检查「上一轮被系统中断」（§3.9），之后不再检查。 */
    @Volatile private var coldStartCheck = true

    /**
     * 崩溃 / 强杀时没送达的消息（审查 R2 Important 1）。存在字段里而不是局部变量：任何 rebuild=true 的 boot
     * （恢复循环、退避期间的按需启动、诊断页 restart、forceRestart 失败后的下一次启动）以及 reloadHistory
     * 都会把它们以「发送失败」接回时间线。重建成功后不清空——之后再次重建（再崩溃、重新加载历史）仍要接回；
     * 只在以下情况移除：历史里已出现（pi 其实已写入会话，见 fromMessages 去重）、用户重新发送了同样的文字、
     * 切换 / 新建会话、引擎关闭。get_messages 失败时原样保留。
     */
    private val carryLock = Any()
    @Volatile private var pendingCarry: List<ChatItem.User> = emptyList()

    private fun addPendingCarry(items: List<ChatItem.User>) {
        if (items.isEmpty()) return
        synchronized(carryLock) {
            val existing = pendingCarry.map { it.key }.toSet()
            pendingCarry = pendingCarry + items.filter { it.key !in existing }
        }
    }

    private fun snapshotPendingCarry(): List<ChatItem.User> = synchronized(carryLock) {
        pendingCarry
    }

    /** 重建后：本批 carry 里没再出现在时间线上的（已写入会话的历史），不再需要接回。 */
    private fun prunePendingCarry(batch: List<ChatItem.User>, timeline: List<ChatItem>) {
        if (batch.isEmpty()) return
        val shown = timeline.mapNotNullTo(HashSet()) { (it as? ChatItem.User)?.key }
        val gone = batch.mapNotNullTo(HashSet()) { it.key.takeIf { k -> k !in shown } }
        if (gone.isEmpty()) return
        synchronized(carryLock) {
            pendingCarry = pendingCarry.filterNot { it.key in gone }
        }
    }

    /** 用户重新发送了某条「发送失败」的文字（重试或重新输入）：去掉一条同文本的待接回消息，避免之后重建时重复出现。 */
    private fun dropPendingCarryText(text: String) {
        synchronized(carryLock) {
            val i = pendingCarry.indexOfFirst { it.text == text }
            if (i >= 0) pendingCarry = pendingCarry.toMutableList().also { it.removeAt(i) }
        }
    }

    private fun clearPendingCarry() {
        synchronized(carryLock) {
            pendingCarry = emptyList()
        }
    }

    /** 对话类扩展 UI 请求按到达顺序逐个投递（无人订阅时等一个短窗口，见 [deliverUi]）。 */
    private val uiQueue = Channel<Pair<Conn, ExtUiRequest>>(Channel.UNLIMITED)

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
        scope.launch(compute) { for ((c, req) in uiQueue) deliverUi(c, req) }
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
            else boot(sessionFile, currentCwd, rebuild = sessionFile != null, reason = "按需启动", allowResume = true)
        }
    }

    /**
     * 只在 [lifecycle] 锁内调用。
     * @param allowResume 本实例首次启动且调用方没指定会话时，可回到运行标记里那个被系统中断的会话
     * @param inRecovery 崩溃自动恢复中的一次尝试：失败时不关前台服务（由恢复循环决定）
     */
    private suspend fun boot(
        session: String?,
        cwd: String,
        rebuild: Boolean,
        reason: String,
        trailingNotice: Pair<NoticeKind, String>? = null,
        allowResume: Boolean = false,
        inRecovery: Boolean = false,
    ): Outcome<Unit> {
        conn?.let { old -> stopConn(old, graceMs = config.stopGraceMs) }
        // 冷启动检查（§3.9）：在启动进程、任何 runMarker.set/clear 之前读出上一次留下的运行标记。
        val cold = coldStartCheck
        val coldMarker = if (cold) readMarker() else null
        var bootSession = session
        var bootCwd = cwd
        var bootRebuild = rebuild
        var resumed = false
        if (cold && allowResume && bootSession == null) {
            resumeTarget(coldMarker)?.let { (file, headerCwd) ->
                log.i(TAG, "上一轮被系统中断，回到该会话")
                bootSession = file
                bootCwd = headerCwd ?: cwd
                bootRebuild = true
                resumed = true
            }
        }
        val r = bootInner(bootSession, bootCwd, bootRebuild, reason, trailingNotice, inRecovery, cold, coldMarker)
        // 回到被中断的会话却启动失败（会话文件损坏等）：清掉标记，下次按需启动走普通流程，不会一直卡在这个会话上。
        if (r is Outcome.Err && resumed) withContext(io) { runCatching { runMarker.clear() } }
        return r
    }

    private suspend fun bootInner(
        session: String?,
        cwd: String,
        rebuild: Boolean,
        reason: String,
        trailingNotice: Pair<NoticeKind, String>?,
        inRecovery: Boolean,
        cold: Boolean,
        coldMarker: String?,
    ): Outcome<Unit> {
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
        // 希望引擎运行：前台服务保持开启（幂等）。不在每次 boot / 进程退出时启停，避免 startForegroundService
        // 之后几十毫秒就 stopService、服务还没来得及 startForeground 的崩溃（Android 8+）。
        setForeground(true)
        val process = try {
            withContext(io) { runtime.start(spec) }
        } catch (e: Exception) {
            log.w(TAG, "启动 pi 进程失败", e)
            val msg = e.message?.let(redactor::redact)
            val d = StderrClassifier.classify(listOfNotNull(msg), null, beforeReady = true)
            _status.value = EngineStatus.Crashed(null, d.kind, d.hint, listOfNotNull(msg), null)
            if (!inRecovery) setForeground(false)
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
        channel.start()
        scope.launch(compute) { pump(c) }
        scope.launch(io) { watch(c) }

        val r = channel.request(RpcCommand.GetState("boot-$gen"), config.bootTimeoutMs)
        if (r is Outcome.Err) {
            c.intentional = true
            // 任何握手失败（超时、success:false、进程已退出）都结束这个进程：否则 conn 置空而进程还活着，
            // 下次按需启动会出现两个 pi。对已退出的进程 stop 是无害的。
            withContext(io + NonCancellable) { safeStop(process, 0) }
            val exit = withTimeoutOrNull(3_000) { process.awaitExit() }
            delay(50) // 让 stderr 线程把最后几行排空
            val tail = ring.tail(50)
            val d = StderrClassifier.classify(tail, exit, beforeReady = true)
            log.w(TAG, "pi 启动失败（${r.message}）：${d.hint}")
            if (conn === c) conn = null
            if (!inRecovery) setForeground(false)
            _status.value = EngineStatus.Crashed(exit, d.kind, d.hint, tail, null)
            mutate { it } // 让 afterPublish 同步唤醒锁
            return Outcome.Err(d.kind, d.hint)
        }
        val st = Responses.state((r as Outcome.Ok).value.data)
        lastPiState = st
        sessionFile = st.sessionFile ?: session
        currentCwd = cwd
        c.ready = true
        mutate { reducer.applyPiState(it, st) }
        if (rebuild) rebuildFromMessages(c, carry = snapshotPendingCarry(), live = st.isStreaming)
        if (cold) {
            coldStartCheck = false
            applyColdStart(coldMarker, rebuilt = rebuild)
        }
        if (trailingNotice != null) mutate { reducer.addNotice(it, trailingNotice.first, trailingNotice.second) }
        // 聊天记录恢复完、提示加上之后才发布就绪状态：观察者不会看到「空闲」而聊天还是半恢复的样子。
        // 恢复期间 afterPublish 不改 Starting；这里按聊天里的 running（含恢复期间到达的事件）定状态。
        _status.value = if (st.isStreaming || _chat.value.running) EngineStatus.Running(lastPiState.copy(isStreaming = true))
        else EngineStatus.Idle(lastPiState.copy(isStreaming = false))
        log.i(TAG, "pi 就绪：session=${st.sessionFile} model=${st.model?.provider}/${st.model?.id}")
        if (rebuild) scope.launch(io) { stats() } // 重建后上下文占用已清空，重新取
        return Outcome.Ok(Unit)
    }

    private val foregroundWanted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 前台服务只在「希望运行」状态变化时通知一次（崩溃恢复中的多次 boot 不重复调用）。 */
    private fun setForeground(on: Boolean) {
        if (foregroundWanted.getAndSet(on) != on) foreground.onEngineActive(on)
    }

    private suspend fun readMarker(): String? = withContext(io) {
        try {
            runMarker.get()
        } catch (e: Exception) {
            log.w(TAG, "读取运行标记失败", e)
            null
        }
    }

    /** 运行标记里的会话文件仍在 → (容器内路径, 会话头里的 cwd)；否则 null。 */
    private suspend fun resumeTarget(marker: String?): Pair<String, String?>? {
        if (marker.isNullOrEmpty()) return null
        return withContext(io) {
            try {
                val f = runtime.hostFile(marker)
                if (!f.isFile) return@withContext null
                val header = f.bufferedReader().use { it.readLine() }
                val cwd = header?.let { (RecordCodec.json.parseToJsonElement(it) as? JsonObject)?.str("cwd") }
                marker to cwd?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                log.w(TAG, "读取被中断会话的信息失败", e)
                null
            }
        }
    }

    /** §3.9「App 被系统杀」：标记仍在（同一会话）或最后一条是没有回复的用户消息 → 顶部提示。 */
    private suspend fun applyColdStart(marker: String?, rebuilt: Boolean) {
        val sameSession = marker != null && (marker.isEmpty() || marker == sessionFile)
        val lastIsUnanswered = rebuilt && _chat.value.items.lastOrNull { it !is ChatItem.Notice } is ChatItem.User
        if (sameSession || lastIsUnanswered) {
            mutate { reducer.addNotice(it, NoticeKind.INTERRUPTED, "上一轮被系统中断") }
        }
        if (marker != null && !liveRunning) {
            withContext(io) {
                try {
                    runMarker.clear()
                } catch (e: Exception) {
                    log.w(TAG, "清除运行标记失败", e)
                }
            }
        }
    }

    private suspend fun rebuildFromMessages(
        c: Conn,
        carry: List<ChatItem.User> = snapshotPendingCarry(),
        live: Boolean = false,
    ): Outcome<Unit> {
        val r = c.channel.request(RpcCommand.GetMessages(ids.next()), config.getMessagesTimeoutMs)
        return if (r is Outcome.Ok) {
            val messages = Responses.messages(r.value.data)
            mutate { reducer.fromMessages(messages, base = it, carry = carry, live = live) }
            prunePendingCarry(carry, _chat.value.items)
            Outcome.Ok(Unit)
        } else {
            val err = r as Outcome.Err
            log.w(TAG, "get_messages 失败：${err.message}")
            // 不悄悄留下一个空的 / 旧的聊天：明确告诉用户历史没加载出来，以及怎么重试。
            // 崩溃前没送达的消息（carry）此时仍以「发送失败」留在时间线上。
            // 反复重试失败时只留一条提示，不叠加。
            mutate { reducer.addNotice(withoutHistoryFailed(it), NoticeKind.ERROR, HISTORY_FAILED) }
            err
        }
    }

    private fun withoutHistoryFailed(s: ChatState): ChatState =
        s.copy(items = s.items.filterNot { it is ChatItem.Notice && it.text == HISTORY_FAILED })

    /**
     * 同一会话的重新加载：生成中且历史没有加载失败时什么也不做——时间线本来就是最新的，
     * 重建会替换掉正在流式输出的消息与乐观气泡。历史加载失败过才重建（fromMessages 在 live 时保留 running 与队列）。
     */
    private suspend fun reloadSame(c: Conn): Outcome<Unit> {
        if (liveRunning && _chat.value.items.none { it is ChatItem.Notice && it.text == HISTORY_FAILED }) return Outcome.Ok(Unit)
        return rebuildFromMessages(c, carry = snapshotPendingCarry(), live = liveRunning)
    }

    /**
     * 重新加载当前会话的历史（「历史加载失败」提示的重试入口）。EngineClient 接口里还没有这个方法，见 PR 说明；
     * 在接口加上之前，界面可用 `openSession(当前会话文件, 当前 cwd)` 达到同样效果（同一会话不会清掉待接回的消息）。
     */
    suspend fun reloadHistory(): Outcome<Unit> {
        val s = ensureStarted()
        if (s is Outcome.Err) return s
        val c = conn ?: return Outcome.Err(ErrCode.ENGINE_EXIT, "引擎未运行")
        return reloadSame(c)
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

    private fun onUiRequest(c: Conn, r: ExtUiRequestRecord) {
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
            inputs.trySend(Input.Mut({ it.copy(statusLine = text) }, null))
        }
        if (!req.needsResponse) {
            if (_uiRequests.subscriptionCount.value > 0) _uiRequests.tryEmit(req)
            return
        }
        // 对话类请求交给单独的协程按顺序投递：不阻塞 stdout 事件的转发。
        uiQueue.trySend(c to req)
    }

    /**
     * 对话类请求会阻塞 pi（§3.6）：有界面在听就交给它；暂时没人听（例如正在切页、Activity 重建）时
     * 等 [EngineConfig.uiRequestGraceMs] 的短窗口；仍无人处理才回 cancelled，保证 pi 不会永久挂起。
     */
    private suspend fun deliverUi(c: Conn, req: ExtUiRequest) {
        if (c.exited || conn !== c) return
        val delivered = withTimeoutOrNull(config.uiRequestGraceMs.coerceAtLeast(1)) {
            _uiRequests.subscriptionCount.first { it > 0 }
            _uiRequests.tryEmit(req)
        } ?: false
        if (!delivered && !c.exited) {
            log.w(TAG, "扩展对话框 ${req.method} 无人处理，自动取消")
            c.channel.send(RpcCommand.ExtensionUiResponse(req.id, cancelled = true))
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
            runMarker.clear()
            delay(100) // 等 stderr 线程把最后几行排空
            var lastCode = code
            var tail = c.channel.stderrTail.tail(50)
            val diag = StderrClassifier.classify(tail, code, beforeReady = false)
            var kind = diag.kind
            var hint = diag.hint
            log.w(TAG, "pi 异常退出：$hint")
            // 进程里的队列随进程一起没了：排队 / 发送中的消息标为「发送失败」，重建后接回时间线，由用户决定是否重发。
            interruptCarrying(NoticeKind.INTERRUPTED, "引擎意外退出：$hint")
            if (clock() - c.bootedAt >= config.stableResetMs) crashCount = 0
            while (!closed) {
                crashCount++
                if (crashCount > config.restartBackoffMs.size) {
                    // 3 次仍失败：停在诊断页（stderr 尾部、复制诊断、修复运行环境）。
                    _status.value = EngineStatus.Crashed(lastCode, kind, hint, tail, null)
                    setForeground(false)
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
                        inRecovery = true,
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
    }

    /** 进程被强杀或意外退出：结束流式状态，返回没送达、需要接回时间线的消息。 */
    private suspend fun interruptCarrying(kind: NoticeKind, text: String): List<ChatItem.User> {
        var carry: List<ChatItem.User> = emptyList()
        mutate { st ->
            val r = reducer.interruptWithdrawing(st, kind, text)
            carry = r.withdrawn
            r.state
        }
        addPendingCarry(carry)
        return carry
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
            interruptCarrying(notice.first, notice.second)
            boot(sessionFile, c.cwd, rebuild = true, reason = reason, trailingNotice = notice)
        }

    /** App 退出 / 服务销毁：结束进程、释放唤醒锁。之后本实例不可再用。 */
    suspend fun close() {
        closed = true
        recoveryJob?.cancel()
        lifecycle.withLock { conn?.let { stopConn(it, config.stopGraceMs) } }
        setForeground(false)
        guard.releaseNow()
        clearPendingCarry()
        uiQueue.close()
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
                dropPendingCarryText(text)
                Outcome.Ok(d)
            }
            is Outcome.Err -> {
                // 失败标红，不自动重发（防止重复）；文本由界面放回输入框（§3.7、§3.9）。
                // 应答超时 ≠ 没收到：pi 可能已经收下这条。气泡仍等 message_start(user) 确认，确认了就变回「已发送」，不会重复出现。
                val uncertain = r.code == ErrCode.RPC_TIMEOUT
                mutate { reducer.setDelivery(it, key, Delivery.FAILED, uncertain = uncertain) }
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
                clearPendingCarry()
                mutate { reducer.cleared(it) }
                sessionFile = null
                boot(null, cwd, rebuild = false, reason = "在 $cwd 新建会话")
            }
            return r
        }
        val r = call { RpcCommand.NewSession(it) }
        if (r is Outcome.Err) return r
        if (Responses.cancelled((r as Outcome.Ok).value.data)) return Outcome.Err(ErrCode.CANCELLED, "新建会话被扩展取消")
        clearPendingCarry()
        mutate { reducer.cleared(it) }
        refreshState()
        return Outcome.Ok(Unit)
    }

    override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> {
        val c = conn
        // 重新打开当前会话（「历史加载失败」提示的重试入口）：崩溃前没送达的消息（pendingCarry）要接回，不能清掉。
        val reopenSame = guestPath == sessionFile
        if (c == null || !c.ready || cwd != currentCwd) {
            return lifecycle.withLock {
                if (!reopenSame) {
                    clearPendingCarry()
                    mutate { reducer.cleared(it) }
                }
                boot(guestPath, cwd, rebuild = true, reason = "打开会话（cwd=$cwd）")
            }
        }
        if (reopenSame) {
            val r = reloadSame(c)
            refreshState()
            scope.launch(io) { stats() }
            return r
        }
        val r = call { RpcCommand.SwitchSession(it, guestPath) }
        if (r is Outcome.Err) return r
        if (Responses.cancelled((r as Outcome.Ok).value.data)) return Outcome.Err(ErrCode.CANCELLED, "切换会话被扩展取消")
        clearPendingCarry()
        sessionFile = guestPath
        val m = call { RpcCommand.GetMessages(it) }
        if (m is Outcome.Ok) {
            val messages = Responses.messages(m.value.data)
            // fromMessages 会清掉旧会话的队列与上下文占用；占用随后由 stats() 重新取。
            mutate { reducer.fromMessages(messages, base = it.copy(sessionName = null, statusLine = null, running = false), live = liveRunning) }
        } else {
            mutate { reducer.addNotice(reducer.cleared(it), NoticeKind.ERROR, HISTORY_FAILED) }
        }
        refreshState()
        scope.launch(io) { stats() }
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
        boot(sessionFile, currentCwd, rebuild = sessionFile != null, reason = "重启：$reason", allowResume = true)
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
        const val HISTORY_FAILED = "历史记录加载失败，可在会话列表里重新打开本会话重试"
    }
}
