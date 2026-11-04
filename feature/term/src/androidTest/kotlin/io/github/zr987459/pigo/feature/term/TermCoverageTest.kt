package io.github.zr987459.pigo.feature.term

import android.view.Choreographer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.actions.TermEntry
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.SessionHost
import io.github.zr987459.pigo.contract.session.UiForm
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §7.1 第 3 条与 §8.5 r3/term 的仪器验收（CI 模拟器 job 按类名 TermCoverageTest 运行，所以都放在本类）：
 * - [everyActionHasATermEntry]：遍历 `PiActions.all`，按终端入口操作，断言 Fake 收到该动作（Esc 按状态分别构造：重试中 / `!` 运行中 / 生成中）；
 * - [escPicksActionByState]：Esc 的三种状态；
 * - [externalKeyboardMatchesPi]：外接键盘 Esc / Enter / Alt+Enter / Ctrl+L / Shift+Tab；
 * - [leaveAndReenterKeepsEverything]、[readingPositionSurvivesSwitch]、[streamingFollowsBottomAcrossSwitch]：切换夹具（草稿、overlay、阅读位置、贴底）；
 * - [atFileSearchOffersCandidates]、[headerListsLoadedResources]：`@` 补全与页眉资源段；
 * - [slowFramesUnder5PercentWith1000Deltas]：1000 条 delta 慢帧 < 5%。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TermCoverageTest {
    @get:Rule val rule = createComposeRule()

    /** 可控的聊天状态（思考块、重试、生成中）+ 注入的 `!` 运行中状态。 */
    private class Host(val fake: FakeSessionHost, override val chat: MutableStateFlow<ChatState>) : SessionHost by fake

    private fun newFake() = FakeSessionHost(CoroutineScope(SupervisorJob() + Dispatchers.Main), initialForm = UiForm.TERM)

    private val baseChat = ChatState(
        items = listOf(ChatItem.User("u0", "你好"), ChatItem.Assistant("a0", listOf(ContentBlock.Thinking(0, "想一想"), ContentBlock.Text(1, "好的")), streaming = false, stopReason = "stop")),
    )

    private fun setUp(env: TermEnv = TermEnv()): Host {
        val host = Host(newFake(), MutableStateFlow(baseChat))
        rule.setContent { Surface(host, env) }
        rule.waitForIdle()
        return host
    }

    @Composable
    private fun Surface(host: SessionHost, env: TermEnv = TermEnv()) {
        val form by host.form.collectAsState()
        if (form == UiForm.TERM) TermSurface(host, env = env) else Text("calm-placeholder")
    }

    /** 横滑行里的键先滚到可见；不在滚动容器里的（编辑器、页眉、消息上的键帽）直接点。 */
    private fun SemanticsNodeInteraction.tap() {
        runCatching { performScrollTo() }
        performClick()
    }

    private fun toggleTwice(host: SessionHost) {
        rule.onNodeWithTag("form:toggle").tap()
        rule.waitForIdle()
        assertEquals(UiForm.CALM, host.form.value)
        host.form.value = UiForm.TERM
        rule.waitForIdle()
    }

    @Test
    fun everyActionHasATermEntry() {
        val bash = MutableStateFlow(false)
        val host = setUp(TermEnv(bashRunning = bash))
        val fake = host.fake
        val missing = mutableListOf<String>()
        for (spec in fake.actions.all) {
            fake.form.value = UiForm.TERM
            host.overlay.clear()
            fake.draft.value = fake.draft.value.withText("")
            host.chat.value = baseChat
            bash.value = false
            rule.waitForIdle()
            val before = fake.fakeActions.ran.count { it.first == spec.id }
            val commandsBefore = fake.commands.value.size
            val failure = runCatching {
                when (val t = spec.term) {
                    TermEntry.Slash -> {
                        fake.draft.value = fake.draft.value.withText("/${spec.slash.first()}")
                        rule.waitForIdle()
                        rule.onNodeWithTag("term:key:⏎").tap()
                    }
                    is TermEntry.KeyStrip -> {
                        if (spec.id == "send" || spec.id == "send.followUp") fake.draft.value = fake.draft.value.withText("hi")
                        // Esc 按状态选动作：为每个 Esc 动作构造它对应的状态
                        when (spec.id) {
                            "retry.abort" -> host.chat.value = baseChat.copy(retry = RetryBanner(1, 3, 4000, "overloaded"))
                            "bash.abort" -> bash.value = true
                            "abort" -> host.chat.value = baseChat.copy(running = true)
                        }
                        rule.waitForIdle()
                        rule.onNodeWithTag("term:key:${t.key}").tap()
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
                        rule.onNodeWithTag("term:keycap:${t.selector}:${t.key}", useUnmergedTree = true).tap()
                    }
                    is TermEntry.Explain -> kotlinx.coroutines.runBlocking { host.runAction(spec.id) }
                }
            }.exceptionOrNull()
            if (failure != null) {
                missing += "${spec.id}: ${failure.message?.lineSequence()?.firstOrNull()}"
                continue
            }
            rule.waitForIdle()
            val ok = when {
                spec.id == "form.toggle" -> fake.form.value == UiForm.CALM && fake.commands.value.size == commandsBefore
                spec.id == "editor.history" -> true // 历史为空时无动作；键存在即可（翻历史见 TermInputTest）
                spec.term is TermEntry.Explain -> host.localBlocks.value.any { it.title == "explain:${spec.id}" }
                else -> fake.fakeActions.ran.count { it.first == spec.id } > before
            }
            if (!ok) missing += "${spec.id}: 没有收到动作"
        }
        assertTrue("终端形态缺少入口：\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun escPicksActionByState() {
        val bash = MutableStateFlow(false)
        val host = setUp(TermEnv(bashRunning = bash))
        fun escRan(): String? {
            // Fake 的占位动作会压一个 Text 面板；面板打开时 Esc 先关面板，所以每次按前清栈
            host.overlay.clear()
            rule.waitForIdle()
            val n = host.fake.fakeActions.ran.size
            rule.onNodeWithTag("term:key:Esc").tap()
            rule.waitForIdle()
            return host.fake.fakeActions.ran.drop(n).lastOrNull()?.first
        }
        host.chat.value = baseChat.copy(retry = RetryBanner(1, 3, 4000, "overloaded"))
        rule.waitForIdle()
        assertEquals("重试倒计时中", "retry.abort", escRan())
        host.chat.value = baseChat
        bash.value = true
        rule.waitForIdle()
        assertEquals("! 命令运行中（agent 空闲）", "bash.abort", escRan())
        bash.value = false
        host.chat.value = baseChat.copy(running = true)
        rule.waitForIdle()
        assertEquals("生成中", "abort", escRan())
    }

    @Test
    fun externalKeyboardMatchesPi() {
        val host = setUp()
        val editor = rule.onNodeWithTag("term:editor")
        editor.performClick()
        // Fake 的占位动作会压一个 Text 面板（面板打开时 Esc 关面板、Enter 交给面板），每步取结果后清栈
        fun last() = host.fake.fakeActions.ran.lastOrNull()?.first.also { host.overlay.clear(); rule.waitForIdle(); editor.performClick(); rule.waitForIdle() }

        host.chat.value = baseChat.copy(running = true)
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals("Esc = 中止", "abort", last())
        host.chat.value = baseChat
        rule.waitForIdle()

        host.fake.draft.value = host.fake.draft.value.withText("hi")
        rule.waitForIdle()
        editor.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("Enter = 发送", "send", last())

        host.fake.draft.value = host.fake.draft.value.withText("稍后")
        rule.waitForIdle()
        editor.performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals("Alt+Enter = 排队（follow-up）", "send.followUp", last())

        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.L) } }
        rule.waitForIdle()
        assertEquals("Ctrl+L = 选模型", "model.select", last())
        host.overlay.clear()
        rule.waitForIdle()

        editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        rule.waitForIdle()
        assertEquals("Shift+Tab = 循环思考级别", "thinking.cycle", last())
    }

    @Test
    fun modelCycleDirectionsAndSplitKeys() {
        val host = setUp()
        rule.onNodeWithTag("term:footer:model").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals("model.cycle" to null, host.fake.fakeActions.ran.last())
        rule.onNodeWithTag("term:footer:model").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals("model.cycle" to "backward", host.fake.fakeActions.ran.last())
        rule.onNodeWithTag("term:key:⇞⇟").tap()
        rule.waitForIdle()
        assertEquals("transcript.jump" to "prev", host.fake.fakeActions.ran.last())
        rule.onNodeWithTag("term:key:⇞⇟:next").tap()
        rule.waitForIdle()
        assertEquals("transcript.jump" to "next", host.fake.fakeActions.ran.last())
    }

    /** DESIGN-r3 §8.5 r3/term「切换夹具」：草稿、overlay（含搜索词）、扩展对话框、流式不断。 */
    @Test
    fun leaveAndReenterKeepsEverything() {
        val host = newFake()
        rule.setContent { Surface(host) }
        host.draft.value = host.draft.value.withText("草稿abc").copy(selectionStart = 3, selectionEnd = 3)
        host.overlay.push(OverlayModel.SelectList("model.select", "选择模型", listOf(SelectGroup(null, listOf(SelectItem("a/sonnet", "sonnet"), SelectItem("b/gpt", "gpt")))), query = "son"))
        host.showExtDialog(ExtUiRequest("req-1", "confirm", title = "继续？", message = "要继续吗"))
        val stream = host.startStream(StreamScript.counting(200, intervalMs = 20), userText = "数数")
        rule.waitForIdle()
        host.clearCommands()

        repeat(6) { toggleTwice(host) }

        assertEquals("草稿abc", host.draft.value.text)
        assertEquals(3, host.draft.value.safeSelectionStart)
        rule.onNodeWithTag("term:editor").assertTextContains("草稿abc")
        assertEquals(listOf("model.select", "ext:req-1"), host.overlay.items.value.map { it.id })
        assertEquals("son", (host.overlay.items.value[0] as OverlayModel.SelectList).query)
        rule.onNodeWithTag("overlay:ext:req-1").assertIsDisplayed()
        assertTrue("切换产生了命令：${host.commands.value}", host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "ui_response") })
        assertTrue(stream.isActive || host.chat.value.items.isNotEmpty())
        rule.onNodeWithTag("term:keycap:ext:req-1:y 确定", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("model.select"), host.overlay.items.value.map { it.id })
        rule.onNodeWithTag("overlay:model.select").assertIsDisplayed()
        rule.onNodeWithText("son").assertIsDisplayed()
    }

    /** §5.3 / §8.5：用户滚到中间，切走再切回，首个可见块与偏移不变、不贴底。 */
    @Test
    fun readingPositionSurvivesSwitch() {
        val many = (0 until 60).flatMap { i ->
            listOf(ChatItem.User("u$i", "问题 $i"), ChatItem.Assistant("a$i", listOf(ContentBlock.Text(0, "回答 $i\n第二行\n第三行")), streaming = false, stopReason = "stop"))
        }
        val host = Host(newFake(), MutableStateFlow(ChatState(items = many)))
        rule.setContent { Surface(host) }
        rule.waitForIdle()
        assertTrue("初始贴底", host.scrollAnchor.value?.followBottom != false)
        rule.onNodeWithTag("term:transcript").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithTag("term:transcript").performTouchInput { swipeDown() }
        rule.waitForIdle()
        val before = host.scrollAnchor.value
        assertNotNull(before)
        assertFalse("用户上滑后不再贴底", before!!.followBottom)

        repeat(3) { toggleTwice(host) }

        val after = host.scrollAnchor.value
        assertEquals("首个可见块不变", before.itemKey, after?.itemKey)
        assertEquals("偏移不变", before.offsetPx, after?.offsetPx)
        assertFalse(after!!.followBottom)
        // 终端形态按行拆成条目（慢帧修复），一条消息的每行都带 term:item:<key>：至少有一行可见。
        val nodes = rule.onAllNodesWithTag("term:item:${before.itemKey}", useUnmergedTree = true)
        val n = nodes.fetchSemanticsNodes().size
        assertTrue("首个可见块仍在屏幕上", (0 until n).any { runCatching { nodes[it].assertIsDisplayed() }.isSuccess })
    }

    /** 审查第 1 轮 Important 3：长回复流式时贴底跟随（停在末尾而不是这条回复的开头），切换形态后仍贴底。 */
    @Test
    fun streamingFollowsBottomAcrossSwitch() {
        val host = newFake()
        rule.setContent { Surface(host) }
        rule.waitForIdle()
        val stream = host.startStream(StreamScript.counting(300, intervalMs = 5), userText = "数数")
        rule.waitUntil(60_000) { !stream.isActive }
        rule.waitForIdle()
        assertTrue("仍贴底：${host.scrollAnchor.value}", host.scrollAnchor.value?.followBottom == true)
        rule.onNodeWithText("300", substring = true, useUnmergedTree = true).assertIsDisplayed()
        repeat(2) { toggleTwice(host) }
        assertTrue(host.scrollAnchor.value?.followBottom == true)
        rule.onNodeWithText("300", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun atFileSearchOffersCandidates() {
        val queries = mutableListOf<String>()
        val host = setUp(
            TermEnv(fileSearch = { q ->
                queries += q
                listOf(FileMatch("src/main.kt", false, "@src/main.kt"), FileMatch("src/main", true, "@src/main/")).filter { it.path.contains(q) }
            }),
        )
        host.fake.draft.value = host.fake.draft.value.withText("看看 @mai")
        rule.waitUntil(5_000) { queries.contains("mai") }
        rule.waitForIdle()
        rule.onNodeWithTag("term:at:src/main.kt", useUnmergedTree = true).assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals("看看 @src/main.kt ", host.fake.draft.value.text)
    }

    @Test
    fun headerListsLoadedResources() {
        setUp(TermEnv(contextFiles = listOf("/root/.pi/agent/AGENTS.md", "/root/proj/AGENTS.md")))
        rule.onNodeWithTag("term:transcript").performTouchInput { swipeDown() } // 页眉在列表顶部
        rule.waitForIdle()
        rule.onNodeWithTag("term:header:section:Context", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("/root/proj/AGENTS.md", substring = true, useUnmergedTree = true).assertIsDisplayed()
        // get_commands 里的提示模板（Fake 夹具 fix-tests）列在 [Prompts]
        rule.onNodeWithTag("term:header:section:Prompts", useUnmergedTree = true).assertIsDisplayed()
    }

    /** 调度者裁定（PR #19 审查 Nit 1）：面板自带键帽发面板事件（overlay id、键帽 actionId、高亮项）给面板宿主，不当作动作 id 执行。 */
    @Test
    fun panelKeyCapsEmitPanelEvents() {
        val events = mutableListOf<Triple<String, String, String?>>()
        val host = setUp(
            TermEnv(onPanelEvent = { id, key, value ->
                events += Triple(id, key, value)
                io.github.zr987459.pigo.contract.actions.ActionResult.Toast("已设为默认")
            }),
        )
        host.overlay.push(
            OverlayModel.SelectList(
                "model.select", "选择模型",
                listOf(SelectGroup(null, listOf(SelectItem("model:a/sonnet", "sonnet"), SelectItem("model:b/gpt", "gpt")))),
                selectedKey = "model:b/gpt",
                keyCaps = listOf(io.github.zr987459.pigo.contract.session.KeyCap("Ctrl+S", "设为默认", "default"), io.github.zr987459.pigo.contract.session.KeyCap("Space", "常用", "star")),
            ),
        )
        rule.waitForIdle()
        val ranBefore = host.fake.fakeActions.ran.size
        rule.onNodeWithTag("term:keycap:model:Ctrl+S 设为默认", useUnmergedTree = true).tap()
        rule.waitForIdle()
        rule.onNodeWithTag("term:keycap:model:Space 常用", useUnmergedTree = true).tap()
        rule.waitForIdle()
        assertEquals(listOf(Triple("model.select", "default", "model:b/gpt"), Triple("model.select", "star", "model:b/gpt")), events)
        assertTrue("键帽不应作为动作 id 交给 runAction：${host.fake.fakeActions.ran.drop(ranBefore)}", host.fake.fakeActions.ran.drop(ranBefore).none { it.first == "default" || it.first == "star" })
        rule.onNodeWithTag("term:toast", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * §8.5 / §7.3：1000 条流式 delta 下慢帧 < 5%（帧间隔超过 2 个刷新周期计为慢帧，与 JankStats 的默认判定相同）。
     *
     * 调度者裁定（PR #18 审查第 1 轮）：CI 的模拟器是软件渲染（swiftshader），同样 1000 条 delta 下连纯 Text 列表也会大量掉帧。
     * 因此同一测试、同一模拟器上先跑一个**基线**（同样 1000 条 delta，用最朴素的 LazyColumn + Text 显示同一份聊天状态），
     * 再跑终端形态：慢帧率 < 5% 直接通过；否则必须 ≤ 基线 + 5 个百分点，并且 ≤ 25%。真机 < 5% 列入真机验收清单（PR 描述）。
     */
    @Test
    fun slowFramesUnder5PercentWith1000Deltas() {
        val baseHost = newFake()
        val termHost = newFake()
        var mode by androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            when (mode) {
                0 -> BaselineList(baseHost)
                else -> Surface(termHost)
            }
        }
        rule.waitForIdle()
        val chunks = (0 until 1000).map { i -> if (i % 40 == 0) "\n\n## 第 ${i / 40} 节\n\n- 列表项 $i " else "字$i " }

        val baseline = measureSlowFrames(baseHost, chunks)
        rule.runOnUiThread { mode = 1 }
        rule.waitForIdle()
        val term = measureSlowFrames(termHost, chunks)

        val msg = "终端慢帧 ${pct(term)}（${term.first}/${term.second}），同模拟器基线 ${pct(baseline)}（${baseline.first}/${baseline.second}）"
        android.util.Log.i("TermCoverageTest", msg)
        println(msg)
        val t = ratio(term)
        val b = ratio(baseline)
        assertTrue("没有采集到帧", term.second > 0 && baseline.second > 0)
        assertTrue("$msg：超过 5% 且超过基线 + 5 个百分点或 25%", t < 0.05 || (t <= b + 0.05 && t <= 0.25))
    }

    private fun ratio(r: Pair<Int, Int>) = if (r.second == 0) 0.0 else r.first.toDouble() / r.second
    private fun pct(r: Pair<Int, Int>) = "%.2f%%".format(ratio(r) * 100)

    /** 跑一次 1000 条 delta 的流，返回（慢帧数，总帧数）。 */
    private fun measureSlowFrames(host: FakeSessionHost, chunks: List<String>): Pair<Int, Int> {
        val frames = AtomicInteger()
        val slow = AtomicInteger()
        val last = AtomicLong(0)
        val tracking = java.util.concurrent.atomic.AtomicBoolean(true)
        val refreshNs = (1_000_000_000L / (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.view.WindowManager::class.java).defaultDisplay.refreshRate.coerceAtLeast(30f))).toLong()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                val p = last.getAndSet(t)
                if (p != 0L) {
                    frames.incrementAndGet()
                    if (t - p > 2 * refreshNs) slow.incrementAndGet()
                }
                if (tracking.get()) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        rule.runOnUiThread { Choreographer.getInstance().postFrameCallback(cb) }
        val stream = host.startStream(StreamScript(chunks, intervalMs = 8), userText = "写长一点")
        rule.waitUntil(120_000) { !stream.isActive }
        rule.waitForIdle()
        tracking.set(false)
        return slow.get() to frames.get()
    }

    /** 基线：最朴素的显示方式（每条消息一个 Text，贴底），只用来度量本模拟器的渲染能力。 */
    @Composable
    private fun BaselineList(host: SessionHost) {
        val chat by host.chat.collectAsState()
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(chat.items.size, chat.items.lastOrNull()) {
            if (chat.items.isNotEmpty()) state.scrollToItem(chat.items.size - 1, Int.MAX_VALUE)
        }
        androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
            items(chat.items.size, key = { chat.items[it].key }) { i ->
                val item = chat.items[i]
                val text = when (item) {
                    is ChatItem.User -> item.text
                    is ChatItem.Assistant -> item.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { b -> b.text }
                    else -> ""
                }
                Text(text, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}
