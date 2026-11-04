package io.github.zr987459.pigo.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.fake.FakePiSettings
import io.github.zr987459.pigo.contract.fake.FakeProviderConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §6.1 设置页界面测试：[SettingsScreen] 有 panels 时画的是「模型」各分区，
 * 42 个服务商可见、搜索「qw」只剩 qwen、点行打开该服务商的面板。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenUiTest {
    @get:Rule val rule = createComposeRule()

    private val panels = FakeModelPanelsForSettings()

    private fun show() = rule.setContent {
        SettingsScreen(providers = FakeProviderConfigStore(), panels = panels, piSettings = FakePiSettings())
    }

    private fun rowTags(section: String): List<String> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag), useUnmergedTree = true)
            .fetchSemanticsNodes().mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
            .filter { it.startsWith(SettingsTags.row("$section/")) }

    @Test fun rootShowsOnlyModelSections() {
        show()
        rule.waitUntil(5_000) { rowTags("default").isNotEmpty() }
        for (key in ModelsSettings.root().single().sections.map { it.key }) {
            rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(SettingsTags.section(key)))
            rule.onNodeWithTag(SettingsTags.section(key)).assertIsDisplayed()
        }
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(SettingsTags.FOOTER))
        rule.onNodeWithText(ModelsSettings.FOOTER_HINT).assertIsDisplayed()
    }

    /** LazyColumn 只组合可见项：先滚到该分区再取行。 */
    private fun scrollTo(section: String) {
        rule.waitUntil(5_000) { rowTags("default").isNotEmpty() }
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(SettingsTags.section(section)))
        rule.waitUntil(5_000) { rowTags(section).isNotEmpty() }
    }

    @Test fun all42ProvidersRenderedAndSearchQwLeavesQwen() {
        show()
        scrollTo("providers")
        assertEquals(42, rowTags("providers").size)
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(SettingsTags.SEARCH))
        rule.onNodeWithTag(SettingsTags.SEARCH).performTextInput("qw")
        rule.waitUntil(5_000) { rowTags("providers").size in 1..41 }
        rule.waitForIdle()
        val tags = rowTags("providers")
        assertTrue(tags.toString(), tags.all { it.contains("provider:qwen") })
    }

    @Test fun tappingProviderOpensItsPanel() {
        show()
        scrollTo("providers")
        val tag = SettingsTags.row("providers/provider:deepseek")
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(5_000) { panels.events.isNotEmpty() }
        assertEquals("auth.login/provider:deepseek", panels.events.last())
        rule.onNodeWithTag(SettingsTags.PANEL).assertIsDisplayed()
    }

    // ---------------- 审查第 2 轮 Important 1：设置页面板交互

    private fun openRow(section: String, key: String) {
        scrollTo(section)
        val tag = SettingsTags.row("$section/$key")
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(SettingsTags.PANEL)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun defaultModelPickWritesDefaultNotSessionModel() {
        show()
        openRow("default", "default")
        rule.onNodeWithTag(SettingsTags.panelItem("model:openai/gpt-5")).performClick()
        rule.waitUntil(5_000) { panels.events.isNotEmpty() }
        assertEquals("model.select/default:openai/gpt-5", panels.events.last())
        // 「选择」键帽（会切当前会话模型）在这里不出现
        assertTrue(rule.onAllNodes(hasTestTag(SettingsTags.cap("select"))).fetchSemanticsNodes().isEmpty())
    }

    @Test fun scopedPickTogglesEnabledModels() {
        show()
        openRow("scoped", "scoped")
        rule.onNodeWithTag(SettingsTags.panelItem("model:openai/gpt-5")).performClick()
        rule.waitUntil(5_000) { panels.events.isNotEmpty() }
        assertEquals("model.select/star:openai/gpt-5", panels.events.last())
    }

    @Test fun keyCapsActOnHighlightedItem() {
        val picker = io.github.zr987459.pigo.contract.session.OverlayModel.SelectList(
            id = "model.select", title = "模型", selectedKey = "model:a/x",
            groups = listOf(io.github.zr987459.pigo.contract.session.SelectGroup(null, listOf(
                io.github.zr987459.pigo.contract.session.SelectItem("model:a/x", "x"),
                io.github.zr987459.pigo.contract.session.SelectItem("model:b/y", "y"),
            ))),
            keyCaps = listOf(io.github.zr987459.pigo.contract.session.KeyCap("Ctrl+S", "设为默认", "default")),
        )
        val got = mutableListOf<Pair<String, String?>>()
        rule.setContent { PanelDialog(picker, onDismiss = {}, onEvent = { k, v -> got += k to v }) }
        rule.onNodeWithTag(SettingsTags.panelItem("model:b/y")).performClick()
        rule.onNodeWithTag(SettingsTags.cap("default")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("default" to "model:b/y"), got)
    }

    @Test fun providerFormHasOAuthAndClearAndToastKeepsForm() {
        show()
        openRow("connected", "provider:anthropic")
        rule.onNodeWithTag(SettingsTags.cap("oauth")).assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.cap("clear")).assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.cap("test")).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(SettingsTags.PANEL_MESSAGE)).fetchSemanticsNodes().isNotEmpty() }
        // Toast 不关面板：表单还在，消息显示在面板里
        rule.onNodeWithTag(SettingsTags.PANEL).assertIsDisplayed()
        rule.onNodeWithText("✓ 连接成功").assertIsDisplayed()
    }

    @Test fun oauthPanelPollsUntilUrlAndOpensIt() {
        val opened = mutableListOf<String>()
        rule.setContent { ModelsSettingsPage(panels = panels, piSettings = FakePiSettings(), openUrl = { opened += it }) }
        openRow("connected", "provider:anthropic")
        rule.onNodeWithTag(SettingsTags.cap("oauth")).performClick()
        rule.waitUntil(10_000) { opened.isNotEmpty() }
        assertEquals("https://example.com/oauth/authorize", opened.single())
        rule.onNodeWithTag(SettingsTags.OPEN_URL).assertIsDisplayed()
        assertTrue(panels.events.count { it == "oauth:anthropic/refresh" } >= 2)
    }
}
