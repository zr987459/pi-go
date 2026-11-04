package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.LocalBlock
import io.github.zr987459.pigo.contract.session.OutgoingMessage
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.ScrollAnchor
import io.github.zr987459.pigo.contract.session.SessionCatalog
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [SessionHost] 的实现（DESIGN-r3 §5.1，r3/session）。由 AppGraph 持有单例，与进程同寿。
 *
 * - 公开 API 没有任何形态参数；[form] 只是一个 StateFlow，改它**不发 RPC、不重启引擎**（§5.3）。
 * - **聊天状态只有一份**（审查第 1 轮 I1）：草稿、发送、扩展对话框、`set_editor_text`、队列退回都经 [chatPort]
 *   （真实实现包住 `ChatController`）；本类不订阅 `uiRequests`、不直接 `engine.send`。
 * - 扩展阻塞对话框按端口的列表镜像进 [overlay]，切换形态不回 cancelled。
 * - [clipboardRequests]：`/copy` 等要写剪贴板的文字，两种形态都收集并真正写入系统剪贴板（审查第 1 轮 I4）。
 */
class SessionHostImpl(
    override val engine: EngineClient,
    override val extras: EngineExtras,
    private val scope: CoroutineScope,
    override val settings: PiSettings,
    override val sessions: SessionCatalog,
    val chatPort: SessionChatPort,
    initialForm: UiForm = UiForm.CALM,
    private val pid: () -> Int? = { null },
    actionsFactory: (SessionHostImpl) -> PiActions,
) : SessionHost {

    override val chat: StateFlow<ChatState> = engine.chat
    override val draft: MutableStateFlow<DraftState> = chatPort.draft
    override val overlay: OverlayStack = OverlayStack()
    override val form: MutableStateFlow<UiForm> = MutableStateFlow(initialForm)
    override val scrollAnchor: MutableStateFlow<ScrollAnchor?> = MutableStateFlow(null)

    private val _history = MutableStateFlow<List<String>>(emptyList())
    override val promptHistory: StateFlow<List<String>> = _history.asStateFlow()

    private val _localBlocks = MutableStateFlow<List<LocalBlock>>(emptyList())
    override val localBlocks: StateFlow<List<LocalBlock>> = _localBlocks.asStateFlow()

    private val _ephemeral = MutableStateFlow(false)
    override val ephemeral: StateFlow<Boolean> = _ephemeral.asStateFlow()

    /** 扩展非阻塞通知（notify）与动作吐司：形态订阅后显示一次。 */
    private val _toasts = MutableStateFlow<List<String>>(emptyList())
    val toasts: StateFlow<List<String>> = _toasts.asStateFlow()

    private val _clipboard = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    override val clipboardRequests: Flow<String> = _clipboard.asSharedFlow()

    override val outgoing: StateFlow<List<OutgoingMessage>> = chatPort.outgoing

    override fun retryOutgoing(id: String) = chatPort.retryOutgoing(id)

    override fun editOutgoing(id: String) = chatPort.editOutgoing(id)

    override fun discardOutgoing(id: String) = chatPort.discardOutgoing(id)

    override val actions: PiActions = actionsFactory(this)

    private var localSeq = 0

    init {
        scope.launch { chatPort.dialogs.collect { syncDialogs(it) } }
        scope.launch { chatPort.editorTextOffer.collect { syncEditorOffer(it) } }
        scope.launch { chatPort.notices.collect { toast(it) } }
    }

    override suspend fun runAction(id: String, args: String?): ActionResult {
        val r = actions.run(id, args)
        when (r) {
            is ActionResult.Overlay -> overlay.push(r.model)
            is ActionResult.LocalBlock -> addLocalBlock(id, r.title, r.text)
            else -> Unit
        }
        return r
    }

    override fun debugEnginePid(): Int? = if (engine.status.value is EngineStatus.Stopped) null else pid()

    // ---- 供动作表与形态使用（都没有形态参数）----

    /** 发送当前草稿（发送键 / Enter / ⏎）：经 [chatPort]（Outbox、`!` 路由、按会话草稿）。 */
    suspend fun sendDraft(mode: SendMode = SendMode.STEER): Outcome<Disposition> {
        val d = draft.value
        if (!d.hasSendableContent) return Outcome.Err(ErrCode.RPC_REJECTED, "草稿为空")
        val r = chatPort.send(mode)
        if (r is Outcome.Ok && d.text.isNotBlank()) _history.update { (it - d.text) + d.text }
        return r
    }

    /** 请求形态把 [text] 写进系统剪贴板。返回是否有形态在收集（没有就不要告诉用户「已复制」）。 */
    fun requestClipboard(text: String): Boolean = _clipboard.subscriptionCount.value > 0 && _clipboard.tryEmit(text)

    fun addLocalBlock(id: String, title: String, text: String) {
        _localBlocks.update { it + LocalBlock(key = "local:$id:${localSeq++}", title = title, text = text) }
    }

    fun clearLocalBlocks() { _localBlocks.value = emptyList() }

    fun toast(text: String) = _toasts.update { it + text }

    fun consumeToasts(): List<String> { val t = _toasts.value; _toasts.value = emptyList(); return t }

    fun setEphemeral(value: Boolean) { _ephemeral.value = value }

    /** 扩展对话框应答（两种形态共用）：交给端口（唯一应答者），栈里的镜像随端口列表移除。 */
    fun answerExtDialog(overlayId: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val d = overlay.items.value.firstOrNull { it.id == overlayId } as? OverlayModel.ExtDialog ?: return
        overlay.remove(overlayId)
        chatPort.answerDialog(d.request.id, value = value, confirmed = confirmed, cancelled = cancelled)
    }

    /** 扩展 `set_editor_text` 要替换非空草稿时的确认框（[EDITOR_OFFER_ID]）：接受 = 替换，拒绝 = 保留草稿。 */
    fun answerEditorTextOffer(accept: Boolean) {
        overlay.remove(EDITOR_OFFER_ID)
        chatPort.resolveEditorTextOffer(accept)
    }

    private fun syncDialogs(list: List<ExtUiRequest>) {
        val wanted = list.associateBy { "ext:${it.id}" }
        overlay.items.value.filterIsInstance<OverlayModel.ExtDialog>().filter { it.id !in wanted }.forEach { overlay.remove(it.id) }
        for ((id, r) in wanted) if (overlay.items.value.none { it.id == id }) {
            overlay.push(OverlayModel.ExtDialog(id = id, title = r.title ?: r.method, request = r, shownAtMillis = System.currentTimeMillis()))
        }
    }

    private fun syncEditorOffer(text: String?) {
        if (text == null) { overlay.remove(EDITOR_OFFER_ID); return }
        if (overlay.items.value.none { it.id == EDITOR_OFFER_ID }) {
            overlay.push(OverlayModel.Confirm(EDITOR_OFFER_ID, "扩展要替换输入框", "输入框里已有内容。替换成扩展给的文字吗？\n\n$text", confirmLabel = "替换"))
        }
    }

    companion object {
        const val EDITOR_OFFER_ID = "overlay:editor-text-offer"
    }
}
