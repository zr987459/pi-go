package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.AuthState
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.Outcome
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #19 审查第 2 轮：服务商表单按钮、OAuth 推进、自定义服务商 Key 不串、常用切换保持页签。 */
class RealModelPanelsFix2Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager(mapOf("deepseek" to AuthState.ApiKey("1234")))
    private val catalog = FakeModelCatalog(auth)
    private val store = KeyCapturingStore(FakeCustomProviderStore())
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val schema = object : PiSettingsSchema {
        override val specs = listOf("enabledModels" to PiSettingType.StrList, "defaultProvider" to PiSettingType.Str,
            "defaultModel" to PiSettingType.Str, "defaultThinkingLevel" to PiSettingType.Str)
            .map { (k, t) -> PiSettingSpec(k, "模型", k, t) }
    }
    private val settings = FakePiSettings(schema)
    private val panels = RealModelPanels(catalog, auth, store, engine, scope, settings)

    @After fun tearDown() = scope.cancel()

    class KeyCapturingStore(private val d: FakeCustomProviderStore) : CustomProviderStore by d {
        val upserts = mutableListOf<Pair<String, String?>>()
        override suspend fun upsert(provider: CustomProvider, rawKey: String?): Outcome<Unit> {
            upserts += provider.id to rawKey
            return d.upsert(provider, rawKey)
        }
    }

    private fun ActionResult.overlay() = (this as ActionResult.Overlay).model

    @Test fun providerFormOffersOAuthAndClear() = runBlocking {
        val anthropic = panels.providerForm("anthropic") as OverlayModel.Form
        assertEquals(listOf("test", "oauth"), anthropic.actions.map { it.actionId })
        val deepseek = panels.providerForm("deepseek") as OverlayModel.Form
        assertEquals(listOf("test", "clear"), deepseek.actions.map { it.actionId })
        // 「清除」真的清掉凭据
        panels.onPanelEvent("provider.form:deepseek", "clear", null)
        assertEquals(AuthState.None, auth.state("deepseek"))
        // 「账号登录」进入 OAuth 面板
        assertTrue(panels.onPanelEvent("provider.form:anthropic", "oauth", "{}").overlay().id == "oauth:anthropic")
    }

    @Test fun oauthPanelAdvancesThroughRefreshToDone() = runBlocking {
        panels.oauthFlow("anthropic")
        // 渲染器每秒发 refresh：最终出现授权链接，然后是填代码表单
        var p: OverlayModel = panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay()
        withTimeout(3_000) {
            while (!(p is OverlayModel.Form)) { delay(20); p = panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay() }
        }
        assertTrue(panels.oauthSteps.value["anthropic"].toString(), p is OverlayModel.Form)
        // 表单的「提交」（key=submit）当作授权码
        panels.onPanelEvent("oauth:anthropic", "submit", """{"code":"ok"}""")
        withTimeout(3_000) {
            while ((p as? OverlayModel.Text)?.text?.startsWith("已登录") != true) { delay(20); p = panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay() }
        }
        assertTrue(auth.state("anthropic") is AuthState.OAuth)
    }

    @Test fun oauthPanelShowsUrlAndFailure() = runBlocking {
        panels.oauthFlow("anthropic")
        withTimeout(3_000) { while (panels.oauthSteps.value["anthropic"] !is io.github.zr987459.pigo.contract.OAuthStep.NeedCode) delay(20) }
        panels.onPanelEvent("oauth:anthropic", "code", "bad")
        var t = ""
        withTimeout(3_000) { while (!t.startsWith("登录失败")) { delay(20); t = (panels.onPanelEvent("oauth:anthropic", "refresh", null).overlay() as? OverlayModel.Text)?.text.orEmpty() } }
        assertTrue(t, t.contains("授权码无效"))
    }

    @Test fun newCustomFormNeverReusesPreviousKey() = runBlocking {
        // 新建 A：填了 Key，没保存就关掉（渲染器不一定发 cancel）
        panels.onPanelEvent("auth.login", "custom", null)
        panels.onPanelEvent("provider.custom", "discover", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        // 再点「＋ 添加」新建 B，不填 Key，保存
        val form = panels.onPanelEvent("auth.login", "custom", null).overlay() as OverlayModel.Form
        assertTrue(form.values.isEmpty() || form.values["baseUrl"].isNullOrEmpty())
        panels.onPanelEvent("provider.custom", "submit", """{"id":"b","baseUrl":"http://b/v1","models":"m1"}""")
        assertEquals("b" to null, store.upserts.last())
    }

    @Test fun keyDroppedWhenBaseUrlOrIdChanges() = runBlocking {
        panels.onPanelEvent("auth.login", "custom", null)
        panels.onPanelEvent("provider.custom", "discover", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        // 同一个表单里把地址改成别的服务器再保存：A 的 Key 不得发给新地址
        panels.onPanelEvent("provider.custom", "submit", """{"id":"a","baseUrl":"http://evil/v1","models":"m1"}""")
        assertEquals("a" to null, store.upserts.last())
        // 地址不变时 Key 正常带上
        panels.onPanelEvent("provider.custom", "discover", """{"id":"c","baseUrl":"http://c/v1","apiKey":"sk-CCCC"}""")
        panels.onPanelEvent("provider.custom", "submit", """{"id":"c","baseUrl":"http://c/v1","models":"m1"}""")
        assertEquals("c" to "sk-CCCC", store.upserts.last())
    }

    @Test fun cancelForgetsDraftKey() = runBlocking {
        panels.onPanelEvent("provider.custom", "discover", """{"id":"a","baseUrl":"http://a/v1","apiKey":"sk-AAAA"}""")
        panels.onPanelEvent("provider.custom", "cancel", null)
        panels.onPanelEvent("provider.custom", "submit", """{"id":"a","baseUrl":"http://a/v1","models":"m1"}""")
        assertNull(store.upserts.last().second)
    }

    @Test fun starKeepsCurrentTab() = runBlocking {
        val all = panels.modelPicker(ModelPickerTab.ALL, "") as OverlayModel.SelectList
        val ref = all.groups.flatMap { it.items }.first { it.key.startsWith("model:") }.key.removePrefix("model:")
        val after = panels.onPanelEvent("model.select", "star:$ref", null).overlay() as OverlayModel.SelectList
        assertEquals("全部", after.tab)
        assertTrue(after.groups.flatMap { it.items }.first { it.key == "model:$ref" }.starred == true)
    }

    @Test fun defaultEventWritesSettingsWithoutSwitchingSessionModel() = runBlocking {
        val before = engine.chat.value.model
        val all = panels.modelPicker(ModelPickerTab.ALL, "") as OverlayModel.SelectList
        val ref = all.groups.flatMap { it.items }.map { it.key.removePrefix("model:") }.first { "model:$it" != all.selectedKey }
        panels.onPanelEvent("model.select", "default:$ref", null)
        assertEquals(ref.substringBefore('/'), (settings.values.value["defaultProvider"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(before, engine.chat.value.model)
    }
}
