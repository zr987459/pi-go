package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
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

        override suspend fun stop(): Outcome<io.github.zr987459.pigo.contract.QueueSnapshot> {
            if (stopDelayMs > 0) kotlinx.coroutines.delay(stopDelayMs)
            return fake.stop()
        }

        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> {
            sends += text to mode
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
}
