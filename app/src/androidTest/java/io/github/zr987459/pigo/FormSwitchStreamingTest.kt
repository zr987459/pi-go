package io.github.zr987459.pigo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.di.BindingContext
import io.github.zr987459.pigo.di.Mode
import io.github.zr987459.pigo.di.SessionBindings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * 装配：宿主经 `SessionBindings.host(Mode.FAKE, …)` 取得——AppGraph 按 `BindingMode.session` 调的就是这个工厂，FAKE 分支给出
 * `FakeSessionHost`（引擎 = FakeScriptedEngine）；界面是 App 里同一个 [ChatPage]（FormHost + CalmSurface + TermSurface）。
 *
 * 流式：500 段、每段 20ms。出段由测试分批放行（[StreamScript.gate] 只放到 `allowed` 之前的段）：
 * - 流自由出段时测试**不做任何要求界面空闲的操作**（查找、点击、waitForIdle 都会等空闲；连续 20ms 出段时界面没有空档，
 *   慢模拟器上 Compose 一直忙 → ComposeNotIdleException，CI 79ffb91、7c8681e 实测）。
 *   等出段只看引擎侧计数（[pollUntil]，不碰 Compose），停在闸门后再 waitForIdle、经 UI 点击、断言；
 * - 每次切换之间放行 [BATCH] 段（20ms × 15 ≈ 300ms 的流），切换确实发生在生成中途；
 * - 16 次切换都做完才放行其余分段，所以「切换完时仍在生成中」不依赖模拟器快慢。
 *
 * 全部切换都经真实 UI：UI 形态顶栏的 ⇄ 与终端键条固定在最右的 ⇄UI（同一个测试标签 form:toggle）。
 * 段号、全文、搜索词尽量从屏幕语义节点读。
 *
 * 宿主是 [MainWindowTestActivity]（仅 debug）：窗口配置与 [MainActivity] 相同——清单 adjustResize、onCreate 里 enableEdgeToEdge。
 * 之前用 ui-test-manifest 的 ComponentActivity（默认 adjustPan）：UI 形态输入框进入时请求焦点、软键盘弹出，窗口被整体上推
 * （CI 9e592d4 / 4f1b69c 实测根节点顶边 -128 / -152），顶栏 ⇄ 被推出屏幕；运行中再调 enableEdgeToEdge + setSoftInputMode(adjustResize) 仍被上推（4f1b69c）。
 * 换成与 App 同配置的宿主，测到的才是 App 的真实窗口行为（键盘由 imePadding 让位、窗口不平移）。
 */
@RunWith(AndroidJUnit4::class)
class FormSwitchStreamingTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainWindowTestActivity>()

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

    /** 屏幕上（当前形态）出现的全部段号。 */
    private fun segmentsOnScreen(): Set<Int> {
        val re = Regex("第(\\d+)段")
        return rule.onAllNodes(hasText("段", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .flatMap { t -> re.findAll(t).map { it.groupValues[1].toInt() } }
            .toSet()
    }

    private fun clickToggle() {
        // 不滚动：⇄UI 固定在键条可见位置（PR #26 审查 I1），UI 形态的 ⇄ 在顶栏
        rule.onNodeWithTag("form:toggle", useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    @Test
    fun switchingDuringStreamingKeepsEverything() {
        val ctx = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext, scope)
        val host = SessionBindings.host(Mode.FAKE, ctx, UiForm.CALM) { error("A 档只用 FAKE") } as FakeSessionHost
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

        // 闸门：第 i 段只在 i < allowed 时发出；atGate = 当前停在闸门前的段号（= 已发出的段数）。
        val allowed = MutableStateFlow(0)
        val atGate = AtomicInteger(-1)
        val script = StreamScript(List(SEGMENTS) { "第${it}段\n" }, intervalMs = 20, gate = { i ->
            atGate.set(i)
            allowed.first { it > i }
        })
        val job = host.startStream(script, userText = "数到五百")
        /** 放行到第 [n] 段之前，等引擎把这些段发完、停在闸门前（只看引擎侧计数，不等界面空闲），再让界面追上。 */
        fun releaseTo(n: Int) {
            allowed.value = n
            pollUntil("引擎发出前 $n 段") { atGate.get() >= n }
            rule.waitForIdle()
        }
        /** 流停在闸门前时经 UI 切换并断言，然后放行下一批（约 300ms 的流）。 */
        fun toggleThenStream(check: () -> Unit) {
            clickToggle()
            check()
            releaseTo(allowed.value + BATCH)
        }
        releaseTo(BATCH)
        rule.waitUntil(5_000) { maxSegmentOnScreen() >= 0 }

        // ① 生成中经真实 UI 每 300ms 切一次，共 10 次：屏幕上的最大段号单调不减（列表贴底，流式不断）
        var last = maxSegmentOnScreen()
        repeat(10) { i ->
            toggleThenStream {
                val want = if (i % 2 == 0) UiForm.TERM else UiForm.CALM
                assertEquals(want, host.form.value)
                assertTrue(exists(if (want == UiForm.TERM) "term:surface" else "calm:composer"))
                val now = maxSegmentOnScreen()
                assertTrue("切换后屏幕上的段号倒退：$last → $now（第 ${i + 1} 次切换）", now >= last)
                last = now
            }
        }
        assertEquals(UiForm.CALM, host.form.value)
        assertTrue("生成仍在进行（其余分段要等切换完才放行）", job.isActive)

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
            toggleThenStream {
                assertEquals(if (i % 2 == 0) UiForm.TERM else UiForm.CALM, host.form.value)
                if (host.form.value == UiForm.TERM) {
                    rule.onNodeWithTag("term:editor").assert(hasText("草稿abc", substring = true))
                    assertTrue(exists("overlay:ext:req-1"))
                }
                val now = maxSegmentOnScreen()
                assertTrue("切换后屏幕上的段号倒退：$last → $now（第 ${i + 11} 次切换）", now >= last)
                last = now
            }
        }
        assertEquals(UiForm.CALM, host.form.value)
        assertTrue("16 次切换完时仍在生成中", job.isActive)
        assertTrue("切换期间只放行了一部分分段：${atGate.get()}", atGate.get() < SEGMENTS)

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
        // 扩展对话框关掉后露出模型面板：屏幕上的搜索框里仍是「son」
        rule.waitUntil(3_000) { exists("overlay:model.select:search") }
        rule.onNodeWithTag("overlay:model.select:search", useUnmergedTree = true).assert(hasText("son"))
        rule.runOnIdle { host.overlay.clear() }

        // 切换全部完成后才放行剩余分段；等流结束只看 job（不碰 Compose），结束后界面才追上
        allowed.value = SEGMENTS
        pollUntil("流式结束", 60_000) { job.isCompleted }
        rule.waitForIdle()

        // 流式完整、引擎只启动一次、切换不发 abort / new_session / switch_session / restart；没有误触附件 / 退到后台
        val full = (host.chat.value.items.last { it is io.github.zr987459.pigo.contract.ChatItem.Assistant } as io.github.zr987459.pigo.contract.ChatItem.Assistant)
            .blocks.filterIsInstance<io.github.zr987459.pigo.contract.ContentBlock.Text>().joinToString("") { it.text }
        assertEquals(script.fullText, full)
        // 屏幕上（UI 形态，整条助手消息在一个列表项里）0..499 每段都在
        rule.waitUntil(5_000) { segmentsOnScreen() == (0 until SEGMENTS).toSet() }
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertTrue("切换形态不得发出命令：${host.commands.value}",
            host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "restart") })
        assertEquals(0, attachOpened)
        assertEquals(0, background)
        // 生成结束：发送键回到「发送」（有草稿可发）；屏幕贴底能看到最后一段——两种形态都看
        assertTrue(exists("send:send"))
        rule.waitUntil(3_000) { maxSegmentOnScreen() == SEGMENTS - 1 }
        clickToggle()
        assertEquals(UiForm.TERM, host.form.value)
        rule.waitUntil(3_000) { maxSegmentOnScreen() == SEGMENTS - 1 }
    }

    /**
     * PR #26 增量审查 I1、Nit 1：UI 形态里有面板 / 扩展对话框时，顶栏只有 ⇄ 可点。
     * - 扩展对话框等待应答：点 ⌘、⋯、☰、标题都不改变栈顶（仍是 ext:<id>）、不发命令、不出菜单 / 会话列表；
     * - ⇄ 照常切换，切过去、切回来对话框都还在栈顶；
     * - 普通面板打开时点 ☰ 不会把会话列表画到面板下面。
     */
    @Test
    fun extDialogKeepsTopBarModalButToggleSwitches() {
        val ctx = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext, scope)
        val host = SessionBindings.host(Mode.FAKE, ctx, UiForm.CALM) { error("A 档只用 FAKE") } as FakeSessionHost
        rule.setContent {
            ChatPage(
                host = host, panelEvents = null, panelSource = null, fileSearch = null,
                cwd = "/root/pi-cwd", home = "/root", onOpenSettings = {}, onAttachImage = {}, onBackground = {},
            )
        }
        rule.waitForIdle()
        // 先开 ⋯ 菜单再来扩展对话框：菜单收起，对话框结束后也不会自己重新弹出
        rule.onNodeWithTag("calm:sessionMenu", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue("⋯ 菜单应已打开", exists("menu:session.clone"))
        rule.runOnIdle {
            host.showExtDialog(ExtUiRequest(id = "req-2", method = "confirm", title = "确认", message = "继续吗？", timeoutMs = 60_000), System.currentTimeMillis())
        }
        rule.waitForIdle()
        host.clearCommands()
        assertEquals("ext:req-2", host.overlay.top?.id)

        for (tag in listOf("calm:commands", "calm:sessionMenu", "persist:session.resume", "persist:session.name")) {
            rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            rule.waitForIdle()
            assertEquals("对话框等待应答时点 $tag 改变了栈顶：${host.overlay.items.value.map { it.id }}", "ext:req-2", host.overlay.top?.id)
            assertEquals("对话框等待应答时点 $tag 压入了面板", 1, host.overlay.items.value.size)
            assertTrue("对话框等待应答时点 $tag 发出了命令：${host.commands.value}", host.commands.value.isEmpty())
            assertTrue("对话框等待应答时点 $tag 弹出了会话菜单", !exists("menu:session.clone"))
            assertTrue("对话框等待应答时点 $tag 打开了会话列表", !exists("calm:drawer"))
        }
        assertTrue(exists("overlay:ext:req-2"))

        // ⇄ 仍可切换，对话框两边都在栈顶
        clickToggle()
        assertEquals(UiForm.TERM, host.form.value)
        assertTrue(exists("overlay:ext:req-2"))
        assertEquals("ext:req-2", host.overlay.top?.id)
        clickToggle()
        assertEquals(UiForm.CALM, host.form.value)
        assertTrue(exists("overlay:ext:req-2"))
        assertEquals("ext:req-2", host.overlay.top?.id)

        // 对话框结束后 ⋯ 菜单不会自己重新弹出
        rule.runOnIdle { host.overlay.clear() }
        rule.waitForIdle()
        assertTrue("对话框结束后 ⋯ 菜单重新弹出了", !exists("menu:session.clone"))

        // 普通面板：点 ☰ 不打开画在面板下面的会话列表
        rule.runOnIdle {
            host.overlay.push(OverlayModel.SelectList(id = "model.select", title = "选择模型", groups = listOf(SelectGroup(null, listOf(SelectItem("a/b", "b"))))))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("persist:session.resume", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue("面板打开时点 ☰ 打开了会话列表", !exists("calm:drawer"))
        assertEquals("model.select", host.overlay.top?.id)
    }

    /**
     * PR #26 第 2 轮 Nit 3：320dp / 360dp 窄屏下 ⇄UI（终端键条）与 ⇄（UI 顶栏）都在屏幕内、可点。
     *
     * CI 模拟器本身就是 320×640dp（avd 默认 skin 320x640、160dpi）。360dp 比屏幕宽：按需缩小密度（同
     * `DeviceConfigurationOverride.ForcedSize` 的做法），让内容按该宽度排版并整体落在窗口内；窄于窗口时靠左排版。
     * 窗口是 [MainWindowTestActivity]（与 MainActivity 同配置），见类注释。
     */
    @Test
    fun narrowScreensKeepToggleVisibleAndClickable() {
        val ctx = BindingContext(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext, scope)
        val host = SessionBindings.host(Mode.FAKE, ctx, UiForm.TERM) { error("A 档只用 FAKE") } as FakeSessionHost
        var screenW by mutableStateOf(320.dp)
        rule.setContent {
            BoxWithConstraints {
                val d = LocalDensity.current
                val scale = if (maxWidth < screenW) maxWidth / screenW else 1f
                CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
                    Box(Modifier.width(screenW).fillMaxHeight()) {
                        ChatPage(
                            host = host, panelEvents = null, panelSource = null, fileSearch = null,
                            cwd = "/root/pi-cwd", home = "/root", onOpenSettings = {}, onAttachImage = {}, onBackground = {},
                        )
                    }
                }
            }
        }
        for (w in listOf(320.dp, 360.dp)) {
            rule.runOnIdle { screenW = w; host.form.value = UiForm.TERM }
            rule.waitForIdle()
            for (want in listOf(UiForm.CALM, UiForm.TERM)) {
                assertToggleOnScreen(w, host)
                rule.onNodeWithTag("form:toggle", useUnmergedTree = true).performClick()
                rule.waitForIdle()
                assertEquals("${w} 宽度下点 form:toggle", want, host.form.value)
            }
        }
    }

    /** ⇄ 显示着、整体落在窗口（根节点）内、尺寸非零；失败时带上形态、边界、窗口软键盘配置与语义树，便于在 CI 日志里定位。 */
    private fun assertToggleOnScreen(w: Dp, host: FakeSessionHost) {
        val node = rule.onNodeWithTag("form:toggle", useUnmergedTree = true)
        val b = node.fetchSemanticsNode().boundsInWindow
        val root = rule.onRoot().fetchSemanticsNode().boundsInWindow
        fun diag(): String {
            var win = ""
            rule.runOnUiThread {
                val decor = rule.activity.window.decorView
                val ins = ViewCompat.getRootWindowInsets(decor)
                win = "softInputMode=0x${Integer.toHexString(rule.activity.window.attributes.softInputMode)} " +
                    "ime=${ins?.isVisible(WindowInsetsCompat.Type.ime())}/${ins?.getInsets(WindowInsetsCompat.Type.ime())} " +
                    "decorScrollY=${decor.scrollY}"
            }
            return "w=$w form=${host.form.value} toggle=$b root=$root $win\n" + rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 6)
        }
        try {
            node.assertIsDisplayed()
        } catch (e: AssertionError) {
            throw AssertionError("form:toggle 未显示：${diag()}", e)
        }
        assertTrue("form:toggle 不在窗口内：${diag()}",
            b.width > 0f && b.height > 0f && b.left >= root.left - 0.5f && b.right <= root.right + 0.5f &&
                b.top >= root.top - 0.5f && b.bottom <= root.bottom + 0.5f)
    }

    /** 轮询引擎侧条件（不碰 Compose、不等界面空闲）。 */
    private fun pollUntil(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("等待超时（${timeoutMs}ms）：$what")
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SEGMENTS = 500
        /** 两次切换之间放行的段数（20ms × 15 ≈ 300ms 的流）。 */
        const val BATCH = 15
    }
}
