package io.github.zr987459.pigo.feature.models

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.fake.FakeAppSettingsStore
import io.github.zr987459.pigo.contract.fake.FakeAuthManager
import io.github.zr987459.pigo.contract.fake.FakeModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** feature-gap W2「界面」验收：独立运行模型选择页（Fake 目录）。 */
@RunWith(AndroidJUnit4::class)
class ModelPickerUiTest {
    @get:Rule val rule = createComposeRule()

    private fun rowTags(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
        .fetchSemanticsNodes().mapNotNull { node -> node.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
        .filter { it.startsWith(ModelTags.ROW_PREFIX) }

    private fun show(
        current: ModelRef?,
        onConfigure: (String) -> Unit = {},
        onSelect: (ModelRef) -> Unit = {},
        onConfigureModel: ((ModelRef) -> Unit)? = null,
    ) = rule.setContent {
        ModelPicker(
            catalog = FakeModelCatalog(FakeAuthManager()),
            settings = FakeAppSettingsStore(),
            current = current,
            thinking = "high",
            thinkingLevels = listOf("off", "low", "high"),
            generating = false,
            onSelect = onSelect,
            onThinking = {},
            onConfigure = onConfigure,
            onConfigureModel = onConfigureModel,
        )
    }

    @Test fun searchQwenLeavesOnlyQwenRows() {
        show(null)
        rule.waitUntil(5_000) { rowTags().isNotEmpty() }
        rule.onNodeWithTag(ModelTags.SEARCH).performTextInput("qwen")
        rule.waitForIdle()
        val tags = rowTags()
        assertTrue(tags.isNotEmpty())
        assertTrue(tags.toString(), tags.all { it.lowercase().contains("qwen") })
    }

    @Test fun unconfiguredModelOpensProviderConfig() {
        var configured: String? = null
        show(null, onConfigure = { configured = it })
        rule.waitUntil(5_000) { rowTags().isNotEmpty() }
        rule.onNodeWithTag(ModelTags.ROW_PREFIX + "anthropic/claude-sonnet-4-5").performClick()
        assertEquals("anthropic", configured)
    }

    @Test fun unconfiguredModelReportsTheClickedModel() {
        var configured: String? = null
        var model: ModelRef? = null
        show(null, onConfigure = { configured = it }, onConfigureModel = { model = it })
        rule.waitUntil(5_000) { rowTags().isNotEmpty() }
        rule.onNodeWithTag(ModelTags.ROW_PREFIX + "anthropic/claude-sonnet-4-5").performClick()
        assertEquals("anthropic", configured)
        assertEquals(ModelRef("anthropic", "claude-sonnet-4-5"), model)
    }

    @Test fun thinkingControlHiddenForNonReasoningModel() {
        show(ModelRef("deepseek", "deepseek-chat"))
        rule.waitUntil(5_000) { rowTags().isNotEmpty() }
        rule.onAllNodesWithTag(ModelTags.THINKING).assertCountEquals(0)
    }

    @Test fun thinkingControlShownForReasoningModel() {
        show(ModelRef("deepseek", "deepseek-reasoner"))
        rule.waitUntil(5_000) { rowTags().isNotEmpty() }
        rule.onAllNodesWithTag(ModelTags.THINKING).assertCountEquals(1)
    }
}
