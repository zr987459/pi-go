package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.DraftStore
import io.github.zr987459.pigo.contract.EngineClient
import io.github.zr987459.pigo.contract.EngineExtras
import io.github.zr987459.pigo.contract.EngineStatus
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ForkResult
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.SendMode
import io.github.zr987459.pigo.contract.SessionStats
import io.github.zr987459.pigo.contract.fake.FakeDraftStore
import io.github.zr987459.pigo.contract.fake.FakeEngineExtras
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #10 审查第 1 轮：带图气泡只显示一次（Important 1）、A → null → B 分叉回填（Important 4）、新会话草稿迁移（Nit）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerReviewTest {

    private class StubEngine(file: String?) : EngineClient {
        val chatFlow = MutableStateFlow(ChatState(sessionFile = file))
        override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Idle(PiState()))
        override val chat: StateFlow<ChatState> = chatFlow
        override val uiRequests: SharedFlow<ExtUiRequest> = MutableSharedFlow()
        override suspend fun send(text: String, mode: SendMode): Outcome<Disposition> = Outcome.Ok(Disposition.STARTED)
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
        fun switchTo(file: String?) = chatFlow.update { it.copy(sessionFile = file, items = emptyList()) }
    }

    /** fork 时可选地按给定路径依次切会话（模拟 PiEngine 先 cleared → null 再出现新文件）。 */
    private class ForkExtras(val base: FakeEngineExtras, val engine: StubEngine) : EngineExtras by base {
        var forkPath: List<String?> = emptyList()
        override suspend fun fork(entryId: String): Outcome<ForkResult> {
            val r = base.fork(entryId)
            forkPath.forEach { engine.switchTo(it) }
            return r
        }
    }

    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun TestScope.make(file: String?, drafts: DraftStore? = FakeDraftStore(), forkWaitMs: Long = 15_000): Triple<ChatController, StubEngine, ForkExtras> {
        val engine = StubEngine(file)
        val extras = ForkExtras(FakeEngineExtras(), engine)
        val c = ChatController(engine, backgroundScope, ChatConfig(draftSaveDelayMs = 100, forkWaitMs = forkWaitMs), clock = { 0L }, extras = extras, drafts = drafts)
        return Triple(c, engine, extras)
    }

    private val png = ImageInput("image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk", 1, 1, "a.png")

    // ---------------- Important 1 ----------------

    @Test fun imageSendShowsOneBubbleOnceEngineEchoes() = runTest {
        val (c, engine, _) = make("/s/a.jsonl")
        settle()
        c.addImages(listOf(png))
        c.setDraft("看看这张图")
        c.submit()
        settle()
        // 真实引擎回放 content=[text,image] → 「看看这张图[图片]」，整轮仍在 running
        engine.chatFlow.update { it.copy(running = true, items = listOf(ChatItem.User("u1", "看看这张图[图片]"))) }
        settle()
        val users = c.state.value.rows.filterIsInstance<ChatRow.User>()
        assertEquals("同一条消息只显示一次：$users", 1, users.size)
        assertTrue(users.single().localId == null)
    }

    @Test fun imageOnlySendShowsOneBubble() = runTest {
        val (c, engine, _) = make("/s/a.jsonl")
        settle()
        c.addImages(listOf(png))
        c.submit()
        settle()
        engine.chatFlow.update { it.copy(running = true, items = listOf(ChatItem.User("u1", "[图片]"))) }
        settle()
        assertEquals(1, c.state.value.rows.filterIsInstance<ChatRow.User>().size)
    }

    // ---------------- Important 4 ----------------

    @Test fun forkThroughNullAppliesTextToNewSession() = runTest {
        val drafts = FakeDraftStore()
        val (c, engine, extras) = make("/s/a.jsonl", drafts)
        settle()
        // fork 返回前引擎还没切；之后 A → null → B，且每一步都被合成协程看到
        c.forkFrom("e5")
        settle()
        assertEquals("", c.state.value.composer.draft)
        engine.switchTo(null)
        settle()
        assertEquals("还在 null 时不放回", "", c.state.value.composer.draft)
        engine.switchTo("/s/fork.jsonl")
        settle()
        assertEquals("换个问法", c.state.value.composer.draft)
        // 之后再切到无关会话，不会再塞一次
        engine.switchTo("/s/other.jsonl")
        settle()
        assertEquals("", c.state.value.composer.draft)
        assertTrue(c.state.value.composer.withdrawn.isEmpty())
        assertEquals("换个问法", drafts.get("/s/fork.jsonl"))
        assertTrue(extras.base.calls.contains("fork:e5"))
    }

    @Test fun forkSwitchingInsideCallThroughNull() = runTest {
        val (c, _, extras) = make("/s/a.jsonl")
        extras.forkPath = listOf(null, "/s/fork.jsonl")
        settle()
        c.forkFrom("e3")
        settle()
        assertEquals("第二条问题", c.state.value.composer.draft)
    }

    @Test fun forkFromUnsavedNewSession() = runTest {
        val (c, engine, _) = make(null)
        settle()
        c.forkFrom("e5")
        engine.switchTo("/s/fork.jsonl") // null → B：首次得知文件的分支
        settle()
        assertEquals("换个问法", c.state.value.composer.draft)
    }

    @Test fun pendingForkExpiresAndNeverLandsInUnrelatedSession() = runTest {
        val (c, engine, _) = make("/s/a.jsonl", forkWaitMs = 2_000)
        settle()
        val msgs = mutableListOf<String>()
        backgroundScope.launch { c.messages.collect { msgs += it } }
        c.forkFrom("e5")
        settle(); settle(); settle()
        assertTrue(msgs.any { it.contains("没有切到新会话") })
        engine.switchTo("/s/unrelated.jsonl")
        settle()
        assertEquals("超时后不再放回", "", c.state.value.composer.draft)
    }

    // ---------------- Nit：新会话草稿迁移 ----------------

    @Test fun newSessionDraftMigratesWhenFileAppears() = runTest {
        val drafts = FakeDraftStore()
        val (c, engine, _) = make(null, drafts)
        settle()
        c.setDraft("还没落盘时写的")
        settle()
        assertEquals("还没落盘时写的", drafts.get(DraftStore.newSessionKey("")))
        engine.switchTo("/s/new.jsonl")
        settle()
        assertNull("旧 key 删掉", drafts.get(DraftStore.newSessionKey("")))
        assertEquals("还没落盘时写的", drafts.get("/s/new.jsonl"))
        assertEquals("还没落盘时写的", c.state.value.composer.draft)
    }

    @Test fun emptyNewSessionDraftJustRemoved() = runTest {
        val drafts = FakeDraftStore()
        drafts.put(DraftStore.newSessionKey(""), "上次残留")
        val (_, engine, _) = make(null, drafts)
        settle()
        engine.switchTo("/s/new.jsonl")
        settle()
        assertNull(drafts.get(DraftStore.newSessionKey("")))
        assertNull(drafts.get("/s/new.jsonl"))
    }
}
