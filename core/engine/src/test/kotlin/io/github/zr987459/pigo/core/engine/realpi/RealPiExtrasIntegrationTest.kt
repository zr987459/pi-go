package io.github.zr987459.pigo.core.engine.realpi

import kotlinx.coroutines.launch
import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.core.engine.InMemoryWakeLock
import io.github.zr987459.pigo.core.engine.ListLog
import io.github.zr987459.pigo.core.engine.PiEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * W1 验收（docs/feature-gap.md §3.2）：桌面真 pi 1.5.0 + 本机假模型服务。默认跳过，设置 `PIGO_PI_BIN` 才运行：
 * ```
 * PIGO_PI_BIN=$(which pi) ./gradlew :core:engine:testDebugUnitTest --tests '*RealPiExtrasIntegrationTest*'
 * ```
 */
class RealPiExtrasIntegrationTest {
    private lateinit var sb: PiSandbox
    private lateinit var engine: PiEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        assumeTrue("设置 PIGO_PI_BIN 指向本机 pi 1.5.0 才运行", PiSandbox.piBin != null)
        sb = PiSandbox()
        File(sb.agentDir, "prompts").mkdirs()
        File(sb.agentDir, "prompts/x.md").writeText("---\ndescription: 测试模板 x\n---\n请复述：\$ARGUMENTS\n")
        val env = object : LaunchEnvProvider {
            override suspend fun env() = sb.keyEnv
        }
        engine = PiEngine(LocalRuntime(PiSandbox.piBin!!), env, ListLog(), scope, sb.engineConfig(), InMemoryWakeLock())
    }

    @After
    fun tearDown() {
        if (!::engine.isInitialized) return
        runBlocking { engine.close() }
        scope.cancel()
        sb.close()
    }

    private suspend fun until(timeoutMs: Long = 30_000, cond: () -> Boolean) = withTimeout(timeoutMs) { while (!cond()) delay(20) }

    private suspend fun turn(text: String, images: List<ImageInput> = emptyList()) {
        assertTrue(engine.send(text, images) is Outcome.Ok)
        until { engine.chat.value.running }
        until(60_000) { !engine.chat.value.running }
    }

    @Test
    fun getCommandsListsPromptTemplate() = runBlocking {
        engine.start()
        val cmds = (engine.commands() as Outcome.Ok).value
        val x = cmds.single { it.name == "x" }
        assertEquals("测试模板 x", x.description)
        assertTrue(x.path!!.endsWith("prompts/x.md"))
    }

    @Test
    fun forkChangesSessionFileAndReturnsText() = runBlocking {
        engine.start()
        turn("第一句 #text")
        turn("第二句")
        val before = engine.currentSessionFile
        assertNotNull(before)
        until { engine.messageMeta.value.size >= 4 }
        val firstUser = engine.chat.value.items.first { it is ChatItem.User }
        val meta = engine.messageMeta.value.getValue(firstUser.key)
        assertTrue(meta.forkable)
        val f = (engine.fork(meta.entryId) as Outcome.Ok).value
        assertFalse(f.cancelled)
        assertEquals("第一句 #text", f.text)
        assertNotEquals(before, engine.currentSessionFile)
        assertTrue("原会话不变", File(before!!).isFile)
        // pi 在新会话有助手消息后才落盘：再跑一轮，新文件出现
        turn("分叉后 #text")
        assertTrue(File(engine.currentSessionFile!!).isFile)
    }

    /**
     * 审查第 1 轮 Important#1：真 pi 1.5.0 普通消息不发 entry_appended。第二轮进行中的任意时刻，
     * 新用户气泡都不能配到第一轮用户消息的 entryId；轮末新气泡配到内容为「第二句」的那条 user entry。
     */
    @Test
    fun messageMetaNeverPairsNewBubbleWithPreviousEntryMidTurn() = runBlocking {
        engine.start()
        turn("第一句 #text")
        until { engine.messageMeta.value.size >= 2 }
        val firstUser = engine.chat.value.items.first { it is ChatItem.User }
        val firstEntry = engine.messageMeta.value.getValue(firstUser.key).entryId
        val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watch = scope.launch {
            engine.messageMeta.collect { m -> m.forEach { (k, v) -> if (v.entryId == firstEntry && k != firstUser.key) bad += k } }
        }
        turn("第二句")
        val second = engine.chat.value.items.last { it is ChatItem.User }
        until { engine.messageMeta.value[second.key] != null }
        watch.cancel()
        assertTrue("轮中错配：$bad", bad.isEmpty())
        val secondEntry = engine.messageMeta.value.getValue(second.key).entryId
        assertNotEquals(firstEntry, secondEntry)
        val entries = (engine.entries(null) as Outcome.Ok).value
        val e = entries.entries.single { it.id == secondEntry }
        assertTrue("配到的条目应是「第二句」：${e.preview}", e.preview.contains("第二句"))
    }

    @Test
    fun treeHasAtLeastTwoLevels() = runBlocking {
        engine.start()
        turn("你好")
        val tree = (engine.tree() as Outcome.Ok).value
        var depth = 0
        var level = tree.roots
        while (level.isNotEmpty()) {
            depth++
            level = level.flatMap { it.children }
        }
        assertTrue("深度 $depth", depth >= 2)
        assertNotNull(tree.leafId)
    }

    @Test
    fun compactSucceedsAndMakesCard() = runBlocking {
        // 会话很短：把 keepRecentTokens 调到 1，否则 pi 报 "Nothing to compact"。
        File(sb.agentDir, "settings.json").writeText(
            """{"defaultProvider":"mock","defaultModel":"mock-model","defaultThinkingLevel":"medium","compaction":{"keepRecentTokens":1}}""",
        )
        engine.start()
        turn("第一轮 #text")
        turn("第二轮")
        val r = engine.compact(null)
        assertTrue("compact: $r", r is Outcome.Ok)
        assertTrue((r as Outcome.Ok).value.summary.isNotBlank())
        until { engine.timelineExtras.value.any { it is TimelineExtra.Compaction && !it.running } }
        val card = engine.timelineExtras.value.filterIsInstance<TimelineExtra.Compaction>().last()
        assertNotNull(card.tokensBefore)
    }

    @Test
    fun bashEchoHi() = runBlocking {
        engine.start()
        val events = withTimeout(30_000) { engine.bash("echo hi").toList() }
        val done = events.last() as BashEvent.Done
        assertEquals("hi", done.result.output.trim())
        assertEquals(0, done.result.exitCode)
        until { engine.timelineExtras.value.any { it is TimelineExtra.Bash && !it.running } }
    }

    @Test
    fun exportHtmlWritesFile() = runBlocking {
        engine.start()
        turn("导出用 #text")
        val path = (engine.exportHtml(File(sb.root, "out.html").absolutePath) as Outcome.Ok).value
        assertTrue(File(path).isFile)
        assertTrue(File(path).length() > 0)
    }

    @Test
    fun imagePromptReachesModelAsImageUrl() = runBlocking {
        engine.start()
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        turn("这是什么图", listOf(ImageInput("image/png", png, 1, 1)))
        assertTrue("mock 收到 ${sb.mock.imageUrls.size} 张图", sb.mock.imageUrls.any { it.startsWith("data:image/png;base64,") && it.contains(png.take(20)) })
        val u = engine.chat.value.items.filterIsInstance<ChatItem.User>().single()
        assertTrue(engine.timelineExtras.value.any { it is TimelineExtra.UserImages && it.anchorKey == u.key })
    }
}
