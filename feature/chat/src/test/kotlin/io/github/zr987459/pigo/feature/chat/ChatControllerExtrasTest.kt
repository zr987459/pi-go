package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.CommandSource
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.InputRoute
import io.github.zr987459.pigo.feature.chat.logic.InputRouting
import io.github.zr987459.pigo.feature.chat.logic.PrimaryAction
import io.github.zr987459.pigo.feature.chat.logic.SlashCompletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W4：ChatController 的 `/` 候选、`!` 命令、带图发送、按会话草稿、分叉回填（feature-gap §3.2 W4 验收）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerExtrasTest {

    /** 可手动切会话的最小引擎。 */
    private class StubEngine(file: String? = "/s/a.jsonl") : EngineClient {
        val chatFlow = MutableStateFlow(ChatState(sessionFile = file))
        val sends = mutableListOf<String>()
        override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Idle(PiState()))
        override val chat: StateFlow<ChatState> = chatFlow
        override val uiRequests: SharedFlow<ExtUiRequest> = MutableSharedFlow()
        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> { sends += text; return Outcome.Ok(Disposition.STARTED) }
        override suspend fun stop(): Outcome<QueueSnapshot> = Outcome.Ok(QueueSnapshot.EMPTY)
        override suspend fun newSession(cwd: String?): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun openSession(guestPath: String, cwd: String): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun rename(name: String): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun models(): Outcome<List<ModelInfo>> = Outcome.Ok(emptyList())
        override suspend fun setModel(provider: String, modelId: String): Outcome<ModelInfo> = Outcome.Ok(ModelInfo(provider, modelId))
        override suspend fun thinkingLevels(): Outcome<List<String>> = Outcome.Ok(emptyList())
        override suspend fun setThinking(level: String): Outcome<Unit> = Outcome.Ok(Unit)
        override suspend fun stats(): Outcome<SessionStats> = Outcome.Ok(SessionStats(null, null, null))
        override suspend fun answerUi(id: String, value: String?, confirmed: Boolean?, cancelled: Boolean) = Unit
        override suspend fun restart(reason: String): Outcome<Unit> = Outcome.Ok(Unit)
        fun switchTo(file: String?) = chatFlow.update { it.copy(sessionFile = file) }
    }

    /** 带图发送第一次失败、之后成功；fork 成功后切到新会话。 */
    private class ScriptedExtras(val base: FakeEngineExtras, val engine: StubEngine) : EngineExtras by base {
        var failSends = 0
        val imageSends = mutableListOf<List<ImageInput>>()
        var forkSwitchesTo: String? = null
        override suspend fun send(text: String, images: List<ImageInput>, mode: SendMode): Outcome<Disposition> {
            imageSends += images
            if (failSends > 0) { failSends--; return Outcome.Err(ErrCode.NETWORK, "断网") }
            return base.send(text, images, mode)
        }
        override suspend fun fork(entryId: String): Outcome<ForkResult> {
            val r = base.fork(entryId)
            forkSwitchesTo?.let { engine.switchTo(it) }
            return r
        }
    }

    private fun TestScope.setup(drafts: FakeDraftStore? = FakeDraftStore()): Triple<ChatController, StubEngine, ScriptedExtras> {
        val engine = StubEngine()
        val extras = ScriptedExtras(FakeEngineExtras(), engine)
        val c = ChatController(engine, backgroundScope, ChatConfig(draftSaveDelayMs = 100), clock = { 0L }, extras = extras, drafts = drafts)
        return Triple(c, engine, extras)
    }

    /** backgroundScope 里的协程不算进 advanceUntilIdle 的「空闲」判断，所以按虚拟时间推进。 */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    private val png = ImageInput("image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk", 1, 1, "a.png")

    @Test
    fun slashDraftShowsPrefixFilteredCandidates() = runTest {
        val (c, _, extras) = setup()
        c.setDraft("/")
        settle()
        val all = c.state.value.slash
        assertNotNull(all)
        assertEquals(listOf(CommandSource.EXTENSION, CommandSource.SKILL, CommandSource.PROMPT), all!!.groups.map { it.source })
        assertEquals(3, all.all.size)

        c.setDraft("/fi")
        settle()
        assertEquals(listOf("fix-tests"), c.state.value.slash!!.all.map { it.name })

        c.setDraft("/pd") // 技能名分段 skill:pdf 的前缀
        settle()
        assertEquals(listOf("skill:pdf"), c.state.value.slash!!.all.map { it.name })

        // 只读一次命令列表（同一会话内缓存）。
        assertEquals(1, extras.base.calls.count { it.startsWith("commands:") })

        c.pickCommand(c.state.value.slash!!.all.single())
        settle()
        assertEquals("/skill:pdf ", c.state.value.composer.draft)
        assertNull("补全后（有空格）不再弹候选", c.state.value.slash)

        c.setDraft("普通文字")
        settle()
        assertNull(c.state.value.slash)
    }

    @Test
    fun commandsCacheInvalidatesOnSessionSwitch() = runTest {
        val (c, engine, extras) = setup(drafts = null)
        c.setDraft("/")
        settle()
        engine.switchTo("/s/b.jsonl")
        settle()
        c.setDraft("/")
        settle()
        assertEquals(listOf("commands:false", "commands:true"), extras.base.calls.filter { it.startsWith("commands:") })
    }

    @Test
    fun bangRoutesToBashNotPrompt() = runTest {
        val (c, engine, extras) = setup()
        c.setDraft("!ls -la")
        c.submit()
        settle()
        assertEquals(listOf("bash:false:ls -la"), extras.base.calls.filter { it.startsWith("bash:") })
        assertTrue(engine.sends.isEmpty())
        assertEquals("", c.state.value.composer.draft)
        assertFalse(c.state.value.bashRunning)

        c.setDraft("!!git status")
        c.submit()
        settle()
        assertEquals("bash:true:git status", extras.base.calls.last { it.startsWith("bash:") })

        c.setDraft("!")
        c.submit()
        settle()
        assertEquals(listOf("!"), engine.sends)
    }

    @Test
    fun inputRouting() {
        assertEquals(InputRoute.Bash("ls", false), InputRouting.route("!ls"))
        assertEquals(InputRoute.Bash("ls", true), InputRouting.route("!!ls"))
        assertEquals(InputRoute.Message("!!"), InputRouting.route("!!"))
        assertEquals(InputRoute.Message("hi"), InputRouting.route("hi"))
        assertNull(SlashCompletion.queryOf("/fix args"))
        assertNull(SlashCompletion.queryOf("//x"))
        assertEquals("", SlashCompletion.queryOf("/"))
    }

    @Test
    fun imagesGoThroughExtrasAndRetryKeepsThem() = runTest {
        val (c, engine, extras) = setup()
        extras.failSends = 1
        c.addImages(listOf(png))
        settle()
        assertEquals(PrimaryAction.SEND, c.state.value.primary)
        c.setDraft("看看这张图")
        c.submit()
        settle()
        assertTrue("带图不走 EngineClient.send", engine.sends.isEmpty())
        assertEquals(listOf(listOf(png)), extras.imageSends)
        assertTrue(c.state.value.attachments.isEmpty())
        val failed = c.state.value.rows.filterIsInstance<ChatRow.User>().single { it.delivery == Delivery.FAILED }
        assertEquals(listOf(png), failed.images)

        c.retry(failed.localId!!)
        settle()
        assertEquals(2, extras.imageSends.size)
        assertEquals(listOf(png), extras.imageSends[1])
        assertEquals(listOf("send:1:看看这张图"), extras.base.calls.filter { it.startsWith("send:") })
    }

    @Test
    fun editFailedImageSendReturnsImagesToComposer() = runTest {
        val (c, _, extras) = setup()
        extras.failSends = 1
        c.addImages(listOf(png))
        c.setDraft("图")
        c.submit()
        settle()
        val failed = c.state.value.rows.filterIsInstance<ChatRow.User>().single()
        c.editFailed(failed.localId!!)
        settle()
        assertEquals("图", c.state.value.composer.draft)
        assertEquals(listOf(png), c.state.value.attachments)
    }

    @Test
    fun imagesRejectedForTextOnlyModel() = runTest {
        val (c, engine, extras) = setup()
        engine.chatFlow.update { it.copy(model = ModelInfo("deepseek", "deepseek-chat", raw = JsonObject(mapOf("input" to JsonArray(listOf(JsonPrimitive("text"))))))) }
        c.addImages(listOf(png))
        c.setDraft("x")
        c.submit()
        settle()
        assertTrue(extras.imageSends.isEmpty())
        assertEquals("x", c.state.value.composer.draft)
        assertEquals(1, c.state.value.attachments.size)
    }

    @Test
    fun draftsSwapOutAndInOnSessionSwitch() = runTest {
        val drafts = FakeDraftStore()
        drafts.put("/s/b.jsonl", "b 的草稿")
        val (c, engine, _) = setup(drafts)
        settle()
        c.setDraft("a 的草稿")
        engine.switchTo("/s/b.jsonl")
        settle()
        assertEquals("b 的草稿", c.state.value.composer.draft)
        assertEquals("a 的草稿", drafts.get("/s/a.jsonl"))

        engine.switchTo("/s/a.jsonl")
        settle()
        assertEquals("a 的草稿", c.state.value.composer.draft)
        assertEquals("b 的草稿", drafts.get("/s/b.jsonl"))
    }

    @Test
    fun draftPersistsAfterTypingAndRestoresOnStart() = runTest {
        val drafts = FakeDraftStore()
        val (c, _, _) = setup(drafts)
        settle()
        c.setDraft("还没写完")
        settle()
        assertEquals("还没写完", drafts.get("/s/a.jsonl"))

        // 新的控制器（App 重启）从草稿存储恢复。
        val engine2 = StubEngine("/s/a.jsonl")
        val c2 = ChatController(engine2, backgroundScope, ChatConfig(draftSaveDelayMs = 100), clock = { 0L }, extras = FakeEngineExtras(), drafts = drafts)
        settle()
        assertEquals("还没写完", c2.state.value.composer.draft)

        c2.submit()
        settle()
        assertNull("发出后草稿清掉", drafts.get("/s/a.jsonl"))
    }

    @Test
    fun forkWritesReturnedTextIntoNewSessionDraft() = runTest {
        val (c, _, extras) = setup()
        extras.forkSwitchesTo = "/s/fork.jsonl"
        settle()
        val r = c.forkFrom("e3")
        settle()
        assertTrue(r is Outcome.Ok)
        assertEquals("fork:e3", extras.base.calls.last())
        assertEquals("第二条问题", c.state.value.composer.draft)
    }

    @Test
    fun forkTextAppliedWhenSwitchArrivesLater() = runTest {
        val (c, engine, _) = setup()
        settle()
        c.forkFrom("e5") // 引擎还没报告新会话文件
        assertEquals("", c.state.value.composer.draft)
        engine.switchTo("/s/fork2.jsonl")
        settle()
        assertEquals("换个问法", c.state.value.composer.draft)
    }

    @Test
    fun forkErrorKeepsDraft() = runTest {
        val (c, _, _) = setup()
        settle()
        c.setDraft("原草稿")
        val r = c.forkFrom("nope")
        settle()
        assertTrue(r is Outcome.Err)
        assertEquals("原草稿", c.state.value.composer.draft)
    }

    @Test
    fun withoutExtrasBehavesAsBefore() = runTest {
        val engine = StubEngine()
        val c = ChatController(engine, backgroundScope, clock = { 0L })
        c.setDraft("/fix")
        settle()
        assertNull(c.state.value.slash)
        c.setDraft("!ls")
        c.submit()
        settle()
        assertEquals(listOf("!ls"), engine.sends)
    }
}
