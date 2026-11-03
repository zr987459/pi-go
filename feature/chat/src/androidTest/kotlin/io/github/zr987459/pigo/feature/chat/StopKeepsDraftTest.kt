package io.github.zr987459.pigo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.feature.chat.ui.ChatScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN.md §5.5 Q3/Q4 的 UI 测试：停止时撤回的排队消息只在输入框为空时放回，
 * 用户正在写草稿时草稿不被覆盖，改为「已撤回 n 条」芯片。需要模拟器或真机运行（CI 只编译）。
 */
@RunWith(AndroidJUnit4::class)
class StopKeepsDraftTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: FakeEngineClient
    private lateinit var controller: ChatController

    @Before
    fun setUp() {
        // 回放放慢，保证测试期间一直处于「生成中」
        engine = FakeEngineClient(scope, stepDelayMs = 400)
        controller = ChatController(engine, scope)
        compose.setContent { PiGoTheme { ChatScreen(controller, backToBackground = false) } }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun type(text: String) {
        compose.onNodeWithTag("chat:input").performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun startRunningAndQueue(queued: String) {
        type("列出当前目录并读 README")
        compose.onNodeWithTag("chat:send").performClick()
        compose.waitUntil(10_000) { engine.chat.value.running }
        type(queued)
        compose.onNodeWithTag("chat:send").assertTextContains("排队").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("chip:followUp")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun stopWhileWritingDraftKeepsDraftAndShowsWithdrawnChip() {
        startRunningAndQueue("再检查一遍格式")
        type("我正在写的新草稿")
        compose.onNodeWithTag("chat:stopAlt").performClick()
        compose.waitUntil(20_000) { !engine.chat.value.running && !controller.state.value.composer.stopping }
        compose.waitForIdle()

        compose.onNodeWithTag("chat:input").assertTextContains("我正在写的新草稿")
        assertEquals("我正在写的新草稿", controller.state.value.composer.draft)
        compose.onNodeWithTag("chip:withdrawn").assertTextContains("已撤回 1 条", substring = true)
        assertEquals(listOf("再检查一遍格式"), controller.state.value.composer.withdrawn)
    }

    @Test
    fun stopWithEmptyDraftPutsQueuedTextBack() {
        startRunningAndQueue("再检查一遍格式")
        // 输入框为空时发送键就是停止键
        compose.onNodeWithTag("chat:send").assertTextContains("停止").performClick()
        compose.waitUntil(20_000) { !engine.chat.value.running && !controller.state.value.composer.stopping }
        compose.waitForIdle()

        compose.onNodeWithTag("chat:input").assertTextContains("再检查一遍格式")
        assertTrue(controller.state.value.composer.withdrawn.isEmpty())
    }

    @Test
    fun typingDuringStreamingIsNeverInterrupted() {
        type("列出当前目录并读 README")
        compose.onNodeWithTag("chat:send").performClick()
        compose.waitUntil(10_000) { engine.chat.value.running }
        // 流式更新期间逐字输入，输入框内容不应被滞后的状态回写打断
        val input = compose.onNodeWithTag("chat:input")
        for (ch in "边看边打字") input.performTextInput(ch.toString())
        compose.waitForIdle()
        input.assertTextContains("边看边打字")
        assertEquals("边看边打字", controller.state.value.composer.draft)
    }
}
