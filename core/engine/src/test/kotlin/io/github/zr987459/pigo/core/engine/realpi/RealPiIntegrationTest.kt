package io.github.zr987459.pigo.core.engine.realpi

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.core.engine.InMemoryWakeLock
import io.github.zr987459.pigo.core.engine.ListLog
import io.github.zr987459.pigo.core.engine.PiEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 桌面真 pi 1.5.0 的 JVM 集成测试（DESIGN.md §6 engine 行）。默认跳过，CI 不跑、不依赖网络：
 * 模型请求全部打到本机 [MockOpenAiServer]。
 *
 * ```
 * npm i -g @earendil-works/pi-coding-agent@1.5.0
 * PIGO_PI_BIN=$(which pi) ./gradlew :core:engine:testDebugUnitTest --tests '*RealPiIntegrationTest*'
 * ```
 */
class RealPiIntegrationTest {
    private lateinit var sb: PiSandbox
    private lateinit var runtime: LocalRuntime
    private lateinit var engine: PiEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = InMemoryWakeLock()
    private val log = ListLog()

    @Before
    fun setUp() {
        assumeTrue("设置 PIGO_PI_BIN 指向本机 pi 1.5.0 才运行", PiSandbox.piBin != null)
        sb = PiSandbox()
        // 一个扩展命令：/pigo-ping 被扩展消费（disposition=handled），不会启动一轮
        File(sb.agentDir, "extensions").mkdirs()
        File(sb.agentDir, "extensions/pigo-test.ts").writeText(
            """
            export default function (pi: any) {
              pi.registerCommand("pigo-ping", {
                description: "pi-go integration test",
                handler: async (_args: string, ctx: any) => { ctx.ui.notify("pong", "info"); },
              });
            }
            """.trimIndent(),
        )
        runtime = LocalRuntime(PiSandbox.piBin!!)
        val env = object : LaunchEnvProvider {
            override suspend fun env() = sb.keyEnv
        }
        engine = PiEngine(runtime, env, log, scope, sb.engineConfig(), wake)
    }

    @After
    fun tearDown() {
        if (!::engine.isInitialized) return
        runBlocking { engine.close() }
        scope.cancel()
        sb.close()
    }

    private suspend fun until(timeoutMs: Long = 30_000, cond: () -> Boolean) = withTimeout(timeoutMs) { while (!cond()) delay(20) }

    private suspend fun settledAfter(block: suspend () -> Unit) {
        block()
        until { engine.chat.value.running }
        until(60_000) { !engine.chat.value.running }
    }

    private fun ChatState.texts() = items.filterIsInstance<ChatItem.Assistant>().flatMap { a -> a.blocks.filterIsInstance<ContentBlock.Text>().map { it.text } }

    @Test
    fun promptStreamsToAgentSettled() = runBlocking {
        assertTrue(engine.start() is Outcome.Ok)
        assertEquals("mock", engine.chat.value.model?.provider)
        assertFalse(wake.isHeld)
        var sawRunningWithLock = false
        settledAfter {
            assertEquals(Outcome.Ok(Disposition.STARTED), engine.send("#text 你好"))
            until { engine.chat.value.running }
            sawRunningWithLock = wake.isHeld
        }
        assertTrue(sawRunningWithLock)
        assertFalse("空闲时不持有唤醒锁（N1）", wake.isHeld)
        val st = engine.chat.value
        assertEquals(Delivery.SENT, st.items.filterIsInstance<ChatItem.User>().single().delivery)
        assertEquals("你好，世界\u2028第二行", st.texts().single())
        assertTrue(engine.status.value is EngineStatus.Idle)
        until { engine.chat.value.contextPercent != null || true }
    }

    @Test
    fun handledPromptDoesNotWait() = runBlocking {
        engine.start()
        val r = withTimeout(10_000) { engine.send("/pigo-ping") }
        assertEquals(Outcome.Ok(Disposition.HANDLED), r)
        delay(300)
        assertFalse(engine.chat.value.running)
        assertEquals(0, sb.mock.requests.get())
    }

    @Test
    fun toolCardsSuccessAndFailure() = runBlocking {
        engine.start()
        settledAfter { engine.send("#tool-ok 跑命令") }
        val ok = engine.chat.value.items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals(ToolStatus.OK, ok.status)
        assertEquals("bash", ok.toolName)
        settledAfter { engine.send("#tool-fail 读文件") }
        val failed = engine.chat.value.items.filterIsInstance<ChatItem.Tool>().last()
        assertEquals(ToolStatus.ERROR, failed.status)
        assertEquals("read", failed.toolName)
    }

    @Test
    fun followUpAndSteerAreDeliveredOnce() = runBlocking {
        engine.start()
        settledAfter {
            engine.send("#slow 慢慢说")
            until { engine.chat.value.texts().any { it.isNotEmpty() } }
            assertEquals(Outcome.Ok(Disposition.QUEUED), engine.send("#text 做完再说", SendMode.FOLLOW_UP))
            until { engine.chat.value.queue.followUp == listOf("#text 做完再说") }
        }
        var users = engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(listOf("#slow 慢慢说", "#text 做完再说"), users)
        assertTrue(engine.chat.value.queue.isEmpty)

        settledAfter {
            engine.send("#slow-tool 慢命令")
            until { engine.chat.value.items.any { it is ChatItem.Tool && it.status == ToolStatus.RUNNING } }
            assertEquals(Outcome.Ok(Disposition.QUEUED), engine.send("#text 插话", SendMode.STEER))
        }
        users = engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(1, users.count { it == "#text 插话" })
        val order = engine.chat.value.items
        assertTrue(order.indexOfFirst { it is ChatItem.Tool && it.toolName == "bash" } < order.indexOfFirst { it is ChatItem.User && it.text == "#text 插话" })
        assertFalse(wake.isHeld)
    }

    @Test
    fun stopClearsQueueAndAborts() = runBlocking {
        engine.start()
        engine.send("#slow 慢慢说")
        until { engine.chat.value.texts().any { it.isNotEmpty() } }
        engine.send("#text 不该发出", SendMode.FOLLOW_UP)
        until { engine.chat.value.queue.followUp.isNotEmpty() }
        val r = withTimeout(25_000) { engine.stop() } as Outcome.Ok
        assertEquals(listOf("#text 不该发出"), r.value.followUp)
        until { !engine.chat.value.running }
        delay(1_000)
        val st = engine.chat.value
        assertFalse(st.items.filterIsInstance<ChatItem.User>().any { it.text.contains("不该发出") })
        assertTrue(st.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        assertFalse(wake.isHeld)
        assertEquals("进程没有被重启", 1, runtime.started)
    }

    @Test
    fun switchSessionRestoresHistory() = runBlocking {
        engine.start()
        settledAfter { engine.send("#text 第一个会话") }
        val first = engine.currentSessionFile!!
        assertTrue(engine.newSession() is Outcome.Ok)
        assertTrue(engine.chat.value.items.isEmpty())
        settledAfter { engine.send("#text 第二个会话") }
        val second = engine.currentSessionFile!!
        assertNotEquals(first, second)
        assertTrue(engine.openSession(first, sb.cwd.absolutePath) is Outcome.Ok)
        assertEquals(1, runtime.started) // 同 cwd：switch_session，不重启
        assertEquals(listOf("#text 第一个会话"), engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text })
        // 另一个 cwd：以该 cwd 重启
        assertTrue(engine.openSession(second, sb.otherCwd.absolutePath) is Outcome.Ok)
        assertEquals(2, runtime.started)
        assertEquals(listOf("#text 第二个会话"), engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text })
    }

    @Test
    fun killedProcessIsRestartedAndSessionRestored() = runBlocking {
        engine.start()
        settledAfter { engine.send("#text 被杀之前") }
        val file = engine.currentSessionFile
        val victim = runtime.last!!
        victim.kill9() // 模拟系统/用户杀掉进程（不经过我们的 stop）
        until { runtime.started == 2 && engine.status.value is EngineStatus.Idle }
        assertEquals(file, engine.currentSessionFile)
        assertTrue(engine.chat.value.items.filterIsInstance<ChatItem.User>().any { it.text == "#text 被杀之前" })
        assertTrue(engine.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.INTERRUPTED })
        // 恢复后还能继续对话
        settledAfter { engine.send("#text 恢复之后") }
        assertTrue(engine.chat.value.texts().last().contains("你好"))
        assertFalse(wake.isHeld)
    }

    @Test
    fun providerErrorAndModels() = runBlocking {
        engine.start()
        val models = (engine.models() as Outcome.Ok).value
        assertTrue(models.any { it.provider == "mock" && it.id == "mock-model" })
        assertTrue((engine.thinkingLevels() as Outcome.Ok).value.contains("off"))
        settledAfter { engine.send("#fail401 错误 Key") }
        assertTrue(engine.chat.value.items.any { it is ChatItem.Notice && it.kind == NoticeKind.ERROR && it.text.startsWith("Key 错误") })
        assertTrue(engine.rename("集成测试") is Outcome.Ok)
        until { engine.chat.value.sessionName == "集成测试" }
        assertTrue((engine.stats() as Outcome.Ok).value.raw.isNotEmpty())
    }
}
