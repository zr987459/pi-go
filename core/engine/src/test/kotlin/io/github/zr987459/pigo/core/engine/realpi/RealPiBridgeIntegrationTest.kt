package io.github.zr987459.pigo.core.engine.realpi

import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.bridge.BridgeState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * DESIGN-r3 §8.5 r3/core「realpi/ 集成测试」：真 pi 1.5.0 + 随包 pigo-bridge.ts + 本机假模型服务，走真实 [PiEngine]。
 * 默认跳过；设置 `PIGO_PI_BIN` 才运行（CI 的 realpi.yml 会先 `npm i` pi 1.5.0）：
 *
 * PIGO_PI_BIN=$(which pi) ./gradlew :core:engine:testDebugUnitTest --tests '*RealPiBridgeIntegrationTest*'
 */
class RealPiBridgeIntegrationTest {
    private lateinit var sb: PiSandbox
    private lateinit var engine: PiEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val asset = File("src/main/assets/pi/pigo-bridge.ts")

    @Before
    fun setUp() {
        assumeTrue("设置 PIGO_PI_BIN 指向本机 pi 1.5.0 才运行", PiSandbox.piBin != null)
        sb = PiSandbox()
        val env = object : LaunchEnvProvider {
            override suspend fun env() = sb.keyEnv
        }
        val bridgePath = File(sb.root, "pigo/pigo-bridge.ts").absolutePath
        engine = PiEngine(
            LocalRuntime(PiSandbox.piBin!!), env, ListLog(), scope,
            sb.engineConfig().copy(bridgeExtension = bridgePath, bridgeReadyTimeoutMs = 20_000),
            InMemoryWakeLock(),
            bridgeSource = { asset.inputStream() },
        )
    }

    @After
    fun tearDown() {
        if (!::engine.isInitialized) return
        runBlocking { engine.close() }
        scope.cancel()
        sb.close()
    }

    private suspend fun until(timeoutMs: Long = 30_000, cond: () -> Boolean) = withTimeout(timeoutMs) { while (!cond()) delay(20) }

    /** 会话里已写盘的助手消息数（get_entries）；取不到为 -1。 */
    private suspend fun assistantEntries(): Int =
        (engine.entries() as? Outcome.Ok)?.value?.entries?.count { it.role == "assistant" } ?: -1

    /**
     * 跑完一轮。不再等「running 变成 true」：本机假模型对 `#text` 几乎立即答完，整轮可能落在两次 20 ms 轮询之间，
     * 从来观察不到 running=true（CI 偶发 TimeoutCancellationException 的根因）。改为等单调信号：
     * 助手消息数比发送前多、且不在生成中。
     */
    private suspend fun turn(text: String) {
        val before = assistantEntries()
        assertTrue(engine.send(text) is Outcome.Ok)
        withTimeout(60_000) {
            while (engine.chat.value.running || assistantEntries() <= before) delay(50)
        }
    }

    private suspend fun startReady() {
        assertTrue(engine.start() is Outcome.Ok)
        until(20_000) { engine.bridgeStatus.value.state != BridgeState.PENDING }
        assertEquals("桥接加载成功：${engine.bridgeStatus.value.reason}", BridgeState.READY, engine.bridgeStatus.value.state)
    }

    @Test
    fun bridgeLoadsAndHidesItsCommands() = runBlocking {
        startReady()
        val names = (engine.commands(refresh = true) as Outcome.Ok).value.map { it.name }
        assertTrue("pigo:* 不出现在 / 补全", names.none { it.startsWith("pigo:") })
    }

    @Test
    fun treeNavChangesLeafKeepsSessionFile() = runBlocking {
        startReady()
        turn("第一句 #text")
        turn("第二句 #text")
        val before = (engine.tree() as Outcome.Ok).value
        val file = engine.currentSessionFile
        assertNotNull(file)
        val firstUser = (engine.entries() as Outcome.Ok).value.entries.first { it.role == "user" }
        val nav = (engine.treeNavigate(firstUser.id) as Outcome.Ok).value
        assertFalse(nav.viaFork)
        assertFalse(nav.cancelled)
        val after = (engine.tree() as Outcome.Ok).value
        assertNotEquals("leafId 改变", before.leafId, after.leafId)
        assertEquals("sessionFile 不变", file, engine.currentSessionFile)
        assertEquals(nav.leafId, after.leafId)
        // 审查 Nit 3：选中用户消息 → 原文随回复带回，r3/session 放回共享草稿（pi /tree 的行为）。
        assertEquals("第一句 #text", nav.editorText)
        assertFalse(nav.unconfirmed)
    }

    @Test
    fun switchViaBridgeReturnsToEarlierSession() = runBlocking {
        startReady()
        turn("会话一 #text")
        val first = engine.currentSessionFile
        assertNotNull(first)
        assertTrue(engine.newSession(null) is Outcome.Ok)
        turn("会话二 #text")
        assertNotEquals(first, engine.currentSessionFile)
        // 请求序号经 globalThis 随新实例的 ready（reason=resume）带回；路径换一种写法也能对上（规范化比较）。
        assertEquals(Outcome.Ok(Unit), engine.switchSessionViaBridge(first!!.replace("/sessions/", "//sessions/./")))
        assertEquals(first, engine.currentSessionFile)
    }

    @Test
    fun labelAppearsInTree() = runBlocking {
        startReady()
        turn("打个标签 #text")
        val asst = (engine.entries() as Outcome.Ok).value.entries.last { it.role == "assistant" }
        assertEquals(Outcome.Ok(Unit), engine.setEntryLabel(asst.id, "书签 一"))
        assertEquals("书签 一", (engine.tree() as Outcome.Ok).value.find(asst.id)?.label)
        assertEquals("清除", Outcome.Ok(Unit), engine.setEntryLabel(asst.id, null))
        assertEquals(null, (engine.tree() as Outcome.Ok).value.find(asst.id)?.label)
    }

    @Test
    fun reloadPicksUpNewTemplate() = runBlocking {
        startReady()
        assertTrue((engine.commands(refresh = true) as Outcome.Ok).value.none { it.name == "pigonew" })
        File(sb.agentDir, "prompts").mkdirs()
        File(sb.agentDir, "prompts/pigonew.md").writeText("---\ndescription: reload 后出现\n---\nhi \$1\n")
        assertEquals(Outcome.Ok(Unit), engine.reloadResources())
        assertTrue("reload 后新模板出现在 get_commands", (engine.commands(refresh = true) as Outcome.Ok).value.any { it.name == "pigonew" })
        assertEquals(BridgeState.READY, engine.bridgeStatus.value.state)
    }

    @Test
    fun labelDuringGenerationRunsImmediatelyWithoutInterrupting() = runBlocking {
        startReady()
        turn("先来一轮 #text")
        val asst = (engine.entries() as Outcome.Ok).value.entries.last { it.role == "assistant" }
        assertTrue(engine.send("慢一点 #slow") is Outcome.Ok)
        until { engine.chat.value.running }
        until { engine.chat.value.items.lastOrNull()?.let { it.key.isNotEmpty() } == true }
        val t0 = System.currentTimeMillis()
        assertEquals(Outcome.Ok(Unit), engine.setEntryLabel(asst.id, "生成中"))
        val dt = System.currentTimeMillis() - t0
        assertTrue("立即执行（${dt}ms）", dt < 2_000)
        assertTrue("没有打断生成", engine.chat.value.running)
        until(60_000) { !engine.chat.value.running }
        val last = (engine.lastAssistantText() as Outcome.Ok).value.orEmpty()
        assertTrue("回复完整：$last", last.contains("片段14"))
        assertEquals("生成中", (engine.tree() as Outcome.Ok).value.find(asst.id)?.label)
    }

    /**
     * r4/model-config 热加载（用户 2026-11-05「存好 Key 不应该重启」）：生成中新增一个服务商，Key 只写进 auth.json
     * （models.json 的 apiKey 仍是一个没注入的 `$PIGO_KEY_*` 引用），`/pigo:models-refresh` 后 `set_model` 立即可用，
     * 请求带的就是新 Key；正在生成的回复不被打断，进程与会话文件不变。
     */
    @Test
    fun hotReloadNewKeyWithoutRestartWhileGenerating() = runBlocking {
        startReady()
        turn("先来一轮 #text")
        val file = engine.currentSessionFile
        assertTrue("新服务商未配置前切不过去", engine.setModel("hot", "hot-model") is Outcome.Err)

        assertTrue(engine.send("慢一点 #slow") is Outcome.Ok)
        until { engine.chat.value.running }
        val models = File(sb.agentDir, "models.json")
        val root = kotlinx.serialization.json.Json.parseToJsonElement(models.readText()) as kotlinx.serialization.json.JsonObject
        val providers = root["providers"] as kotlinx.serialization.json.JsonObject
        val hot = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"baseUrl":"${sb.mock.baseUrl}","api":"openai-completions","apiKey":"${'$'}PIGO_KEY_HOT","models":[{"id":"hot-model"}]}""",
        )
        models.writeText(kotlinx.serialization.json.JsonObject(root + ("providers" to kotlinx.serialization.json.JsonObject(providers + ("hot" to hot)))).toString())
        File(sb.agentDir, "auth.json").writeText("""{"hot":{"type":"api_key","key":"sk-hot-0123456789abcdef","pigoManaged":true}}""")

        val t0 = System.currentTimeMillis()
        assertEquals(Outcome.Ok(Unit), engine.refreshModels())
        assertTrue("立即执行（${System.currentTimeMillis() - t0}ms）", System.currentTimeMillis() - t0 < 5_000)
        assertTrue("没有打断生成", engine.chat.value.running)
        until(60_000) { !engine.chat.value.running }
        val last = (engine.lastAssistantText() as Outcome.Ok).value.orEmpty()
        assertTrue("回复完整：$last", last.contains("片段14"))

        val switched = engine.setModel("hot", "hot-model")
        assertTrue("热加载后可切换：$switched", switched is Outcome.Ok)
        sb.mock.authorizations.clear()
        turn("用新 Key #text")
        assertEquals("请求带的是 auth.json 里的新 Key", "Bearer sk-hot-0123456789abcdef", sb.mock.authorizations.lastOrNull())
        assertEquals("没有重启：会话文件不变", file, engine.currentSessionFile)
        assertEquals(BridgeState.READY, engine.bridgeStatus.value.state)
    }
}
