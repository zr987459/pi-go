package io.github.zr987459.pigo.contract.fake

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeSessionHostTest {

    private fun lastAssistantText(host: FakeSessionHost): String =
        (host.chat.value.items.last { it is ChatItem.Assistant } as ChatItem.Assistant)
            .blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    @Test
    fun programmedStreamArrivesChunkByChunkAndSettlesOnce() = runTest {
        val host = FakeSessionHost(backgroundScope)
        val script = StreamScript.counting(200, intervalMs = 50)
        host.program(script)

        val r = host.engine.send("从 1 数到 200", SendMode.FOLLOW_UP)
        assertEquals(Outcome.Ok(Disposition.STARTED), r)
        assertTrue(host.chat.value.running)

        advanceTimeBy(50L * 10 + 1)
        runCurrent()
        assertEquals((1..10).joinToString("") { "$it\n" }, lastAssistantText(host))
        assertTrue(host.chat.value.running)

        advanceTimeBy(50L * 200)
        runCurrent()
        assertEquals(script.fullText, lastAssistantText(host))
        assertFalse(host.chat.value.running)
        assertEquals(1, host.fakeEngine.agentSettledCount)
        val asst = host.chat.value.items.last() as ChatItem.Assistant
        assertEquals("stop", asst.stopReason)
        assertFalse(asst.streaming)
    }

    @Test
    fun commandLogRecordsRpcTypesInOrder() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.program(StreamScript(listOf("a", "b", "c"), intervalMs = 100))
        host.engine.send("hi")
        host.engine.send("再说一句", SendMode.STEER)
        host.engine.send("之后再说", SendMode.FOLLOW_UP)
        host.engine.stop()
        host.engine.setThinking("high")
        host.engine.rename("测试会话")
        host.engine.newSession()

        assertEquals(
            listOf("prompt", "steer", "follow_up", "clear_queue", "abort", "set_thinking_level", "set_session_name", "new_session"),
            host.commands.value.map { it.type },
        )
        assertEquals("hi", host.commands.value.first().args["message"])
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
    }

    @Test
    fun stopReturnsQueueAndAbortsStream() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.program(StreamScript(List(10) { "x" }, intervalMs = 100))
        host.engine.send("go")
        host.engine.send("queued", SendMode.FOLLOW_UP)
        val q = (host.engine.stop() as Outcome.Ok).value
        assertEquals(listOf("queued"), q.followUp)
        assertFalse(host.chat.value.running)
        assertEquals("aborted", (host.chat.value.items.last() as ChatItem.Assistant).stopReason)
        assertEquals(0, host.fakeEngine.agentSettledCount)
    }

    @Test
    fun switchingFormDuringStreamingSendsNoCommandsAndKeepsState() = runTest {
        val host = FakeSessionHost(backgroundScope)
        val script = StreamScript.counting(200, intervalMs = 50)
        host.program(script)
        host.engine.send("数数")
        val chatFlow = host.chat
        host.draft.value = DraftState(text = "草稿abc", selectionStart = 3, images = listOf(DraftImage("content://img/1")), fileRefs = listOf("a.kt"))
        val draftBefore = host.draft.value
        host.overlay.push(OverlayModel.SelectList(id = "model.select", title = "选择模型", query = "son"))
        host.showExtDialog(ExtUiRequest(id = "req-1", method = "confirm", title = "确认？"))
        val overlayBefore = host.overlay.items.value
        val logBefore = host.commands.value
        val fileBefore = host.chat.value.sessionFile

        repeat(20) {
            host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM
            advanceTimeBy(100)
            runCurrent()
        }
        advanceTimeBy(50L * 200)
        runCurrent()

        assertEquals(logBefore, host.commands.value)
        assertSame(chatFlow, host.chat)
        assertEquals(script.fullText, lastAssistantText(host))
        assertEquals(1, host.fakeEngine.agentSettledCount)
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertEquals(draftBefore, host.draft.value)
        assertEquals(overlayBefore, host.overlay.items.value)
        assertEquals(fileBefore, host.chat.value.sessionFile)
        assertEquals(UiForm.CALM, host.form.value)
    }

    @Test
    fun extDialogAnswerIsLoggedWithSameRequestId() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.showExtDialog(ExtUiRequest(id = "req-9", method = "confirm"))
        val top = host.overlay.top as OverlayModel.ExtDialog
        host.engine.answerUi(top.request.id, confirmed = true)
        val call = host.commands.value.single()
        assertEquals("extension_ui_response", call.type)
        assertEquals("req-9", call.args["id"])
        assertEquals("true", call.args["confirmed"])
        assertFalse(call.args.containsKey("cancelled"))
    }

    @Test
    fun restartIsCountedSeparatelyFromStart() = runTest {
        val host = FakeSessionHost(backgroundScope)
        assertNotNull(host.debugEnginePid())
        host.engine.restart("PI_OFFLINE 变更")
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(1, host.fakeEngine.restartCount)
        assertEquals("restart", host.commands.value.last().type)
    }

    @Test
    fun sendDraftClearsDraftAndRecordsHistory() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.draft.value = DraftState(text = "你好")
        host.sendDraft()
        assertEquals(DraftState.EMPTY, host.draft.value)
        assertTrue(host.draft.value.isEmpty)
        assertEquals(listOf("你好"), host.promptHistory.value)
        assertEquals("prompt", host.commands.value.single().type)
    }

    @Test
    fun runActionPushesOverlayExactlyOnceAndRunDoesNotPush() = runTest {
        val host = FakeSessionHost(backgroundScope)
        // run 只返回结果，不碰栈
        val raw = host.actions.run("session.tree")
        assertTrue(raw is ActionResult.Overlay)
        assertEquals(0, host.overlay.items.value.size)
        // runAction 压栈一次
        val r = host.runAction("session.tree")
        assertTrue(r is ActionResult.Overlay)
        assertEquals(1, host.overlay.items.value.size)
        assertEquals("session.tree", host.overlay.top?.id)
        // 一次返回就关掉
        host.overlay.pop()
        assertEquals(0, host.overlay.items.value.size)
    }

    @Test
    fun placeholderActionsReturnOverlayOrExplain() = runTest {
        val host = FakeSessionHost(backgroundScope)
        val r = host.runAction("session.tree")
        assertTrue(r is ActionResult.Overlay)
        assertEquals("session.tree", host.overlay.top?.id)
        val e = host.runAction("help.tuiMode")
        assertTrue(e is ActionResult.LocalBlock && e.title == "explain:help.tuiMode" && e.text.isNotBlank())
        assertEquals("explain:help.tuiMode", host.localBlocks.value.single().title)
        assertEquals(1, host.overlay.items.value.size)
        assertEquals(listOf("action:session.tree", "action:help.tuiMode"), host.commands.value.map { it.type })
        assertTrue(host.runAction("no.such") is ActionResult.Toast)
    }

    @Test
    fun queuedSteerAndFollowUpAreDeliveredAfterStreamEnds() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.program(StreamScript(listOf("a", "b"), intervalMs = 100))
        host.engine.send("hi")
        host.engine.send("跟进", SendMode.FOLLOW_UP)
        host.engine.send("插话", SendMode.STEER)
        assertEquals(listOf("插话"), host.chat.value.queue.steering)
        advanceTimeBy(10_000); runCurrent()
        assertFalse(host.chat.value.running)
        assertTrue(host.chat.value.queue.steering.isEmpty() && host.chat.value.queue.followUp.isEmpty())
        val users = host.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(listOf("hi", "插话", "跟进"), users)
        assertEquals(1, host.fakeEngine.agentSettledCount)
        assertEquals(listOf("prompt", "follow_up", "steer"), host.commands.value.map { it.type })
    }

    @Test
    fun secondStartStreamCancelsFirst() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.startStream(StreamScript(listOf("x", "y", "z"), intervalMs = 100), "一")
        advanceTimeBy(150); runCurrent()
        host.startStream(StreamScript(listOf("p", "q", "r", "s", "t"), intervalMs = 100), "二")
        advanceTimeBy(250); runCurrent()
        // 第一段已被取消并标 aborted；第一段原本 300ms 结束，此时第二段仍在跑
        assertTrue(host.chat.value.running)
        val asst = host.chat.value.items.filterIsInstance<ChatItem.Assistant>()
        assertEquals("aborted", asst.first().stopReason)
        assertFalse(asst.first().streaming)
        advanceTimeBy(1_000); runCurrent()
        assertFalse(host.chat.value.running)
        assertEquals(1, host.fakeEngine.agentSettledCount)
        assertEquals("pqrst", lastAssistantText(host))
    }

    @Test
    fun uiRequestsDoNotReplayToLateSubscribers() = runTest {
        val host = FakeSessionHost(backgroundScope)
        host.showExtDialog(ExtUiRequest(id = "req-1", method = "confirm"))
        assertTrue(host.engine.uiRequests.replayCache.isEmpty())
    }

    @Test
    fun sendDraftKeepsImagesRefsAndBashModeAndSkipsEmpty() = runTest {
        val host = FakeSessionHost(backgroundScope)
        assertTrue(host.sendDraft() is Outcome.Err)
        host.draft.value = DraftState(text = "   ")
        assertTrue(host.sendDraft() is Outcome.Err)
        assertTrue(host.commands.value.isEmpty())
        assertTrue(host.promptHistory.value.isEmpty())

        host.draft.value = DraftState(text = "看图", images = listOf(DraftImage("content://a.png", "image/png")), fileRefs = listOf("src/A.kt"), recalled = listOf("旧排队"))
        host.sendDraft()
        val c = host.commands.value.single()
        assertEquals("prompt", c.type)
        assertEquals("@src/A.kt 看图", c.args["message"])
        assertEquals("1", c.args["images"])
        assertEquals(listOf("旧排队"), host.draft.value.recalled)

        host.draft.value = DraftState(text = "", images = listOf(DraftImage("content://b.png")))
        host.sendDraft()
        assertEquals(listOf("看图"), host.promptHistory.value)

        advanceTimeBy(10_000); runCurrent()
        host.clearCommands()
        host.draft.value = DraftState(text = "ls", bashMode = io.github.zr987459.pigo.contract.session.BashMode.EXCLUDE)
        host.sendDraft()
        val b = host.commands.value.single()
        assertEquals("bash", b.type)
        assertEquals("ls", b.args["command"])
        assertEquals("true", b.args["excludeFromContext"])
    }

    @Test
    fun hostExposesExtrasSettingsAndSessions() = runTest {
        val host = FakeSessionHost(backgroundScope)
        assertTrue(host.settings.set("compaction.enabled", kotlinx.serialization.json.JsonPrimitive(false)) is Outcome.Ok)
        assertTrue(host.settings.set("no.such", null) is Outcome.Err)
        assertEquals(3, host.sessions.sessions.value.size)
        val p = (host.sessions.delete("/fake/session-2.jsonl") as Outcome.Ok).value
        assertEquals(2, host.sessions.sessions.value.size)
        assertTrue(host.sessions.undoDelete(p) is Outcome.Ok)
        assertEquals(3, host.sessions.sessions.value.size)
        host.sessions.open("/fake/session-3.jsonl")
        assertEquals("switch_session", host.commands.value.last().type)
        assertTrue(host.extras.commands() is Outcome.Ok)
    }
}
