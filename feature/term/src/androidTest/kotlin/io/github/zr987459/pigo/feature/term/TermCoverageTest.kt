package io.github.zr987459.pigo.feature.term

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.actions.TermEntry
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §7.1 第 3 条（r3/term）：遍历 `PiActions.all`，按每个动作声明的终端入口操作——输入 `/别名` 回车、点键条键、点页脚段、
 * 点选择器 / 页眉 / 编辑器 / 补全浮层 / 消息里的键帽——断言 Fake 收到该动作（`FakePiActions.ran`）或出现说明条目。
 * 形态切换（`form.toggle`）断言 `host.form` 改变且命令日志无新增。**任何一项缺失即失败。**
 */
@RunWith(AndroidJUnit4::class)
class TermCoverageTest {
    @get:Rule val rule = createComposeRule()

    /** 让消息区里有一个思考块（「思考块」键帽在消息上）。 */
    private class Host(val fake: FakeSessionHost, override val chat: StateFlow<ChatState>) : SessionHost by fake

    private fun setUp(): Host {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val fake = FakeSessionHost(scope, initialForm = UiForm.TERM)
        val chat = MutableStateFlow(
            ChatState(items = listOf(ChatItem.User("u0", "你好"), ChatItem.Assistant("a0", listOf(ContentBlock.Thinking(0, "想一想"), ContentBlock.Text(1, "好的")), streaming = false, stopReason = "stop"))),
        )
        val host = Host(fake, chat)
        rule.setContent { Surface(host) }
        rule.waitForIdle()
        return host
    }

    @Composable
    private fun Surface(host: SessionHost) {
        val form by host.form.collectAsState()
        if (form == UiForm.TERM) TermSurface(host)
    }

    @Test
    fun everyActionHasATermEntry() {
        val host = setUp()
        val fake = host.fake
        val missing = mutableListOf<String>()
        for (spec in fake.actions.all) {
            fake.form.value = UiForm.TERM
            host.overlay.clear()
            fake.draft.value = fake.draft.value.withText("")
            rule.waitForIdle()
            val before = fake.fakeActions.ran.count { it.first == spec.id }
            val commandsBefore = fake.commands.value.size
            runCatching {
                when (val t = spec.term) {
                    TermEntry.Slash -> {
                        fake.draft.value = fake.draft.value.withText("/${spec.slash.first()}")
                        rule.waitForIdle()
                        rule.onNodeWithTag("term:key:⏎").performScrollTo().performClick()
                    }
                    is TermEntry.KeyStrip -> {
                        if (spec.id == "send" || spec.id == "send.followUp") fake.draft.value = fake.draft.value.withText("hi")
                        if (spec.id == "editor.history") { fake.draft.value = fake.draft.value.withText("") }
                        rule.waitForIdle()
                        rule.onNodeWithTag("term:key:${t.key}").performScrollTo().performClick()
                    }
                    is TermEntry.Footer -> {
                        if (spec.id == "model.cycle") rule.onNodeWithTag("term:footer:model").performTouchInput { swipeLeft() }
                        else rule.onNodeWithTag("term:footer:${t.segment}").performClick()
                    }
                    is TermEntry.KeyCap -> {
                        TermInput.SELECTOR_OVERLAY[t.selector]?.let { overlayId ->
                            host.overlay.push(OverlayModel.Text(overlayId, overlayId, "夹具"))
                            rule.waitForIdle()
                        }
                        if (t.selector == "slash") { fake.draft.value = fake.draft.value.withText("/"); rule.waitForIdle() }
                        rule.onNodeWithTag("term:keycap:${t.selector}:${t.key}", useUnmergedTree = true).performClick()
                    }
                    is TermEntry.Explain -> {
                        // 说明条目：终端形态里经 /help 列出；这里直接执行，断言产生 explain 本地块。
                        kotlinx.coroutines.runBlocking { host.runAction(spec.id) }
                    }
                }
            }.onFailure { missing += "${spec.id}: ${it.message?.lineSequence()?.firstOrNull()}"; continue }
            rule.waitForIdle()
            val ok = when {
                spec.id == "form.toggle" -> fake.form.value == UiForm.CALM && fake.commands.value.size == commandsBefore
                spec.term is TermEntry.KeyStrip && (spec.term as TermEntry.KeyStrip).key == "Esc" -> true // Esc 按状态选动作（JVM TermInputTest.escPicksByState），这里只要求键在
                spec.id == "editor.history" -> true // 历史为空时无动作；键存在即可（翻历史由 TermInputTest 覆盖）
                spec.term is TermEntry.Explain -> host.localBlocks.value.any { it.title == "explain:${spec.id}" }
                else -> fake.fakeActions.ran.count { it.first == spec.id } > before
            }
            if (!ok) missing += "${spec.id}: 没有收到动作"
        }
        assertTrue("终端形态缺少入口：\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
