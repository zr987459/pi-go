package io.github.zr987459.pigo.feature.term

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §8.5 r3/term「切换夹具」：终端形态离开（切到另一形态的占位）再进入后，草稿、overlay（含搜索词）、阅读位置都恢复；
 * 生成中切换不中断流式，切换本身不产生任何命令（§5.3）。
 */
@RunWith(AndroidJUnit4::class)
class TermFormSwitchFixtureTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun leaveAndReenterKeepsEverything() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val host = FakeSessionHost(scope, initialForm = UiForm.TERM)
        rule.setContent {
            val form by host.form.collectAsState()
            if (form == UiForm.TERM) TermSurface(host) else Text("calm-placeholder")
        }
        // 草稿、打开中的模型面板（搜索词「son」）、扩展 confirm 对话框
        host.draft.value = host.draft.value.withText("草稿abc").copy(selectionStart = 3, selectionEnd = 3)
        host.overlay.push(OverlayModel.SelectList("model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("a/sonnet", "sonnet"), SelectItem("b/gpt", "gpt")))), query = "son"))
        host.showExtDialog(ExtUiRequest("req-1", "confirm", title = "继续？", message = "要继续吗"))
        val stream = host.startStream(StreamScript.counting(200, intervalMs = 20), userText = "数数")
        rule.waitForIdle()
        host.clearCommands()

        repeat(6) {
            rule.onNodeWithTag("form:toggle").performScrollTo().performClick()
            rule.waitForIdle()
            assertEquals(UiForm.CALM, host.form.value)
            host.form.value = UiForm.TERM
            rule.waitForIdle()
        }

        assertEquals("草稿abc", host.draft.value.text)
        assertEquals(3, host.draft.value.safeSelectionStart)
        rule.onNodeWithTag("term:editor").assertTextContains("草稿abc")
        // 栈顶是扩展对话框，下面是搜索词保留的模型面板
        assertEquals(listOf("model.select", "ext:req-1"), host.overlay.items.value.map { it.id })
        assertEquals("son", (host.overlay.items.value[0] as OverlayModel.SelectList).query)
        rule.onNodeWithTag("overlay:ext:req-1").assertIsDisplayed()
        // 切换本身没有发出任何命令（流式仍在进行）
        assertTrue("切换产生了命令：${host.commands.value}", host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "ui_response") })
        assertTrue(stream.isActive || host.chat.value.items.isNotEmpty())
        // 确认对话框仍可应答，Fake 收到同一个 request id
        rule.onNodeWithTag("term:keycap:ext:req-1:y 确定", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("model.select"), host.overlay.items.value.map { it.id })
        rule.onNodeWithTag("overlay:model.select").assertIsDisplayed()
        rule.onNodeWithText("son").assertIsDisplayed()
    }
}
