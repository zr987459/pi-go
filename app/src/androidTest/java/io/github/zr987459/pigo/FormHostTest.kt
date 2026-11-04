package io.github.zr987459.pigo

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.form.FormHost
import io.github.zr987459.pigo.form.TermPlaceholder
import io.github.zr987459.pigo.form.toggleForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FormHost 骨架（PR 1）：一键切到终端占位、再切回 UI；切换不发 RPC、不重启 Fake 引擎、草稿原样。
 * 真实两种形态 + 慢速流的 A 档测试是 FormSwitchStreamingTest（PR 2）。
 */
@RunWith(AndroidJUnit4::class)
class FormHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneTapSwitchesBothWaysWithoutTouchingEngine() {
        val host = FakeSessionHost(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), initialForm = UiForm.CALM)
        val draft = DraftState(text = "草稿abc", selectionStart = 3)
        host.draft.value = draft
        val before = host.commands.value
        compose.setContent {
            FormHost(
                host,
                calm = { Button(onClick = { host.toggleForm() }, modifier = Modifier.testTag("calm:toggle")) { Text("⇄") } },
                term = { TermPlaceholder(onBackToUi = { host.toggleForm() }) },
            )
        }
        compose.onNodeWithTag("calm:toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("term:placeholder").assertIsDisplayed()
        assertEquals(UiForm.TERM, host.form.value)

        compose.onNodeWithTag("form:toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("calm:toggle").assertIsDisplayed()
        assertEquals(UiForm.CALM, host.form.value)

        assertEquals(before, host.commands.value)
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertEquals(draft, host.draft.value)
    }
}
