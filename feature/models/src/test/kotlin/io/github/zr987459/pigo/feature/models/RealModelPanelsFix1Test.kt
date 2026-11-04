package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.CustomProvider
import io.github.zr987459.pigo.contract.CustomProviderStore
import io.github.zr987459.pigo.contract.ErrCode
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
import io.github.zr987459.pigo.contract.settings.PiSettings
import io.github.zr987459.pigo.contract.settings.PiSettingsSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #19 审查第 1 轮：Important 3、4 与 Nit 1–3 的回归测试。 */
class RealModelPanelsFix1Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val auth = FakeAuthManager()
    private val catalog = FakeModelCatalog(auth)
    private val store = CountingStore(FakeCustomProviderStore())
    private val engine = FakeEngineClient(scope, stepDelayMs = 0)
    private val schema = object : PiSettingsSchema {
        override val specs = listOf("enabledModels" to PiSettingType.StrList, "defaultProvider" to PiSettingType.Str,
            "defaultModel" to PiSettingType.Str, "defaultThinkingLevel" to PiSettingType.Str)
            .map { (k, t) -> PiSettingSpec(k, "模型", k, t) }
    }
    private val settings = FakePiSettings(schema)
    private val panels = RealModelPanels(catalog, auth, store, engine, scope, settings)

    @After fun tearDown() = scope.cancel()

    private fun ActionResult.overlay() = (this as ActionResult.Overlay).model
    private fun ActionResult.text(): String = when (this) {
        is ActionResult.Toast -> text
        is ActionResult.Overlay -> "overlay:${model.id}"
        else -> toString()
    }

    // ---------------- Important 3：页签与键帽

    @Test fun tabLabelsAndNamesNeverThrow() = runBlocking {
        for ((raw, want) in listOf("全部" to "全部", "常用" to "常用", "最近" to "最近", "SCOPED" to "常用", "recent" to "最近", "乱写" to "全部")) {
            val m = panels.onPanelEvent(RealModelPanels.ID_MODEL, "tab:$raw").overlay() as OverlayModel.SelectList
            assertEquals(raw, want, m.tab)
        }
    }

    @Test fun everyRenderedNodeAndKeyCapRoundTripsWithoutUnknown() = runBlocking {
        val all: List<OverlayModel> = listOf(
            panels.modelPicker(), panels.modelPicker(ModelPickerTab.SCOPED), panels.modelPicker(ModelPickerTab.RECENT),
            panels.providerList(), panels.providerList("qw"), panels.providerForm("deepseek"), panels.customProviderForm(null),
            panels.logoutPicker(),
        )
        for (m in all) {
            val firstItem = (m as? OverlayModel.SelectList)?.groups?.flatMap { it.items }?.firstOrNull()?.key
            for (node in ModelPanelsRenderFixtureTest.calmNodes(m)) {
                val rest = node.removePrefix("overlay:${m.id}").removePrefix("/")
                if (rest.isEmpty() || rest.startsWith("field:") || rest == "text") continue
                val (key, value) = when {
                    rest.startsWith("cap:") -> rest.removePrefix("cap:") to firstItem
                    rest == "submit" -> "submit" to "{}"
                    else -> rest to null
                }
                val r = panels.onPanelEvent(m.id, key, value).text()
                assertFalse("${m.id} / $key → $r", r.startsWith("未知"))
            }
        }
    }

    @Test fun keyCapsActOnHighlightedModel() = runBlocking {
        val ref = "deepseek/deepseek-chat"
        panels.onPanelEvent(RealModelPanels.ID_MODEL, RealModelPanels.CAP_STAR, "model:$ref")
        assertTrue(settings.values.value["enabledModels"].toString().contains(ref))
        val r = panels.onPanelEvent(RealModelPanels.ID_MODEL, "${RealModelPanels.ID_MODEL}:${RealModelPanels.CAP_DEFAULT}", "model:$ref")
        assertTrue(r.text(), r.text().startsWith("已设为默认"))
        assertEquals(JsonPrimitive("deepseek"), settings.values.value["defaultProvider"])
        assertEquals(JsonPrimitive("deepseek-chat"), settings.values.value["defaultModel"])
        val providers = panels.onPanelEvent(RealModelPanels.ID_MODEL, RealModelPanels.CAP_PROVIDERS).overlay()
        assertEquals(RealModelPanels.ID_PROVIDERS, providers.id)
        val form = panels.onPanelEvent(RealModelPanels.ID_PROVIDERS, RealModelPanels.CAP_SELECT, "provider:deepseek").overlay()
        assertEquals("provider.form:deepseek", form.id)
    }

    // ---------------- Nit 1：搜索保持页签

    @Test fun searchKeepsCurrentTab() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_MODEL, "tab:常用")
        val m = panels.onPanelEvent(RealModelPanels.ID_MODEL, "query", "deep").overlay() as OverlayModel.SelectList
        assertEquals("常用", m.tab)
        assertEquals("deep", m.query)
    }

    // ---------------- Nit 2：设为默认写三个键，失败回滚

    @Test fun setDefaultWritesThinkingLevelToo() = runBlocking {
        panels.onPanelEvent(RealModelPanels.ID_MODEL, RealModelPanels.CAP_DEFAULT, "model:deepseek/deepseek-chat")
        assertEquals(JsonPrimitive("medium"), settings.values.value["defaultThinkingLevel"])
    }

    @Test fun setDefaultRollsBackOnPartialFailure() = runBlocking {
        val base = FakePiSettings(schema, mapOf("defaultProvider" to JsonPrimitive("anthropic"), "defaultModel" to JsonPrimitive("claude")))
        val failing = object : PiSettings by base {
            override suspend fun set(key: String, value: JsonElement?): Outcome<Unit> =
                if (key == "defaultModel" && value == JsonPrimitive("deepseek-chat")) Outcome.Err(ErrCode.RPC_REJECTED, "写入失败") else base.set(key, value)
        }
        val p = RealModelPanels(catalog, auth, store, engine, scope, failing)
        val r = p.onPanelEvent(RealModelPanels.ID_MODEL, RealModelPanels.CAP_DEFAULT, "model:deepseek/deepseek-chat")
        assertTrue(r.text(), r.text().startsWith("保存失败"))
        assertEquals(JsonPrimitive("anthropic"), base.values.value["defaultProvider"])
        assertEquals(JsonPrimitive("claude"), base.values.value["defaultModel"])
        assertNull(base.values.value["defaultThinkingLevel"])
    }

    // ---------------- Important 4：保留能力、测试不落盘

    private suspend fun seed() {
        store.upsert(
            CustomProvider("my", "http://old:8000/v1", models = listOf(CustomModel("a", contextWindow = 32_000, reasoning = true, input = setOf("text", "image")))),
            "sk-old",
        )
        store.upserts.clear()
    }

    @Test fun editingCustomProviderKeepsModelCapabilities() = runBlocking {
        seed()
        val form = panels.customProviderForm("my") as OverlayModel.Form
        val r = panels.onPanelEvent(form.id, "submit", """{"baseUrl":"http://old:8000/v1","api":"openai-completions","models":"a,b"}""")
        assertTrue(r.text(), r.text().startsWith("已保存"))
        val saved = store.list().single()
        val a = saved.models.first { it.id == "a" }
        assertTrue(a.reasoning)
        assertEquals(setOf("text", "image"), a.input)
        assertEquals(32_000L, a.contextWindow)
        assertTrue(saved.models.any { it.id == "b" })
        assertNull("留空 Key 不覆盖", store.upserts.single().second)
    }

    @Test fun discoveredCapabilitiesAreSaved() = runBlocking {
        val id = RealModelPanels.ID_CUSTOM
        panels.onPanelEvent(id, "discover", """{"id":"q","baseUrl":"http://q/v1","api":"openai-completions","apiKey":"sk-q"}""")
        panels.onPanelEvent(id, "submit", """{"id":"q","baseUrl":"http://q/v1","api":"openai-completions","models":"qwen-plus,qwen-max"}""")
        val m = store.list().single().models
        assertTrue(m.first { it.id == "qwen-max" }.reasoning)
        assertEquals(131_072L, m.first { it.id == "qwen-plus" }.contextWindow)
    }

    @Test fun testConnectionDoesNotPersist() = runBlocking {
        seed()
        val r = panels.onPanelEvent("${RealModelPanels.ID_CUSTOM}:my", "test",
            """{"baseUrl":"http://wrong/v1","api":"openai-completions","apiKey":"sk-wrong","models":"a"}""")
        assertTrue(r.text(), r.text().contains("未保存"))
        assertTrue("测试不得写 models.json / Key", store.upserts.isEmpty())
        assertEquals("http://old:8000/v1", store.list().single().baseUrl)
    }

    // ---------------- Nit 3：Key 密文、不进表单值

    @Test fun apiKeyIsSecretAndNeverInFormValues() = runBlocking {
        val pf = panels.providerForm("deepseek") as OverlayModel.Form
        assertTrue(pf.fields.first { it.key == RealModelPanels.KEY_FIELD }.secret)
        val id = RealModelPanels.ID_CUSTOM
        val after = panels.onPanelEvent(id, "discover", """{"id":"z","baseUrl":"http://z/v1","api":"openai-completions","apiKey":"sk-secret-123"}""").overlay() as OverlayModel.Form
        assertTrue(after.fields.first { it.key == RealModelPanels.KEY_FIELD }.secret)
        assertFalse(after.values.containsKey(RealModelPanels.KEY_FIELD))
        assertFalse(after.toString().contains("sk-secret-123"))
        // 表单里没再填 Key，保存时仍用刚才填的（只在内存里）
        panels.onPanelEvent(id, "submit", """{"id":"z","baseUrl":"http://z/v1","api":"openai-completions","models":"qwen-plus"}""")
        assertEquals("sk-secret-123", store.upserts.last().second)
    }

    /** 记录 upsert 调用（provider id, rawKey）。 */
    class CountingStore(private val d: FakeCustomProviderStore) : CustomProviderStore by d {
        val upserts = mutableListOf<Pair<String, String?>>()
        override suspend fun upsert(provider: CustomProvider, rawKey: String?): Outcome<Unit> {
            upserts += provider.id to rawKey
            return d.upsert(provider, rawKey)
        }
    }
}
