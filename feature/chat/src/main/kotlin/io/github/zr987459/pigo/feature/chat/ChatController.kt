package io.github.zr987459.pigo.feature.chat

import androidx.compose.runtime.Immutable
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ChatRows
import io.github.zr987459.pigo.feature.chat.logic.ComposerLogic
import io.github.zr987459.pigo.feature.chat.logic.ComposerState
import io.github.zr987459.pigo.feature.chat.logic.ExtUi
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAction
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.Outbox
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 聊天界面的可调参数。 */
data class ChatConfig(
    /** 停止请求超过这个时长仍未完成时，提示「仍在停止，正在强制结束…」（§3.7：20 s 后引擎强杀重启）。 */
    val stopSlowHintMs: Long = 20_000,
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
    )

    private val local = MutableStateFlow(Local())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 一次性提示（Snackbar）：扩展 notify、命令失败、停止失败等。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** App 在后台时收到对话类请求，用通知提醒（由页面注入 Android 实现）。 */
    @Volatile
    var notifier: ExtUiNotifier? = null

    @Volatile
    private var foreground = false
    private var seq = 0
    private var stopJob: Job? = null

    /** 思考块计时：key = "assistantKey#index" → (开始, 结束)。只在合成协程里读写。 */
    private val thinkingStart = HashMap<String, Long?>()
    private val thinkingEnd = HashMap<String, Long>()

    val state: StateFlow<ChatScreenState> =
        combine(engine.chat, engine.status, local) { chat, status, l -> compose(chat, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, compose(engine.chat.value, engine.status.value, local.value))

    init {
        scope.launch { engine.uiRequests.collect { onUiRequest(it) } }
        scope.launch {
            var wasRunning = engine.chat.value.running
            engine.chat.collect { chat ->
                local.update { l ->
                    val sends = Outbox.reconcile(l.sends, chat)
                    if (sends === l.sends) l else l.copy(sends = sends)
                }
                if (wasRunning && !chat.running) refreshStats()
                wasRunning = chat.running
            }
        }
        scope.launch { refreshStats() }
    }

    // ---------------- 合成 ----------------

    private fun compose(chat: ChatState, status: EngineStatus, l: Local): ChatScreenState {
        trackThinking(chat)
        val rows = ChatRows.build(
            items = chat.items,
            running = chat.running,
            expanded = l.expanded,
            local = Outbox.visible(l.sends, chat),
            thinkingMs = ::thinkingMs,
        )
        val statusLines = buildList {
            chat.statusLine?.takeIf { it.isNotBlank() }?.let(::add)
            l.status.values.filter { it.isNotBlank() }.forEach(::add)
        }
        return ChatScreenState(
            chat = chat,
            rows = rows,
            composer = l.composer,
            primary = ComposerLogic.primaryAction(chat.running, l.composer),
            engineStatus = status,
            dialog = l.dialogs.firstOrNull(),
            pendingDialogs = (l.dialogs.size - 1).coerceAtLeast(0),
            replaceDraftOffer = l.replaceDraftOffer,
            statusLines = statusLines,
            title = l.title?.takeIf { it.isNotBlank() } ?: chat.sessionName?.takeIf { it.isNotBlank() } ?: "新会话",
            contextPercent = chat.contextPercent ?: l.statsPercent,
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

    fun setDraft(text: String) = local.update { it.copy(composer = it.composer.copy(draft = text)) }

    /** 发送键：空闲时发送；生成中默认排队（[mode] 为 null 时 FOLLOW_UP）；长按菜单可传 STEER。 */
    fun submit(mode: SendMode? = null) {
        val l = local.value
        if (l.composer.stopping) return
        val text = l.composer.draft.trim()
        if (text.isEmpty()) return
        local.update { it.copy(composer = it.composer.withDraft("")) }
        sendText(text, mode ?: SendMode.FOLLOW_UP, reuseId = null)
    }

    /** 失败气泡上的「重试」：只在用户点击时重发，绝不自动重发。 */
    fun retry(localId: String) {
        val l = local.value.sends.firstOrNull { it.id == localId && it.status == Delivery.FAILED } ?: return
        sendText(l.text, SendMode.FOLLOW_UP, reuseId = l.id)
    }

    /** 失败气泡上的「改一改」：文本退回输入框（输入框不空时以撤回芯片显示，不覆盖草稿）。 */
    fun editFailed(localId: String) {
        local.update { s ->
            val l = s.sends.firstOrNull { it.id == localId } ?: return@update s
            s.copy(sends = s.sends - l, composer = ComposerLogic.onSendRejected(s.composer, l.text))
        }
    }

    fun deleteFailed(localId: String) = local.update { s -> s.copy(sends = s.sends.filterNot { it.id == localId }) }

    /** 错误卡上的「重试」：重发该回合的用户原文（§3.9）。 */
    fun resend(text: String) {
        if (text.isBlank() || local.value.composer.stopping) return
        sendText(text, SendMode.FOLLOW_UP, reuseId = null)
    }

    private fun sendText(text: String, mode: SendMode, reuseId: String?) {
        val chat = engine.chat.value
        val id = reuseId ?: "s${seq++}"
        val pending = LocalSend(id = id, text = text, status = Delivery.SENDING, baselineUserCount = Outbox.userCount(chat, text))
        local.update { s -> s.copy(sends = s.sends.filterNot { it.id == id } + pending) }
        scope.launch {
            val r = try {
                engine.send(text, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Outcome.Err(ErrCode.UNKNOWN, t.message ?: t::class.simpleName.orEmpty(), t)
            }
            val now = engine.chat.value
            local.update { s ->
                val cur = s.sends.firstOrNull { it.id == id } ?: return@update s
                val next: LocalSend? = when (r) {
                    is Outcome.Ok -> when (r.value) {
                        Disposition.STARTED -> cur.copy(status = Delivery.SENT, sawRunning = now.running)
                        Disposition.QUEUED -> cur.copy(status = Delivery.QUEUED, sawRunning = now.running)
                        Disposition.HANDLED -> null
                    }
                    is Outcome.Err -> cur.copy(status = Delivery.FAILED, error = sendErrorText(r))
                }
                val sends = s.sends.map { if (it.id == id) next else it }.filterNotNull()
                s.copy(sends = Outbox.reconcile(sends, now))
            }
            if (r is Outcome.Ok && r.value == Disposition.HANDLED) _messages.tryEmit("已由扩展命令处理")
        }
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
        local.update { it.copy(composer = it.composer.copy(stopping = true, stopSlow = false)) }
        stopJob = scope.launch {
            val slow = launch {
                delay(config.stopSlowHintMs)
                local.update { it.copy(composer = it.composer.copy(stopSlow = true)) }
            }
            val r = try {
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
                if (r is Outcome.Ok) c = ComposerLogic.onWithdrawn(c, ComposerLogic.withdrawnTexts(r.value))
                // 排队中的本地气泡已随队列一起撤回（文本在撤回结果里），不再显示。
                s.copy(composer = c, sends = s.sends.filterNot { it.status == Delivery.QUEUED })
            }
            if (r is Outcome.Err) _messages.tryEmit("停止失败：" + r.message.ifBlank { "未知错误" })
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
                _messages.tryEmit(prefix + a.text)
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

    suspend fun models(): List<ModelInfo> = when (val r = engine.models()) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> {
            _messages.tryEmit("读取模型列表失败：${r.message}")
            emptyList()
        }
    }

    fun setModel(m: ModelInfo) {
        scope.launch {
            when (val r = engine.setModel(m.provider, m.id)) {
                is Outcome.Ok -> {
                    _messages.tryEmit("已切换到 ${r.value.name ?: r.value.id}")
                    refreshStats()
                }
                is Outcome.Err -> _messages.tryEmit("切换模型失败：${r.message}")
            }
        }
    }

    suspend fun thinkingLevels(): List<String> = when (val r = engine.thinkingLevels()) {
        is Outcome.Ok -> r.value
        is Outcome.Err -> {
            _messages.tryEmit("读取思考级别失败：${r.message}")
            emptyList()
        }
    }

    fun setThinking(level: String) {
        scope.launch {
            val r = engine.setThinking(level)
            if (r is Outcome.Err) _messages.tryEmit("切换思考级别失败：${r.message}")
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
            if (r is Outcome.Err) _messages.tryEmit("重启失败：${r.message}")
        }
    }
}

/** 后台时提醒用户有扩展对话框等待回答（Android 实现见 ui/AndroidExtUiNotifier）。 */
interface ExtUiNotifier {
    fun notifyDialog(request: ExtUiRequest)
    fun cancel(id: String)
    fun clearAll()
}
