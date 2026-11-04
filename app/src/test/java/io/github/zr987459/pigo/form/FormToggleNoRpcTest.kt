package io.github.zr987459.pigo.form

import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * app 层的一键切换（[toggleForm]）只改 `form`：生成中连续切换不发任何 RPC、不重启引擎、草稿不变（硬性要求 ② 的装配层部分）。
 * 端到端的 UI 版本是 FormSwitchStreamingTest（PR 2，r3/term 合入后）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormToggleNoRpcTest {
    @Test
    fun toggleDuringStreamingSendsNoCommandAndKeepsDraft() = runTest {
        val host = FakeSessionHost(backgroundScope, initialForm = UiForm.CALM)
        val script = StreamScript.counting(100, intervalMs = 50)
        host.program(script)
        host.engine.send("从 1 数到 100", SendMode.FOLLOW_UP)
        runCurrent()
        val draft = DraftState(text = "草稿abc", selectionStart = 3)
        host.draft.value = draft
        val chatFlow = host.chat
        val before = host.commands.value

        repeat(20) {
            host.toggleForm()
            advanceTimeBy(60)
            runCurrent()
        }

        assertEquals(UiForm.CALM, host.form.value) // 偶数次切换回到原形态
        assertEquals("切换不得发出任何 RPC 命令", before, host.commands.value)
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertEquals(draft, host.draft.value)
        assertSame(chatFlow, host.chat)

        advanceTimeBy(50L * 100)
        runCurrent()
        assertFalse(host.chat.value.running)
        assertEquals(1, host.fakeEngine.agentSettledCount)
    }
}
