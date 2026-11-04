package io.github.zr987459.pigo.feature.settings

import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.models.ModelPickerTab
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN-r3 §6.1、§7.3：设置页只有「模型」。断言的是 [SettingsScreen]（有 panels 时）逐行渲染的
 * [SettingsModel]（[SettingsModelBuilder.build]），分区 key 取自 [ModelsSettings.root]；
 * 界面渲染本身另由 androidTest `SettingsScreenUiTest` 断言 testTag。
 */
class SettingsOnlyModelsTest {

    private val panels = FakeModelPanelsForSettings()
    private val settings = FakePiSettings(
        schema = FakePiSettings.FIXTURE_SCHEMA,
        initial = mapOf("defaultProvider" to JsonPrimitive("anthropic"), "defaultModel" to JsonPrimitive("claude-sonnet-4-5"), "defaultThinkingLevel" to JsonPrimitive("high")),
    )

    @Test fun rootHasOnlyModelsGroup() {
        val root = ModelsSettings.root()
        assertEquals(1, root.size)
        assertEquals("模型", root.single().title)
    }

    @Test fun pageSectionsComeFromModelsSettingsInOrder() = runTest {
        val m = SettingsModelBuilder.build(panels, settings)
        assertEquals(ModelsSettings.root().single().sections.map { it.key }, m.sections.map { it.key })
        assertEquals(listOf("default", "scoped", "connected", "providers", "custom", "advanced"), m.sections.map { it.key })
        assertEquals(ModelsSettings.FOOTER_HINT, m.footer)
    }

    @Test fun defaultAndScopedRowsShowCurrentValues() = runTest {
        val m = SettingsModelBuilder.build(panels, settings)
        val d = m.section("default")!!.rows.single()
        assertEquals("anthropic/claude-sonnet-4-5 · high", d.detail)
        assertEquals(SettingsModel.Open.Picker(ModelPickerTab.ALL), d.open)
        val s = m.section("scoped")!!.rows.single()
        assertEquals("2 个", s.detail)
        assertEquals(SettingsModel.Open.Picker(ModelPickerTab.SCOPED), s.open)
        // 没有 settings.json 时显示「未设置」
        assertEquals("未设置", SettingsModelBuilder.build(panels, null).section("default")!!.rows.single().detail)
    }

    @Test fun all42ProvidersVisibleAndConnectedFirst() = runTest {
        val m = SettingsModelBuilder.build(panels, settings)
        val all = m.section("providers")!!
        assertEquals("全部服务商（42）", all.title)
        assertTrue(all.searchable)
        assertEquals(42, all.rows.size)
        assertEquals(42, all.rows.map { it.key }.toSet().size)
        assertEquals(setOf("provider:anthropic", "provider:deepseek"), all.rows.take(2).map { it.key }.toSet())
        assertTrue(all.rows.take(2).all { it.connected == true })
        assertEquals(listOf("provider:anthropic", "provider:deepseek"), m.section("connected")!!.rows.map { it.key }.sorted())
        // 每一行都可配置：点了交给 ModelPanels（面板 id 与 /login 相同）
        assertTrue(all.rows.all { it.open == SettingsModel.Open.Event("auth.login", it.key) })
    }

    @Test fun searchIsPassedToProviderListAndFiltersRows() = runTest {
        val m = SettingsModelBuilder.build(panels, settings, query = "qw")
        assertEquals("qw", panels.queries.last())
        assertEquals("qw", m.query)
        val rows = m.section("providers")!!.rows
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.key.startsWith("provider:qwen") })
        assertTrue(m.section("connected")!!.rows.isEmpty())
        assertEquals("全部服务商（42）", m.section("providers")!!.title)
    }

    @Test fun customSectionHasAddAndExisting() = runTest {
        val rows = SettingsModelBuilder.build(panels, settings).section("custom")!!.rows
        assertEquals("custom", rows.first().key)
        assertEquals("＋ 添加自定义服务商", rows.first().label)
        assertEquals("custom:my-vllm", rows[1].key)
        assertEquals("http://10.0.0.2:8000", rows[1].detail)
    }

    @Test fun advancedHasThreeItems() = runTest {
        val adv = SettingsModelBuilder.build(panels, settings).section("advanced")!!.rows
        assertEquals(ModelsSettings.ADVANCED_ITEMS, adv.map { it.label })
        assertEquals(SettingsModel.Open.Event("model.select", "refresh"), adv.last().open)
    }

    @Test fun noRemovedSettingsLeak() = runTest {
        val m = SettingsModelBuilder.build(panels, settings)
        val titles = m.sections.map { it.title } + m.sections.filter { it.key !in setOf("providers", "connected", "custom") }.flatMap { s -> s.rows.map { it.label } }
        for (banned in listOf("代理", "费用", "回收站", "运行环境", "后台", "主题", "会话默认", "汇率")) {
            assertFalse("$banned 不应出现在设置页", titles.any { banned in it })
        }
    }

    @Test fun formHelpers() {
        assertEquals(listOf("test" to "测试"), formActions("provider.form:deepseek"))
        assertEquals(listOf("discover", "test"), formActions("provider.custom:my").map { it.first })
        assertEquals("""{"k":"v"}""", formJson(mapOf("k" to "v")))
    }
}
