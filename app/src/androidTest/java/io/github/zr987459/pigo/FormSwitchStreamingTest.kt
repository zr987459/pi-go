package io.github.zr987459.pigo

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmSurface
import io.github.zr987459.pigo.feature.term.TermSurface
import io.github.zr987459.pigo.form.FormHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §7.2 第 3 条（A 档，无 Key，CI 模拟器跑）：生成中经真实 UI 一键切换形态（UI 顶栏 ⇄、终端键条 ⇄UI），
 * 流式不中断、不重启、不发命令；草稿（文字、光标、图片、@ 引用）、扩展对话框、打开中的模型面板（搜索词）都保留。
 * 两层是真实的 CalmSurface 与 TermSurface，经 FormHost（Crossfade(form)）切换，共用同一个 SessionHost。
 * B 档（真引擎，需 pigoProvider / pigoKey）见 docs/testing/r3-device-checklist.md G 节。
 */
@RunWith(AndroidJUnit4::class)
class FormSwitchStreamingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun assistantText(host: FakeSessionHost): String =
        (host.chat.value.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)
            ?.blocks?.filterIsInstance<ContentBlock.Text>()?.joinToString("") { it.text }.orEmpty()

    @Test
    fun switchingDuringStreamingKeepsEverything() {
        val host = FakeSessionHost(scope, initialForm = UiForm.CALM)
        rule.setContent {
            FormHost(host, calm = { CalmSurface(host, dark = false) }, term = { TermSurface(host) })
        }
        rule.waitForIdle()
        val draft = DraftState(
            text = "草稿abc", selectionStart = 3, selectionEnd = 3,
            images = listOf(DraftImage("content://fake/1.png", "image/png", "1.png")),
            fileRefs = listOf("a.kt"),
        )
        rule.runOnIdle { host.draft.value = draft }
        host.clearCommands()

        // 慢速流：500 段、每段 20ms（约 10 秒）
        val script = StreamScript(List(500) { "第${it}段\n" }, intervalMs = 20)
        val job = host.startStream(script, userText = "数到五百")
        rule.waitUntil(5_000) { assistantText(host).isNotEmpty() }

        // ① 经真实 UI 每 300ms 点一次 ⇄，共 10 次；屏幕背后的助手文本长度单调不减
        var lastLen = 0
        repeat(10) { i ->
            rule.onNodeWithTag("form:toggle", useUnmergedTree = true).performClick()
            rule.waitForIdle()
            assertEquals(if (i % 2 == 0) UiForm.TERM else UiForm.CALM, host.form.value)
            if (host.form.value == UiForm.TERM) assertTrue(exists("term:surface"))
            val len = assistantText(host).length
            assertTrue("切换后文本变短：$lastLen → $len", len >= lastLen)
            lastLen = len
            Thread.sleep(300)
        }
        assertEquals(UiForm.CALM, host.form.value)

        // ② 生成中压入扩展 confirm 与打开中的模型面板（搜索词 son），再切换 6 次
        rule.runOnIdle {
            host.overlay.push(
                OverlayModel.SelectList(
                    id = "model.select", title = "选择模型", query = "son",
                    groups = listOf(SelectGroup(null, listOf(SelectItem("anthropic/sonnet", "sonnet-4.5"), SelectItem("openai/gpt", "gpt")))),
                ),
            )
            host.showExtDialog(ExtUiRequest(id = "req-1", method = "confirm", title = "确认", message = "继续吗？", timeoutMs = 60_000), System.currentTimeMillis())
        }
        repeat(6) {
            rule.runOnIdle { host.form.value = UiForm.TERM }
            rule.waitForIdle()
            rule.runOnIdle { host.form.value = UiForm.CALM }
            rule.waitForIdle()
        }

        // 草稿逐字段相等（文字、光标、图片、@ 引用），UI 上也看得到
        assertEquals(draft, host.draft.value)
        assertTrue(exists("calm:image:0"))
        assertTrue(exists("calm:ref:a.kt"))
        // 模型面板与搜索词还在、扩展对话框还在；点「确定」后 Fake 收到同一 request id
        assertEquals("son", (host.overlay.items.value.first { it.id == "model.select" } as OverlayModel.SelectList).query)
        rule.waitUntil(3_000) { exists("overlay:ext:req-1") }
        rule.onNodeWithTag("ext:ok", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(host.commands.value.any { it.type == "extension_ui_response" && it.args["id"] == "req-1" && it.args["confirmed"] == "true" })
        assertTrue(host.commands.value.none { it.args["cancelled"] == "true" })
        rule.runOnIdle { host.overlay.clear() }

        // 流式完整（不丢不重）、引擎只启动一次、切换不发 abort / new_session / switch_session / restart
        rule.waitUntil(30_000) { job.isCompleted }
        assertEquals(script.fullText, assistantText(host))
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertTrue("切换形态不得发出命令：${host.commands.value}",
            host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "restart") })
        // 最终在终端形态也看得到同一份结尾（列表贴底）
        rule.runOnIdle { host.form.value = UiForm.TERM }
        rule.waitForIdle()
        rule.waitUntil(3_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("第499段", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
