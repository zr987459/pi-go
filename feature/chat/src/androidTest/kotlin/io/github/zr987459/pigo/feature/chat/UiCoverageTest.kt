package io.github.zr987459.pigo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.setValue
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

    /** 动作经协程派发，模拟器慢时点完不一定立刻记上：最多等 [timeoutMs]（只放宽时序，不放宽断言）。 */
    private fun ranSoon(id: String, timeoutMs: Long = 3_000): Boolean = try {
        compose.waitUntil(timeoutMs) { ran(id) }; true
    } catch (_: Throwable) { false }

    private fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String, timeoutMs: Long = 3_000): Boolean = try {
        compose.waitUntil(timeoutMs) { exists(tag) }; true
    } catch (_: Throwable) { false }

    private fun resetUi() {
        compose.runOnIdle { host.overlay.clear() }
        if (exists("calm:expandedClose")) compose.onNodeWithTag("calm:expandedClose").performClick()
        // r5：会话列表是整页（OpenMinis 首页），用「继续当前对话」回到聊天页
        if (exists("calm:drawer")) compose.onNodeWithTag("drawer:back").performClick()
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
            if (!ok) missing += "${spec.id}（${spec.ui::class.simpleName}）" + (explainWhy[spec.id]?.let { "：$it" } ?: "")
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

    /**
     * 说明条目：⌘ 里点它 → 动作真的执行 → 本地结果块出现在消息列表里，且能在列表里滚到并显示出来。
     * 结果块追加在列表末尾，可能在视口外（LazyColumn 不组合视口外的项），所以先等结果块落进 host，
     * 再在 `calm:messages` 上 performScrollToNode 把它滚进视口后断言显示（不放宽：块不在列表里照样失败）。
     */
    private fun checkExplain(spec: PiActionSpec): Boolean {
        if (!searchAndClick(spec.title, spec.id)) { explainWhy[spec.id] = "⌘ 里找不到或点不到"; return false }
        if (!ran(spec.id)) { explainWhy[spec.id] = "动作没有执行"; return false }
        val tag = "local:explain:${spec.id}"
        val landed = try {
            compose.waitUntil(3_000) { host.localBlocks.value.any { it.title == "explain:${spec.id}" } }; true
        } catch (_: Throwable) { false }
        if (!landed) { explainWhy[spec.id] = "结果块没有进 localBlocks"; return false }
        resetUi() // 收起 ⌘ 面板，消息列表回到前台
        val shown = runCatching {
            compose.onNodeWithTag("calm:messages").performScrollToNode(hasTestTag(tag))
            compose.waitForIdle()
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        }.isSuccess
        if (!shown) explainWhy[spec.id] = "结果块不在消息列表里或无法显示"
        return shown
    }

    private val explainWhy = mutableMapOf<String, String>()

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
                "msg:" + host.chat.value.items.last { it is ChatItem.User }.key
            }
            "model.setDefault" -> "persist:model.select"
            else -> return false
        }
        compose.waitForIdle()
        if (anchor.startsWith("msg:")) runCatching { compose.onNodeWithTag("calm:messages").performScrollToNode(hasTestTag(anchor)) }
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
        assertTrue("应出现 send:send", waitFor("send:send", 5_000))
        compose.onNodeWithTag("persist:send").assertIsNotEnabled()
        // 空闲 + 有字：可发送
        compose.runOnIdle { host.draft.value = DraftState(text = "你好") }
        compose.onNodeWithTag("persist:send").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue("点发送后应执行 send", ranSoon("send"))
        // `!` 模式：文字为「运行」
        compose.runOnIdle { host.overlay.clear(); host.draft.value = DraftState(text = "ls", bashMode = BashMode.INCLUDE) }
        assertTrue("应出现 send:run", waitFor("send:run", 5_000))
        assertTrue("应出现 calm:bashPrefix", waitFor("calm:bashPrefix", 5_000))
        // 生成中 + 空：■ 停止 → abort
        compose.runOnIdle { host.draft.value = DraftState.EMPTY }
        startLongStream()
        assertTrue("应出现 send:stop", waitFor("send:stop", 5_000))
        compose.onNodeWithTag("persist:send").performClick()
        compose.waitForIdle()
        assertTrue("生成中点停止应执行 abort", ranSoon("abort"))
        // 生成中 + 有字：引导（steer = send），长按：排队 / 停止
        compose.runOnIdle { host.overlay.clear() }
        startLongStream()
        compose.runOnIdle { host.draft.value = DraftState(text = "顺便补测试") }
        assertTrue("应出现 send:steer", waitFor("send:steer", 5_000))
        compose.onNodeWithTag("persist:send").performTouchInput { longClick() }
        assertTrue("应出现 longpress:send.followUp", waitFor("longpress:send.followUp", 5_000))
        assertTrue("应出现 longpress:abort", waitFor("longpress:abort", 5_000))
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
        // 一键进入终端：聊天页顶栏的终端图标（r5 起终端是 OpenMinis 式独立页面）
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

    // ---- PR #17 遗留 Nit 1（r3/app PR 2 并入）：CalmSurface 慢帧 + 深浅色截图 ----

    /**
     * 1000 条流式 delta 下慢帧（帧间隔超过 2 个刷新周期，与 JankStats 默认判定相同）。做法同 TermCoverageTest：
     * 同一模拟器先跑朴素 LazyColumn + Text 基线，CalmSurface 慢帧率 < 5% 直接通过，否则须 ≤ 基线 + 5 个百分点且 ≤ 25%
     * （调度者裁定的相对阈值；CI 模拟器是软件渲染）。替代原指向旧 ChatScreen 的 StreamingJankTest。
     */
    @Test
    fun calmSlowFramesWithin1000Deltas() {
        val baseHost = FakeSessionHost(scope)
        val calmHost = FakeSessionHost(scope)
        var mode by androidx.compose.runtime.mutableStateOf(0)
        compose.setContent {
            if (mode == 0) BaselineList(baseHost) else CalmSurface(calmHost, dark = false)
        }
        compose.waitForIdle()
        val chunks = (0 until 1000).map { i -> if (i % 40 == 0) "\n\n## 第 ${i / 40} 节\n\n- 列表项 $i " else "字$i " }
        val baseline = measureSlowFrames(baseHost, chunks)
        compose.runOnUiThread { mode = 1 }
        compose.waitForIdle()
        val calm = measureSlowFrames(calmHost, chunks)
        val r = { p: Pair<Int, Int> -> if (p.second == 0) 0.0 else p.first.toDouble() / p.second }
        val msg = "UI 形态慢帧 ${"%.2f".format(r(calm) * 100)}%（${calm.first}/${calm.second}），同模拟器基线 ${"%.2f".format(r(baseline) * 100)}%（${baseline.first}/${baseline.second}）"
        android.util.Log.i("UiCoverageTest", msg)
        assertTrue("没有采集到帧", calm.second > 0 && baseline.second > 0)
        val t = r(calm)
        assertTrue("$msg：超过 5% 且超过基线 + 5 个百分点或 25%", t < 0.05 || (t <= r(baseline) + 0.05 && t <= 0.25))
        val last = calmHost.chat.value.items.last { it is ChatItem.Assistant } as ChatItem.Assistant
        assertTrue(last.blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }.contains("字999"))
    }

    private fun measureSlowFrames(h: FakeSessionHost, chunks: List<String>): Pair<Int, Int> {
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val slow = java.util.concurrent.atomic.AtomicInteger()
        val last = java.util.concurrent.atomic.AtomicLong(0)
        val tracking = java.util.concurrent.atomic.AtomicBoolean(true)
        val refreshNs = (1_000_000_000L / (androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.view.WindowManager::class.java).defaultDisplay.refreshRate.coerceAtLeast(30f))).toLong()
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                val p = last.getAndSet(t)
                if (p != 0L) {
                    frames.incrementAndGet()
                    if (t - p > 2 * refreshNs) slow.incrementAndGet()
                }
                if (tracking.get()) android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        compose.runOnUiThread { android.view.Choreographer.getInstance().postFrameCallback(cb) }
        val stream = h.startStream(StreamScript(chunks, intervalMs = 8), userText = "写长一点")
        compose.waitUntil(120_000) { !stream.isActive }
        compose.waitForIdle()
        tracking.set(false)
        return slow.get() to frames.get()
    }

    @androidx.compose.runtime.Composable
    private fun BaselineList(h: FakeSessionHost) {
        val chat by h.chat.collectAsState()
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
                androidx.compose.material3.Text(text)
            }
        }
    }

    /**
     * 深浅色截图：同一会话分别在浅色、深色下截整屏，存到应用外部文件目录（calm-light.png / calm-dark.png，CI 可取作产物），
     * 并断言两张都有内容、深色平均亮度明显低于浅色、正文在两种主题下都可见。
     */
    @Test
    fun calmLightAndDarkScreenshots() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { CalmSurface(host, dark = dark) }
        compose.waitForIdle()
        val job = host.startStream(StreamScript(listOf("# 标题\n\n", "正文一段，", "`code`\n"), intervalMs = 10), userText = "截图")
        compose.waitUntil(5_000) { job.isCompleted }
        compose.waitForIdle()
        val light = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.runOnIdle { dark = true }
        compose.waitForIdle()
        val darkShot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        if (dir != null) {
            java.io.File(dir, "calm-light.png").outputStream().use { light.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            java.io.File(dir, "calm-dark.png").outputStream().use { darkShot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val lumL = meanLuma(light)
        val lumD = meanLuma(darkShot)
        assertTrue("浅色截图亮度 $lumL 应明显高于深色 $lumD", lumL - lumD > 60)
        assertTrue("浅色截图不应是纯色", distinctColors(light) > 8)
        assertTrue("深色截图不应是纯色", distinctColors(darkShot) > 8)
        compose.onNodeWithText("正文一段", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun meanLuma(b: android.graphics.Bitmap): Double {
        var sum = 0.0; var n = 0
        val step = 8
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            sum += 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c); n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun distinctColors(b: android.graphics.Bitmap): Int {
        val set = HashSet<Int>()
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) set += b.getPixel(x, y)
        return set.size
    }
}
