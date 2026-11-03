package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ExtUiAnswer
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * ChatController + Fake 引擎回放（JVM，虚拟时间）。覆盖 S6、Q1–Q4 的界面逻辑与扩展对话框应答路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    /** 记录 send/answerUi 调用；可让 send 失败。 */
    private class RecordingEngine(val fake: FakeEngineClient) : EngineClient by fake {
        val sends = mutableListOf<Pair<String, SendMode>>()
        val answers = mutableListOf<Triple<String, String?, Boolean>>()
        var failNext: Outcome.Err? = null
        var stopDelayMs = 0L
        var sendDelayMs = 0L

        override suspend fun stop(): Outcome<io.github.zr987459.pigo.contract.QueueSnapshot> {
            if (stopDelayMs > 0) kotlinx.coroutines.delay(stopDelayMs)
            return fake.stop()
        }

        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
            sends += text to mode
            if (sendDelayMs > 0) kotlinx.coroutines.delay(sendDelayMs)
            failNext?.let {
                failNext = null
                return it
            }
            return fake.send(text, mode)
        }

        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
            answers += Triple(id, value ?: confirmed?.toString(), cancelled)
            fake.answerUi(id, value, confirmed, cancelled)
        }
    }

    /** 状态可由测试直接设置的引擎（模拟重启进入 Starting）。 */
    private class StatusEngine(val fake: FakeEngineClient) : EngineClient by fake {
        val st = kotlinx.coroutines.flow.MutableStateFlow(fake.status.value)
        override val status: kotlinx.coroutines.flow.StateFlow<io.github.zr987459.pigo.contract.EngineStatus> get() = st
        val answers = mutableListOf<String>()
        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {
            answers += id
        }
    }

    /** 状态可控、可让 send 延迟/失败的引擎：用来模拟「发送中引擎崩溃」「排队中引擎重启」。 */
    private class RestartEngine(val fake: FakeEngineClient) : EngineClient by fake {
        val st = kotlinx.coroutines.flow.MutableStateFlow(fake.status.value)
        override val status: kotlinx.coroutines.flow.StateFlow<EngineStatus> get() = st
        val sends = mutableListOf<Pair<String, SendMode>>()
        var sendDelayMs = 0L
        var failNext: Outcome.Err? = null

        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
            sends += text to mode
            if (sendDelayMs > 0) kotlinx.coroutines.delay(sendDelayMs)
            failNext?.let {
                failNext = null
                return it
            }
            return fake.send(text, mode)
        }

        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {}
    }

    private class RestartEnv(val engine: RestartEngine, val controller: ChatController)

    private fun TestScope.restartEnv(): RestartEnv {
        val fake = FakeEngineClient(backgroundScope, stepDelayMs = 100)
        val engine = RestartEngine(fake)
        val c = ChatController(engine, backgroundScope, ChatConfig(stopSlowHintMs = 20_000))
        runCurrent()
        return RestartEnv(engine, c)
    }

    /** 跑完一整回合（内置夹具），让夹具自带的排队消息落定、队列清空。 */
    private fun TestScope.finishOneTurn(c: ChatController) {
        c.setDraft("列出当前目录并读 README")
        c.submit()
        advanceTimeBy(120_000)
        runCurrent()
    }

    /** 开一轮并推进到「生成中」。 */
    private fun TestScope.goRunning(c: ChatController, text: String = "检查一下代码") {
        c.setDraft(text)
        c.submit()
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertTrue("应处于生成中", c.state.value.chat.running)
    }

    private class Env(val engine: RecordingEngine, val controller: ChatController)

    private fun TestScope.env(): Env {
        val fake = FakeEngineClient(backgroundScope, stepDelayMs = 100)
        val engine = RecordingEngine(fake)
        val c = ChatController(engine, backgroundScope, ChatConfig(stopSlowHintMs = 20_000))
        runCurrent()
        return Env(engine, c)
    }

    private fun TestScope.drain() {
        advanceTimeBy(120_000)
        runCurrent()
    }

    private fun Env.say(text: String, mode: SendMode? = null) {
        controller.setDraft(text)
        controller.submit(mode)
    }

    private val Env.users get() = controller.state.value.chat.items.filterIsInstance<ChatItem.User>().map { it.text }

    /** 先跑完第一轮（内置演示夹具），之后的回合都是简短回放，便于观察排队。 */
    private fun TestScope.warmUp(e: Env) {
        e.say("列出当前目录并读 README")
        drain()
    }

    /** 开始一轮并推进到「生成中」。 */
    private fun TestScope.startRunning(e: Env, text: String = "检查一下代码") {
        e.say(text)
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertTrue("应处于生成中", e.controller.state.value.chat.running)
    }

    @Test
    fun idleSendShowsOptimisticBubbleThenEngineMessageOnce() = runTest {
        val e = env()
        e.controller.setDraft("你好")
        runCurrent()
        assertEquals(PrimaryAction.SEND, e.controller.state.value.primary)
        e.controller.submit()
        runCurrent()
        // 发送后输入框立即清空，气泡立即出现（发送中/已发送）
        assertEquals("", e.controller.state.value.composer.draft)
        val bubbles = e.controller.state.value.rows.filterIsInstance<ChatRow.User>()
        assertEquals(listOf("你好"), bubbles.map { it.text })
        drain()
        // 第一轮是内置演示夹具，其中自带一条排队消息；这里只关心「你好」只出现一次
        assertEquals(1, e.users.count { it == "你好" })
        assertTrue(e.controller.state.value.rows.filterIsInstance<ChatRow.User>().none { it.localId != null })
        assertEquals(1, e.engine.sends.size)
    }

    @Test
    fun failedSendIsRedWithRetryAndNeverAutoResent() = runTest {
        val e = env()
        e.engine.failNext = Outcome.Err(ErrCode.RPC_REJECTED, "boom")
        e.say("你好")
        runCurrent()
        val failed = e.controller.state.value.rows.filterIsInstance<ChatRow.User>().single()
        assertEquals(Delivery.FAILED, failed.delivery)
        assertEquals("pi 拒绝了这条消息：boom", failed.error)
        drain()
        assertEquals("失败后不得自动重发", 1, e.engine.sends.size)

        e.controller.retry(failed.localId!!)
        runCurrent()
        drain()
        assertEquals(2, e.engine.sends.size)
        assertEquals(1, e.users.count { it == "你好" })
        assertTrue(e.controller.state.value.rows.none { it is ChatRow.User && it.delivery == Delivery.FAILED })
    }

    @Test
    fun editFailedPutsTextBackWithoutOverwritingDraft() = runTest {
        val e = env()
        e.engine.failNext = Outcome.Err(ErrCode.RPC_TIMEOUT, "")
        e.say("第一条")
        runCurrent()
        val id = e.controller.state.value.rows.filterIsInstance<ChatRow.User>().single().localId!!
        e.controller.setDraft("新草稿")
        e.controller.editFailed(id)
        runCurrent()
        assertEquals("新草稿", e.controller.state.value.composer.draft)
        assertEquals(listOf("第一条"), e.controller.state.value.composer.withdrawn)
    }

    @Test
    fun q1_sendWhileRunningQueuesAndIsDeliveredExactlyOnce() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.controller.setDraft("再检查一遍格式")
        runCurrent()
        assertEquals(PrimaryAction.QUEUE, e.controller.state.value.primary)
        e.controller.submit()
        runCurrent()
        val s = e.controller.state.value
        assertEquals(SendMode.FOLLOW_UP, e.engine.sends.last().second)
        assertEquals(listOf("再检查一遍格式"), s.chat.queue.followUp)
        // 排队消息以芯片显示，不再重复显示本地气泡
        assertTrue(s.rows.none { it is ChatRow.User && it.localId != null })
        drain()
        assertEquals(1, e.users.count { it == "再检查一遍格式" })
        assertTrue(e.controller.state.value.chat.queue.isEmpty)
    }

    @Test
    fun q2_longPressSteerGoesToSteeringChip() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.say("先别改测试", SendMode.STEER)
        runCurrent()
        assertEquals(SendMode.STEER, e.engine.sends.last().second)
        assertEquals(listOf("先别改测试"), e.controller.state.value.chat.queue.steering)
        drain()
        assertEquals(1, e.users.count { it == "先别改测试" })
        assertTrue(e.controller.state.value.chat.queue.steering.isEmpty())
    }

    @Test
    fun q3_stopWithEmptyDraftPutsQueuedTextBack() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.say("再检查一遍格式")
        runCurrent()
        assertEquals(PrimaryAction.STOP, e.controller.state.value.primary)
        e.controller.stop()
        runCurrent()
        val s = e.controller.state.value
        assertFalse(s.composer.stopping)
        assertEquals("再检查一遍格式", s.composer.draft)
        assertTrue(s.composer.withdrawn.isEmpty())
        assertTrue(s.chat.queue.isEmpty)
        assertFalse(s.chat.running)
        assertTrue(s.chat.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        // 撤回的消息不会再被发出
        drain()
        assertEquals(0, e.users.count { it == "再检查一遍格式" })
    }

    @Test
    fun stoppingStateAndSlowHint() = runTest {
        val e = env()
        warmUp(e)
        e.engine.stopDelayMs = 25_000
        startRunning(e)
        e.controller.stop()
        runCurrent()
        assertTrue(e.controller.state.value.composer.stopping)
        assertEquals(PrimaryAction.DISABLED, e.controller.state.value.primary)
        assertFalse(e.controller.state.value.composer.stopSlow)
        advanceTimeBy(20_500)
        runCurrent()
        assertTrue("20 秒后提示正在强制结束", e.controller.state.value.composer.stopSlow)
        // 停止期间用户继续打字，结果回来时以当时的草稿为准
        e.controller.setDraft("停止期间写的")
        advanceTimeBy(5_000)
        runCurrent()
        val s = e.controller.state.value
        assertFalse(s.composer.stopping)
        assertEquals("停止期间写的", s.composer.draft)
    }

    @Test
    fun q4_stopWhileWritingDraftKeepsDraftAndShowsWithdrawnChip() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.say("再检查一遍格式")
        runCurrent()
        e.controller.setDraft("我正在写的新草稿")
        e.controller.stop()
        runCurrent()
        val s = e.controller.state.value
        assertEquals("我正在写的新草稿", s.composer.draft)
        assertEquals(listOf("再检查一遍格式"), s.composer.withdrawn)

        e.controller.insertWithdrawn()
        runCurrent()
        assertEquals("我正在写的新草稿\n再检查一遍格式", e.controller.state.value.composer.draft)
        assertTrue(e.controller.state.value.composer.withdrawn.isEmpty())
    }

    @Test
    fun extDialogsAreQueuedAndAnswered() = runTest {
        val e = env()
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "d1", method = "select", title = "选分支", options = listOf("main", "dev")))
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "d2", method = "confirm", title = "允许运行 rm？"))
        runCurrent()
        var s = e.controller.state.value
        assertEquals("d1", s.dialog?.request?.id)
        assertEquals(1, s.pendingDialogs)

        e.controller.answerDialog("d1", ExtUiAnswer(value = "dev"))
        runCurrent()
        s = e.controller.state.value
        assertEquals("d2", s.dialog?.request?.id)
        // 关闭对话框 = cancelled
        e.controller.answerDialog("d2", ExtUiAnswer.CANCELLED)
        runCurrent()
        assertNull(e.controller.state.value.dialog)
        assertEquals(listOf(Triple("d1", "dev", false), Triple("d2", null, true)), e.engine.answers)

        // 重复关闭不会重复应答
        e.controller.answerDialog("d2", ExtUiAnswer.CANCELLED)
        runCurrent()
        assertEquals(2, e.engine.answers.size)
    }

    @Test
    fun inputAndEditorAnswersCarryValue() = runTest {
        val e = env()
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "i", method = "input", placeholder = "名字"))
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "ed", method = "editor", prefill = "旧内容"))
        runCurrent()
        e.controller.answerDialog("i", ExtUiAnswer(value = "pi"))
        e.controller.answerDialog("ed", ExtUiAnswer(value = "新内容"))
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "c", method = "confirm"))
        runCurrent()
        e.controller.answerDialog("c", ExtUiAnswer(confirmed = true))
        runCurrent()
        assertEquals(listOf(Triple("i", "pi", false), Triple("ed", "新内容", false), Triple("c", "true", false)), e.engine.answers)
    }

    @Test
    fun unknownMethodIsCancelledImmediatelyWithoutDialog() = runTest {
        val e = env()
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "u1", method = "pickColor"))
        runCurrent()
        assertNull(e.controller.state.value.dialog)
        assertEquals(listOf(Triple("u1", null, true)), e.engine.answers)
    }

    @Test
    fun expiredDialogClosesWithoutAnswer() = runTest {
        val e = env()
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "t", method = "confirm", timeoutMs = 5_000))
        runCurrent()
        assertNotNull(e.controller.state.value.dialog)
        e.controller.expireDialog("t")
        runCurrent()
        assertNull(e.controller.state.value.dialog)
        assertTrue(e.engine.answers.isEmpty())
    }

    @Test
    fun notifyStatusTitleAndEditorText() = runTest {
        val e = env()
        val raw = { m: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit ->
            ExtUiRequest(id = "n-$m", method = m, raw = kotlinx.serialization.json.buildJsonObject(b))
        }
        e.engine.fake.emitUiRequest(raw("setStatus") { put("statusKey", kotlinx.serialization.json.JsonPrimitive("git")); put("statusText", kotlinx.serialization.json.JsonPrimitive("main 分支")) })
        e.engine.fake.emitUiRequest(raw("setTitle") { put("title", kotlinx.serialization.json.JsonPrimitive("修登录 bug")) })
        e.engine.fake.emitUiRequest(raw("set_editor_text") { put("text", kotlinx.serialization.json.JsonPrimitive("ls -la")) })
        runCurrent()
        var s = e.controller.state.value
        assertEquals(listOf("main 分支"), s.statusLines)
        assertEquals("修登录 bug", s.title)
        assertEquals("ls -la", s.composer.draft)

        // 用户正在写草稿时，扩展写入要先询问
        e.controller.setDraft("我的草稿")
        e.engine.fake.emitUiRequest(raw("set_editor_text") { put("text", kotlinx.serialization.json.JsonPrimitive("pwd")) })
        runCurrent()
        s = e.controller.state.value
        assertEquals("我的草稿", s.composer.draft)
        assertEquals("pwd", s.replaceDraftOffer)
        e.controller.rejectReplaceDraft()
        runCurrent()
        assertEquals("我的草稿", e.controller.state.value.composer.draft)
        assertNull(e.controller.state.value.replaceDraftOffer)
        assertTrue("通知类不需要应答", e.engine.answers.isEmpty())
    }

    @Test
    fun foldingToggle() = runTest {
        val e = env()
        warmUp(e)
        val summary = e.controller.state.value.rows.filterIsInstance<ChatRow.TurnSummary>().single()
        assertFalse(summary.expanded)
        e.controller.toggleTurn(summary.key)
        runCurrent()
        assertTrue(e.controller.state.value.rows.filterIsInstance<ChatRow.TurnSummary>().single().expanded)
        assertEquals(2, e.controller.state.value.rows.count { it is ChatRow.Tool })
    }

    @Test
    fun errorCardRetryResendsLastUserText() = runTest {
        val e = env()
        warmUp(e)
        e.controller.resend("列出当前目录并读 README")
        runCurrent()
        drain()
        assertEquals(2, e.engine.sends.size)
        assertEquals("列出当前目录并读 README", e.engine.sends.last().first)
    }

    @Test
    fun topBarShowsModelThinkingAndContext() = runTest {
        val e = env()
        drain()
        val s = e.controller.state.value
        assertEquals("fake-model", s.chat.model?.id)
        assertEquals("medium", s.chat.thinkingLevel)
        assertEquals(0.0, s.contextPercent!!, 0.0)
        e.controller.setThinking("high")
        runCurrent()
        assertEquals("high", e.controller.state.value.chat.thinkingLevel)
    }

    private fun req(id: String, m: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        ExtUiRequest(id = id, method = m, raw = kotlinx.serialization.json.buildJsonObject(b))

    @Test
    fun switchingSessionClearsPerSessionLocalState() = runTest {
        val e = env()
        e.engine.fake.openSession("/root/.pi/agent/sessions/a.jsonl", "/root")
        runCurrent()
        e.engine.fake.emitUiRequest(req("t", "setTitle") { put("title", JsonPrimitive("旧会话标题")) })
        e.engine.fake.emitUiRequest(req("s", "setStatus") { put("statusKey", JsonPrimitive("git")); put("statusText", JsonPrimitive("main")) })
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "d1", method = "confirm", title = "继续？"))
        e.engine.failNext = Outcome.Err(ErrCode.RPC_TIMEOUT, "")
        e.controller.setDraft("我的草稿")
        e.controller.submit()
        runCurrent()
        var s = e.controller.state.value
        assertEquals("旧会话标题", s.title)
        assertEquals(listOf("main"), s.statusLines)
        assertNotNull(s.dialog)
        assertTrue(s.rows.any { it is ChatRow.User && it.delivery == Delivery.FAILED })
        e.controller.setDraft("新草稿")
        e.engine.fake.emitUiRequest(req("o3", "set_editor_text") { put("text", JsonPrimitive("扩展草稿")) })
        runCurrent()
        assertNotNull(e.controller.state.value.replaceDraftOffer)

        e.engine.fake.openSession("/root/.pi/agent/sessions/b.jsonl", "/root")
        runCurrent()
        s = e.controller.state.value
        assertEquals("新会话", s.title)
        assertTrue(s.statusLines.isEmpty())
        assertNull(s.dialog)
        assertNull(s.replaceDraftOffer)
        assertTrue("旧会话的气泡不能出现在新会话里", s.rows.none { it is ChatRow.User })
        // 草稿保留；失败气泡的文本不丢，以撤回芯片退回
        assertEquals("新草稿", s.composer.draft)
        assertEquals(listOf("我的草稿"), s.composer.withdrawn)
        // 同一进程内切会话：阻塞类对话框补发 cancelled，pi 不会一直等
        assertTrue(e.engine.answers.contains(Triple("d1", null, true)))
    }

    @Test
    fun engineRestartClearsDialogsWithoutAnsweringOldIds() = runTest {
        val fake = FakeEngineClient(backgroundScope, stepDelayMs = 100)
        val engine = StatusEngine(fake)
        val c = ChatController(engine, backgroundScope)
        runCurrent()
        fake.emitUiRequest(ExtUiRequest(id = "d1", method = "select", options = listOf("a", "b")))
        fake.emitUiRequest(req("t", "setTitle") { put("title", JsonPrimitive("旧标题")) })
        runCurrent()
        assertNotNull(c.state.value.dialog)
        assertEquals("旧标题", c.state.value.title)
        engine.st.value = io.github.zr987459.pigo.contract.EngineStatus.Starting
        runCurrent()
        assertNull(c.state.value.dialog)
        assertEquals("新会话", c.state.value.title)
        assertTrue("新进程不认识旧 id，不应答", engine.answers.isEmpty())
    }

    @Test
    fun messagesProducedWhileChatHiddenAreDeliveredLater() = runTest {
        val e = env()
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "n", method = "notify", message = "后台提示"))
        runCurrent()
        // 页面之后才开始收集，仍能收到
        assertEquals("后台提示", kotlinx.coroutines.withTimeout(1_000) { e.controller.messages.first() })
    }

    @Test
    fun stopWaitsForInFlightSendSoItIsWithdrawnNotSent() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.engine.sendDelayMs = 300
        e.say("这条要撤回")
        runCurrent()
        e.controller.stop()
        runCurrent()
        // 停止期间发送键不可用
        e.controller.setDraft("停止中又写的")
        e.controller.submit()
        drain()
        assertEquals(1, e.engine.sends.count { it.first == "这条要撤回" })
        assertEquals("停止中又写的不应被发送", 0, e.engine.sends.count { it.first == "停止中又写的" })
        assertTrue("撤回的消息不能在停止后照样发出", e.users.none { it == "这条要撤回" })
        assertEquals(listOf("这条要撤回"), e.controller.state.value.composer.withdrawn)
    }

    // ---------- §5.5 Q4 文字不丢：引擎崩溃 / 重启不得静默丢弃 ----------

    /**
     * 「发送中崩溃 → 文本回到输入框」：一条消息还在途（SENDING，send 尚未返回）时引擎崩溃重启，
     * 乐观气泡被清，但文本必须退回输入框，且延迟到达的失败结果不得把它再退一次。
     */
    @Test
    fun crashWhileSendingReturnsTextToInput() = runTest {
        val e = restartEnv()
        finishOneTurn(e.controller)
        goRunning(e.controller)
        // 让这条发送卡在途中（send 延迟返回），并模拟引擎已退出：返回前就崩溃。
        e.engine.sendDelayMs = 10_000
        e.engine.failNext = Outcome.Err(ErrCode.ENGINE_EXIT, "")
        e.controller.setDraft("崩溃时正在发送的消息")
        e.controller.submit()
        runCurrent()
        assertEquals("", e.controller.state.value.composer.draft)
        val sending = e.controller.state.value.rows.filterIsInstance<ChatRow.User>().last { it.localId != null }
        assertEquals(Delivery.SENDING, sending.delivery)

        // 引擎崩溃重启。
        e.engine.st.value = EngineStatus.Starting
        runCurrent()
        // 文本回到（空的）输入框，气泡清掉，不进「已撤回」芯片。
        assertEquals("崩溃时正在发送的消息", e.controller.state.value.composer.draft)
        assertTrue(e.controller.state.value.composer.withdrawn.isEmpty())
        assertTrue(e.controller.state.value.rows.none { it is ChatRow.User && it.localId != null })

        // 迟到的失败结果回来，不能把同一条文本再退一次。
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("崩溃时正在发送的消息", e.controller.state.value.composer.draft)
        assertTrue(e.controller.state.value.composer.withdrawn.isEmpty())
    }

    /**
     * 「排队中重启 → 文本不丢」：一条消息已排队（引擎队列里、以芯片显示）时引擎重启，
     * 队列随旧进程消失，文本必须退回输入框，不能随本地气泡一起被清掉。
     */
    @Test
    fun restartWhileQueuedKeepsText() = runTest {
        val e = restartEnv()
        finishOneTurn(e.controller)
        goRunning(e.controller)
        e.controller.setDraft("排队中的消息")
        e.controller.submit()
        runCurrent()
        // 已进入引擎队列、输入框清空、本地气泡改由芯片显示。
        assertEquals(listOf("排队中的消息"), e.controller.state.value.chat.queue.followUp)
        assertEquals("", e.controller.state.value.composer.draft)

        // 引擎重启：旧队列随旧进程消失。
        e.engine.st.value = EngineStatus.Starting
        runCurrent()
        // 排队文本退回（空的）输入框，只退一份。
        assertEquals("排队中的消息", e.controller.state.value.composer.draft)
        assertTrue(e.controller.state.value.composer.withdrawn.isEmpty())
    }

    // ---------- 同一进程内切换 / 新建会话：已入 pi 队列的消息不得静默丢弃 ----------

    /**
     * 镜像 Fake 引擎的 [ChatState]，但可以模拟 pi 的 switchSession / newSession：
     * 旧会话先 abort 再 dispose，steering / followUp 队列直接丢弃、**不发** queue_update，新会话空闲。
     */
    private class SwitchEngine(val fake: FakeEngineClient, scope: kotlinx.coroutines.CoroutineScope) : EngineClient by fake {
        var file: String? = "/root/.pi/agent/sessions/a.jsonl"
        private var mirror = true
        private val flow = kotlinx.coroutines.flow.MutableStateFlow(fake.chat.value.copy(sessionFile = file))
        override val chat: kotlinx.coroutines.flow.StateFlow<io.github.zr987459.pigo.contract.ChatState> get() = flow

        init {
            scope.launch { fake.chat.collect { if (mirror) flow.value = it.copy(sessionFile = file) } }
        }

        fun switchTo(newFile: String?) {
            mirror = false
            file = newFile
            flow.value = io.github.zr987459.pigo.contract.ChatState(model = fake.chat.value.model, sessionFile = newFile)
        }

        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {}
    }

    private fun TestScope.queueThenSwitch(newFile: String?, draftAfterQueue: String?): ChatController {
        val engine = SwitchEngine(FakeEngineClient(backgroundScope, stepDelayMs = 100), backgroundScope)
        val c = ChatController(engine, backgroundScope, ChatConfig(stopSlowHintMs = 20_000))
        runCurrent()
        finishOneTurn(c)
        goRunning(c)
        c.setDraft("排队中的消息")
        c.submit()
        runCurrent()
        // 已进入 pi 队列、以芯片显示，本地气泡已被对账吸收。
        assertEquals(listOf("排队中的消息"), c.state.value.chat.queue.followUp)
        assertTrue(c.state.value.rows.none { it is ChatRow.User && it.localId != null })
        if (draftAfterQueue != null) c.setDraft(draftAfterQueue)

        engine.switchTo(newFile)
        runCurrent()
        return c
    }

    @Test
    fun switchSessionWhileQueuedReturnsTextToInputOnce() = runTest {
        val c = queueThenSwitch("/root/.pi/agent/sessions/b.jsonl", draftAfterQueue = null)
        assertEquals("排队中的消息", c.state.value.composer.draft)
        assertTrue(c.state.value.composer.withdrawn.isEmpty())
        assertTrue(c.state.value.chat.queue.isEmpty)
        // 之后再无任何事件或结果把它退第二次。
        drain()
        assertEquals("排队中的消息", c.state.value.composer.draft)
        assertTrue(c.state.value.composer.withdrawn.isEmpty())
    }

    @Test
    fun newSessionWhileQueuedWithDraftGoesToWithdrawnChipOnce() = runTest {
        val c = queueThenSwitch(newFile = null, draftAfterQueue = "我正在写的")
        assertEquals("我正在写的", c.state.value.composer.draft)
        assertEquals(listOf("排队中的消息"), c.state.value.composer.withdrawn)
        drain()
        assertEquals("我正在写的", c.state.value.composer.draft)
        assertEquals(listOf("排队中的消息"), c.state.value.composer.withdrawn)
    }

    /**
     * 引擎重启时在途的发送已立即退回；若迟到的结果其实是成功（旧进程已收下或新进程补发），
     * 提示「该消息已发出」并撤掉退回的那份，避免用户再发一遍。
     */
    @Test
    fun lateOkAfterRestartRetractsReturnedTextAndTellsUser() = runTest {
        val e = restartEnv()
        finishOneTurn(e.controller)
        goRunning(e.controller)
        e.engine.sendDelayMs = 10_000
        e.controller.setDraft("重启时在途的消息")
        e.controller.submit()
        runCurrent()

        e.engine.st.value = EngineStatus.Starting
        runCurrent()
        assertEquals("重启时在途的消息", e.controller.state.value.composer.draft)

        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("", e.controller.state.value.composer.draft)
        assertTrue(e.controller.state.value.composer.withdrawn.isEmpty())
        assertEquals(
            "该消息已发出",
            kotlinx.coroutines.withTimeout(1_000) { e.controller.messages.first { it == "该消息已发出" } },
        )
    }

    @Test
    fun lateOkAfterRestartLeavesEditedDraftAlone() = runTest {
        val e = restartEnv()
        finishOneTurn(e.controller)
        goRunning(e.controller)
        e.engine.sendDelayMs = 10_000
        e.controller.setDraft("重启时在途的消息")
        e.controller.submit()
        runCurrent()
        e.engine.st.value = EngineStatus.Starting
        runCurrent()
        // 用户在退回的文本上继续改了：不能替用户清掉。
        e.controller.setDraft("重启时在途的消息，再补一句")
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals("重启时在途的消息，再补一句", e.controller.state.value.composer.draft)
    }

    // ---------- integration：停止收起确认框、「去设置模型」、阶段提示 ----------

    @Test
    fun stopClosesPendingBlockingDialogsAndCancelsThemOnce() = runTest {
        val e = env()
        warmUp(e)
        startRunning(e)
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "c1", method = "confirm", title = "允许执行 rm？"))
        e.engine.fake.emitUiRequest(ExtUiRequest(id = "q1", method = "input", title = "要叫什么？"))
        runCurrent()
        assertNotNull(e.controller.state.value.dialog)
        e.controller.stop()
        runCurrent()
        assertNull("停止后不留悬空弹窗", e.controller.state.value.dialog)
        assertEquals(0, e.controller.state.value.pendingDialogs)
        drain()
        assertEquals(1, e.engine.answers.count { it.first == "c1" && it.third })
        assertEquals(1, e.engine.answers.count { it.first == "q1" && it.third })
    }

    @Test
    fun noModelSendFailureOffersSettings() = runTest {
        val e = env()
        e.engine.failNext = Outcome.Err(ErrCode.NO_MODEL, "no model")
        e.say("你好")
        runCurrent()
        val row = e.controller.state.value.rows.filterIsInstance<ChatRow.User>().single { it.localId != null }
        assertEquals(Delivery.FAILED, row.delivery)
        assertTrue(row.needsSettings)

        e.engine.failNext = Outcome.Err(ErrCode.RPC_TIMEOUT, "")
        e.controller.retry(row.localId!!)
        runCurrent()
        val again = e.controller.state.value.rows.filterIsInstance<ChatRow.User>().single { it.localId != null }
        assertFalse("超时与模型配置无关", again.needsSettings)
    }

    @Test
    fun phaseHintAppearsBetweenSendAndFirstToken() = runTest {
        val e = env()
        warmUp(e)
        e.engine.sendDelayMs = 50
        e.say("检查一下代码")
        runCurrent()
        assertEquals(io.github.zr987459.pigo.feature.chat.logic.PhaseLabel.CONNECTING, e.controller.state.value.phase)
        assertEquals(e.controller.state.value.phase, e.controller.state.value.statusLines.firstOrNull())
        drain()
        assertNull("回合结束后阶段提示消失", e.controller.state.value.phase)
    }
}
