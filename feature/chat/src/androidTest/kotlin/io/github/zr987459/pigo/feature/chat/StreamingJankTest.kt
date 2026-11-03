package io.github.zr987459.pigo.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.metrics.performance.JankStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeFixtures
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.core.ui.PiGoTheme
import io.github.zr987459.pigo.feature.chat.ui.ChatScreen
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DESIGN.md §6 chat-ui 验收：回放 1000 条 text_delta 时慢帧 < 5%（JankStats）。
 * 需要模拟器或真机运行（CI 只编译）。
 */
@RunWith(AndroidJUnit4::class)
class StreamingJankTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun streamingTurn(deltas: Int): List<EngineEvent> = buildList {
        val empty = JsonObject(emptyMap())
        fun upd(e: AssistantMessageEvent) = EngineEvent.MessageUpdate(null, e, null, empty)
        add(EngineEvent.AgentStart(empty))
        add(EngineEvent.MessageStart(FakeFixtures.userMessage("写一篇长一点的说明"), empty))
        add(EngineEvent.MessageStart(FakeFixtures.assistantMessage(), empty))
        add(upd(AssistantMessageEvent.TextStart(0)))
        for (i in 0 until deltas) {
            // 混入 Markdown：每 40 条换段，每 200 条一个代码块，覆盖分段渲染路径
            val d = when {
                i % 200 == 0 -> "\n\n```kotlin\nval x$i = $i\n```\n\n"
                i % 40 == 0 -> "\n\n## 第 ${i / 40} 节\n\n- 列表项 $i "
                else -> "字$i "
            }
            add(upd(AssistantMessageEvent.TextDelta(0, d)))
        }
        add(upd(AssistantMessageEvent.TextEnd(0, null)))
        add(EngineEvent.MessageEnd(FakeFixtures.assistantMessage("（完）"), empty))
        add(EngineEvent.AgentEnd(JsonArray(emptyList()), willRetry = false, raw = empty))
        add(EngineEvent.AgentSettled(aborted = false, raw = empty))
    }

    @Test
    fun replaying1000DeltasKeepsSlowFramesUnder5Percent() {
        val engine = FakeEngineClient(scope, stepDelayMs = 0)
        val controller = ChatController(engine, scope)
        compose.setContent { PiGoTheme { ChatScreen(controller, backToBackground = false) } }
        compose.waitForIdle()

        val frames = AtomicInteger()
        val janky = AtomicInteger()
        lateinit var stats: JankStats
        compose.runOnUiThread {
            stats = JankStats.createAndTrack(compose.activity.window) { f ->
                frames.incrementAndGet()
                if (f.isJank) janky.incrementAndGet()
            }
            stats.isTrackingEnabled = true
        }

        val done = AtomicBoolean(false)
        scope.launch {
            engine.replay(streamingTurn(1000), delayMs = 8)
            done.set(true)
        }
        compose.waitUntil(timeoutMillis = 120_000) { done.get() }
        compose.waitForIdle()
        compose.runOnUiThread { stats.isTrackingEnabled = false }

        // 正文确实流式增长到了最后
        val text = (engine.chat.value.items.last { it is ChatItem.Assistant } as ChatItem.Assistant)
            .blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
        assertTrue(text.contains("字999"))

        val total = frames.get()
        val ratio = if (total == 0) 0.0 else janky.get().toDouble() / total
        assertTrue("没有采集到帧", total > 0)
        assertTrue("慢帧比例 ${"%.2f".format(ratio * 100)}%（$janky/$total）超过 5%", ratio < 0.05)
    }
}
