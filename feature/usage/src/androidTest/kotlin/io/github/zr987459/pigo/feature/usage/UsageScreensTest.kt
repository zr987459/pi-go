package io.github.zr987459.pigo.feature.usage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.usage.logic.SessionTreeRows
import io.github.zr987459.pigo.feature.usage.logic.TreeUiState
import io.github.zr987459.pigo.feature.usage.logic.UsageController
import io.github.zr987459.pigo.feature.usage.ui.SessionTreeContent
import io.github.zr987459.pigo.feature.usage.ui.UsageSheetContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** feature-gap W4 验收「用量页（Compose 测试）」「会话树」：独立运行新页面，用 Fake 数据。 */
@RunWith(AndroidJUnit4::class)
class UsageScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun usageSheetShowsStatsAndCompactsWithInstructions() {
        val extras = FakeEngineExtras()
        val c = UsageController(extras, MainScope())
        rule.setContent {
            val s by c.state.collectAsState()
            UsageSheetContent(s, onCompact = c::compact, onRefresh = c::refresh, onCnyRate = c::setCnyRate)
        }
        rule.runOnIdle { c.refresh() }
        rule.waitUntil(5_000) { c.state.value.stats != null }
        rule.onNodeWithText("105k").assertIsDisplayed()
        rule.onNodeWithText("上下文 30% · 60k / 200k").assertIsDisplayed()
        rule.onNodeWithTag("compact-instructions").performTextInput("保留接口设计")
        rule.onNodeWithTag("compact-button").performClick()
        rule.waitUntil(5_000) { c.state.value.lastCompaction != null }
        assertTrue(extras.calls.contains("compact:保留接口设计"))
        rule.onNodeWithText("上下文 压缩后待更新").assertIsDisplayed()
    }

    @Test
    fun treeShowsThreeLeavesAndForkButtonCallsBack() {
        val tree = FakeEngineExtras.FIXTURE_TREE
        val forked = mutableListOf<String>()
        rule.setContent {
            SessionTreeContent(TreeUiState(tree = tree, rows = SessionTreeRows.build(tree)), onToggle = {}, onFork = { forked += it }, onClone = {})
        }
        rule.onNodeWithText("会话树 · 3 个分支").assertIsDisplayed()
        rule.onNodeWithTag("fork-e5").performClick()
        assertEquals(listOf("e5"), forked)
    }
}
