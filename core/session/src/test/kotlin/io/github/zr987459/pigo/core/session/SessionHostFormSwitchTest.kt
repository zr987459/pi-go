package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.UiForm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 硬性要求 ② 的 JVM 行为（DESIGN-r3 §7.2 第 2 条）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHostFormSwitchTest {
    @Test
    fun switchingFormDuringStreamingLosesNothing() = runTest {
        val (host, engine) = testHost(backgroundScope)
        testScheduler.runCurrent() // 让宿主开始订阅扩展请求
        val chatFlow = host.chat
        val sessionFile = host.chat.value.sessionFile
        val script = StreamScript.counting(200, 50)
        val job = engine.startStream(script, userText = "数到 200")
        val draft = DraftState(text = "草稿abc", selectionStart = 3, images = listOf(DraftImage("content://img/1", "image/png")), fileRefs = listOf("a.kt"))
        host.draft.value = draft
        engine.pushUiRequest(ExtUiRequest(id = "c1", method = "confirm", title = "确认？"))
        val panel = OverlayModel.SelectList("overlay:model.select", "选择模型", query = "son")
        host.overlay.push(panel)
        delay(1)
        val overlayBefore = host.overlay.items.value
        assertTrue("扩展 confirm 应已压栈", overlayBefore.any { it is OverlayModel.ExtDialog && it.request.id == "c1" })

        // 切换期间只允许出现流式本身产生的记录：切换前后命令日志的差集必须为空（§7.2 第 1 条，审查 N1）。
        repeat(20) { i ->
            delay(script.intervalMs * 200 / 25)
            assertTrue("第 ${i + 1} 次切换时应仍在生成", host.chat.value.running)
            val before = engine.commands.value.size
            host.form.value = if (host.form.value == UiForm.CALM) UiForm.TERM else UiForm.CALM
            testScheduler.runCurrent()
            assertEquals("第 ${i + 1} 次切换发出了命令：${engine.commands.value.drop(before)}", before, engine.commands.value.size)
        }
        job.join()

        assertEquals(1, engine.startCount)
        assertEquals(0, engine.restartCount)
        val types = engine.commands.value.map { it.type }
        for (t in listOf("abort", "new_session", "switch_session", "extension_ui_response")) assertTrue("不应出现 $t：$types", t !in types)
        assertSame(chatFlow, host.chat)
        val last = host.chat.value.items.filterIsInstance<ChatItem.Assistant>().last()
        assertEquals(script.fullText, last.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text })
        assertEquals(1, engine.agentSettledCount)
        assertEquals(draft, host.draft.value)
        assertEquals(overlayBefore, host.overlay.items.value)
        assertEquals("son", host.overlay.items.value.filterIsInstance<OverlayModel.SelectList>().single().query)
        assertEquals(sessionFile, host.chat.value.sessionFile)
    }
}
