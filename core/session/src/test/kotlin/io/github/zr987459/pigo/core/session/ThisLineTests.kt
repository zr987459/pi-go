package io.github.zr987459.pigo.core.session

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.actions.ActionResult
import io.github.zr987459.pigo.contract.session.DraftState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** DESIGN-r3 §7.3 中属 r3/session 的测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbortRetryTest {
    @Test
    fun retryAbortSendsOnlyAbortRetry() = runTest(UnconfinedTestDispatcher()) {
        val (host, engine) = testHost(backgroundScope)
        engine.clearCommands()
        host.runAction("retry.abort")
        assertEquals(listOf("abort_retry"), engine.commands.value.map { it.type })
    }
}

class QueueBackToDraftTest {
    private val queue = QueueSnapshot(steering = listOf("先看 README"), followUp = listOf("再跑测试"))

    @Test
    fun emptyDraftGetsQueuedTextBack() {
        val d = QueueRecall.merge(DraftState.EMPTY, queue.steering + queue.followUp)
        assertEquals("先看 README\n再跑测试", d.text)
        assertTrue(d.recalled.isEmpty())
    }

    @Test
    fun nonEmptyDraftKeepsTextAndShowsChip() {
        val d = QueueRecall.merge(DraftState(text = "我在写"), queue.steering + queue.followUp)
        assertEquals("我在写", d.text)
        assertEquals(listOf("先看 README", "再跑测试"), d.recalled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionSwitchAndEngineRestartRecallQueue() = runTest(UnconfinedTestDispatcher()) {
        val st = PiState(sessionFile = "/s/1.jsonl")
        val chat = MutableStateFlow(ChatState(sessionFile = "/s/1.jsonl", queue = queue, running = true))
        val status = MutableStateFlow<EngineStatus>(EngineStatus.Running(st))
        val got = ArrayList<List<String>>()
        backgroundScope.launch { QueueRecall.watch(chat, status) { got += it } }
        chat.value = ChatState(sessionFile = "/s/2.jsonl")
        assertEquals(listOf(listOf("先看 README", "再跑测试")), got)
        chat.value = ChatState(sessionFile = "/s/2.jsonl", queue = QueueSnapshot(emptyList(), listOf("第三条")), running = true)
        status.value = EngineStatus.Starting
        assertEquals(listOf("第三条"), got.last())
    }
}

/**
 * `@` 引用写法快照：夹具取自 `@earendil-works/pi-tui@1.5.0` `dist/autocomplete.js` `buildCompletionValue`
 * 与 `applyCompletion`（文件后跟空格、目录不跟；含空白或 CJK 标点加引号）。
 */
class AtFileExpansionSnapshotTest {
    @Test
    fun matchesPiTuiCompletionFormat() {
        assertEquals("@src/main.kt", AtFileExpansion.token("src/main.kt"))
        assertEquals("@src/", AtFileExpansion.token("src/"))
        assertEquals("@\"my notes.md\"", AtFileExpansion.token("my notes.md"))
        assertEquals("@\"笔记，草稿.md\"", AtFileExpansion.token("笔记，草稿.md"))
        assertEquals("@a.kt 看一下", AtFileExpansion.compose("看一下", listOf("a.kt")))
        assertEquals("@a.kt @src/ 对比", AtFileExpansion.compose("对比", listOf("a.kt", "src/")))
        assertEquals("@a.kt", AtFileExpansion.compose("", listOf("a.kt")))
        assertEquals("只有文字", AtFileExpansion.compose("只有文字", emptyList()))
    }
}

class PiSettingsSnapshotTest {
    private val original = """
        {
          "defaultModel": "deepseek-chat",
          "someUnknownKey": {"keep": [1, 2, 3]},
          "compaction": {"enabled": true, "reserveTokens": 16384},
          "retry": {"provider": {"openai": {"maxRetries": 5}}}
        }
    """.trimIndent()

    private fun tmp(): File = Files.createTempDirectory("pis").toFile().resolve("settings.json").also { it.writeText(original) }

    @Test
    fun onlyTargetKeyChanges() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(original) as kotlinx.serialization.json.JsonObject
        val next = SettingsJson.put(root, "compaction.enabled".split('.'), JsonPrimitive(false))
        val a = SettingsJson.flatten(root); val b = SettingsJson.flatten(next)
        assertEquals(a.keys, b.keys)
        for (k in a.keys) if (k != "compaction.enabled") assertEquals(k, a[k], b[k])
        assertEquals(JsonPrimitive(false), b["compaction.enabled"])
        assertEquals(listOf("defaultModel", "someUnknownKey", "compaction", "retry"), next.keys.toList())
    }

    @Test
    fun setWritesFileUnderLockAndReloads() = kotlinx.coroutines.runBlocking {
        val f = tmp(); var reloads = 0
        val s = PiSettingsImpl(f, reload = { reloads++ })
        s.refresh()
        s.set("retry.maxRetries", JsonPrimitive(7))
        val after = SettingsJson.flatten(SettingsJson.read(f))
        assertEquals("7", (after["retry.maxRetries"] as JsonPrimitive).content)
        assertEquals("5", (after["retry.provider.openai.maxRetries"] as JsonPrimitive).content)
        assertTrue(after.containsKey("someUnknownKey.keep"))
        assertEquals(1, reloads)
        s.set("defaultModel", null)
        assertFalse(SettingsJson.flatten(SettingsJson.read(f)).containsKey("defaultModel"))
    }

    @Test
    fun concurrentWritesAllLand() {
        val f = tmp()
        val pool = Executors.newFixedThreadPool(8)
        repeat(40) { i -> pool.submit { SettingsJson.locked(f) { SettingsJson.write(f, SettingsJson.put(SettingsJson.read(f), listOf("k$i"), JsonPrimitive(i))) } } }
        pool.shutdown(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val keys = SettingsJson.flatten(SettingsJson.read(f)).keys
        for (i in 0 until 40) assertTrue("k$i 丢了", "k$i" in keys)
        assertTrue("someUnknownKey.keep" in keys)
    }
}

/** schema 覆盖清单 §8 每一行列出的全部键（§7.3 PiSettingsSchemaCoverageTest）。 */
class PiSettingsSchemaCoverageTest {
    @Test
    fun schemaCoversEveryKeyInChecklist() {
        val doc = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/pi-features.md") }.first { it.exists() }
        val lines = (System.getProperty("pigo.piFeatures")?.let(::File) ?: doc).readLines()
        val start = lines.indexOfFirst { it.startsWith("## 8.") }
        val end = lines.indexOfFirst { it.startsWith("### 8.1") }
        val keys = HashSet<String>()
        for (l in lines.subList(start, end).filter { it.startsWith("| ") }) {
            val cells = l.trim('|').split('|')
            if (cells.size < 2) continue
            Regex("`([A-Za-z][A-Za-z0-9.*]+)`").findAll(cells[1]).forEach { keys += it.groupValues[1] }
        }
        assertTrue("没解析到键", keys.size > 60)
        val specs = PiSettingsSchemaV150.specs.map { it.key }.toSet()
        // 清单把同组子键写成简称（如 compaction 行里的 reserveTokens），按「完整键或同组前缀.简称」匹配。
        val prefixes = specs.map { it.substringBeforeLast('.', "") }.filter { it.isNotEmpty() }.toSet()
        val missing = keys.filter { k ->
            val bare = k.removeSuffix(".*")
            bare !in specs && prefixes.none { p -> "$p.$bare" in specs } && specs.none { it.startsWith("$bare.") }
        }
        assertTrue("schema 缺这些键：$missing", missing.isEmpty())
    }
}

/** `/session`、`/hotkeys`、`/changelog` 的输出只进本地块，不发任何命令、不写会话（§7.3）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBlocksNotPersistedTest {
    @Test
    fun localBlocksSendNothing() = runTest(UnconfinedTestDispatcher()) {
        val (host, engine) = testHost(backgroundScope)
        engine.clearCommands()
        val itemsBefore = host.chat.value.items
        for (id in listOf("session.info", "hotkeys", "changelog")) {
            val r = host.runAction(id)
            assertTrue("$id 应返回本地块：$r", r is ActionResult.LocalBlock)
        }
        assertEquals(3, host.localBlocks.value.size)
        assertEquals(emptyList<String>(), engine.commands.value.map { it.type })
        assertEquals(itemsBefore, host.chat.value.items)
    }
}
