package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.OAuthStep
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeCustomProviderStore
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.settings.PiSettingSpec
import io.github.zr987459.pigo.contract.settings.PiSettingType
import io.github.zr987459.pigo.contract.settings.PiSettingsSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN-r3 §8.5 r3/models：OAuth 冒烟、自定义服务商发现与测试、未配置模型点选 → 配置 → 自动选回、生成中切模型「下一轮生效」。 */
class RealModelPanelsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager()
    private val catalog = FakeModelCatalog(auth)
    private val custom = FakeCustomProviderStore()
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val settings = FakePiSettings(
        schema = object : PiSettingsSchema {
            override val specs = listOf("enabledModels" to PiSettingType.StrList, "defaultProvider" to PiSettingType.Str, "defaultModel" to PiSettingType.Str)
                .map { (k, t) -> PiSettingSpec(k, "模型", k, t) }
        },
    )
    private var generating = false
    private val credentialEvents = mutableListOf<String>()
    private val panels = RealModelPanels(
        catalog, auth, custom, engine, scope, settings,
        isGenerating = { generating },
        onCredentialsChanged = { credentialEvents += it },
    )

    @After fun tearDown() = scope.cancel()

    private fun ActionResult.toast() = (this as ActionResult.Toast).text
    private fun ActionResult.overlay() = (this as ActionResult.Overlay).model

    @Test fun pickerListsConfiguredFirstAndUnconfiguredGreyed() = runBlocking {
        val m = panels.modelPicker() as OverlayModel.SelectList
        assertEquals(RealModelPanels.ID_MODEL, m.id)
        assertEquals(listOf("全部", "常用", "最近"), m.tabs)
        assertEquals("已配置", m.groups.first().title)
        assertTrue(m.groups.first().items.all { it.key.startsWith("model:deepseek/") })
        assertTrue(m.groups.drop(1).all { g -> g.title!!.endsWith("未配置") })
        val q = panels.modelPicker(ModelPickerTab.ALL, "son") as OverlayModel.SelectList
        assertEquals("son", q.query)
        val hits = q.groups.flatMap { it.items }
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.map { it.key + it.label }.toString(), hits.all { (it.key + it.label + it.detail).lowercase().contains("son") })
    }

    @Test fun switchWhileGeneratingSaysNextTurn() = runBlocking {
        generating = true
        val r = panels.onPanelEvent(RealModelPanels.ID_MODEL, "model:deepseek/deepseek-reasoner")
        assertTrue(r.toast(), r.toast().endsWith("下一轮生效"))
        assertEquals("deepseek-reasoner", engine.chat.value.model?.id)
        generating = false
        val r2 = panels.onPanelEvent(RealModelPanels.ID_MODEL, "model:deepseek/deepseek-chat")
        assertFalse(r2.toast().contains("下一轮生效"))
        assertEquals(ModelRef("deepseek", "deepseek-chat"), catalog.recent().first())
    }

    @Test fun unconfiguredTapConfiguresThenAutoSelectsBack() = runBlocking {
        val r = panels.onPanelEvent(RealModelPanels.ID_MODEL, "model:qwen-token-plan-cn/qwen3-coder-plus")
        val form = r.overlay() as OverlayModel.Form
        assertEquals("provider.form:qwen-token-plan-cn", form.id)
        assertEquals(ModelRef("qwen-token-plan-cn", "qwen3-coder-plus"), panels.pending.value)
        assertEquals("fake-model", engine.chat.value.model?.id) // 还没切
        val saved = panels.onPanelEvent(form.id, "submit", """{"apiKey":"sk-abcdef9876"}""")
        assertTrue(saved.toast(), saved.toast().startsWith("已切换到 Qwen3 Coder Plus"))
        assertEquals("qwen3-coder-plus", engine.chat.value.model?.id)
        assertEquals(null, panels.pending.value)
        assertEquals(listOf("qwen-token-plan-cn"), credentialEvents)
    }

    @Test fun oauthSmokeWithFakeCallbackAutoSelectsPending() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_MODEL, "model:anthropic/claude-sonnet-4-5")
        val first = panels.onPanelEvent("provider.form:anthropic", "oauth").overlay()
        assertEquals("oauth:anthropic", first.id)
        withTimeout(5_000) { panels.oauthSteps.first { it["anthropic"] is OAuthStep.NeedCode } }
        val form = panels.onPanelEvent("oauth:anthropic", "refresh").overlay()
        assertTrue(form is OverlayModel.Form)
        panels.onPanelEvent("oauth:anthropic", "code", """{"code":"ok"}""")
        withTimeout(5_000) { panels.oauthSteps.first { it["anthropic"] is OAuthStep.Done } }
        withTimeout(5_000) { engine.chat.first { it.model?.id == "claude-sonnet-4-5" } }
        assertTrue(auth.state("anthropic") is AuthState.OAuth)
        val done = panels.onPanelEvent("oauth:anthropic", "refresh").overlay() as OverlayModel.Text
        assertTrue(done.text.contains("fake@example.com"))
    }

    @Test fun oauthDeviceCodeShowsCode() = runBlocking {
        panels.oauthFlow("github-copilot")
        withTimeout(5_000) { panels.oauthSteps.first { it["github-copilot"] is OAuthStep.Done } }
        assertTrue(auth.state("github-copilot").configured)
    }

    @Test fun customProviderDiscoverAndTest() = runBlocking {
        val open = panels.onPanelEvent(RealModelPanels.ID_PROVIDERS, "custom").overlay() as OverlayModel.Form
        assertEquals(RealModelPanels.ID_CUSTOM, open.id)
        val vals = """{"id":"my-vllm","baseUrl":"http://10.0.0.2:8000/v1","api":"openai-completions","apiKey":"sk-local-1"}"""
        val discovered = panels.onPanelEvent(open.id, "discover", vals).overlay() as OverlayModel.Form
        assertEquals("qwen-plus,qwen-max", discovered.values["models"])
        val withModels = """{"id":"my-vllm","baseUrl":"http://10.0.0.2:8000/v1","api":"openai-completions","apiKey":"sk-local-1","models":"qwen-plus,qwen-max"}"""
        val tested = panels.onPanelEvent(open.id, "test", withModels).toast()
        assertTrue(tested, tested.startsWith("✓ 连接成功"))
        assertTrue("测试不落盘", custom.list().isEmpty())
        val saved = panels.onPanelEvent(open.id, "submit", withModels).toast()
        assertTrue(saved, saved.startsWith("已保存"))
        assertEquals(listOf("qwen-plus", "qwen-max"), custom.list().single().models.map { it.id })
        // 已保存后、表单未改：用已存配置做完整测试
        val tested2 = panels.onPanelEvent("${RealModelPanels.ID_CUSTOM}:my-vllm", "test", """{"baseUrl":"http://10.0.0.2:8000/v1","api":"openai-completions","models":"qwen-plus"}""").toast()
        assertTrue(tested2, tested2.startsWith("✓ 连接正常"))
        val list = panels.providerList() as OverlayModel.SelectList
        assertTrue(list.groups.last().items.any { it.key == "custom:my-vllm" })
        // 无 Key 发现失败给出中文提示
        val fail = panels.onPanelEvent(RealModelPanels.ID_CUSTOM, "discover", """{"baseUrl":"http://x","api":"openai-completions"}""").toast()
        assertTrue(fail.startsWith("发现模型失败"))
    }

    @Test fun providerListSearchAndLogout() = runBlocking {
        val all = panels.providerList() as OverlayModel.SelectList
        assertEquals("已连接", all.groups.first().title)
        assertTrue(all.groups.first().items.single().detail!!.contains("1234"))
        val qw = panels.providerList("qw") as OverlayModel.SelectList
        assertEquals(listOf("provider:qwen-token-plan-cn"), qw.groups.flatMap { it.items }.filter { it.key.startsWith("provider:") }.map { it.key })
        val lo = panels.logoutPicker() as OverlayModel.SelectList
        assertEquals(listOf("provider:deepseek"), lo.groups.single().items.map { it.key })
        panels.onPanelEvent(RealModelPanels.ID_LOGOUT, "provider:deepseek")
        assertFalse(auth.state("deepseek").configured)
    }

    @Test fun scopedAndDefaultWriteSettingsJson() = runBlocking {
        val r = panels.onPanelEvent(RealModelPanels.ID_MODEL, "star:deepseek/deepseek-chat").overlay() as OverlayModel.SelectList
        assertEquals("常用", r.tab)
        assertEquals(listOf("model:deepseek/deepseek-chat"), r.groups.single().items.map { it.key })
        panels.onPanelEvent(RealModelPanels.ID_MODEL, "default:deepseek/deepseek-chat")
        assertTrue(settings.writes.contains("defaultProvider=\"deepseek\""))
        assertTrue(settings.writes.contains("defaultModel=\"deepseek-chat\""))
    }
}
