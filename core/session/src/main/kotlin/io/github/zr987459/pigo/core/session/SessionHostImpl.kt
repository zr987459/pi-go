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
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.LocalBlock
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.ScrollAnchor
import io.github.zr987459.pigo.contract.session.SessionCatalog
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.contract.settings.PiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [SessionHost] 的实现（DESIGN-r3 §5.1，r3/session）。由 AppGraph 持有单例，与进程同寿。
 *
 * - 公开 API 没有任何形态参数；[form] 只是一个 StateFlow，改它**不发 RPC、不重启引擎**（§5.3）。
 * - 动作表在构造时由 [actionsFactory] 用本宿主创建（[PiActionsImpl] 需要读草稿、写本地块）。
 * - 扩展阻塞对话框（select / confirm / input / editor）收到后压进 [overlay]，切换形态不回 cancelled。
 * - 切换会话 / 引擎重启时，排队中的文字回到草稿（[QueueRecall]，§7.3 QueueBackToDraftTest）。
 */
class SessionHostImpl(
    override val engine: EngineClient,
    override val extras: EngineExtras,
    private val scope: CoroutineScope,
    override val settings: PiSettings,
    override val sessions: SessionCatalog,
    initialForm: UiForm = UiForm.CALM,
    private val pid: () -> Int? = { null },
    actionsFactory: (SessionHostImpl) -> PiActions,
) : SessionHost {

    override val chat: StateFlow<ChatState> = engine.chat
    override val draft: MutableStateFlow<DraftState> = MutableStateFlow(DraftState.EMPTY)
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

    override val actions: PiActions = actionsFactory(this)

    private var localSeq = 0

    init {
        scope.launch { engine.uiRequests.collect { onUiRequest(it) } }
        scope.launch { QueueRecall.watch(engine.chat, engine.status) { texts -> recallToDraft(texts) } }
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

    /**
     * 发送当前草稿（发送键 / Enter / ⏎）。`!`、`!!` 模式走 bash（`excludeFromContext`），`@` 引用按 pi 交互模式
     * 的写法拼进文字（[AtFileExpansion]），图片走 extras.send。成功后清空草稿但保留「已撤回」芯片。
     */
    suspend fun sendDraft(mode: SendMode = SendMode.STEER): Outcome<Disposition> {
        val d = draft.value
        if (!d.hasSendableContent) return Outcome.Err(ErrCode.RPC_REJECTED, "草稿为空")
        if (d.text.isNotBlank()) _history.update { (it - d.text) + d.text }
        draft.value = DraftState(recalled = d.recalled)
        if (d.bashMode != BashMode.NONE) {
            scope.launch { extras.bash(d.text, excludeFromContext = d.bashMode == BashMode.EXCLUDE).collect { } }
            return Outcome.Ok(Disposition.STARTED)
        }
        val message = AtFileExpansion.compose(d.text, d.fileRefs)
        val r = if (d.images.isEmpty()) engine.send(message, mode) else extras.send(message, imageLoader(d), mode)
        if (r is Outcome.Err) draft.value = d // 发送失败：草稿原样放回，不丢字
        return r
    }

    /** 由 AppGraph 注入：把草稿里的图片 URI 读成 base64（core/session 不碰 Android ContentResolver）。 */
    var imageLoader: (DraftState) -> List<io.github.zr987459.pigo.contract.ImageInput> = { emptyList() }

    fun addLocalBlock(id: String, title: String, text: String) {
        _localBlocks.update { it + LocalBlock(key = "local:$id:${localSeq++}", title = title, text = text) }
    }

    fun clearLocalBlocks() { _localBlocks.value = emptyList() }

    fun toast(text: String) = _toasts.update { it + text }

    fun consumeToasts(): List<String> { val t = _toasts.value; _toasts.value = emptyList(); return t }

    fun setEphemeral(value: Boolean) { _ephemeral.value = value }

    /** 扩展对话框应答（两种形态共用）；应答后从栈里移除。 */
    suspend fun answerExtDialog(overlayId: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val d = overlay.items.value.firstOrNull { it.id == overlayId } as? OverlayModel.ExtDialog ?: return
        overlay.remove(overlayId)
        engine.answerUi(d.request.id, value = value, confirmed = confirmed, cancelled = cancelled)
    }

    private fun onUiRequest(r: ExtUiRequest) {
        when (r.method) {
            "select", "confirm", "input", "editor" -> {
                val id = "ext:${r.id}"
                if (overlay.items.value.none { it.id == id }) {
                    overlay.push(OverlayModel.ExtDialog(id = id, title = r.title ?: r.method, request = r, shownAtMillis = System.currentTimeMillis()))
                }
            }
            "set_editor_text" -> r.prefill?.let { draft.value = draft.value.withText(it) }
            "notify" -> (r.message ?: r.title)?.let { toast(it) }
            else -> Unit
        }
    }

    private fun recallToDraft(texts: List<String>) {
        if (texts.isEmpty()) return
        draft.update { d -> QueueRecall.merge(d, texts) }
    }
}
