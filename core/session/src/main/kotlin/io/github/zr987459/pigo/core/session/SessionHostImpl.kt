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
        val impl = actions as? PiActionsImpl
        // 1) SessionHost 自己的面板（overlay:*）：选中 / 提交 / 确认 → 真实行为（PR #18 审查附注，不再「未知动作」）。
        if (impl != null && id.startsWith(OverlayPanels.PREFIX)) return panelEvent(impl, id, args)
        // CalmSheet 点树节点发 runAction("session.tree", 节点 id)：与终端形态的 overlay:session.tree 同一路径（成功后关树）。
        if (impl != null && id == "session.tree" && !args.isNullOrBlank()) return panelEvent(impl, OverlayPanels.TREE, args)
        // 2) ModelPanels 产出的面板（/model、/login、/logout、自定义服务商…）：选中项、表单提交、键帽 → onPanelEvent。
        if (impl != null) modelPanelRoute(id, args)?.let { (overlayId, key, value) ->
            impl.onModelPanel(overlayId, key, value)?.let { return landModelPanel(overlayId, key, value, it) }
        }
        val r = actions.run(id, args)
        when (r) {
            is ActionResult.Overlay -> {
                if (id in MODEL_PANEL_ACTIONS) modelPanelIds += r.model.id
                show(r.model)
            }
            is ActionResult.LocalBlock -> addLocalBlock(id, r.title, r.text)
            else -> Unit
        }
        return r
    }

    /** ModelPanels 产出过的面板 id（按来源路由，不按 id 是否在动作表里：`model.select` 既是动作 id 也是面板 id）。 */
    private val modelPanelIds = mutableSetOf<String>()

    /** 最近一次见到的 ModelPanels 面板（终端形态提交表单前会先弹栈，这时按它的字段解析 `k=v`）。 */
    private val lastModelPanel = mutableMapOf<String, OverlayModel>()

    /** ModelPanels 确认框 → 打开它的那层面板 id（确认后结果是 Done 时一并关闭，例如删掉的自定义服务商表单）。 */
    private val confirmParent = mutableMapOf<String, String>()

    /** 最近一次见到的 `overlay:*` 表单（终端形态提交前先弹栈；失败时按它把带已填值的表单压回，审查第 1 轮 N2）。 */
    private val lastOverlayForm = mutableMapOf<String, OverlayModel.Form>()

    private val catalogIds: Set<String> by lazy { actions.all.map { it.id }.toSet() }

    /**
     * 判断一次 runAction 是不是 ModelPanels 面板的事件，是则返回 (面板 id, 事件 key, value)：
     * - id 是栈顶的 ModelPanels 面板且带值：列表项（`star:<key>` = 常用键帽作用于该项）、表单提交（`submit` + 表单值 JSON）、确认；
     * - id 是刚弹栈的 ModelPanels 表单、值是该表单的 `k=v`：提交；
     * - id 不在动作表里、栈顶是 ModelPanels 面板：面板自带键帽（value = 传入值，否则高亮项 / 表单值 JSON）。
     */
    private fun modelPanelRoute(id: String, args: String?): Triple<String, String, String?>? {
        val items = overlay.items.value
        val top = items.lastOrNull()
        // 1) 面板仍在栈里（终端形态、或上面压着扩展对话框）：按这个面板的类型解析。
        val inStack = items.lastOrNull { it.id == id }
        if (inStack != null && id in modelPanelIds && args != null) return modelPanelEvent(inStack, args)
        // 2) 面板已被形态先弹栈 / 移除（CalmSheet 的确认框「先 remove 再 runAction」、终端表单先 pop 再提交）：
        //    按来源（最近一次见到的同 id ModelPanels 面板）路由，两形态得到同一个面板事件（审查第 1 轮 I1）。
        if (id in modelPanelIds && args != null && inStack == null) {
            when (val last = lastModelPanel[id]) {
                is OverlayModel.Confirm -> return modelPanelEvent(last, args)
                is OverlayModel.Form -> {
                    val parsed = OverlayPanels.parseForm(args, OverlayPanels.fieldKeys(last))
                    if (parsed.isNotEmpty()) return Triple(id, "submit", OverlayPanels.formJson(parsed))
                }
                else -> Unit
            }
        }
        if (top != null && top.id in modelPanelIds && id !in catalogIds && !id.startsWith(OverlayPanels.PREFIX)) {
            val value = args ?: when (top) {
                is OverlayModel.SelectList -> top.selectedKey
                is OverlayModel.Form -> OverlayPanels.formJson(top.values)
                else -> null
            }
            return Triple(top.id, id, value)
        }
        return null
    }

    private fun modelPanelEvent(panel: OverlayModel, args: String): Triple<String, String, String?> = when (panel) {
        is OverlayModel.Form -> Triple(panel.id, "submit", OverlayPanels.formJson(OverlayPanels.parseForm(args, OverlayPanels.fieldKeys(panel)).ifEmpty { panel.values }))
        is OverlayModel.Confirm -> Triple(panel.id, if (args == "confirm") "confirm" else "cancel", null)
        else -> if (args.startsWith("star:")) Triple(panel.id, "star", args.removePrefix("star:")) else Triple(panel.id, args, null)
    }

    private fun landModelPanel(sourceId: String, key: String, value: String?, r: ActionResult): ActionResult {
        val source = lastModelPanel[sourceId]
        // 确认框不论结果如何都已用完：先移除（终端形态它还在栈里；UI 形态已被 CalmSheet 移除，这里是空操作）。
        if (source is OverlayModel.Confirm && !(r is ActionResult.Overlay && r.model.id == sourceId)) {
            val parent = confirmParent.remove(sourceId)
            val closeParent = r == ActionResult.Done && key == "confirm" && parent != null
            overlay.update { list -> list.filterNot { it.id == sourceId || (closeParent && it.id == parent) } }
        }
        when (r) {
            is ActionResult.Overlay -> { modelPanelIds += r.model.id; show(r.model) }
            ActionResult.Done -> overlay.remove(sourceId)
            is ActionResult.LocalBlock -> addLocalBlock(sourceId, r.title, r.text)
            // 提示不关面板：失败时已填的值不丢。终端形态提交前已弹栈的表单按提交值压回（审查第 1 轮 N2；密钥字段不回填）。
            is ActionResult.Toast -> if (key == "submit" && source is OverlayModel.Form && overlay.items.value.none { it.id == sourceId }) {
                val secret = source.fields.filter { it.secret }.map { it.key }.toSet()
                val submitted = OverlayPanels.parseForm(value, OverlayPanels.fieldKeys(source)) - secret
                show(source.copy(values = source.values + submitted))
            }
        }
        return r
    }

    private suspend fun panelEvent(impl: PiActionsImpl, panelId: String, args: String?): ActionResult {
        val panel = overlay.items.value.lastOrNull { it.id == panelId }
        val form = panel as? OverlayModel.Form ?: lastOverlayForm[panelId]
        val res = impl.onPanel(panelId, args, panel ?: form)
        val closeIds = res.close.toSet()
        val closeRefs = res.closeInstances
        if (closeIds.isNotEmpty() || closeRefs.isNotEmpty()) {
            overlay.update { list -> list.filterNot { m -> m.id in closeIds || closeRefs.any { it === m } } }
        }
        res.replace?.let { m -> replaceById(m) }
        when (val r = res.result) {
            is ActionResult.Overlay -> show(r.model)
            is ActionResult.LocalBlock -> addLocalBlock(panelId, r.title, r.text)
            // 失败（面板没被关）且表单已被形态先弹栈（终端形态）：按提交值把表单压回，已填内容不丢（审查第 1 轮 N2）。
            is ActionResult.Toast -> if (panel == null && form != null && panelId !in closeIds) {
                show(form.copy(values = form.values + OverlayPanels.parseForm(args, OverlayPanels.fieldKeys(form))))
            }
            else -> Unit
        }
        return res.result
    }

    /** 扩展阻塞对话框与 editor-text-offer：等用户应答，任何面板替换 / 回退都不能把它们弹掉或压到下面（审查第 1 轮 I2）。 */
    private fun isPinned(m: OverlayModel) = m is OverlayModel.ExtDialog || m.id == EDITOR_OFFER_ID

    /**
     * 压一个面板（单次原子 update）：
     * - 同 id 已在栈里（栈顶或更深处）→ 回到它并替换，它上面的普通面板移除；
     * - 否则压在普通面板之上；
     * - 扩展对话框 / editor-text-offer 始终保留并留在最上面，不被移除、不被应答。
     */
    private fun show(m: OverlayModel) {
        overlay.update { list -> placed(list, m) }
        if (m.id in modelPanelIds) {
            lastModelPanel[m.id] = m
            if (m is OverlayModel.Confirm && m.id !in confirmParent) {
                overlay.items.value.lastOrNull { it.id != m.id && !isPinned(it) && it.id in modelPanelIds }?.let { confirmParent[m.id] = it.id }
            }
        }
        if (m is OverlayModel.Form && m.id.startsWith(OverlayPanels.PREFIX)) lastOverlayForm[m.id] = m
    }

    private fun placed(list: List<OverlayModel>, m: OverlayModel): List<OverlayModel> {
        if (isPinned(m)) return list.filterNot { it.id == m.id } + m
        val i = list.indexOfLast { it.id == m.id }
        val below = if (i >= 0) list.subList(0, i) else list.filterNot(::isPinned)
        val pinned = (if (i >= 0) list.subList(i + 1, list.size) else list).filter(::isPinned)
        return below + m + pinned
    }

    /** 就地替换栈里同 id 的面板（单次原子 update，不弹不压）；不在栈里则不动。 */
    private fun replaceById(m: OverlayModel) {
        overlay.update { list -> list.map { if (it.id == m.id) m else it } }
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

    /** 按端口列表同步扩展对话框（单次原子 update：移除已应答的、追加新的，不碰其他面板）。 */
    private fun syncDialogs(list: List<ExtUiRequest>) {
        val wanted = list.associateBy { "ext:${it.id}" }
        val now = System.currentTimeMillis()
        overlay.update { items ->
            val kept = items.filterNot { it is OverlayModel.ExtDialog && it.id !in wanted }
            kept + wanted.filterKeys { id -> kept.none { it.id == id } }.map { (id, r) ->
                OverlayModel.ExtDialog(id = id, title = r.title ?: r.method, request = r, shownAtMillis = now)
            }
        }
    }

    private fun syncEditorOffer(text: String?) {
        if (text == null) { overlay.remove(EDITOR_OFFER_ID); return }
        val offer = OverlayModel.Confirm(EDITOR_OFFER_ID, "扩展要替换输入框", "输入框里已有内容。替换成扩展给的文字吗？\n\n$text", confirmLabel = "替换")
        overlay.update { items -> if (items.any { it.id == EDITOR_OFFER_ID }) items else items + offer }
    }

    companion object {
        const val EDITOR_OFFER_ID = "overlay:editor-text-offer"
        /** 结果面板来自 ModelPanels 的动作。 */
        val MODEL_PANEL_ACTIONS = setOf("model.select", "model.scoped", "auth.login", "auth.logout", "provider.custom")
    }
}
