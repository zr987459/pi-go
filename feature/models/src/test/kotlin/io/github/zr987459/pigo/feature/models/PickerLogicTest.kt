package io.github.zr987459.pigo.feature.models

import io.github.zr987459.pigo.contract.CatalogEntry
import io.github.zr987459.pigo.contract.CostDisplaySettings
import io.github.zr987459.pigo.contract.CustomModel
import io.github.zr987459.pigo.contract.ModelAvailability
import io.github.zr987459.pigo.contract.ModelCost
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型选择页逻辑（与 Compose 仪器测试同一组断言的 JVM 版，CI 外也能跑）。 */
class PickerLogicTest {
    private val catalog = FakeModelCatalog(FakeAuthManager())
    private fun entries() = runBlocking { (catalog.entries() as Outcome.Ok).value }
    private fun providers() = runBlocking { (catalog.providers() as Outcome.Ok).value }

    @Test fun searchQwenLeavesOnlyQwen() {
        val s = PickerLogic.sections(entries(), providers(), emptyList(), "QWEN")
        val rows = s.flatMap { it.rows }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { listOfNotNull(it.model.id, it.model.name, it.model.provider).any { x -> x.lowercase().contains("qwen") } })
    }

    @Test fun readyFirstThenUnconfiguredProvidersAndRecentOnTop() {
        val s = PickerLogic.sections(entries(), providers(), listOf(ModelRef("deepseek", "deepseek-reasoner")), "")
        assertEquals(listOf("recent", "ready"), s.take(2).map { it.key })
        assertTrue(s[1].rows.all { it.availability == ModelAvailability.READY })
        assertTrue(s.drop(2).all { !it.ready && it.provider != null })
        // 搜索时不显示「最近使用」
        assertFalse(PickerLogic.sections(entries(), providers(), listOf(ModelRef("deepseek", "deepseek-reasoner")), "deep").any { it.key == "recent" })
    }

    @Test fun clickingUnconfiguredGoesToProviderConfig() {
        val e = entries().first { it.availability == ModelAvailability.NEEDS_AUTH }
        assertEquals(PickerLogic.Click.Configure(e.model.provider), PickerLogic.onClick(e))
        val r = entries().first { it.availability == ModelAvailability.READY }
        assertEquals(PickerLogic.Click.Select(r.model.ref), PickerLogic.onClick(r))
    }

    @Test fun thinkingHiddenForNonReasoningModels() {
        val chat = entries().first { it.model.id == "deepseek-chat" }
        val reasoner = entries().first { it.model.id == "deepseek-reasoner" }
        val levels = listOf("off", "low", "high")
        assertFalse(PickerLogic.showThinking(chat, levels))
        assertTrue(PickerLogic.showThinking(reasoner, levels))
        assertFalse(PickerLogic.showThinking(null, levels))
    }

    @Test fun textHelpers() {
        assertEquals("128K", PickerLogic.contextText(128_000))
        assertEquals("1M", PickerLogic.contextText(1_000_000))
        assertEquals("1.1M", PickerLogic.contextText(1_048_576))
        val usd = CostDisplaySettings()
        assertEquals("输入 \$0.27 · 输出 \$1.10（每百万 token）", PickerLogic.priceText(ModelCost(0.27, 1.1), usd))
        val cny = CostDisplaySettings(showCny = true, usdToCny = 7.1)
        assertTrue(PickerLogic.priceText(ModelCost(1.0, 2.0), cny)!!.contains("约 ¥7.10"))
        assertEquals("免费 / 套餐内", PickerLogic.priceText(ModelCost(), usd))
        assertEquals("高", PickerLogic.thinkingLabel("high"))
    }

    @Test fun discoverMergeFillsGapsAndAppends() {
        val cur = listOf(CustomModel("a", contextWindow = 1000), CustomModel("b"))
        val found = listOf(CustomModel("a", contextWindow = 9, reasoning = true), CustomModel("b", contextWindow = 200), CustomModel("c"))
        val m = CustomMerge.merge(cur, found)
        assertEquals(listOf("a", "b", "c"), m.map { it.id })
        assertEquals(1000L, m[0].contextWindow) // 用户填的优先
        assertTrue(m[0].reasoning)
        assertEquals(200L, m[1].contextWindow)
    }

    @Test fun providerListSortAndText() {
        val ps = providers()
        val states = runBlocking { mapOf("anthropic" to io.github.zr987459.pigo.contract.AuthState.OAuth()) }
        assertEquals("anthropic", ProviderListLogic.sort(ps, states).first().id)
        assertEquals(listOf("qwen-token-plan-cn"), ProviderListLogic.filter(ps, "通义").map { it.id })
        assertTrue(ProviderListLogic.kindsText(ps.first { it.id == "anthropic" }).contains("账号登录"))
    }
}
