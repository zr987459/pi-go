package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OutgoingStatus
import io.github.zr987459.pigo.core.session.DraftCodec
import io.github.zr987459.pigo.core.session.SessionHostImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查第 2 轮 R2-I1 ~ R2-I4 的行为测试：全部经 [SessionHostImpl] + 真实的 [ChatControllerPort] / [ChatController]
 * （终端形态只依赖 SessionHost，所以这里断言的就是终端形态能看到的东西）。
 * 引擎是 [FakeScriptedEngine]（会真的流式输出、入队、停止），只在需要时包一层让 send 失败或换成「不支持看图」的模型。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerReview2Test {

    private class MemDrafts : DraftStore {
        val map = LinkedHashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, draft: String) { map[key] = draft }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private object NoActions : PiActions {
        override val all = PiActionCatalog.all
        override suspend fun run(id: String, args: String?): ActionResult = ActionResult.Done
    }

    /** 包住 Fake 引擎：[failNext] 非空时下一次 send 返回它；[textOnlyModel] 为真时当前模型声明只收文字。 */
    private class TestEngine(val inner: FakeScriptedEngine, scope: CoroutineScope) : EngineClient by inner {
        var failNext: Outcome.Err? = null
        val textOnlyModel = MutableStateFlow(false)
        override val chat: StateFlow<ChatState> = combine(inner.chat, textOnlyModel) { c, textOnly ->
            if (!textOnly) c else c.copy(model = (c.model ?: ModelInfo("fake", "fake-model")).copy(raw = JsonObject(mapOf("input" to JsonArray(listOf(JsonPrimitive("text")))))))
        }.stateIn(scope, SharingStarted.Eagerly, inner.chat.value)

        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
            failNext?.let { failNext = null; return it }
            return inner.send(text, mode)
        }
    }

    /** 包住 Fake 扩展：[failNext] 非空时下一次带图 send 返回它。 */
    private class TestExtras(val inner: FakeEngineExtras = FakeEngineExtras()) : EngineExtras by inner {
        var failNext: Outcome.Err? = null
        override suspend fun send(text: String, images: List<ImageInput>, mode: SendMode): Outcome<Disposition> {
            failNext?.let { failNext = null; return it }
            return inner.send(text, images, mode)
        }
    }

    /** 终端形态 Esc 走的真实动作表需要的 SessionOps：本测试只用到 abort，其余不该被调用。 */
    private object NoOps : io.github.zr987459.pigo.core.session.SessionOps {
        private fun <T> no(): Outcome<T> = Outcome.Err(ErrCode.UNKNOWN, "not used")
        override suspend fun abortRetry() = no<Unit>()
        override suspend fun dequeueAll() = no<io.github.zr987459.pigo.contract.QueueSnapshot>()
        override suspend fun stopEngine() = no<Unit>()
        override suspend fun setLabel(entryId: String, label: String?) = no<Unit>()
        override suspend fun reload() = no<Unit>()
        override suspend fun treeNavigate(entryId: String, summarize: Boolean) = no<io.github.zr987459.pigo.core.session.TreeNav>()
        override suspend fun runGuest(command: String, timeoutMs: Long) = no<io.github.zr987459.pigo.core.session.GuestOutput>()
        override suspend fun writeReport(fileName: String, text: String) = no<String>()
        override suspend fun newSessionInDir(dir: String) = no<Unit>()
    }

    private class Rig(val host: SessionHostImpl, val controller: ChatController, val engine: TestEngine, val extras: TestExtras, val drafts: MemDrafts)

    private fun TestScope.rig(drafts: MemDrafts = MemDrafts(), withExtras: Boolean = true, realActions: Boolean = false): Rig {
        val fake = FakeScriptedEngine(backgroundScope).also { it.start() }
        val engine = TestEngine(fake, backgroundScope)
        val extras = TestExtras()
        val controller = ChatController(engine, backgroundScope, extras = if (withExtras) extras else null, drafts = drafts)
        val loader: suspend (List<DraftImage>) -> List<ImageInput> = { list -> list.map { ImageInput("image/png", "b64:" + it.uri) } }
        val host = SessionHostImpl(
            engine = engine, extras = extras, scope = backgroundScope, settings = FakePiSettings(),
            sessions = FakeSessionCatalog(fake), chatPort = ChatControllerPort(controller, loader),
            actionsFactory = { h -> if (realActions) io.github.zr987459.pigo.core.session.PiActionsImpl(h, NoOps) else NoActions },
        )
        testScheduler.runCurrent()
        return Rig(host, controller, engine, extras, drafts)
    }

    private fun TestScope.settle() { testScheduler.advanceTimeBy(50); testScheduler.runCurrent() }

    private fun prompts(engine: TestEngine) = engine.inner.commands.value.filter { it.type in setOf("prompt", "steer", "follow_up") }.map { it.args["message"] }

    // ------------------------------------------------------------ R2-I1 外发状态经 SessionHost 可见

    @Test
    fun asyncSendFailureIsVisibleThroughSessionHostAndRetryable() = runTest {
        val r = rig()
        r.engine.failNext = Outcome.Err(ErrCode.NETWORK, "断网")
        r.host.draft.value = DraftState(text = "帮我看看这个报错")
        val res = r.host.sendDraft()
        settle()
        assertTrue("交给 Outbox 即接受：$res", res is Outcome.Ok)

        val failed = r.host.outgoing.value.single()
        assertEquals("帮我看看这个报错", failed.text)
        assertEquals(OutgoingStatus.FAILED, failed.status)
        assertTrue("失败可重试", failed.retryable)
        assertNotNull("带失败原因", failed.error)
        assertTrue("失败发了一条提示（终端形态经 toasts 看到）：${r.host.toasts.value}", r.host.toasts.value.any { "没有发出" in it })
        assertTrue("引擎没收到", prompts(r.engine).isEmpty())

        // 终端形态点「重试」：同一条消息真的发出去，失败条目消失。
        r.host.retryOutgoing(failed.id)
        settle()
        assertEquals(listOf("帮我看看这个报错"), prompts(r.engine))
        assertTrue("不再是失败：${r.host.outgoing.value}", r.host.outgoing.value.none { it.status == OutgoingStatus.FAILED })
    }

    @Test
    fun failedMessageGoesBackToSharedDraftWithoutOverwritingNewInput() = runTest {
        val r = rig()
        r.engine.failNext = Outcome.Err(ErrCode.PROVIDER, "401")
        r.host.draft.value = DraftState(text = "第一条")
        r.host.sendDraft(); settle()
        val id = r.host.outgoing.value.single().id
        assertTrue("没配好模型类失败提示去设置", r.host.outgoing.value.single().needsSettings)

        r.host.draft.value = DraftState(text = "我又写了新的")
        r.host.editOutgoing(id); settle()
        assertEquals("新输入不被覆盖", "我又写了新的", r.host.draft.value.text)
        assertEquals("失败的原文进「已撤回」芯片（共享草稿）", listOf("第一条"), r.host.draft.value.recalled)
        assertTrue("从外发列表移除", r.host.outgoing.value.isEmpty())

        // 草稿为空时直接放回输入框。
        r.engine.failNext = Outcome.Err(ErrCode.NETWORK, "断网")
        r.host.draft.value = DraftState(text = "第二条")
        r.host.sendDraft(); settle()
        val id2 = r.host.outgoing.value.single().id
        r.host.editOutgoing(id2); settle()
        assertEquals("第二条", r.host.draft.value.text)

        // 丢弃：从列表移除，草稿不变。
        r.engine.failNext = Outcome.Err(ErrCode.NETWORK, "断网")
        r.host.draft.value = DraftState(text = "第三条")
        r.host.sendDraft(); settle()
        r.host.discardOutgoing(r.host.outgoing.value.single().id); settle()
        assertTrue(r.host.outgoing.value.isEmpty())
    }

    @Test
    fun failedImageSendKeepsImagesAndEditPutsUrisBackIntoDraft() = runTest {
        val r = rig()
        r.extras.failNext = Outcome.Err(ErrCode.NETWORK, "断网")
        val img = DraftImage("content://pics/1", "image/png")
        r.host.draft.value = DraftState(text = "看图", images = listOf(img))
        assertTrue(r.host.sendDraft() is Outcome.Ok)
        settle()
        val failed = r.host.outgoing.value.single()
        assertEquals(OutgoingStatus.FAILED, failed.status)
        assertEquals(1, failed.imageCount)
        assertTrue("已交出的图片从草稿清掉", r.host.draft.value.images.isEmpty())

        r.host.editOutgoing(failed.id); settle()
        assertEquals("看图", r.host.draft.value.text)
        assertEquals("图片 URI 回到共享草稿", listOf(img), r.host.draft.value.images)
        assertTrue("控制器附件里不留副本（不会重复）", r.controller.state.value.attachments.isEmpty())
    }

    @Test
    fun queuedMessagesAreVisibleThroughSessionHost() = runTest {
        val r = rig()
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "先问一句")
        settle()
        r.host.draft.value = DraftState(text = "插一句话")
        assertTrue(r.host.sendDraft(SendMode.STEER) is Outcome.Ok)
        settle()
        val q = r.host.outgoing.value
        assertTrue("「已排队」经 SessionHost 可见：$q", q.any { it.text == "插一句话" && it.status == OutgoingStatus.QUEUED })
        assertEquals("同一条只出现一次", 1, q.count { it.text == "插一句话" })
    }

    // ------------------------------------------------------------ R2-I2 停止撤回 = 共享草稿的「已撤回」芯片

    @Test
    fun stopWithDraftPutsWithdrawnIntoSharedRecalledPersistsAndCanBeInserted() = runTest {
        val drafts = MemDrafts()
        val r = rig(drafts)
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "先问一句")
        settle()
        r.host.draft.value = DraftState(text = "排队的那条")
        r.host.sendDraft(SendMode.FOLLOW_UP); settle()
        r.host.draft.value = DraftState(text = "我正在写")

        // 终端形态的 Esc：经端口停止。
        val stopped = r.host.chatPort.stop()
        settle()
        assertTrue("停止成功：$stopped", stopped is Outcome.Ok)
        assertEquals("草稿不被覆盖", "我正在写", r.host.draft.value.text)
        assertEquals("撤回的文字在共享草稿的芯片里（终端形态看得到）", listOf("排队的那条"), r.host.draft.value.recalled)
        assertEquals("UI 形态的芯片是同一份", listOf("排队的那条"), r.controller.state.value.composer.withdrawn)

        // 随草稿持久化：进程被杀后重建，芯片还在。
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        val key = r.engine.chat.value.sessionFile!!
        assertEquals(listOf("排队的那条"), DraftCodec.decode(drafts.map[key]).recalled)
        val r2 = rig(drafts)
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        assertEquals(listOf("排队的那条"), r2.host.draft.value.recalled)
        assertEquals("我正在写", r2.host.draft.value.text)

        // 插回输入框（UI 形态的芯片按钮）后，终端形态看到的草稿同步变化。
        r2.controller.insertWithdrawn(); testScheduler.runCurrent()
        assertEquals("我正在写\n排队的那条", r2.host.draft.value.text)
        assertTrue(r2.host.draft.value.recalled.isEmpty())
    }

    @Test
    fun escAbortActionThroughSessionHostRecallsIntoSharedDraft() = runTest {
        val r = rig(realActions = true)
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "先问一句")
        settle()
        r.host.draft.value = DraftState(text = "排队A")
        r.host.sendDraft(SendMode.FOLLOW_UP); settle()
        r.host.draft.value = DraftState(text = "草稿B")
        val res = r.host.runAction("abort")
        settle()
        assertEquals(ActionResult.Done, res)
        assertTrue("经 clear_queue + abort 停止", r.engine.inner.commands.value.map { it.type }.containsAll(listOf("clear_queue", "abort")))
        assertEquals("草稿B", r.host.draft.value.text)
        assertEquals("abort 动作与 UI 停止键是同一条路径：撤回进共享芯片", listOf("排队A"), r.host.draft.value.recalled)
        assertEquals(listOf("排队A"), r.controller.state.value.composer.withdrawn)
        assertTrue("排队条目随撤回消失", r.host.outgoing.value.none { it.text == "排队A" })
    }

    @Test
    fun recalledWrittenByAFormShowsUpAsWithdrawnChip() = runTest {
        val r = rig()
        r.host.draft.value = DraftState(text = "草稿", recalled = listOf("形态写的芯片"))
        testScheduler.runCurrent()
        assertEquals(listOf("形态写的芯片"), r.controller.state.value.composer.withdrawn)
        r.controller.discardWithdrawn(); testScheduler.runCurrent()
        assertTrue(r.host.draft.value.recalled.isEmpty())
        assertEquals("草稿", r.host.draft.value.text)
    }

    @Test
    fun uiStopWithEmptyDraftPutsTextBackIntoSharedDraft() = runTest {
        val r = rig()
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "先问一句")
        settle()
        r.host.draft.value = DraftState(text = "排队一")
        r.host.sendDraft(SendMode.FOLLOW_UP); settle()
        r.controller.stop(); settle()
        assertEquals("输入框为空时直接放回", "排队一", r.host.draft.value.text)
        assertTrue(r.host.draft.value.recalled.isEmpty())
    }

    // ------------------------------------------------------------ R2-I3 带图发送被拒

    @Test
    fun imageOnlyDraftRejectedReturnsErrAndKeepsImages() = runTest {
        val r = rig()
        r.engine.textOnlyModel.value = true
        testScheduler.runCurrent()
        val img = DraftImage("content://pics/only", "image/png")
        r.host.draft.value = DraftState(images = listOf(img))
        val res = r.host.sendDraft()
        testScheduler.runCurrent()
        assertTrue("只有图片被拒必须报失败：$res", res is Outcome.Err)
        assertEquals("图片留在草稿里", listOf(img), r.host.draft.value.images)
        assertTrue("控制器附件不残留", r.controller.state.value.attachments.isEmpty())
        assertTrue("没发出任何东西", r.extras.inner.calls.none { it.startsWith("send:") } && prompts(r.engine).isEmpty())
        assertTrue("没有外发条目", r.host.outgoing.value.isEmpty())
    }

    @Test
    fun rejectedImageSendThenResendSendsEachImageOnce() = runTest {
        val r = rig()
        r.engine.textOnlyModel.value = true
        testScheduler.runCurrent()
        val img = DraftImage("content://pics/a", "image/png")
        r.host.draft.value = DraftState(text = "看看这张", images = listOf(img))
        assertTrue(r.host.sendDraft() is Outcome.Err)
        assertEquals("被拒草稿原样", DraftState(text = "看看这张", images = listOf(img)), r.host.draft.value)

        // 换成支持图片的模型后重发：图片只带一次。
        r.engine.textOnlyModel.value = false
        testScheduler.runCurrent()
        assertTrue(r.host.sendDraft() is Outcome.Ok)
        settle()
        assertEquals("只发了一次", 1, r.extras.inner.sentImages.size)
        assertEquals("一次只有一张图", 1, r.extras.inner.sentImages.single().size)
        assertTrue("草稿里的图片已清掉", r.host.draft.value.images.isEmpty() && r.host.draft.value.text.isEmpty())
    }

    @Test
    fun noImageSupportRejectsAndKeepsEverything() = runTest {
        val r = rig(withExtras = false)
        val img = DraftImage("content://pics/b", "image/png")
        val before = DraftState(text = "看图", images = listOf(img), fileRefs = listOf("a.kt"))
        r.host.draft.value = before
        assertTrue(r.host.sendDraft() is Outcome.Err)
        assertEquals(before, r.host.draft.value)
    }

    @Test
    fun sendWhileStoppingIsRejectedAndDraftKept() = runTest {
        val r = rig()
        r.host.draft.value = DraftState(text = "!ls")
        // 停止进行中发送被拒：草稿（含 ! 命令）原样不动。
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "q")
        settle()
        r.controller.stop()
        val res = r.host.sendDraft()
        assertTrue(res is Outcome.Err)
        assertEquals("!ls", r.host.draft.value.text)
    }

    // ------------------------------------------------------------ R2-I4 单一数据源，不吞字

    private fun TestScope.alternatingWrites(r: Rig, rounds: Int, settleEvery: Int) {
        var expected = ""
        for (i in 0 until rounds) {
            if (i % 2 == 0) {
                // 终端形态：直接写 SessionHost.draft。
                r.host.draft.update { it.withText(it.text + "a") }
                expected += "a"
            } else {
                // UI 形态旧输入框：按它自己看到的文字（可能还没合成）打一个字。
                r.controller.setDraft(r.controller.composerDraft + "b")
                expected += "b"
            }
            if (settleEvery > 0 && i % settleEvery == 0) testScheduler.runCurrent()
            assertEquals("第 $i 次写入后立刻读到最新文字", expected, r.host.draft.value.text)
        }
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals("最终等于最后一次输入", expected, r.host.draft.value.text)
        assertEquals("输入框派生值一致", expected, r.controller.state.value.composer.draft)
        assertEquals("落盘的也是最新值", expected, DraftCodec.decode(r.drafts.map[r.engine.chat.value.sessionFile!!]).text)
    }

    @Test
    fun fastAlternatingInputNeverRollsBackStandardDispatcher() = runTest {
        val r = rig()
        alternatingWrites(r, rounds = 400, settleEvery = 7)
        alternatingWrites(rig(), rounds = 200, settleEvery = 0)
    }

    @Test
    fun fastAlternatingInputNeverRollsBackUnconfinedDispatcher() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        alternatingWrites(r, rounds = 400, settleEvery = 1)
    }

    @Test
    fun programmaticEditDuringTypingKeepsEveryCharacterAndBumpsRevisionOnlyThen() = runTest {
        val r = rig()
        r.engine.inner.startStream(StreamScript(List(200) { "字" }, intervalMs = 50), userText = "q")
        settle()
        r.host.draft.value = DraftState(text = "排队")
        r.host.sendDraft(SendMode.FOLLOW_UP); settle()

        r.controller.setDraft("我")
        testScheduler.runCurrent()
        val rev0 = r.controller.state.value.composer.revision
        r.controller.setDraft("我在")
        r.controller.setDraft("我在打")
        testScheduler.runCurrent()
        assertEquals("打字不让 revision 变化（旧输入框不被回写打断）", rev0, r.controller.state.value.composer.revision)

        r.controller.stop()
        r.controller.setDraft("我在打字") // 停止进行中继续打字
        settle()
        assertEquals("停止期间的输入不丢", "我在打字", r.host.draft.value.text)
        assertEquals(listOf("排队"), r.host.draft.value.recalled)

        r.controller.insertWithdrawn(); testScheduler.runCurrent()
        assertEquals("我在打字\n排队", r.host.draft.value.text)
        assertTrue("程序改写才让 revision 加一", r.controller.state.value.composer.revision > rev0)
        assertEquals("光标在末尾", "我在打字\n排队".length, r.host.draft.value.safeSelectionStart)
    }

    @Test
    fun formSelectionIsNotMovedBySync() = runTest {
        val r = rig()
        r.host.draft.value = DraftState(text = "hello world", selectionStart = 5, selectionEnd = 5)
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals("没有同步协程改写光标", 5, r.host.draft.value.selectionStart)
        assertFalse(r.host.draft.value.text.isEmpty())
    }
}
