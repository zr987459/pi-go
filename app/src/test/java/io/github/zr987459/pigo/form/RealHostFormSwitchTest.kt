package io.github.zr987459.pigo.form

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionRepository
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.core.session.SessionHostImpl
import io.github.zr987459.pigo.di.AppSessionOps
import io.github.zr987459.pigo.di.ModelPanelEvents
import io.github.zr987459.pigo.di.SessionBindings
import io.github.zr987459.pigo.feature.chat.ChatController
import io.github.zr987459.pigo.feature.models.RealModelPanels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 硬性要求 ② 的装配层证据（r3/app 第 3 步）：用 **App 真实的装配代码** [SessionBindings.real]
 * 得到 `SessionHostImpl`（聊天控制器 + Outbox + DraftStore + PiActionsImpl + RealModelPanels），引擎用 Fake。
 *
 * 生成中经 [toggleForm] 连切 20 次：不发命令、引擎不重启（start=1、restart=0）、流式完整、草稿与 overlay 原样；
 * 另外核对 UI 形态需要的几条真实路径：发消息走控制器、/model 打开 RealModelPanels 面板、面板事件路由。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealHostFormSwitchTest {

    private class Wired(val host: SessionHostImpl, val engine: FakeScriptedEngine, val controller: ChatController, val panels: RealModelPanels)

    private fun TestScope.wire(scope: CoroutineScope): Wired {
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val controller = ChatController(engine, scope, drafts = FakeDraftStore())
        val auth = FakeAuthManager()
        val settings = FakePiSettings()
        val panels = RealModelPanels(FakeModelCatalog(auth), auth, FakeCustomProviderStore(), engine, scope, settings)
        val host = SessionBindings.real(
            scope, UiForm.CALM,
            SessionBindings.Deps(
                engine = engine,
                extras = FakeEngineExtras(),
                controller = controller,
                settings = settings,
                repo = FakeSessionRepository(),
                panels = panels,
                ops = AppSessionOps(engine, FakeRuntimeManager(stepDelayMs = 0), File(System.getProperty("java.io.tmpdir"), "pigo-test-cache")),
            ),
        )
        runCurrent()
        return Wired(host, engine, controller, panels)
    }

    @Test
    fun toggleDuringStreamingOnRealHost() = runTest {
        val w = wire(backgroundScope)
        val host = w.host
        val script = StreamScript.counting(200, 50)
        val job = w.engine.startStream(script, userText = "数到 200")
        val draft = DraftState(text = "草稿abc", selectionStart = 3, fileRefs = listOf("a.kt"))
        host.draft.value = draft
        host.overlay.push(OverlayModel.SelectList("overlay:model.select", "选择模型", query = "son"))
        runCurrent()
        val overlayBefore = host.overlay.items.value

        repeat(20) { i ->
            delay(script.intervalMs * 200 / 25)
            assertTrue("第 ${i + 1} 次切换时应仍在生成", host.chat.value.running)
            val before = w.engine.commands.value.size
            host.toggleForm()
            runCurrent()
            assertEquals("第 ${i + 1} 次切换发出了命令：${w.engine.commands.value.drop(before)}", before, w.engine.commands.value.size)
        }
        job.join()

        assertEquals(UiForm.CALM, host.form.value)
        assertEquals("引擎只启动一次", 1, w.engine.startCount)
        assertEquals("引擎没有重启", 0, w.engine.restartCount)
        val last = host.chat.value.items.filterIsInstance<ChatItem.Assistant>().last()
        assertEquals("生成没有中断", script.fullText, last.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text })
        assertEquals("草稿保留（同一份 ChatController 草稿）", draft, host.draft.value)
        assertEquals(draft.text, w.controller.state.value.composer.draft)
        assertEquals("overlay 栈保留", overlayBefore, host.overlay.items.value)
    }

    @Test
    fun sendGoesThroughControllerAndModelPanelOpens() = runTest {
        val w = wire(backgroundScope)
        val host = w.host
        host.draft.value = DraftState(text = "你好")
        runCurrent()
        val r = host.runAction("send", null)
        runCurrent()
        assertTrue("发送应被接受：$r", r !is ActionResult.Toast || !r.text.contains("失败"))
        assertTrue("经引擎发出：${w.engine.commands.value}", w.engine.commands.value.any { it.type in setOf("prompt", "steer", "follow_up") })
        assertEquals("草稿清空", "", host.draft.value.text)

        // /model 打开 RealModelPanels 的面板（id = model.select），并且这个 id 归面板事件路由。
        val m = host.runAction("model.select", null)
        assertTrue("$m", m is ActionResult.Overlay)
        val id = (m as ActionResult.Overlay).model.id
        assertTrue("模型面板 $id 应由面板事件处理", ModelPanelEvents.isModelPanel(id))
        assertEquals(id, host.overlay.top?.id)
    }

    @Test
    fun panelEventRoutingOwnsOnlyModelPanels() {
        for (id in listOf("model.select", "auth.login", "auth.logout", "provider.custom", "provider.custom:my", "provider.form:deepseek", "oauth:anthropic", "confirm.clear:deepseek", "confirm.logout:x", "confirm.remove:y")) {
            assertTrue(id, ModelPanelEvents.isModelPanel(id))
        }
        for (id in listOf("overlay:settings", "overlay:session.cwd", "commands", "ext:1", "explain:help.env", "overlay:model.thinkingAdvanced", "session.tree")) {
            assertTrue(id, !ModelPanelEvents.isModelPanel(id))
        }
    }
}
