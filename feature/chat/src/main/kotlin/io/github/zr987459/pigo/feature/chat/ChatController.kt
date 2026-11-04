package io.github.zr987459.pigo.feature.chat

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.SlashCommand
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.core.session.DraftCodec
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ChatRows
import io.github.zr987459.pigo.feature.chat.logic.ComposerLogic
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.ExtUi
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAction
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.Outbox
import io.github.zr987459.pigo.feature.chat.logic.ErrorText
import io.github.zr987459.pigo.feature.chat.logic.PhaseLabel
import io.github.zr987459.pigo.feature.chat.logic.RetryAnchor
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import io.github.zr987459.pigo.feature.chat.logic.InputRoute
import io.github.zr987459.pigo.feature.chat.logic.InputRouting
import io.github.zr987459.pigo.feature.chat.logic.SlashCandidates
import io.github.zr987459.pigo.feature.chat.logic.SlashCompletion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 聊天界面的可调参数。 */
data class ChatConfig(
    /** 停止请求超过这个时长仍未完成时，提示「仍在停止，正在强制结束…」（§3.7：20 s 后引擎强杀重启）。 */
    val stopSlowHintMs: Long = 20_000,
    /** 停止前最多等这么久，让正在发出的消息先拿到 pi 的响应（防止它在 clear_queue 之后才入队、停止后照样发出）。 */
    val stopWaitSendMs: Long = 5_000,
    /** 打字停下这么久后才把草稿写盘（S7）。 */
    val draftSaveDelayMs: Long = 500,
    /** 分叉成功后最多等这么久让引擎报告新会话文件；超时就放弃把原文放回，免得塞进不相干的会话。 */
    val forkWaitMs: Long = 15_000,
)

/** 正在显示（或排队等待显示）的扩展对话框。 */
@Immutable
data class DialogEntry(val request: ExtUiRequest, val shownAtMillis: Long)

/** 聊天页渲染所需的全部状态。 */
@Immutable
data class ChatScreenState(
    val chat: ChatState = ChatState(),
    val rows: List<ChatRow> = emptyList(),
    val composer: ComposerState = ComposerState(),
    val primary: PrimaryAction = PrimaryAction.DISABLED,
    val engineStatus: EngineStatus = EngineStatus.Stopped,
    /** 队首对话框；同时到达的多个请求按顺序逐个显示。 */
    val dialog: DialogEntry? = null,
    val pendingDialogs: Int = 0,
    /** 扩展 `set_editor_text` 想替换用户正在写的草稿时，先询问。 */
    val replaceDraftOffer: String? = null,
    /** `setStatus` / `setWidget` 与引擎状态行，显示在输入框上方。 */
    val statusLines: List<String> = emptyList(),
    val title: String = "新会话",
    val contextPercent: Double? = null,
    /** 要显示的自动重试黄条；重试已开始出字或回合结束后为 null（见 [RetryVisibility]）。 */
    val retry: RetryBanner? = null,
    /** 发送到出第一个字之间、长时间跑工具时的阶段提示（见 [PhaseLabel]）；已并入 [statusLines] 的第一行。 */
    val phase: String? = null,
    /** 草稿以 `/` 开头、正在输入命令名时的候选（C3）；其他时候为 null。 */
    val slash: SlashCandidates? = null,
    /** 待发送的图片（C1），发送后清空。 */
    val attachments: List<ImageInput> = emptyList(),
    /** 用户的 `!` 命令正在执行（C6）。 */
    val bashRunning: Boolean = false,
)

/**
 * 聊天页的状态持有者：把引擎的 [ChatState] 与界面本地状态（草稿、乐观气泡、对话框、折叠）合成 [ChatScreenState]。
 *
 * 生命周期跟随引擎而不是页面：扩展对话框请求在聊天页不可见时也有人接收与应答（pi 会阻塞等待），
 * 草稿在旋转屏幕、切页、切后台后都保留。见 [ChatControllers]。
 */
class ChatController(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    private val config: ChatConfig = ChatConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** pi 1.5.0 的其余 RPC（图片、斜杠命令、`!` 命令、分叉）；为 null 时这些功能不可用，行为同旧版。 */
    private val extras: EngineExtras? = null,
    /** 按会话保存草稿（S7）；为 null 时草稿只在内存里。 */
    private val drafts: DraftStore? = null,
) {
    private data class Local(
        val composer: ComposerState = ComposerState(),
        val sends: List<LocalSend> = emptyList(),
        val expanded: Set<String> = emptySet(),
        val dialogs: List<DialogEntry> = emptyList(),
        val replaceDraftOffer: String? = null,
        val status: Map<String, String> = emptyMap(),
        val title: String? = null,
        val statsPercent: Double? = null,
        /** 会话代数：切换会话或引擎重启时递增，按会话缓存的本地状态（思考计时等）随之作废。 */
        val generation: Int = 0,
        /**
         * 同一进程内切会话时仍在途（SENDING）的发送：气泡已从界面清掉，但 send 结果还没回来。
         * key = 本地 id，value = 原文。结果回来若是失败，就把原文退回输入框；成功则已由引擎接管。
         */
        val orphanSends: Map<String, String> = emptyMap(),
        /** 停止进行中被引擎重启提前退回的文本；停止结果回来时从撤回文本里扣掉，避免同一条退回两次。 */
        val withdrawnDuringStop: List<String> = emptyList(),
        /**
         * 引擎重启时还在途（SENDING）、已立即退回输入框或芯片的发送：key = 本地 id，value = 原文。
         * 迟到的 send 结果若是成功（旧进程退出前已收下，或引擎在新进程就绪后补发），说明消息其实已发出：
         * 提示「该消息已发出」，并在草稿或芯片仍等于原文时撤掉退回的那份，避免用户再发一遍。
         */
        val returnedSending: Map<String, String> = emptyMap(),
        /** 待发送的图片。 */
        val attachments: List<ImageInput> = emptyList(),
        /** 斜杠命令缓存；null 表示还没读过（或会话已换、需要重读）。 */
        val commands: List<SlashCommand>? = null,
        /** [commands] 属于哪个会话（按会话文件缓存，切会话或新建后失效）。 */
        val commandsKey: String? = null,
        val commandsLoading: Boolean = false,
        val bashRunning: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /**
     * 两种形态共用的草稿（DESIGN-r3 §5.1；审查第 1 轮 I1）。文字与输入框 `composer.draft` 双向同步，
     * 另带光标、`@` 引用、`!` 模式、图片 URI、「已撤回」芯片；按会话经 [drafts] 持久化（[DraftCodec]）。
     * `SessionHostImpl.draft` 就是这一个对象（经 `ChatControllerPort`）。
     */
    val sharedDraft: MutableStateFlow<DraftState> = MutableStateFlow(DraftState.EMPTY)

    /** 合成协程最后处理过的会话文件（草稿和分叉文本按它归属）。 */
    @Volatile
    private var observedFile: String? = engine.chat.value.sessionFile

    /**
     * 分叉成功后要写进新会话输入框的文本，以及分叉前的会话文件 [PendingFork.before]。
     * 等第一次出现「非 null 且不同于 before」的会话文件时放回；引擎换会话可能先经过 null（A → null → B），
     * 也可能从还没落盘的新会话（null）分叉。超过 [ChatConfig.forkWaitMs] 仍没等到就清掉（审查第 1 轮 Important 4）。
     */
    private class PendingFork(val text: String, val before: String?)

    @Volatile
    private var pendingFork: PendingFork? = null

    private var draftJob: Job? = null
    /**
     * 带缓冲的一次性提示：聊天页不在前台（没人收集）时产生的提示先存着，回到聊天页再显示，每条只显示一次。
     * 只留最近 [MESSAGE_BUFFER] 条（满时丢最旧的），离开很久再回来不会连弹一长串。
     * 已知限制：单消费者模型，Snackbar 正显示时旋转屏幕，已取出的那一条会丢（见 PR 说明）。
     */
    private val _messages = Channel<String>(capacity = MESSAGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 一次性提示（Snackbar）：扩展 notify、命令失败、停止失败等。 */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** App 在后台时收到对话类请求，用通知提醒（由页面注入 Android 实现）。 */
    @Volatile
    var notifier: ExtUiNotifier? = null

    @Volatile
    private var foreground = false
    private var seq = 0
    private var stopJob: Job? = null

    /** 引擎重启前最后一次看到的队列（以 `queue_update` 为准）。排队消息的本地气泡入队后即撤掉、改由芯片显示，
     *  所以重启时要从这里取回仍在旧进程队列里的文本。 */
    @Volatile
    private var lastQueue: QueueSnapshot = QueueSnapshot.EMPTY

    /** 已调用 engine.send() 但还没拿到响应的发送；停止前要等它们返回。 */
    private val inflightSends: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** 思考块计时：key = "assistantKey#index" → (开始, 结束)。只在合成协程里读写。 */
    private val thinkingStart = HashMap<String, Long?>()
    private val thinkingEnd = HashMap<String, Long>()

    /** 自动重试黄条锚点（每次 attempt 变化重新记录）；只在合成协程里读写。
     * 必须在 [state] 之前初始化：[state] 的初始值在构造时就调用 compose()。 */
    private val retryAnchor = RetryAnchor()

    val state: StateFlow<ChatScreenState> =
        combine(engine.chat, engine.status, local) { chat, status, l -> compose(chat, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, compose(engine.chat.value, engine.status.value, local.value))

    init {
        scope.launch { engine.uiRequests.collect { onUiRequest(it) } }
        // composer.draft → sharedDraft（输入框、队列退回、分叉文本、扩展 set_editor_text 改的都是 composer）。
        scope.launch {
            local.collect { l ->
                val t = l.composer.draft
                if (sharedDraft.value.text != t) sharedDraft.update { if (it.text == t) it else it.withText(t) }
            }
        }
        // sharedDraft → composer.draft（两种形态改的是 sharedDraft），并按会话持久化。
        scope.launch {
            var last = sharedDraft.value
            sharedDraft.collect { d ->
                if (local.value.composer.draft != d.text) {
                    local.update { it.copy(composer = it.composer.copy(draft = d.text)) }
                    if (SlashCompletion.queryOf(d.text) != null) ensureCommands()
                }
                if (d != last) persistDraftSoon()
                last = d
            }
        }
        scope.launch {
            var wasRunning = engine.chat.value.running
            var lastFile = engine.chat.value.sessionFile
            swapInDraft(lastFile, firstLearn = true)
            engine.chat.collect { chat ->
                // 切到了另一个会话（null → 文件只是首次得知会话文件，不算切换）。
                if (lastFile != null && chat.sessionFile != lastFile) {
                    swapOutDraft(lastFile)
                    observedFile = chat.sessionFile
                    resetSessionLocal(sameProcess = true)
                    swapInDraft(chat.sessionFile, firstLearn = false)
                } else if (lastFile == null && chat.sessionFile != null) {
                    val file: String = chat.sessionFile ?: return@collect
                    observedFile = file
                    migrateNewSessionDraft(file)
                    swapInDraft(file, firstLearn = true)
                }
                lastFile = chat.sessionFile
                observedFile = chat.sessionFile
                maybeApplyFork(chat.sessionFile)
                // 引擎重启中：旧进程的队列快照要留给 resetSessionLocal 退回；也不拿重启中的空状态去对账，
                // 否则「已排队」的本地气泡会被误判为已送达而被清掉。
                if (isRestarting(engine.status.value)) return@collect
                lastQueue = chat.queue
                local.update { l ->
                    val sends = Outbox.reconcile(l.sends, chat)
                    if (sends === l.sends) l else l.copy(sends = sends)
                }
                if (wasRunning && !chat.running) refreshStats()
                wasRunning = chat.running
            }
        }
        scope.launch {
            var prev = engine.status.value
            engine.status.collect { st ->
                if (isRestarting(st) && !isRestarting(prev)) resetSessionLocal(sameProcess = false)
                prev = st
            }
        }
        scope.launch { refreshStats() }
    }

    private fun isRestarting(st: EngineStatus) = st is EngineStatus.Starting || st is EngineStatus.Crashed

    /**
     * 切换会话或引擎重启（进入 Starting / Crashed）时，清掉只属于原会话或原进程的本地状态，
     * 避免新会话显示旧标题、旧状态行、旧对话框和旧气泡：
     * - 扩展写的标题、状态行、替换草稿询问、折叠展开记录、上下文统计、思考计时；
     * - 扩展对话框（同时取消对应通知）。同一进程内切会话时，对阻塞类对话框补发 cancelled，pi 不会一直等；
     *   引擎重启后旧 id 新进程不认识，不再应答；
     * - 乐观气泡全部清掉，但**文本一律不丢**（§5.5 Q4），按撤回规则退回（输入框为空时放回，否则进「已撤回」芯片，
     *   不覆盖草稿）：
     *   - 发送失败（FAILED）：退回；
     *   - 已排队：本地 QUEUED 气泡与已入 pi 队列、改由芯片显示的 [lastQueue] 文本都退回（同文本只退一次）。
     *     引擎重启时队列随旧进程消失；同一进程内切换或新建会话时，pi 先 abort 再 dispose 旧会话，
     *     旧的 steering / followUp 队列同样直接丢弃、不会发出，而契约里 newSession / openSession 没有交还撤回文本的通道，
     *     所以两种情况都由这里从 [lastQueue]（此时仍是旧会话的快照）救回，并把它置空；
     *   - 发送中（SENDING）：引擎重启时立即退回，并记进 [Local.returnedSending]：迟到的失败结果不再退第二次，
     *     迟到的成功结果提示「该消息已发出」并撤掉退回的那份；
     *     同一进程内切会话时 pi 仍可能收下它，先记进 [Local.orphanSends]，等 send 结果：失败才退回，成功则已由引擎接管；
     *   - 已发出（SENT）：pi 已收下并开始处理，历史里有，不退回。
     * 草稿本身保留。
     */
    private fun resetSessionLocal(sameProcess: Boolean) {
        var dropped: List<DialogEntry> = emptyList()
        // 调用时 lastQueue 仍是旧会话 / 旧进程最后一次 queue_update 的快照（collect 里在此之后才更新）。
        val engineQueue = lastQueue
        lastQueue = QueueSnapshot.EMPTY
        local.update { s ->
            dropped = s.dialogs
            val texts = rescuedTexts(s.sends, engineQueue, includeSending = !sameProcess)
            val orphans = if (sameProcess) {
                s.orphanSends + s.sends.filter { it.status == Delivery.SENDING }.associate { it.id to it.text }
            } else {
                // 旧进程已不在：之前同进程切会话留下的在途发送也不会再有成功结果，一并退回。
                emptyMap()
            }
            val all = if (sameProcess) texts else texts + s.orphanSends.values
            val returned = if (sameProcess) {
                s.returnedSending
            } else {
                s.returnedSending + s.orphanSends + s.sends.filter { it.status == Delivery.SENDING }.associate { it.id to it.text }
            }
            s.copy(
                composer = ComposerLogic.onWithdrawn(s.composer, all),
                sends = emptyList(),
                orphanSends = orphans,
                returnedSending = returned,
                withdrawnDuringStop = if (s.composer.stopping) s.withdrawnDuringStop + all else s.withdrawnDuringStop,
                expanded = emptySet(),
                dialogs = emptyList(),
                replaceDraftOffer = null,
                status = emptyMap(),
                title = null,
                statsPercent = null,
                generation = s.generation + 1,
            )
        }
        for (d in dropped) {
            notifier?.cancel(d.request.id)
            if (sameProcess && d.request.needsResponse) {
                scope.launch { engine.answerUi(d.request.id, cancelled = true) }
            }
        }
    }

    // ---------------- 合成 ----------------

    /** 思考计时所属的会话代数；变化时清空计时缓存（跨会话 assistant key 可能相同）。 */
    private var thinkingGeneration = 0

    private fun compose(chat: ChatState, status: EngineStatus, l: Local): ChatScreenState {
        val visibleRetry = retryAnchor.update(chat)
        if (l.generation != thinkingGeneration) {
            thinkingStart.clear()
            thinkingEnd.clear()
            thinkingGeneration = l.generation
        }
        trackThinking(chat)
        val rows = ChatRows.build(
            items = chat.items,
            running = chat.running,
            expanded = l.expanded,
            local = Outbox.visible(l.sends, chat),
            thinkingMs = ::thinkingMs,
        )
        val phase = PhaseLabel.of(status, chat, l.sends, l.composer.stopping)
        val statusLines = buildList {
            phase?.let(::add)
            // 阶段提示已经概括了引擎状态行（如「正在压缩上下文（n 条排队）」）时不再重复显示。
            if (phase == null) chat.statusLine?.takeIf { it.isNotBlank() }?.let(::add)
            l.status.values.filter { it.isNotBlank() }.forEach(::add)
        }
        // 只带图片、没写字时也能发送。
        val primaryComposer = if (l.attachments.isNotEmpty() && l.composer.draft.isBlank()) l.composer.copy(draft = IMAGE_ONLY_PLACEHOLDER) else l.composer
        return ChatScreenState(
            chat = chat,
            rows = rows,
            composer = l.composer,
            primary = ComposerLogic.primaryAction(chat.running, primaryComposer),
            engineStatus = status,
            dialog = l.dialogs.firstOrNull(),
            pendingDialogs = (l.dialogs.size - 1).coerceAtLeast(0),
            replaceDraftOffer = l.replaceDraftOffer,
            statusLines = statusLines,
            title = l.title?.takeIf { it.isNotBlank() } ?: chat.sessionName?.takeIf { it.isNotBlank() } ?: "新会话",
            contextPercent = chat.contextPercent ?: l.statsPercent,
            retry = visibleRetry,
            phase = phase,
            slash = if (extras == null) null else SlashCompletion.candidates(l.composer.draft, l.commands, l.commandsLoading),
            attachments = l.attachments,
            bashRunning = l.bashRunning,
        )
    }

    private fun trackThinking(chat: ChatState) {
        val now = clock()
        for (item in chat.items) {
            if (item !is ChatItem.Assistant) continue
            val lastIndex = item.blocks.maxOfOrNull { it.index } ?: continue
            for (b in item.blocks) {
                if (b !is ContentBlock.Thinking || b.durationMs != null) continue
                val k = "${item.key}#${b.index}"
                if (k !in thinkingStart) {
                    // 第一次见到时消息已结束（例如 get_messages 重建），耗时未知。
                    thinkingStart[k] = if (item.streaming) now else null
                }
                val done = !item.streaming || b.index < lastIndex
                if (done && k !in thinkingEnd) thinkingEnd[k] = now
            }
        }
    }

    private fun thinkingMs(assistantKey: String, index: Int): Long? {
        val k = "$assistantKey#$index"
        val start = thinkingStart[k] ?: return null
        val end = thinkingEnd[k] ?: return null
        return (end - start).coerceAtLeast(0)
    }

    // ---------------- 输入框 ----------------

    fun setDraft(text: String) {
        local.update { it.copy(composer = it.composer.copy(draft = text)) }
        if (SlashCompletion.queryOf(text) != null) ensureCommands()
        persistDraftSoon()
    }

    /**
     * 发送键：空闲时发送；生成中默认排队（[mode] 为 null 时 FOLLOW_UP）；长按可选 STEER。
     * 以 `!` 开头走 `bash`（输出进上下文），`!!` 走 `bash` 且不进上下文（C6）；带图片时走 [EngineExtras.send]（C1）。
     */
    fun submit(mode: SendMode? = null) {
        val l = local.value
        if (l.composer.stopping) return
        val text = l.composer.draft.trim()
        val images = l.attachments
        if (text.isEmpty() && images.isEmpty()) return
        val ex = extras
        val route = if (ex != null && images.isEmpty()) InputRouting.route(text) else InputRoute.Message(text)
        if (route is InputRoute.Bash && ex != null) {
            if (l.bashRunning) {
                emitMessage("上一条命令还在执行")
                return
            }
            local.update { it.copy(composer = it.composer.withDraft("")) }
            persistDraftSoon()
            runBash(ex, route, original = text)
            return
        }
        if (images.isNotEmpty()) {
            if (ex == null) {
                emitMessage("当前版本还不支持发送图片")
                return
            }
            if (!modelAcceptsImages(engine.chat.value.model)) {
                emitMessage("当前模型不支持看图，请换一个支持图片的模型")
                return
            }
        }
        local.update { it.copy(composer = it.composer.withDraft(""), attachments = emptyList()) }
        persistDraftSoon()
        sendText(text, mode ?: SendMode.FOLLOW_UP, reuseId = null, images = images)
    }

    // ---------------- 图片附件（C1） ----------------

    /** 附件选择器（W3）产出的图片；最多 [MAX_IMAGES] 张。 */
    fun addImages(images: List<ImageInput>) {
        if (images.isEmpty()) return
        var dropped = false
        local.update { s ->
            val all = s.attachments + images
            dropped = all.size > MAX_IMAGES
            s.copy(attachments = all.take(MAX_IMAGES))
        }
        if (dropped) emitMessage("一次最多附 $MAX_IMAGES 张图片")
    }

    fun removeImage(index: Int) = local.update { s ->
        if (index !in s.attachments.indices) s else s.copy(attachments = s.attachments.filterIndexed { i, _ -> i != index })
    }

    fun clearImages() = local.update { it.copy(attachments = emptyList()) }

    // ---------------- 斜杠命令（C3） ----------------

    /** 选中候选：草稿变成 `/name `，等用户补参数。 */
    fun pickCommand(command: SlashCommand) {
        local.update { it.copy(composer = it.composer.withDraft(SlashCompletion.complete(command))) }
        persistDraftSoon()
    }

    /** 命令面板的「刷新」：技能或模板改过之后重新读取。 */
    fun refreshCommands() {
        local.update { it.copy(commands = null, commandsKey = null) }
        ensureCommands(forceRefresh = true)
    }

    private fun ensureCommands(forceRefresh: Boolean = false) {
        val ex = extras ?: return
        val key = engine.chat.value.sessionFile.orEmpty()
        var start = false
        var refresh = forceRefresh
        local.update { s ->
            if (s.commandsLoading || (s.commands != null && s.commandsKey == key)) {
                start = false
                s
            } else {
                start = true
                // 换过会话（cwd 可能变了）就让引擎重新请求，不用它按旧 cwd 缓存的结果。
                refresh = forceRefresh || (s.commandsKey != null && s.commandsKey != key)
                s.copy(commandsLoading = true)
            }
        }
        if (!start) return
        scope.launch {
            val r = try {
                ex.commands(refresh = refresh)
            } catch (e: CancellationException) {
                local.update { it.copy(commandsLoading = false) }
                throw e
            } catch (t: Throwable) {
                Outcome.Err(ErrCode.UNKNOWN, t.message ?: "", t)
            }
            local.update { s ->
                when (r) {
                    is Outcome.Ok -> s.copy(commands = r.value, commandsKey = key, commandsLoading = false)
                    is Outcome.Err -> s.copy(commands = emptyList(), commandsKey = key, commandsLoading = false)
                }
            }
            if (r is Outcome.Err) emitMessage("读取命令列表失败：" + r.message.ifBlank { "未知错误" })
        }
    }

    // ---------------- `!` 命令（C6） ----------------

    private fun runBash(ex: EngineExtras, route: InputRoute.Bash, original: String) {
        local.update { it.copy(bashRunning = true) }
        scope.launch {
            var failed: Outcome.Err? = null
            try {
                ex.bash(route.command, route.excludeFromContext).collect { ev ->
                    if (ev is BashEvent.Failed) failed = ev.error
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed = Outcome.Err(ErrCode.UNKNOWN, t.message ?: "", t)
            } finally {
                local.update { it.copy(bashRunning = false) }
            }
            failed?.let { e ->
                local.update { it.copy(composer = ComposerLogic.onSendRejected(it.composer, original)) }
                emitMessage("命令没有执行：" + e.message.ifBlank { "未知错误" })
            }
        }
    }

    /** 终端卡上的「停止」。 */
    fun abortBash() {
        val ex = extras ?: return
        scope.launch {
            val r = ex.abortBash()
            if (r is Outcome.Err) emitMessage("停止命令失败：${r.message}")
        }
    }

    // ---------------- 分叉（S1） ----------------

    /**
     * 「从这里分叉」：`fork{entryId}` 成功后 pi 切到新会话，被分叉的那条用户消息原文写回输入框，
     * 方便用户改一改再发（输入框不空时以芯片显示，不覆盖草稿）。
     */
    suspend fun forkFrom(entryId: String): Outcome<ForkResult> {
        val ex = extras ?: return Outcome.Err(ErrCode.UNKNOWN, "当前版本不支持分叉")
        val before = observedFile
        val r = try {
            ex.fork(entryId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Outcome.Err(ErrCode.UNKNOWN, t.message ?: "", t)
        }
        when (r) {
            is Outcome.Ok -> if (r.value.cancelled) {
                emitMessage("分叉被扩展取消了")
            } else {
                val t = r.value.text.orEmpty()
                if (t.isNotBlank()) {
                    val now = observedFile
                    // 会话切换已经处理过（已见到不同于分叉前的新文件）就直接放进输入框，否则等新文件出现时放。
                    if (now != null && now != before) {
                        applyForkText(t)
                    } else {
                        val p = PendingFork(t, before)
                        pendingFork = p
                        scope.launch {
                            delay(config.forkWaitMs)
                            if (pendingFork === p) {
                                pendingFork = null
                                emitMessage("分叉后没有切到新会话，原文没有放回输入框")
                            }
                        }
                    }
                }
            }
            is Outcome.Err -> emitMessage("分叉失败：" + r.message.ifBlank { "未知错误" })
        }
        return r
    }

    /** 合成协程每处理一次会话文件就调用：等到「非 null 且不同于分叉前」的文件才放回分叉原文。 */
    private fun maybeApplyFork(file: String?) {
        val p = pendingFork ?: return
        if (file == null || file == p.before) return
        applyForkText(p.text)
    }

    private fun applyForkText(t: String) {
        pendingFork = null
        local.update { it.copy(composer = ComposerLogic.onWithdrawn(it.composer, listOf(t))) }
        persistDraftSoon()
    }

    // ---------------- 草稿（S7） ----------------

    private fun keyOf(file: String?): String = file ?: DraftStore.newSessionKey("")

    private fun persistDraftSoon() {
        val store = drafts ?: return
        val key = keyOf(observedFile)
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(config.draftSaveDelayMs)
            store.put(key, DraftCodec.encode(currentDraft()))
        }
    }

    /**
     * 新会话落盘（null → 文件）：会话文件还是 null 时输入的草稿存在 `newSessionKey("")` 下，
     * 现在改存到新文件的 key 下并删掉旧 key，不在 drafts.json 里留孤儿（审查第 1 轮 Nit）。
     */
    private fun migrateNewSessionDraft(file: String) {
        val store = drafts ?: return
        draftJob?.cancel()
        draftJob = null
        val draft = currentDraft()
        scope.launch {
            try {
                store.remove(keyOf(null))
                if (!DraftCodec.isBlank(draft)) store.put(keyOf(file), DraftCodec.encode(draft))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    /** 离开会话：立即保存它的草稿，并清空输入框（撤回的文本随后由 resetSessionLocal 放回）。 */
    private fun swapOutDraft(file: String?) {
        draftJob?.cancel()
        draftJob = null
        val draft = currentDraft()
        if (drafts == null) return
        local.update { it.copy(composer = it.composer.withDraft("")) }
        sharedDraft.value = DraftState.EMPTY
        val key = keyOf(file)
        scope.launch { if (DraftCodec.isBlank(draft)) drafts.remove(key) else drafts.put(key, DraftCodec.encode(draft)) }
    }

    /**
     * 进入会话：取回它的草稿。输入框为空时直接放回；不空（刚撤回的文本已经放进去）时以芯片显示，不覆盖。
     * [firstLearn]：只是首次得知会话文件（冷启动或新会话落盘），草稿还是同一份，只在输入框为空时恢复。
     */
    private fun swapInDraft(file: String?, firstLearn: Boolean) {
        val store = drafts ?: return
        if (file == null) return
        val key = keyOf(file)
        scope.launch {
            val saved = try {
                store.get(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            val restored = DraftCodec.decode(saved)
            if (DraftCodec.isBlank(restored)) return@launch
            // 读回来时已经又换了会话：不要放进别的会话。
            if (observedFile != file) return@launch
            var applied = false
            local.update { s ->
                when {
                    s.composer.draft.isBlank() -> { applied = true; s.copy(composer = s.composer.withDraft(restored.text)) }
                    firstLearn || s.composer.draft.trim() == restored.text.trim() -> s
                    else -> s.copy(composer = s.composer.copy(withdrawn = s.composer.withdrawn + restored.text))
                }
            }
            // 光标、@ 引用、! 模式、图片、撤回芯片随文字一起回来（只在文字真的放回输入框时）。
            if (applied) sharedDraft.value = restored
        }
    }

    /** 要持久化的草稿：以输入框文字为准，加上共享草稿里的其余字段。 */
    private fun currentDraft(): DraftState = sharedDraft.value.let { d ->
        val t = local.value.composer.draft
        if (d.text == t) d else d.withText(t)
    }

    // ---------------- 供 SessionHost（ChatControllerPort）用的单一状态 ----------------

    /** 等待应答的扩展阻塞对话框（唯一订阅者是本控制器）。 */
    val pendingDialogs: StateFlow<List<ExtUiRequest>> =
        local.map { l -> l.dialogs.map { it.request } }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 扩展 `set_editor_text` 想替换非空草稿时的待确认文字。 */
    val editorTextOffer: StateFlow<String?> =
        local.map { it.replaceDraftOffer }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    /** 输入框当前文字（同步读取，不经合成后的 [state]）。 */
    val composerDraft: String get() = local.value.composer.draft

    /** 正在停止（此时发送会被忽略）。 */
    val isStopping: Boolean get() = local.value.composer.stopping

    private fun modelAcceptsImages(m: ModelInfo?): Boolean {
        val input = m?.raw?.get("input") as? JsonArray ?: return true
        return input.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
    }

    /** 失败气泡上的「重试」：只在用户点击时重发，绝不自动重发。 */
    fun retry(localId: String) {
        if (local.value.composer.stopping) return
        val l = local.value.sends.firstOrNull { it.id == localId && it.status == Delivery.FAILED } ?: return
        sendText(l.text, SendMode.FOLLOW_UP, reuseId = l.id, images = l.images)
    }

    /** 失败气泡上的「改一改」：文本退回输入框（输入框不空时以撤回芯片显示，不覆盖草稿）。 */
    fun editFailed(localId: String) {
        local.update { s ->
            val l = s.sends.firstOrNull { it.id == localId } ?: return@update s
            s.copy(
                sends = s.sends - l,
                composer = if (l.text.isBlank()) s.composer else ComposerLogic.onSendRejected(s.composer, l.text),
                attachments = if (s.attachments.isEmpty()) l.images else s.attachments,
            )
        }
    }

    fun deleteFailed(localId: String) = local.update { s -> s.copy(sends = s.sends.filterNot { it.id == localId }) }

    /** 错误卡上的「重试」：重发该回合的用户原文（§3.9）。 */
    fun resend(text: String) {
        if (text.isBlank() || local.value.composer.stopping) return
        sendText(text, SendMode.FOLLOW_UP, reuseId = null)
    }

    private fun sendText(text: String, mode: SendMode, reuseId: String?, images: List<ImageInput> = emptyList()) {
        val chat = engine.chat.value
        val id = reuseId ?: "s${seq++}"
        val pending = LocalSend(id = id, text = text, status = Delivery.SENDING, baselineUserCount = Outbox.userCount(chat, text), images = images)
        val ex = extras
        local.update { s -> s.copy(sends = s.sends.filterNot { it.id == id } + pending) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val r = try {
                if (images.isNotEmpty() && ex != null) ex.send(text, images, mode) else engine.send(text, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Outcome.Err(ErrCode.UNKNOWN, t.message ?: t::class.simpleName.orEmpty(), t)
            }
            val now = engine.chat.value
            var lateDelivered = false
            local.update { s ->
                lateDelivered = false
                val cur = s.sends.firstOrNull { it.id == id }
                    ?: return@update settleOrphan(s, id, r).also { lateDelivered = it.second }.first
                val next: LocalSend? = when (r) {
                    is Outcome.Ok -> when (r.value) {
                        Disposition.STARTED -> cur.copy(status = Delivery.SENT, sawRunning = now.running)
                        Disposition.QUEUED -> cur.copy(status = Delivery.QUEUED, sawRunning = now.running)
                        Disposition.HANDLED -> null
                    }
                    is Outcome.Err -> cur.copy(status = Delivery.FAILED, error = sendErrorText(r), needsSettings = needsModelSettings(r))
                }
                val sends = s.sends.map { if (it.id == id) next else it }.filterNotNull()
                s.copy(sends = Outbox.reconcile(sends, now))
            }
            if (lateDelivered) emitMessage("该消息已发出")
            else if (r is Outcome.Ok && r.value == Disposition.HANDLED) emitMessage("已由扩展命令处理")
        }
        inflightSends += job
        job.invokeOnCompletion { inflightSends.remove(job) }
        job.start()
    }

    /**
     * send 结果回来时，本地条目已不在（会话切换 / 引擎重启时被清掉）：
     * - 同进程切会话留下的在途发送（[Local.orphanSends]）：失败 → 文本退回输入框（§5.5 Q4）；成功 → 已由引擎接管，只移除记录；
     * - 引擎重启时已立即退回的发送（[Local.returnedSending]）：失败 → 不重复退回；成功 → 消息其实已发出，
     *   在草稿或芯片仍等于原文时撤掉退回的那份，并返回 true（调用方提示「该消息已发出」）；
     * - 其他情况（已被引擎接管后对账移除）：什么都不做。
     */
    private fun settleOrphan(s: Local, id: String, r: Outcome<Disposition>): Pair<Local, Boolean> {
        s.returnedSending[id]?.let { text ->
            val rest = s.returnedSending - id
            if (r is Outcome.Err) return s.copy(returnedSending = rest) to false
            return s.copy(returnedSending = rest, composer = retractReturned(s.composer, text)) to true
        }
        val text = s.orphanSends[id] ?: return s to false
        val rest = s.orphanSends - id
        return if (r is Outcome.Err) {
            s.copy(orphanSends = rest, composer = ComposerLogic.onWithdrawn(s.composer, listOf(text))) to false
        } else {
            s.copy(orphanSends = rest) to false
        }
    }

    /** 撤掉之前退回的 [text]：草稿仍等于原文就清空；否则从芯片里去掉一条同文本；用户已改动过的草稿不碰。 */
    private fun retractReturned(c: ComposerState, text: String): ComposerState = when {
        c.draft.trim() == text.trim() -> c.withDraft("")
        c.withdrawn.any { it.trim() == text.trim() } -> c.copy(withdrawn = minusEach(c.withdrawn, listOf(text)))
        else -> c
    }

    private fun emitMessage(text: String) {
        _messages.trySend(text)
    }

    /** 没配模型、Key/鉴权失败、连不上模型服务时，失败气泡上给「去设置模型」。 */
    private fun needsModelSettings(e: Outcome.Err): Boolean = when (e.code) {
        ErrCode.NO_MODEL, ErrCode.PROVIDER -> true
        ErrCode.RPC_REJECTED, ErrCode.UNKNOWN -> ErrorText.classify(e.message).suggestsSettings
        else -> false
    }

    private fun sendErrorText(e: Outcome.Err): String = when (e.code) {
        ErrCode.RPC_TIMEOUT -> "发送超时，pi 没有回应"
        ErrCode.RPC_REJECTED -> "pi 拒绝了这条消息" + e.message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        ErrCode.ENGINE_START, ErrCode.ENGINE_EXIT -> "引擎没有在运行"
        ErrCode.NO_MODEL -> "还没有配置可用的模型"
        ErrCode.NETWORK -> "网络不可用"
        ErrCode.CANCELLED -> "已取消"
        else -> "发送失败" + e.message.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
    }

    // ---------------- 停止 ----------------

    /**
     * 停止（§3.7）：引擎内部先 `clear_queue` 再 `abort`；撤回的文本只在输入框为空时放回，
     * 否则以「已撤回 n 条」芯片显示，**绝不覆盖草稿**。草稿以停止完成那一刻为准（停止期间用户可以继续打字）。
     */
    fun stop() {
        if (local.value.composer.stopping) return
        // 先收起所有还在等用户回答的阻塞类对话框（工具确认、提问等），不留悬空弹窗（piweb-study G1）。
        // pi 1.5.0 的 RPC 对话框在 abort 时不会自己结束、也不发关闭事件：不先应答 cancelled，
        // 扩展没传 signal 时 abort 会一直等到 20 s 超时被强杀。移植自 agegr/pi-web@025e24e
        // `lib/rpc-manager.ts:1323-1345`（停止先撤销扩展对话框，再 abort），MIT。
        var dropped: List<DialogEntry> = emptyList()
        local.update { s ->
            dropped = s.dialogs.filter { it.request.needsResponse }
            s.copy(
                composer = s.composer.copy(stopping = true, stopSlow = false),
                withdrawnDuringStop = emptyList(),
                dialogs = s.dialogs.filterNot { it.request.needsResponse },
            )
        }
        for (d in dropped) {
            notifier?.cancel(d.request.id)
            scope.launch { engine.answerUi(d.request.id, cancelled = true) }
        }
        stopJob = scope.launch {
            val slow = launch {
                delay(config.stopSlowHintMs)
                local.update { it.copy(composer = it.composer.copy(stopSlow = true)) }
            }
            val r = try {
                // 停止期间发送键不可用；先等已经发出的消息拿到响应，再 clear_queue → abort，
                // 否则它可能在 clear_queue 之后才入队，停止后照样发出。
                val pendingSends = inflightSends.toList()
                if (pendingSends.isNotEmpty()) withTimeoutOrNull(config.stopWaitSendMs) { pendingSends.joinAll() }
                engine.stop()
            } catch (e: CancellationException) {
                slow.cancel()
                local.update { it.copy(composer = it.composer.copy(stopping = false, stopSlow = false)) }
                throw e
            } catch (t: Throwable) {
                Outcome.Err(ErrCode.UNKNOWN, t.message ?: "", t)
            }
            slow.cancel()
            local.update { s ->
                var c = s.composer.copy(stopping = false, stopSlow = false)
                if (r is Outcome.Ok) {
                    // 停止超时触发强杀重启时，引擎重启已经把队列文本退回过一次，这里扣掉，避免出现两份。
                    val texts = minusEach(ComposerLogic.withdrawnTexts(r.value), s.withdrawnDuringStop)
                    c = ComposerLogic.onWithdrawn(c, texts)
                }
                // 排队中的本地气泡已随队列一起撤回（文本在撤回结果里），不再显示。
                s.copy(composer = c, sends = s.sends.filterNot { it.status == Delivery.QUEUED }, withdrawnDuringStop = emptyList())
            }
            if (r is Outcome.Err) emitMessage("停止失败：" + r.message.ifBlank { "未知错误" })
        }
    }

    fun insertWithdrawn() = local.update { it.copy(composer = ComposerLogic.insertWithdrawn(it.composer)) }

    fun discardWithdrawn() = local.update { it.copy(composer = ComposerLogic.discardWithdrawn(it.composer)) }

    // ---------------- 折叠 ----------------

    fun toggleTurn(key: String) = local.update {
        it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key)
    }

    // ---------------- 扩展 UI ----------------

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) notifier?.clearAll()
    }

    private fun onUiRequest(r: ExtUiRequest) {
        when (val a = ExtUi.classify(r)) {
            is ExtUiAction.Dialog -> {
                if (r.needsResponse && local.value.composer.stopping) {
                    // 停止过程中才到的阻塞类对话框（引擎队列或宽限窗口里的）：直接回 cancelled，不弹出，
                    // 否则 abort 可能一直等它，直到 20 s 被强杀。
                    scope.launch { engine.answerUi(r.id, cancelled = true) }
                    return
                }
                local.update { s ->
                    if (s.dialogs.any { it.request.id == r.id }) s
                    else s.copy(dialogs = s.dialogs + DialogEntry(r, clock()))
                }
                if (!foreground) notifier?.notifyDialog(r)
            }
            is ExtUiAction.CancelUnknown -> scope.launch { engine.answerUi(a.id, cancelled = true) }
            is ExtUiAction.Notify -> if (a.text.isNotBlank()) {
                val prefix = when (a.level) {
                    "error" -> "错误："
                    "warning" -> "注意："
                    else -> ""
                }
                emitMessage(prefix + a.text)
            }
            is ExtUiAction.Status -> local.update { s ->
                s.copy(status = if (a.text.isNullOrBlank()) s.status - a.key else s.status + (a.key to a.text))
            }
            is ExtUiAction.Title -> local.update { it.copy(title = a.title) }
            is ExtUiAction.EditorText -> local.update { s ->
                val applied = ComposerLogic.applyEditorText(s.composer, a.text)
                if (applied != null) s.copy(composer = applied) else s.copy(replaceDraftOffer = a.text)
            }
        }
    }

    /** 用户在对话框里作答或关闭（关闭 = cancelled）。 */
    fun answerDialog(id: String, answer: ExtUiAnswer) {
        val removed = removeDialog(id)
        if (!removed) return
        scope.launch { engine.answerUi(id, value = answer.value, confirmed = answer.confirmed, cancelled = answer.cancelled) }
    }

    /** 带 timeout 的对话框到时：pi 会按默认值处理，客户端只关掉对话框，不再应答。 */
    fun expireDialog(id: String) {
        removeDialog(id)
    }

    private fun removeDialog(id: String): Boolean {
        var removed = false
        local.update { s ->
            val next = s.dialogs.filterNot { it.request.id == id }
            removed = next.size != s.dialogs.size
            s.copy(dialogs = next)
        }
        notifier?.cancel(id)
        return removed
    }

    fun acceptReplaceDraft() = local.update { s ->
        val t = s.replaceDraftOffer ?: return@update s
        s.copy(composer = s.composer.withDraft(t), replaceDraftOffer = null)
    }

    fun rejectReplaceDraft() = local.update { it.copy(replaceDraftOffer = null) }

    // ---------------- 顶栏：模型 / 思考 / 统计 ----------------

    /** 顶栏模型菜单用：失败时返回错误，让菜单区分「读取失败」和「确实没有模型」。 */
    suspend fun loadModels(): Outcome<List<ModelInfo>> = try {
        engine.models()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Outcome.Err(ErrCode.UNKNOWN, t.message ?: t::class.simpleName.orEmpty(), t)
    }

    suspend fun models(): List<ModelInfo> = when (val r = engine.models()) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> {
            emitMessage("读取模型列表失败：${r.message}")
            emptyList()
        }
    }

    fun setModel(m: ModelInfo) {
        scope.launch {
            when (val r = engine.setModel(m.provider, m.id)) {
                is Outcome.Ok -> {
                    emitMessage("已切换到 ${r.value.name ?: r.value.id}")
                    refreshStats()
                }
                is Outcome.Err -> emitMessage("切换模型失败：${r.message}")
            }
        }
    }

    suspend fun thinkingLevels(): List<String> = when (val r = engine.thinkingLevels()) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> {
            emitMessage("读取思考级别失败：${r.message}")
            emptyList()
        }
    }

    fun setThinking(level: String) {
        scope.launch {
            val r = engine.setThinking(level)
            if (r is Outcome.Err) emitMessage("切换思考级别失败：${r.message}")
        }
    }

    private suspend fun refreshStats() {
        val r = try {
            engine.stats()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return
        }
        if (r is Outcome.Ok) local.update { it.copy(statsPercent = r.value.contextPercent) }
    }

    fun restartEngine() {
        scope.launch {
            val r = engine.restart("用户在聊天页点击「重启引擎」")
            if (r is Outcome.Err) emitMessage("重启失败：${r.message}")
        }
    }

    internal companion object {
        /** 页面不在前台时最多攒几条一次性提示。 */
        const val MESSAGE_BUFFER = 3

        /** 一条消息最多附几张图。 */
        const val MAX_IMAGES = 8

        /** 只带图片时，按「有字」计算发送键状态用的占位（不会发出去）。 */
        private const val IMAGE_ONLY_PLACEHOLDER = "[image]"

        /**
         * 会话切换 / 引擎重启时要退回输入框的文本，按 pi 送达顺序：已入队的（先插话、后排队）→
         * 本地还没见到入队的排队气泡 → 发送中（[includeSending]）→ 发送失败的。已在 [queue] 里的同文本只退一次。
         */
        fun rescuedTexts(sends: List<LocalSend>, queue: QueueSnapshot, includeSending: Boolean): List<String> {
            val fromQueue = ComposerLogic.withdrawnTexts(queue)
            val queued = minusEach(sends.filter { it.status == Delivery.QUEUED }.map { it.text }, fromQueue)
            val sending = if (includeSending) sends.filter { it.status == Delivery.SENDING }.map { it.text } else emptyList()
            val failed = sends.filter { it.status == Delivery.FAILED }.map { it.text }
            return fromQueue + queued + sending + failed
        }

        /** 多重集差：[remove] 里每出现一次，就从 [from] 里去掉一条同文本（按 trim 比较）。 */
        fun minusEach(from: List<String>, remove: List<String>): List<String> {
            if (remove.isEmpty()) return from
            val pool = remove.map { it.trim() }.toMutableList()
            return from.filter { t -> !pool.remove(t.trim()) }
        }
    }
}

/** 后台时提醒用户有扩展对话框等待回答（Android 实现见 ui/AndroidExtUiNotifier）。 */
interface ExtUiNotifier {
    fun notifyDialog(request: ExtUiRequest)
    fun cancel(id: String)
    fun clearAll()
}
