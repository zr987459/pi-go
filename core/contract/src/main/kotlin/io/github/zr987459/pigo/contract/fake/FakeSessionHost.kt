package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.SessionCatalog
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.LocalBlock
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.OverlayStack
import io.github.zr987459.pigo.contract.session.ScrollAnchor
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 开发期与测试用的 [SessionHost]（DESIGN-r3 §8.3 第 1 步）。r3/calm、r3/term 的界面与仪器测试、
 * r3/app 的 A 档切换测试都用它。
 *
 * - 引擎是 [FakeScriptedEngine]：命令日志（[commands]）、start / restart 计数、可编程流式（[program] / [startStream]）。
 * - [form] 只是一个 StateFlow，改它**不碰引擎**（命令日志不变）。
 * - 动作表默认是 [FakePiActions]（占位，只返回结果）；[runAction] 按契约把面板压进 [overlay] **一次**。
 * - [extras]、[settings]、[sessions] 分别是 [FakeEngineExtras]、[FakePiSettings]、[FakeSessionCatalog]。
 */
class FakeSessionHost(
    scope: CoroutineScope,
    val fakeEngine: FakeScriptedEngine = FakeScriptedEngine(scope),
    initialForm: UiForm = UiForm.CALM,
    autoStart: Boolean = true,
) : SessionHost {

    override val engine: EngineClient get() = fakeEngine
    override val chat: StateFlow<ChatState> = fakeEngine.chat
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

    val fakeActions: FakePiActions = FakePiActions(fakeEngine)
    override val actions: PiActions get() = fakeActions

    val fakeExtras: FakeEngineExtras = FakeEngineExtras()
    override val extras: EngineExtras get() = fakeExtras

    val fakeSettings: FakePiSettings = FakePiSettings()
    override val settings: PiSettings get() = fakeSettings

    val fakeSessions: FakeSessionCatalog = FakeSessionCatalog(fakeEngine)
    override val sessions: SessionCatalog get() = fakeSessions

    override suspend fun runAction(id: String, args: String?): ActionResult {
        val r = actions.run(id, args)
        when (r) {
            is ActionResult.Overlay -> overlay.push(r.model)
            is ActionResult.LocalBlock -> _localBlocks.update { it + LocalBlock(key = "local:$id:${it.size}", title = r.title, text = r.text) }
            else -> Unit
        }
        return r
    }

    /** 假 PID；引擎每次 start / restart 都不变（真实实现经 ProcessTree 读取）。 */
    var fakePid: Int? = 4242

    init {
        if (autoStart) fakeEngine.start()
    }

    override fun debugEnginePid(): Int? = if (fakeEngine.startCount > 0) fakePid else null

    // ---- 测试便利 API ----

    val commands: StateFlow<List<RpcCall>> get() = fakeEngine.commands

    fun program(script: StreamScript) = fakeEngine.program(script)

    fun clearCommands() = fakeEngine.clearCommands()

    fun startStream(script: StreamScript, userText: String? = null): Job = fakeEngine.startStream(script, userText)

    /**
     * 发送当前草稿，不丢内容：
     * - `!` / `!!` 模式记一条 `bash`（`command`、`excludeFromContext`），不进 prompt；
     * - `@` 引用按 pi 交互模式的写法拼成 `@路径` 放在文字前；图片个数记进命令的 `images` 参数；
     * - 没有可发送内容（[DraftState.hasSendableContent] 为假）时返回 `Err` 且什么都不记；
     * - 只把非空白文字记入历史；发送后清空草稿，但保留「已撤回」芯片。
     */
    suspend fun sendDraft(mode: SendMode = SendMode.STEER): Outcome<Disposition> {
        val d = draft.value
        if (!d.hasSendableContent) return Outcome.Err(ErrCode.RPC_REJECTED, "草稿为空")
        if (d.text.isNotBlank()) _history.update { it + d.text }
        draft.value = DraftState(recalled = d.recalled)
        if (d.bashMode != BashMode.NONE) {
            fakeEngine.record("bash", mapOf("command" to d.text, "excludeFromContext" to (d.bashMode == BashMode.EXCLUDE).toString()))
            return Outcome.Ok(Disposition.STARTED)
        }
        val message = (d.fileRefs.map { "@$it" } + listOfNotNull(d.text.takeIf { it.isNotEmpty() })).joinToString(" ")
        return fakeEngine.sendWithImages(message, d.images.size, mode)
    }

    /** 模拟扩展阻塞对话框：发出请求并压进 overlay 栈。 */
    fun showExtDialog(request: ExtUiRequest, nowMillis: Long = 0L) {
        fakeEngine.pushUiRequest(request)
        overlay.push(OverlayModel.ExtDialog(id = "ext:${request.id}", title = request.title ?: request.method, request = request, shownAtMillis = nowMillis))
    }

    fun addLocalBlock(block: LocalBlock) = _localBlocks.update { it + block }

    fun setEphemeral(value: Boolean) { _ephemeral.value = value }
}
