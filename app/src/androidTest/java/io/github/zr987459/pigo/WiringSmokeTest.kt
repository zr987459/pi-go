package io.github.zr987459.pigo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.onFirst
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.di.BindingMode
import io.github.zr987459.pigo.di.Mode
import io.github.zr987459.pigo.feature.models.ModelTags
import io.github.zr987459.pigo.nav.PendingModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W5 接线的仪器冒烟（docs/feature-gap.md §3.2 W5 验收 ①–⑥）。CI 只编译（assembleDebugAndroidTest）；
 * 在模拟器/真机上用 `./gradlew :app:connectedDebugAndroidTest` 跑。
 *
 * 需要外部条件的用例用 [Assume] 跳过而不是失败：
 * - ①④⑤ 需要真实模型 Key：`-Pandroid.testInstrumentationRunnerArguments.pigoProvider=deepseek
 *   -Pandroid.testInstrumentationRunnerArguments.pigoKey=sk-...`（不写进仓库）。
 * - ②③④⑤ 用 W1（feat/engine-rpc，已合入）的真实 EngineExtras（BindingMode.extras = REAL）；本地改回 FAKE 时自动跳过。⑥ 用 W3 真实文件页。
 */
@RunWith(AndroidJUnit4::class)
class WiringSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val graph get() = (rule.activity.application as PiGoApp).graph
    private val args get() = InstrumentationRegistry.getArguments()

    private fun waitTag(tag: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitText(text: String, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun rowTags(): List<String> = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TestTag))
        .fetchSemanticsNodes().mapNotNull { node -> node.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }
        .filter { it.startsWith(ModelTags.ROW_PREFIX) }

    private fun drawer(tag: String) {
        rule.onRoot().performTouchInput { swipeRight(startX = 2f, endX = centerX) }
        waitTag("drawer")
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Before
    fun runtimeReady() {
        Assume.assumeTrue("运行环境未安装，先在设备上完成安装向导", graph.runtime.status.value.phase == Phase.READY)
        waitTag("page:chat", 20_000)
    }

    /** ① 顶栏选一个未配 Key 的模型 → 跳到配置页 → 填 Key → 回到聊天自动选中该模型。 */
    @Test
    fun pickUnconfiguredModelConfigureKeyAndAutoSelect() {
        val provider = args.getString("pigoProvider")
        val key = args.getString("pigoKey")
        Assume.assumeTrue("需要 pigoProvider / pigoKey 参数", !provider.isNullOrBlank() && !key.isNullOrBlank())
        runBlocking { graph.models.auth.clear(provider!!) }
        rule.onNodeWithTag("chat:model").performClick()
        waitTag(ModelTags.LIST)
        rule.onNodeWithTag(ModelTags.SEARCH).performTextInput(provider!!)
        // 点该服务商下的某一个模型（不是分组标题的「去配置」）：配置完成后要选回的正是它（审查第 1 轮 Important 3）。
        rule.waitUntil(10_000) { rowTags().any { it.startsWith(ModelTags.ROW_PREFIX + provider + "/") } }
        val tag = rowTags().first { it.startsWith(ModelTags.ROW_PREFIX + provider + "/") }
        val id = tag.removePrefix(ModelTags.ROW_PREFIX + provider + "/")
        rule.onNodeWithTag(tag).performClick()
        waitTag("page:provider")
        assertEquals(PendingModel.Target(provider, ModelRef(provider, id)), PendingModel.target.value)
        rule.onNodeWithTag("provider.key").performTextInput(key!!)
        rule.onNodeWithTag("provider.save").performClick()
        waitTag("page:chat", 30_000)
        rule.waitUntil(60_000) { graph.engine.chat.value.model?.let { it.provider == provider && it.id == id } == true }
        assertEquals(provider, graph.engine.chat.value.model?.provider)
        assertEquals(id, graph.engine.chat.value.model?.id)
    }

    /** ② 「+」选一张图发给支持看图的模型，时间线显示缩略图（UserImages 附加项）。 */
    @Test
    fun imageSendShowsThumbnail() {
        Assume.assumeTrue("需要 W1 真实 EngineExtras（W3 已合入）", BindingMode.extras == Mode.REAL)
        val png = ImageInput("image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", 1, 1, "dot.png")
        rule.runOnUiThread { graph.chatDeps.controller.addImages(listOf(png)) }
        waitTag("chat:attachments")
        rule.onNodeWithTag("chat:input").performTextInput("这张图是什么颜色？")
        rule.onNodeWithTag("chat:send").performClick()
        waitTag("extra:images", 60_000)
    }

    /** ③ 输入 `/` 出现至少一个技能或模板。 */
    @Test
    fun slashShowsAtLeastOneSkillOrTemplate() {
        Assume.assumeTrue("需要 W1 真实 EngineExtras（get_commands）：Fake 的命令列表不算", BindingMode.extras == Mode.REAL)
        rule.onNodeWithTag("chat:input").performTextReplacement("/")
        waitTag("command-palette", 15_000)
        val cmds = runBlocking { graph.liveExtras!!.commands(false) }
        assertTrue("pi 没有返回任何命令：$cmds", cmds is Outcome.Ok && cmds.value.isNotEmpty())
        val first = (cmds as Outcome.Ok).value.first()
        waitTag("cmd-${first.name}")
        rule.onNodeWithTag("chat:input").performTextReplacement("")
    }

    /** ④ 「从这里分叉」后会话列表多一条，原会话不变。 */
    @Test
    fun forkAddsSessionAndKeepsOriginal() {
        Assume.assumeTrue("需要 W1 真实 EngineExtras（fork、messageMeta）", BindingMode.extras == Mode.REAL)
        Assume.assumeTrue("需要已配置模型", graph.engine.chat.value.model != null)
        rule.onNodeWithTag("chat:input").performTextInput("只回复：好")
        rule.onNodeWithTag("chat:send").performClick()
        rule.waitUntil(90_000) { !graph.engine.chat.value.running && graph.engine.chat.value.items.size >= 2 }
        val original = graph.engine.chat.value.sessionFile
        val originalSize = original?.let { graph.runtime.hostFile(it).length() }
        val before = runBlocking { graph.sessions.refresh(); graph.sessions.observe().first().size }
        rule.onAllNodes(hasTestTag("row:user")).onFirst().performTouchInput { longClick() }
        waitTag("menu:fork")
        rule.onNodeWithTag("menu:fork").performClick()
        rule.waitUntil(30_000) { graph.engine.chat.value.sessionFile != original }
        val after = runBlocking { graph.sessions.refresh(); graph.sessions.observe().first().size }
        assertEquals(before + 1, after)
        assertEquals("原会话文件不变", originalSize, original?.let { graph.runtime.hostFile(it).length() })
    }

    /** ⑤ 用量弹层的「压缩」跑完后时间线出现压缩卡。 */
    @Test
    fun compactFromUsageSheetShowsCompactionCard() {
        Assume.assumeTrue("需要 W1 真实 EngineExtras（compact、timelineExtras）", BindingMode.extras == Mode.REAL)
        Assume.assumeTrue("需要已配置模型且会话里有内容", graph.engine.chat.value.model != null && graph.engine.chat.value.items.isNotEmpty())
        waitTag("chat:usage")
        rule.onNodeWithTag("chat:usage").performClick()
        waitTag("sheet:usage")
        rule.onNodeWithTag("compact-button").performClick()
        waitTag("extra:compaction", 120_000)
    }

    /** ⑥ 文件页打开 pi 刚写的文件，内容一致。 */
    @Test
    fun filesPageShowsFileWrittenByPi() {
        Assume.assumeTrue("需要 W3 真实文件页（feat/files）", BindingMode.workspace == Mode.REAL)
        val text = "pigo-smoke-" + System.currentTimeMillis()
        val f = graph.runtime.hostFile("/root/pi-cwd/pigo-smoke.txt")
        f.parentFile?.mkdirs()
        f.writeText(text)
        drawer("nav:files")
        waitTag("page:files")
        waitText("pigo-smoke.txt")
        rule.onNode(hasText("pigo-smoke.txt")).performClick()
        waitText(text)
        assertTrue(f.delete())
    }
}
