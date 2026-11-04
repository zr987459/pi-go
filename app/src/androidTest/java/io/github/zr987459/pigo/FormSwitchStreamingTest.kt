package io.github.zr987459.pigo

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.nav.ChatPage
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
 * DESIGN-r3 §7.2 第 3 条（A 档，无 Key，CI 模拟器跑）。
 *
 * 装配：App 里的同一个 [ChatPage]（FormHost + CalmSurface + TermSurface，TermEnv 的返回键 / 附件 / 面板事件同 PiGoNavHost），
 * 只把 AppGraph 的依赖换成 Fake（引擎 = FakeScriptedEngine，经 FakeSessionHost）。
 *
 * 流式：100 段、每段 100ms，共约 10 秒。段间隔取 100ms 是因为 Compose 测试的每次查找 / 点击都要等界面空闲，
 * 500 段 × 20ms 下界面在流结束前没有空档（CI 79ffb91 实测 ComposeNotIdleException），无法在流中途经 UI 点击切换。
 *
 * 全部切换都经真实 UI：UI 形态顶栏的 ⇄ 与终端键条固定在最右的 ⇄UI（同一个测试标签 form:toggle），共 16 次。
 */
@RunWith(AndroidJUnit4::class)
class FormSwitchStreamingTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** 屏幕上（当前形态）能看到的最大段号；-1 = 一段都没看到。读的是界面语义树里的文字，不是 host 状态。 */
    private fun maxSegmentOnScreen(): Int {
        val re = Regex("第(\\d+)段")
        return rule.onAllNodes(hasText("段", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .flatMap { t -> re.findAll(t).map { it.groupValues[1].toInt() } }
            .maxOrNull() ?: -1
    }

    private fun clickToggle() {
        // 不滚动：⇄UI 固定在键条可见位置（PR #26 审查 I1），UI 形态的 ⇄ 在顶栏
        rule.onNodeWithTag("form:toggle", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun switchingDuringStreamingKeepsEverything() {
        val host = FakeSessionHost(scope, initialForm = UiForm.CALM)
        var attachOpened = 0
        var background = 0
        rule.setContent {
            ChatPage(
                host = host, panelEvents = null, panelSource = null, fileSearch = null,
                cwd = "/root/pi-cwd", home = "/root",
                onOpenSettings = {}, onAttachImage = { attachOpened++ }, onBackground = { background++ },
            )
        }
        rule.waitForIdle()
        val draft = DraftState(
            text = "草稿abc", selectionStart = 3, selectionEnd = 3,
            images = listOf(DraftImage("content://fake/1.png", "image/png", "1.png")),
            fileRefs = listOf("a.kt"),
        )
        rule.runOnIdle { host.draft.value = draft }
        host.clearCommands()

        val script = StreamScript(List(100) { "第${it}段\n" }, intervalMs = 100)
        val job = host.startStream(script, userText = "数到一百")
        rule.waitUntil(5_000) { maxSegmentOnScreen() >= 0 }

        // ① 生成中经真实 UI 每 300ms 切一次，共 10 次：屏幕上的最大段号单调不减（列表贴底，流式不断）
        var last = maxSegmentOnScreen()
        repeat(10) { i ->
            clickToggle()
            val want = if (i % 2 == 0) UiForm.TERM else UiForm.CALM
            assertEquals(want, host.form.value)
            assertTrue(exists(if (want == UiForm.TERM) "term:surface" else "calm:composer"))
            val now = maxSegmentOnScreen()
            assertTrue("切换后屏幕上的段号倒退：$last → $now（第 ${i + 1} 次切换）", now >= last)
            last = now
            Thread.sleep(300)
        }
        assertEquals(UiForm.CALM, host.form.value)
        assertTrue("生成仍在进行", job.isActive)

        // 发送键状态：生成中 + 输入框有草稿 = 「引导」；输入框草稿与光标原样
        assertTrue(exists("send:steer"))
        rule.onNodeWithTag("calm:input").assert(hasText("草稿abc"))
        rule.onNodeWithTag("calm:input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        assertTrue(exists("calm:image:0"))
        assertTrue(exists("calm:ref:a.kt"))

        // ② 生成中压入扩展 confirm 与打开中的模型面板（搜索词 son），再经真实 UI 切 6 次
        rule.runOnIdle {
            host.overlay.push(
                OverlayModel.SelectList(
                    id = "model.select", title = "选择模型", query = "son",
                    groups = listOf(SelectGroup(null, listOf(SelectItem("anthropic/sonnet", "sonnet-4.5"), SelectItem("openai/gpt", "gpt")))),
                ),
            )
            host.showExtDialog(ExtUiRequest(id = "req-1", method = "confirm", title = "确认", message = "继续吗？", timeoutMs = 60_000), System.currentTimeMillis())
        }
        rule.waitForIdle()
        repeat(6) { i ->
            clickToggle()
            assertEquals(if (i % 2 == 0) UiForm.TERM else UiForm.CALM, host.form.value)
            if (host.form.value == UiForm.TERM) {
                rule.onNodeWithTag("term:editor").assert(hasText("草稿abc", substring = true))
                assertTrue(exists("overlay:ext:req-1"))
            }
        }
        assertEquals(UiForm.CALM, host.form.value)

        // 草稿逐字段相等，屏幕上也是原样
        assertEquals(draft, host.draft.value)
        rule.onNodeWithTag("calm:input").assert(hasText("草稿abc"))
        rule.onNodeWithTag("calm:input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        // 模型面板搜索词还在、扩展对话框还在；点「确定」后 Fake 收到同一 request id
        assertEquals("son", (host.overlay.items.value.first { it.id == "model.select" } as OverlayModel.SelectList).query)
        rule.waitUntil(3_000) { exists("overlay:ext:req-1") }
        rule.onNodeWithTag("ext:ok", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(host.commands.value.any { it.type == "extension_ui_response" && it.args["id"] == "req-1" && it.args["confirmed"] == "true" })
        assertTrue(host.commands.value.none { it.args["cancelled"] == "true" })
        rule.runOnIdle { host.overlay.clear() }

        // 流式完整、引擎只启动一次、切换不发 abort / new_session / switch_session / restart；没有误触附件 / 退到后台
        rule.waitUntil(30_000) { job.isCompleted }
        rule.waitForIdle()
        val full = (host.chat.value.items.last { it is io.github.zr987459.pigo.contract.ChatItem.Assistant } as io.github.zr987459.pigo.contract.ChatItem.Assistant)
            .blocks.filterIsInstance<io.github.zr987459.pigo.contract.ContentBlock.Text>().joinToString("") { it.text }
        assertEquals(script.fullText, full)
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertTrue("切换形态不得发出命令：${host.commands.value}",
            host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "restart") })
        assertEquals(0, attachOpened)
        assertEquals(0, background)
        // 生成结束：发送键回到「发送」（有草稿可发）；屏幕贴底能看到最后一段——两种形态都看
        assertTrue(exists("send:send"))
        rule.waitUntil(3_000) { maxSegmentOnScreen() == 99 }
        clickToggle()
        assertEquals(UiForm.TERM, host.form.value)
        rule.waitUntil(3_000) { maxSegmentOnScreen() == 99 }
    }
}
