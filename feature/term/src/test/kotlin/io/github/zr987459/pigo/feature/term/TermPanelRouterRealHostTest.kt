package io.github.zr987459.pigo.feature.term

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeScriptedEngine
import io.github.zr987459.pigo.contract.fake.FakeSessionCatalog
import io.github.zr987459.pigo.contract.models.ModelPanels
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.FlowState
import io.github.zr987459.pigo.contract.session.FormField
import io.github.zr987459.pigo.contract.session.OutgoingMessage
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.core.session.GuestOutput
import io.github.zr987459.pigo.core.session.PiActionsImpl
import io.github.zr987459.pigo.core.session.SessionChatPort
import io.github.zr987459.pigo.core.session.SessionHostImpl
import io.github.zr987459.pigo.core.session.SessionOps
import io.github.zr987459.pigo.core.session.TreeNav
import io.github.zr987459.pigo.feature.term.ui.overlayEnter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #18 审查第 3 轮 Important 1、2 与 Nit 2：用**真实的 SessionHostImpl + PiActionsImpl**（Fake 引擎）证明终端形态的
 * /model、/login 等 ModelPanels 面板选得中、提交得了，失败不关表单，OAuth 关闭发 cancel / 成功发 done。
 *
 * 面板 id 与 RealModelPanels 一致（`model.select`、`auth.login`、`provider.form:<id>`、`oauth:<id>`），其中前两个恰好也是动作 id——
 * 旧规则（按 id 是否在动作表里）会把它们交给 runAction，这里断言不再如此。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TermPanelRouterRealHostTest {

    /** 模仿 RealModelPanels 的面板宿主：选模型 → engine.setModel；/login 选服务商 → 表单；表单提交按 Key 成败返回。 */
    private class Panels(private val engine: EngineClient) : ModelPanels {
        val events = mutableListOf<Triple<String, String, String?>>()
        var oauthStep: FlowState = FlowState.RUNNING

        override suspend fun modelPicker(tab: ModelPickerTab, query: String) = OverlayModel.SelectList(
            "model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("model:anthropic/sonnet", "sonnet"), SelectItem("model:openai/gpt-5", "gpt-5")))), query = query,
        )
        override suspend fun providerList(query: String) = OverlayModel.SelectList(
            "auth.login", "登录", listOf(SelectGroup(null, listOf(SelectItem("provider:deepseek", "DeepSeek"), SelectItem("provider:github-copilot", "GitHub Copilot")))),
        )
        override suspend fun providerForm(providerId: String) = OverlayModel.Form(
            "provider.form:$providerId", providerId, listOf(FormField("apiKey", "API Key", PiSettingType.Str, secret = true)),
        )
        override suspend fun customProviderForm(providerId: String?) = OverlayModel.Form("provider.custom", "自定义服务商", listOf(FormField("id", "名称", PiSettingType.Str)))
        override suspend fun oauthFlow(providerId: String) = OverlayModel.Text("oauth:$providerId", "登录 $providerId", "…", flow = oauthStep)
        override suspend fun logoutPicker() = OverlayModel.SelectList("auth.logout", "退出登录", listOf(SelectGroup(null, listOf(SelectItem("provider:deepseek", "DeepSeek")))))

        override suspend fun onPanelEvent(overlayId: String, key: String, value: String?): ActionResult {
            events += Triple(overlayId, key, value)
            return when {
                overlayId == "model.select" && key.startsWith("model:") -> {
                    val (p, m) = key.removePrefix("model:").split("/", limit = 2)
                    engine.setModel(p, m); ActionResult.Done
                }
                overlayId == "auth.login" && key == "provider:github-copilot" -> ActionResult.Overlay(oauthFlow("github-copilot"))
                overlayId == "auth.login" && key.startsWith("provider:") -> ActionResult.Overlay(providerForm(key.removePrefix("provider:")))
                overlayId.startsWith("provider.form:") && key == "submit" ->
                    if (value?.contains("sk-good") == true) ActionResult.Done else ActionResult.Toast("保存失败：Key 无效", failed = true)
                overlayId.startsWith("oauth:") && key == "done" -> ActionResult.Toast("已登录，已自动选回模型")
                overlayId.startsWith("oauth:") -> ActionResult.Done
                else -> ActionResult.Toast("未知：$overlayId/$key")
            }
        }
    }

    private class Ops : SessionOps {
        override suspend fun abortRetry() = Outcome.Ok(Unit)
        override suspend fun dequeueAll() = Outcome.Ok(QueueSnapshot.EMPTY)
        override suspend fun stopEngine() = Outcome.Ok(Unit)
        override suspend fun setLabel(entryId: String, label: String?) = Outcome.Ok(Unit)
        override suspend fun reload() = Outcome.Ok(Unit)
        override suspend fun treeNavigate(entryId: String, summarize: Boolean) = Outcome.Ok(TreeNav(entryId))
        override suspend fun runGuest(command: String, timeoutMs: Long) = Outcome.Ok(GuestOutput(0, ""))
        override suspend fun writeReport(fileName: String, text: String) = Outcome.Ok("/tmp/$fileName")
        override suspend fun newSessionInDir(dir: String) = Outcome.Ok(Unit)
    }

    private class Port(private val engine: EngineClient) : SessionChatPort {
        override val draft = MutableStateFlow(DraftState.EMPTY)
        override suspend fun send(mode: SendMode): Outcome<Disposition> = engine.send(draft.value.text, mode)
        override val outgoing: StateFlow<List<OutgoingMessage>> = MutableStateFlow(emptyList())
        override fun retryOutgoing(id: String) {}
        override fun editOutgoing(id: String) {}
        override fun discardOutgoing(id: String) {}
        override suspend fun stop(): Outcome<QueueSnapshot> = engine.stop()
        override val dialogs: StateFlow<List<ExtUiRequest>> = MutableStateFlow(emptyList())
        override fun answerDialog(requestId: String, value: String?, confirmed: Boolean?, cancelled: Boolean) {}
        override val editorTextOffer: StateFlow<String?> = MutableStateFlow(null)
        override fun resolveEditorTextOffer(accept: Boolean) {}
        override val notices = MutableSharedFlow<String>()
    }

    private class Ctx(val host: SessionHostImpl, val engine: FakeScriptedEngine, val panels: Panels, val router: TermPanelRouter, val ran: MutableList<Pair<String, String?>>, val toasts: MutableList<String>)

    private fun TestScope.ctx(scope: CoroutineScope): Ctx {
        val engine = FakeScriptedEngine(scope).also { it.start() }
        val panels = Panels(engine)
        val tracker = PanelSourceTracker(panels)
        val host = SessionHostImpl(
            engine, FakeEngineExtras(), scope, FakePiSettings(), FakeSessionCatalog(engine), Port(engine),
            actionsFactory = { h -> PiActionsImpl(h, Ops(), tracker) },
        )
        val ran = mutableListOf<Pair<String, String?>>()
        val toasts = mutableListOf<String>()
        val router = TermPanelRouter(
            host, TermEnv(panelSource = tracker), host.actions.all.map { it.id }.toSet(), scope,
            runAction = { id, a -> ran += id to a; scope.launch { host.runAction(id, a) } },
            toast = { toasts += it },
        )
        testScheduler.runCurrent()
        return Ctx(host, engine, panels, router, ran, toasts)
    }

    @Test
    fun modelPickSwitchesSessionModel() = runTest(UnconfinedTestDispatcher()) {
        val c = ctx(backgroundScope)
        c.host.runAction("model.select") // 终端里 /model（真实 PiActionsImpl → ModelPanels.modelPicker）
        val top = c.host.overlay.top as OverlayModel.SelectList
        assertEquals("model.select", top.id)
        assertTrue("面板来自 ModelPanels，应走面板事件", c.router.toPanel(top))

        c.router.pick(top, "model:openai/gpt-5") // 点选一项
        testScheduler.runCurrent()

        assertEquals(Triple("model.select", "model:openai/gpt-5", null), c.panels.events.single())
        assertTrue("会话模型真的切换：${c.engine.commands.value}", c.engine.commands.value.any { it.type == "set_model" && it.args["modelId"] == "gpt-5" })
        assertEquals("gpt-5", c.host.chat.value.model?.id)
        assertTrue("不再交给 runAction(model.select, 项)", c.ran.none { it.first == "model.select" })
        assertNull("Done 关闭模型面板", c.host.overlay.top)
    }

    @Test
    fun externalEnterOnModelPickerAlsoSwitches() = runTest(UnconfinedTestDispatcher()) {
        val c = ctx(backgroundScope)
        c.host.runAction("model.select")
        val top = (c.host.overlay.top as OverlayModel.SelectList).copy(selectedKey = "model:anthropic/sonnet")
        c.host.overlay.replaceTop(top)
        overlayEnter(c.host, top, backgroundScope, c.router)
        testScheduler.runCurrent()
        assertEquals("sonnet", c.host.chat.value.model?.id)
    }

    @Test
    fun loginFormSubmitReachesModelPanelsAndKeepsInputOnFailure() = runTest(UnconfinedTestDispatcher()) {
        val c = ctx(backgroundScope)
        c.host.runAction("auth.login") // 终端里 /login
        val list = c.host.overlay.top as OverlayModel.SelectList
        assertEquals("auth.login", list.id)
        c.router.pick(list, "provider:deepseek")
        testScheduler.runCurrent()
        assertEquals("选服务商经面板事件到达 ModelPanels", Triple("auth.login", "provider:deepseek", null), c.panels.events.last())
        val form = c.host.overlay.top as OverlayModel.Form
        assertEquals("provider.form:deepseek", form.id)

        // 填一个错 Key 提交：到达 ModelPanels，失败 Toast → 表单不关、Key 不丢、提示在面板里
        val filled = form.copy(values = mapOf("apiKey" to "sk-bad"))
        c.host.overlay.replaceTop(filled)
        c.router.submitForm(filled)
        testScheduler.runCurrent()
        assertEquals(Triple("provider.form:deepseek", "submit", """{"apiKey":"sk-bad"}"""), c.panels.events.last())
        assertEquals("失败不关表单", "provider.form:deepseek", c.host.overlay.top?.id)
        assertEquals("已填 Key 保留", "sk-bad", (c.host.overlay.top as OverlayModel.Form).values["apiKey"])
        assertEquals("保存失败：Key 无效", c.router.notices.value["provider.form:deepseek"])
        assertTrue("失败提示在面板内，不走全局提示", c.toasts.isEmpty())

        // 改成好 Key 再提交：Done → 关表单，提示清掉
        val good = filled.copy(values = mapOf("apiKey" to "sk-good"))
        c.host.overlay.replaceTop(good)
        c.router.submitForm(good)
        testScheduler.runCurrent()
        assertEquals("auth.login", c.host.overlay.top?.id)
        assertNull(c.router.notices.value["provider.form:deepseek"])
        assertTrue("ModelPanels 面板的选择 / 提交都不交给 runAction", c.ran.none { it.first == "auth.login" || it.first.startsWith("provider.form:") })
    }

    @Test
    fun oauthCloseSendsCancelWhileRunningAndDoneAfterSuccess() = runTest(UnconfinedTestDispatcher()) {
        val c = ctx(backgroundScope)
        c.host.runAction("auth.login")
        c.router.pick(c.host.overlay.top!!, "provider:github-copilot")
        testScheduler.runCurrent()
        val running = c.host.overlay.top as OverlayModel.Text
        assertEquals(FlowState.RUNNING, running.flow)
        c.router.close(running)
        testScheduler.runCurrent()
        assertEquals("进行中关闭发 cancel", Triple("oauth:github-copilot", "cancel", null), c.panels.events.last())
        assertEquals("auth.login", c.host.overlay.top?.id)

        c.panels.oauthStep = FlowState.DONE
        c.router.pick(c.host.overlay.top!!, "provider:github-copilot")
        testScheduler.runCurrent()
        val done = c.host.overlay.top as OverlayModel.Text
        c.router.close(done)
        testScheduler.runCurrent()
        assertEquals("成功后关闭发 done", Triple("oauth:github-copilot", "done", null), c.panels.events.last())
        assertEquals("done 的提示（如已自动选回模型）显示出来", "已登录，已自动选回模型", c.toasts.last())
    }

    @Test
    fun nonModelPanelsStillRunActionEvenWithSameShape() = runTest(UnconfinedTestDispatcher()) {
        val c = ctx(backgroundScope)
        // SessionHost 自己的面板（不是 ModelPanels 产出的）照旧 runAction(面板 id, 值)
        val own = OverlayModel.SelectList("overlay:thinking", "思考档", listOf(SelectGroup(null, listOf(SelectItem("high", "high")))))
        c.host.overlay.push(own)
        assertTrue(!c.router.toPanel(own))
        c.router.pick(own, "high")
        assertEquals("overlay:thinking" to "high", c.ran.last())
        assertTrue(c.panels.events.isEmpty())
    }
}
