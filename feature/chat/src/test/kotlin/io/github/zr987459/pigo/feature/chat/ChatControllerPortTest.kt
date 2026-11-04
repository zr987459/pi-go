package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.actions.PiActions
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.core.session.DraftCodec
import io.github.zr987459.pigo.core.session.SessionHostImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审查第 1 轮 I1 / I2：SessionHostImpl 经 [ChatControllerPort] 使用**真实的** ChatController 状态
 * （草稿按会话经 DraftStore 持久化、发送走 Outbox、扩展对话框只订阅一次），切换测试测的就是这份状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerPortTest {

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

    private fun wire(scope: CoroutineScope, engine: FakeScriptedEngine, drafts: DraftStore): Pair<SessionHostImpl, ChatController> {
        val controller = ChatController(engine, scope, extras = null, drafts = drafts)
        val host = SessionHostImpl(
            engine = engine, extras = FakeEngineExtras(), scope = scope, settings = FakePiSettings(),
            sessions = FakeSessionCatalog(engine), chatPort = ChatControllerPort(controller), actionsFactory = { NoActions },
        )
        return host to controller
    }

    @Test
    fun hostDraftIsTheControllersDraftAndPersistsPerSession() = runTest {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val drafts = MemDrafts()
        val (host, controller) = wire(backgroundScope, engine, drafts)
        testScheduler.runCurrent()
        assertSame("SessionHost.draft 必须就是 ChatController 的草稿", controller.sharedDraft, host.draft)

        val d = DraftState(text = "写到一半", selectionStart = 2, fileRefs = listOf("src/a.kt"), bashMode = BashMode.NONE)
        host.draft.value = d
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        assertEquals("输入框文字与共享草稿同步", "写到一半", controller.state.value.composer.draft)
        val key = engine.chat.value.sessionFile!!
        assertEquals("按会话持久化（含光标与 @ 引用）", d, DraftCodec.decode(drafts.map[key]))

        // 进程被杀后重建：同一个 DraftStore → 草稿回来。
        val engine2 = FakeScriptedEngine(backgroundScope).also { it.start() }
        val (host2, _) = wire(backgroundScope, engine2, drafts)
        testScheduler.advanceTimeBy(2_000); testScheduler.runCurrent()
        assertEquals(d, host2.draft.value)
    }

    @Test
    fun formSwitchDuringStreamingOnRealControllerState() = runTest {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val (host, controller) = wire(backgroundScope, engine, MemDrafts())
        testScheduler.runCurrent()
        val script = StreamScript.counting(200, 50)
        val job = engine.startStream(script, userText = "数到 200")
        val draft = DraftState(text = "草稿abc", selectionStart = 3, fileRefs = listOf("a.kt"))
        host.draft.value = draft
        engine.pushUiRequest(ExtUiRequest(id = "c1", method = "confirm", title = "确认？"))
        host.overlay.push(OverlayModel.SelectList("overlay:model.select", "选择模型", query = "son"))
        delay(1)
        assertTrue("扩展 confirm 由 ChatController 收到并镜像进 overlay", host.overlay.items.value.any { it is OverlayModel.ExtDialog && it.request.id == "c1" })
        assertEquals(listOf("c1"), controller.pendingDialogs.value.map { it.id })
        val overlayBefore = host.overlay.items.value

        repeat(20) { i ->
            delay(script.intervalMs * 200 / 25)
            assertTrue("第 ${i + 1} 次切换时应仍在生成", host.chat.value.running)
            val before = engine.commands.value.size
            host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM
            testScheduler.runCurrent()
            assertEquals("切换发出了命令：${engine.commands.value.drop(before)}", before, engine.commands.value.size)
        }
        job.join()

        assertEquals(1, engine.startCount)
        assertEquals(0, engine.restartCount)
        val last = host.chat.value.items.filterIsInstance<ChatItem.Assistant>().last()
        assertEquals(script.fullText, last.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text })
        assertEquals(draft, host.draft.value)
        assertEquals("草稿abc", controller.state.value.composer.draft)
        assertEquals(overlayBefore, host.overlay.items.value)

        // 只有一个应答者：经宿主应答一次，引擎只收到一次回复。
        host.answerExtDialog("ext:c1", confirmed = true)
        testScheduler.runCurrent()
        assertEquals(1, engine.commands.value.count { it.type == "extension_ui_response" })
        assertTrue(controller.pendingDialogs.value.isEmpty())
        assertTrue(host.overlay.items.value.none { it is OverlayModel.ExtDialog })
    }

    @Test
    fun setEditorTextReadsTextFieldAndAsksBeforeReplacingDraft() = runTest {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val (host, _) = wire(backgroundScope, engine, MemDrafts())
        testScheduler.runCurrent()
        // 草稿为空：直接写入（读 `text` 字段，不是 prefill）。
        engine.pushUiRequest(ExtUiRequest(id = "e1", method = "set_editor_text", raw = JsonObject(mapOf("text" to JsonPrimitive("扩展给的")))))
        testScheduler.runCurrent()
        assertEquals("扩展给的", host.draft.value.text)
        // 草稿非空：不覆盖，压确认框；拒绝后草稿不变，接受后才替换。
        host.draft.value = DraftState(text = "我自己写的")
        testScheduler.runCurrent()
        engine.pushUiRequest(ExtUiRequest(id = "e2", method = "set_editor_text", raw = JsonObject(mapOf("text" to JsonPrimitive("新文字")))))
        testScheduler.runCurrent()
        assertEquals("我自己写的", host.draft.value.text)
        assertTrue(host.overlay.items.value.any { it.id == SessionHostImpl.EDITOR_OFFER_ID })
        host.answerEditorTextOffer(accept = false)
        testScheduler.runCurrent()
        assertEquals("我自己写的", host.draft.value.text)
        assertNull(host.overlay.items.value.firstOrNull { it.id == SessionHostImpl.EDITOR_OFFER_ID })
        engine.pushUiRequest(ExtUiRequest(id = "e3", method = "set_editor_text", raw = JsonObject(mapOf("text" to JsonPrimitive("新文字")))))
        testScheduler.runCurrent()
        host.answerEditorTextOffer(accept = true)
        testScheduler.runCurrent()
        assertEquals("新文字", host.draft.value.text)
    }

    @Test
    fun sendGoesThroughOutbox() = runTest {
        val engine = FakeScriptedEngine(backgroundScope).also { it.start() }
        val (host, controller) = wire(backgroundScope, engine, MemDrafts())
        testScheduler.runCurrent()
        host.draft.value = DraftState(text = "你好", fileRefs = listOf("a.kt"))
        val r = host.sendDraft()
        testScheduler.runCurrent()
        assertTrue("发送应被接受：$r", r is io.github.zr987459.pigo.contract.Outcome.Ok)
        assertTrue("草稿清空", host.draft.value.text.isEmpty() && host.draft.value.fileRefs.isEmpty())
        assertEquals("输入框也清空", "", controller.state.value.composer.draft)
        assertTrue("经 engine.send 发出（Outbox 路径）：${engine.commands.value}", engine.commands.value.any { it.type == "prompt" || it.type == "steer" || it.type == "follow_up" })
    }
}
