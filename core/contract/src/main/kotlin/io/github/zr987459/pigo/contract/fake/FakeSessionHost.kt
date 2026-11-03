package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.actions.PiActions
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
 * - 动作表默认是 [FakePiActions]（占位），结果里的面板自动压进 [overlay]。
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

    val fakeActions: FakePiActions = FakePiActions(fakeEngine, onOverlay = { overlay.push(it) })
    override val actions: PiActions get() = fakeActions

    /** 假 PID；引擎每次 start / restart 都不变（真实实现经 ProcessTree 读取）。 */
    var fakePid: Int? = 4242

    init {
        if (autoStart) fakeEngine.start()
    }

    override fun debugEnginePid(): Int? = if (fakeEngine.startCount > 0) fakePid else null

    // ---- 测试便利 API ----

    val commands: StateFlow<List<RpcCall>> get() = fakeEngine.commands

    fun program(script: StreamScript) = fakeEngine.program(script)

    fun startStream(script: StreamScript, userText: String? = null): Job = fakeEngine.startStream(script, userText)

    /** 发送当前草稿（记 prompt / steer / follow_up），清空草稿并记入历史。 */
    suspend fun sendDraft(mode: SendMode = SendMode.STEER): Outcome<Disposition> {
        val text = draft.value.text
        _history.update { it + text }
        draft.value = DraftState.EMPTY
        return fakeEngine.send(text, mode)
    }

    /** 模拟扩展阻塞对话框：发出请求并压进 overlay 栈。 */
    fun showExtDialog(request: ExtUiRequest, nowMillis: Long = 0L) {
        fakeEngine.pushUiRequest(request)
        overlay.push(OverlayModel.ExtDialog(id = "ext:${request.id}", title = request.title ?: request.method, request = request, shownAtMillis = nowMillis))
    }

    fun addLocalBlock(block: LocalBlock) = _localBlocks.update { it + block }

    fun setEphemeral(value: Boolean) { _ephemeral.value = value }
}
