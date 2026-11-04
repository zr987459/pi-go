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

    @Test fun all42ProvidersRenderedAndSearchQwLeavesQwen() {
        show()
        rule.waitUntil(5_000) { rowTags("providers").isNotEmpty() }
        assertEquals(42, rowTags("providers").size)
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(SettingsTags.SEARCH))
        rule.onNodeWithTag(SettingsTags.SEARCH).performTextInput("qw")
        rule.waitUntil(5_000) { rowTags("providers").size in 1..41 }
        val tags = rowTags("providers")
        assertTrue(tags.toString(), tags.all { it.contains("provider:qwen") })
    }

    @Test fun tappingProviderOpensItsPanel() {
        show()
        rule.waitUntil(5_000) { rowTags("providers").isNotEmpty() }
        val tag = SettingsTags.row("providers/provider:deepseek")
        rule.onNodeWithTag("settings:list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
        rule.waitUntil(5_000) { panels.events.isNotEmpty() }
        assertEquals("auth.login/provider:deepseek", panels.events.last())
        rule.onNodeWithTag(SettingsTags.PANEL).assertIsDisplayed()
    }
}
