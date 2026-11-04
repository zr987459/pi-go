package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 记录命令的 [SessionOps] 替身：写进 Fake 引擎的命令日志，类型名与 pi RPC 一致。 */
class RecordingOps(private val engine: FakeScriptedEngine) : SessionOps {
    override suspend fun abortRetry(): Outcome<Unit> { engine.record("abort_retry"); return Outcome.Ok(Unit) }
    override suspend fun dequeueAll(): Outcome<QueueSnapshot> { engine.record("dequeue"); return Outcome.Ok(QueueSnapshot.EMPTY) }
    override suspend fun stopEngine(): Outcome<Unit> { engine.record("stop_engine"); return Outcome.Ok(Unit) }
    override suspend fun setLabel(entryId: String, label: String?): Outcome<Unit> { engine.record("label", mapOf("entryId" to entryId)); return Outcome.Ok(Unit) }
    override suspend fun reload(): Outcome<Unit> { engine.record("reload"); return Outcome.Ok(Unit) }
    val guestCommands = mutableListOf<String>()
    var guestResult: (String) -> GuestOutput = { GuestOutput(0, "ok") }
    override suspend fun treeNavigate(entryId: String, summarize: Boolean): Outcome<TreeNav> {
        engine.record("tree_nav", mapOf("entryId" to entryId, "summarize" to summarize.toString()))
        return Outcome.Ok(TreeNav(leafId = entryId, editorText = navEditorText))
    }
    var navEditorText: String? = null
    override suspend fun runGuest(command: String, timeoutMs: Long): Outcome<GuestOutput> { guestCommands += command; return Outcome.Ok(guestResult(command)) }
    val reports = mutableListOf<Pair<String, String>>()
    override suspend fun writeReport(fileName: String, text: String): Outcome<String> { reports += fileName to text; return Outcome.Ok("/cache/$fileName") }
    override suspend fun newSessionInDir(dir: String): Outcome<Unit> { engine.record("new_session_in_dir", mapOf("dir" to dir)); return Outcome.Ok(Unit) }
}

/**
 * 测试用 [SessionChatPort]：唯一订阅 `uiRequests` 的一方（宿主不再订阅），发送直接走引擎。
 * 真实端口（ChatController + DraftStore + Outbox）的切换测试在 feature/chat 的 ChatControllerPortFormSwitchTest。
 */
class TestChatPort(private val engine: io.github.zr987459.pigo.contract.EngineClient, scope: CoroutineScope) : SessionChatPort {
    override val draft = kotlinx.coroutines.flow.MutableStateFlow(io.github.zr987459.pigo.contract.session.DraftState.EMPTY)
    private val _dialogs = kotlinx.coroutines.flow.MutableStateFlow<List<io.github.zr987459.pigo.contract.ExtUiRequest>>(emptyList())
    override val dialogs: kotlinx.coroutines.flow.StateFlow<List<io.github.zr987459.pigo.contract.ExtUiRequest>> = _dialogs
    private val _offer = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val editorTextOffer: kotlinx.coroutines.flow.StateFlow<String?> = _offer
    override val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val answers = mutableListOf<Triple<String, String?, Boolean>>()

    init {
        scope.launch {
            engine.uiRequests.collect { r ->
                when {
                    r.needsResponse -> _dialogs.value = _dialogs.value + r
                    r.method == "set_editor_text" -> {
                        val t = (r.raw["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: r.prefill ?: return@collect
                        if (draft.value.text.isBlank()) draft.value = draft.value.withText(t) else _offer.value = t
                    }
                }
            }
        }
    }

    override suspend fun send(mode: io.github.zr987459.pigo.contract.SendMode): Outcome<io.github.zr987459.pigo.contract.Disposition> {
        val d = draft.value
        draft.value = io.github.zr987459.pigo.contract.session.DraftState(recalled = d.recalled)
        return engine.send(AtFileExpansion.compose(d.text, d.fileRefs), mode)
    }

    override fun answerDialog(requestId: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
        _dialogs.value = _dialogs.value.filterNot { it.id == requestId }
        answers += Triple(requestId, value, cancelled)
    }

    override fun resolveEditorTextOffer(accept: Boolean) {
        val t = _offer.value ?: return
        _offer.value = null
        if (accept) draft.value = draft.value.withText(t)
    }
}

fun testHost(scope: CoroutineScope, engine: FakeScriptedEngine = FakeScriptedEngine(scope)): Pair<SessionHostImpl, FakeScriptedEngine> {
    engine.start()
    val host = SessionHostImpl(
        engine = engine,
        extras = FakeEngineExtras(),
        scope = scope,
        settings = FakePiSettings(),
        sessions = FakeSessionCatalog(engine),
        chatPort = TestChatPort(engine, scope),
        actionsFactory = { h -> PiActionsImpl(h, RecordingOps(engine)) },
    )
    return host to engine
}
