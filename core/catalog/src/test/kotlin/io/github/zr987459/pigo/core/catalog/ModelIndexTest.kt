package io.github.zr987459.pigo.core.catalog

import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.core.store.BuiltinCatalog
import io.github.zr987459.pigo.core.store.PiProviderConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIndexTest {
    private val snap = BuiltinCatalog.bundled()

    private fun inputs(
        runtime: List<ModelInfo>? = null,
        key: Set<String> = emptySet(),
        oauth: Set<String> = emptySet(),
        custom: Map<String, PiProviderConfigStore.CustomProvider> = emptyMap(),
    ) = ModelIndex.Inputs(snap, runtime, custom, key, oauth)

    @Test fun everyCatalogModelIsListedEvenWithoutCredentials() {
        val gs = ModelIndex.build(inputs())
        assertEquals(snap.modelCount, gs.sumOf { it.models.size })
        assertTrue(ModelIndex.availableModels(gs).isEmpty())
        assertTrue(gs.first { it.provider == "github-copilot" }.canOAuth)
        assertFalse(gs.first { it.provider == "openai-codex" }.canKey)
    }

    @Test fun runtimeListDecidesAvailabilityAndAddsUnknownModels() {
        val ds = snap.provider("deepseek")!!.models
        val runtime = ds.map { ModelInfo("deepseek", it.id, it.name) } + ModelInfo("deepseek", "deepseek-new-preview")
        val gs = ModelIndex.build(inputs(runtime = runtime, key = setOf("deepseek", "openai")))
        val avail = ModelIndex.availableModels(gs).map { it.key }.toSet()
        // get_available_models 返回的每个模型都在「可用」组
        assertTrue(runtime.all { "${it.provider}/${it.id}" in avail })
        // 引擎在跑时以它为准：openai 宿主有 Key 但 pi 没列出 → 不算可用
        assertTrue(avail.none { it.startsWith("openai/") })
        assertEquals("deepseek", gs.first().provider) // 可用的服务商排最前
        assertEquals(ModelIndex.Source.RUNTIME, gs.first().models.first { it.id == "deepseek-new-preview" }.source)
    }

    @Test fun withoutEngineCredentialsDecide() {
        val gs = ModelIndex.build(inputs(key = setOf("deepseek"), oauth = setOf("github-copilot")))
        val avail = ModelIndex.availableModels(gs).map { it.provider }.toSet()
        assertEquals(setOf("deepseek", "github-copilot"), avail)
    }

    @Test fun customProvidersAreIncluded() {
        val c = PiProviderConfigStore.CustomProvider(
            "bailian", "https://x", "PIGO_KEY_BAILIAN", listOf("qwen-plus"),
            models = listOf(PiProviderConfigStore.CustomModel("qwen-plus", reasoning = true, contextWindow = 131072)),
        )
        val gs = ModelIndex.build(inputs(key = setOf("bailian"), custom = mapOf("bailian" to c)))
        val e = gs.first { it.provider == "bailian" }.models.single()
        assertTrue(e.available && e.reasoning)
        assertEquals(ModelIndex.Source.CUSTOM, e.source)
    }

    @Test fun searchQwenKeepsOnlyQwenRelated() {
        val gs = ModelIndex.search(ModelIndex.build(inputs()), "qwen")
        assertTrue(gs.isNotEmpty())
        for (g in gs) for (m in g.models) {
            val hit = listOf(m.id, m.name, g.provider, g.label).any { it.lowercase().contains("qwen") }
            assertTrue("${g.provider}/${m.id} 与 qwen 无关", hit)
        }
        assertTrue(gs.none { it.provider == "anthropic" })
        assertEquals(ModelIndex.build(inputs()).size, ModelIndex.search(ModelIndex.build(inputs()), "  ").size)
    }

    @Test fun recentAndThinkingOptions() {
        val gs = ModelIndex.build(inputs())
        val r = ModelIndex.recent(gs, listOf("deepseek/deepseek-flash", "nope/x", "groq/llama-3.1-8b-instant"))
        assertEquals(listOf("deepseek/deepseek-flash", "groq/llama-3.1-8b-instant"), r.map { it.key })
        val noReason = r[1]
        assertFalse(noReason.reasoning)
        assertTrue("reasoning=false 时不显示思考强度", ModelIndex.thinkingOptions(noReason, listOf("off", "high")).isEmpty())
        assertEquals(listOf("off", "low", "high", "max"), ModelIndex.thinkingOptions(r[0], null))
        assertTrue(ModelIndex.thinkingOptions(null, listOf("high")).isEmpty())
    }
}
