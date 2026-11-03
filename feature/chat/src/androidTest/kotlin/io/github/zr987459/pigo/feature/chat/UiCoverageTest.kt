package io.github.zr987459.pigo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.RetryBanner
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.actions.PiActionCatalog
import io.github.zr987459.pigo.contract.actions.PiActionSpec
import io.github.zr987459.pigo.contract.actions.UiEntry
import io.github.zr987459.pigo.contract.fake.FakeSessionHost
import io.github.zr987459.pigo.contract.fake.StreamScript
import io.github.zr987459.pigo.contract.session.BashMode
import io.github.zr987459.pigo.contract.session.DraftImage
import io.github.zr987459.pigo.contract.session.DraftState
import io.github.zr987459.pigo.contract.session.OverlayModel
import io.github.zr987459.pigo.contract.session.SelectGroup
import io.github.zr987459.pigo.contract.session.SelectItem
import io.github.zr987459.pigo.contract.session.UiForm
import io.github.zr987459.pigo.core.ui.calm.CommandSearch
import io.github.zr987459.pigo.feature.chat.ui.calm.CalmSurface
import io.github.zr987459.pigo.feature.chat.ui.calm.SendKey
import io.github.zr987459.pigo.feature.chat.ui.calm.sendKeyOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN-r3 §7.1 第 3 条：UI 形态覆盖测试（硬性要求 ① 的 UI 部分），以及 §8.5 r3/calm 行的
 * 发送键三态、⌘ 三种搜索、UI 形态切换夹具。全部用 [FakeSessionHost]。
 *
 * CI 的模拟器 job 只按类名跑本类，所以 r3/calm 的仪器验收都放在这里。
 */
@RunWith(AndroidJUnit4::class)
class UiCoverageTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: FakeSessionHost

    @Before
    fun setUp() {
        host = FakeSessionHost(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun setCalm() {
        compose.setContent {
            val form by host.form.collectAsState()
            if (form == UiForm.CALM) CalmSurface(host, dark = false) else BasicText("终端形态（占位）")
        }
        compose.waitForIdle()
    }

    private fun ran(id: String) = host.fakeActions.ran.any { it.first == id }

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String, timeoutMs: Long = 3_000): Boolean = try {
        compose.waitUntil(timeoutMs) { exists(tag) }; true
    } catch (_: Throwable) { false }

    private fun resetUi() {
        compose.runOnIdle { host.overlay.clear() }
        if (exists("calm:expandedClose")) compose.onNodeWithTag("calm:expandedClose").performClick()
        if (exists("calm:drawerScrim")) compose.onNodeWithTag("calm:drawerScrim").performTouchInput { click(Offset(width - 8f, height / 2f)) }
        compose.waitForIdle()
    }

    /** 打开 ⌘、输入 [query]、滚到并点 `action:<id>`；找不到返回 false。 */
    private fun searchAndClick(query: String, id: String): Boolean {
        if (!exists("commands:search")) compose.onNodeWithTag("calm:commands").performClick()
        compose.onNodeWithTag("commands:search").performTextReplacement(query)
        compose.waitForIdle()
        return try {
            compose.onNodeWithTag("commands:list").performScrollToNode(hasTestTag("action:$id"))
            compose.onNodeWithTag("action:$id").performClick()
            compose.waitForIdle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun startLongStream(user: String? = "你好") =
        host.startStream(StreamScript(List(2_000) { "字" }, intervalMs = 200), userText = user)

    // ---------------------------------------------------------------- 硬性要求 ① UI 部分

    @Test
    fun everyActionHasUiEntry() {
        setCalm()
        val missing = mutableListOf<String>()
        for (spec in PiActionCatalog.all) {
            val ok = when (val ui = spec.ui) {
                UiEntry.Command -> checkCommand(spec)
                is UiEntry.Explain -> ui.text.isNotBlank() && checkExplain(spec)
                is UiEntry.Persistent -> checkPersistent(spec, ui.tag)
                is UiEntry.LongPress -> checkLongPress(spec, ui.tag)
            }
            if (!ok) missing += "${spec.id}（${spec.ui::class.simpleName}）"
            resetUi()
        }
        assertTrue("UI 形态缺少入口或结果标记：$missing", missing.isEmpty())
    }

    private fun checkCommand(spec: PiActionSpec): Boolean {
        if (!searchAndClick(spec.title, spec.id)) return false
        if (!ran(spec.id)) return false
        // FakePiActions 对非说明动作返回 Overlay（id = 动作 id）→ CalmSheet 画出 overlay:<id>
        return waitFor("overlay:${spec.id}")
    }

    private fun checkExplain(spec: PiActionSpec): Boolean {
        if (!searchAndClick(spec.title, spec.id)) return false
        return ran(spec.id) && waitFor("local:explain:${spec.id}")
    }

    private fun checkPersistent(spec: PiActionSpec, tag: String): Boolean {
        when (spec.id) {
            "session.new" -> compose.onNodeWithTag("persist:session.resume").performClick()
            "send" -> compose.runOnIdle { host.draft.value = DraftState(text = "你好") }
        }
        compose.waitForIdle()
        if (!waitFor(tag)) return false
        val formBefore = host.form.value
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.waitForIdle()
        val ok = when (spec.id) {
            "form.toggle" -> host.form.value != formBefore
            "session.resume" -> waitFor("calm:drawer")
            "thinking.select" -> ran("thinking.cycle") // 单击循环档位，长按才是档位列表（§6.4）
            else -> ran(spec.id)
        }
        if (spec.id == "form.toggle") compose.runOnIdle { host.form.value = UiForm.CALM }
        if (spec.id == "send") compose.runOnIdle { host.draft.value = DraftState.EMPTY }
        return ok
    }

    private fun checkLongPress(spec: PiActionSpec, tag: String): Boolean {
        val anchor = when (spec.id) {
            "send.followUp", "abort" -> {
                startLongStream()
                compose.runOnIdle { host.draft.value = DraftState(text = "引导一下") }
                "persist:send"
            }
            "tree.label" -> {
                val job = host.startStream(StreamScript(listOf("好", "的"), intervalMs = 10), userText = "夹具消息")
                compose.waitUntil(3_000) { job.isCompleted }
                "msg:" + host.chat.value.items.first { it is ChatItem.User }.key
            }
            "model.setDefault" -> "persist:model.select"
            else -> return false
        }
        compose.waitForIdle()
        if (!waitFor(anchor)) return false
        compose.onNodeWithTag(anchor).performTouchInput { longClick() }
        compose.waitForIdle()
        if (!waitFor(tag)) return false
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
        val ok = ran(spec.id)
        compose.runOnIdle { host.draft.value = DraftState.EMPTY }
        return ok
    }

    // ---------------------------------------------------------------- ⌘ 三种搜索

    @Test
    fun commandCenterFindsEveryActionByChineseSlashAndPinyin() {
        setCalm()
        val missing = mutableListOf<String>()
        for (spec in PiActionCatalog.all) {
            val queries = buildList {
                add("中文" to spec.title)
                spec.slash.firstOrNull()?.let { add("斜杠" to "/$it") }
                CommandSearch.initials(spec.title).takeIf { it.isNotEmpty() }?.let { add("拼音" to it) }
            }
            for ((kind, q) in queries) {
                if (!exists("commands:search")) compose.onNodeWithTag("calm:commands").performClick()
                compose.onNodeWithTag("commands:search").performTextReplacement(q)
                compose.waitForIdle()
                val found = try {
                    compose.onNodeWithTag("commands:list").performScrollToNode(hasTestTag("action:${spec.id}")); true
                } catch (_: Throwable) { false }
                if (!found) missing += "${spec.id} 按$kind「$q」"
            }
        }
        assertTrue("⌘ 搜不到：$missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- 发送键三态（§4.4）

    @Test
    fun sendKeyThreeStates() {
        setCalm()
        // 空闲 + 空：↑ 发送，禁用
        assertTrue(waitFor("send:send"))
        compose.onNodeWithTag("persist:send").assertIsNotEnabled()
        // 空闲 + 有字：可发送
        compose.runOnIdle { host.draft.value = DraftState(text = "你好") }
        compose.onNodeWithTag("persist:send").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(ran("send"))
        // `!` 模式：文字为「运行」
        compose.runOnIdle { host.overlay.clear(); host.draft.value = DraftState(text = "ls", bashMode = BashMode.INCLUDE) }
        assertTrue(waitFor("send:run"))
        assertTrue(waitFor("calm:bashPrefix"))
        // 生成中 + 空：■ 停止 → abort
        compose.runOnIdle { host.draft.value = DraftState.EMPTY }
        startLongStream()
        assertTrue(waitFor("send:stop"))
        compose.onNodeWithTag("persist:send").performClick()
        compose.waitForIdle()
        assertTrue(ran("abort"))
        // 生成中 + 有字：引导（steer = send），长按：排队 / 停止
        compose.runOnIdle { host.overlay.clear() }
        startLongStream()
        compose.runOnIdle { host.draft.value = DraftState(text = "顺便补测试") }
        assertTrue(waitFor("send:steer"))
        compose.onNodeWithTag("persist:send").performTouchInput { longClick() }
        assertTrue(waitFor("longpress:send.followUp"))
        assertTrue(waitFor("longpress:abort"))
        // 重试中停止 → retry.abort；bash 运行中停止 → bash.abort（纯逻辑，Fake 引擎造不出这两种状态）
        assertEquals(SendKey.Stop("retry.abort"), sendKeyOf(ChatState(running = true, retry = RetryBanner(1, 3, 2_000, null)), DraftState.EMPTY))
        val bash = ChatItem.Tool("t", "c", "bash", buildJsonObject { put("command", "sleep 9") }, ToolStatus.RUNNING)
        assertEquals(SendKey.Stop("bash.abort"), sendKeyOf(ChatState(items = listOf(bash)), DraftState.EMPTY))
        assertEquals(SendKey.Steer, sendKeyOf(ChatState(running = true), DraftState(text = "x")))
    }

    // ---------------------------------------------------------------- UI 形态切换夹具（§7.2、§8.5）

    @Test
    fun leavingAndReenteringCalmRestoresDraftOverlayAndReadingPosition() {
        setCalm()
        // 一键切换：顶栏 ⇄
        compose.onNodeWithTag("form:toggle").performClick()
        compose.waitForIdle()
        assertEquals(UiForm.TERM, host.form.value)
        compose.runOnIdle { host.form.value = UiForm.CALM }
        compose.waitForIdle()

        val script = StreamScript(List(200) { "第${it}段\n" }, intervalMs = 20)
        host.clearCommands()
        val job = host.startStream(script, userText = "数到两百")
        compose.runOnIdle {
            host.draft.value = DraftState(
                text = "草稿abc", selectionStart = 3, selectionEnd = 3,
                images = listOf(DraftImage("content://fake/1.png", "image/png", "1.png")),
                fileRefs = listOf("a.kt"),
            )
            host.overlay.push(
                OverlayModel.SelectList(
                    id = "model.select", title = "选择模型", query = "son",
                    groups = listOf(SelectGroup(null, listOf(SelectItem("anthropic/sonnet", "sonnet-4.5"), SelectItem("openai/gpt", "gpt")))),
                ),
            )
            host.showExtDialog(ExtUiRequest(id = "req-1", method = "confirm", title = "确认", message = "继续吗？", timeoutMs = 60_000), System.currentTimeMillis())
        }
        // 生成期间切换 10 次
        repeat(10) {
            compose.runOnIdle { host.form.value = UiForm.TERM }
            compose.waitForIdle()
            compose.runOnIdle { host.form.value = UiForm.CALM }
            compose.waitForIdle()
        }
        // 草稿原样（文字、光标、图片、@ 引用）
        compose.onNodeWithTag("calm:input").assert(hasText("草稿abc"))
        compose.onNodeWithTag("calm:input").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        assertTrue(exists("calm:image:0"))
        assertTrue(exists("calm:ref:a.kt"))
        // 扩展对话框仍在，应答带同一 request id
        assertTrue(waitFor("overlay:ext:req-1"))
        compose.onNodeWithTag("ext:ok").performClick()
        compose.waitForIdle()
        assertTrue(host.commands.value.any { it.type == "extension_ui_response" && it.args["id"] == "req-1" && it.args["confirmed"] == "true" })
        assertFalse(host.commands.value.any { it.args["cancelled"] == "true" })
        // 打开中的模型面板搜索词还在
        assertTrue(waitFor("overlay:model.select"))
        compose.onNodeWithTag("overlay:model.select:search").assert(hasText("son"))
        compose.runOnIdle { host.overlay.clear() }

        // 流式不断、不重启、切换不发命令
        compose.waitUntil(15_000) { job.isCompleted }
        val last = host.chat.value.items.last { it is ChatItem.Assistant } as ChatItem.Assistant
        assertEquals(script.fullText, (last.blocks.single() as ContentBlock.Text).text)
        assertEquals(1, host.fakeEngine.startCount)
        assertEquals(0, host.fakeEngine.restartCount)
        assertTrue("切换形态不得发出命令：${host.commands.value}", host.commands.value.none { it.type in setOf("abort", "new_session", "switch_session", "restart") })

        // 阅读位置：滚到顶后离开再回来，仍停在顶部那条（不是贴底）
        val userKey = host.chat.value.items.first { it is ChatItem.User }.key
        compose.onNodeWithTag("calm:messages").performScrollToIndex(0)
        compose.waitForIdle()
        compose.runOnIdle { host.form.value = UiForm.TERM }
        compose.waitForIdle()
        assertEquals(userKey, host.scrollAnchor.value?.itemKey)
        assertEquals(false, host.scrollAnchor.value?.followBottom)
        compose.runOnIdle { host.form.value = UiForm.CALM }
        compose.waitForIdle()
        compose.onNodeWithTag("msg:$userKey").assertIsDisplayed()
    }

    @Test
    fun commandCenterQuerySurvivesFormSwitch() {
        setCalm()
        compose.onNodeWithTag("calm:commands").performClick()
        compose.onNodeWithTag("commands:search").performTextReplacement("fzs")
        compose.waitForIdle()
        compose.runOnIdle { host.form.value = UiForm.TERM }
        compose.waitForIdle()
        compose.runOnIdle { host.form.value = UiForm.CALM }
        compose.waitForIdle()
        compose.onNodeWithTag("commands:search").assert(hasText("fzs"))
        compose.onNodeWithTag("commands:list").performScrollToNode(hasTestTag("action:session.tree"))
    }
}
