package io.github.zr987459.pigo.contract

import io.github.zr987459.pigo.contract.fake.FakeChatReducer
import io.github.zr987459.pigo.contract.fake.FakeEngineClient
import io.github.zr987459.pigo.contract.fake.FakeFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeEngineReplayTest {

    /** backgroundScope 的协程不会被 advanceUntilIdle 等待，这里显式推进虚拟时间。 */
    private fun TestScope.drain() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun ChatState.text(item: ChatItem): String =
        (item as ChatItem.Assistant).blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    @Test
    fun fixtureReplaysStreamingTextTwoToolCardsAndOneQueuedMessage() {
        val reducer = FakeChatReducer()
        var state = ChatState()
        val textLengths = mutableListOf<Int>()
        var sawQueued = false
        var runningMidway = false

        for (e in FakeFixtures.demoTurn("列出当前目录并读 README")) {
            state = reducer.reduce(state, e)
            if (state.queue.followUp == listOf(FakeFixtures.QUEUED_TEXT)) sawQueued = true
            if (state.running && state.items.any { it is ChatItem.Tool }) runningMidway = true
            state.items.lastOrNull { it is ChatItem.Assistant }?.let { textLengths += state.text(it).length }
        }

        // 流式：同一条消息的文本长度逐步增长（至少三次不同的长度）
        assertTrue("文本没有逐步增长：$textLengths", textLengths.distinct().size >= 3)

        // 两张工具卡，都成功
        val tools = state.items.filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf("bash", "read"), tools.map { it.toolName })
        assertTrue(tools.all { it.status == ToolStatus.OK })
        assertEquals(listOf(120L, 35L), tools.map { it.durationMs })

        // 一次排队：中途出现过 followUp，结束时队列已清空，排队消息作为 user 消息出现且只出现一次
        assertTrue(sawQueued)
        assertTrue(state.queue.isEmpty)
        val users = state.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(listOf("列出当前目录并读 README", FakeFixtures.QUEUED_TEXT), users)

        // 只有 agent_settled 才结束 running
        assertTrue(runningMidway)
        assertFalse(state.running)
        val assistants = state.items.filterIsInstance<ChatItem.Assistant>()
        assertEquals(3, assistants.size)
        assertTrue(assistants.none { it.streaming })
        assertEquals("目录里有 README.md 和 src。README 说明这是一个示例项目。", state.text(assistants[1]))
    }

    @Test
    fun agentEndDoesNotStopRunningButSettledDoes() {
        val reducer = FakeChatReducer()
        val events = FakeFixtures.demoTurn()
        var state = ChatState()
        for (e in events.dropLast(1)) state = reducer.reduce(state, e)
        assertTrue("agent_end 之后仍应在运行", state.running)
        state = reducer.reduce(state, events.last())
        assertFalse(state.running)
    }

    @Test
    fun fakeEngineSendReplaysFixtureEndToEnd() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 10, clock = { 0L })
        val r = engine.send("列出当前目录并读 README")
        assertEquals(Outcome.Ok(Disposition.STARTED), r)
        // 乐观气泡立即出现
        assertEquals(Delivery.SENDING, (engine.chat.value.items.single() as ChatItem.User).delivery)

        drain()
        val s = engine.chat.value
        assertFalse(s.running)
        assertEquals(2, s.items.count { it is ChatItem.Tool })
        assertEquals(2, s.items.count { it is ChatItem.User })
        assertTrue(s.items.filterIsInstance<ChatItem.User>().all { it.delivery == Delivery.SENT })
        assertTrue(engine.status.value is EngineStatus.Idle)
    }

    @Test
    fun sendWhileRunningQueuesAndIsDeliveredExactlyOnce() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 10, clock = { 0L })
        engine.send("第一条")
        advanceTimeBy(50)
        runCurrent()
        assertTrue(engine.chat.value.running)

        assertEquals(Outcome.Ok(Disposition.QUEUED), engine.send("排队的", SendMode.FOLLOW_UP))
        assertEquals(listOf("排队的"), engine.chat.value.queue.followUp)

        drain()
        val users = engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(1, users.count { it == "排队的" })
        assertTrue(engine.chat.value.queue.isEmpty)
        assertFalse(engine.chat.value.running)
    }

    @Test
    fun stopReturnsWithdrawnQueueAndEndsTurn() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 10, clock = { 0L })
        engine.send("第一条")
        advanceTimeBy(50)
        runCurrent()
        engine.send("排队1")
        engine.send("插话1", SendMode.STEER)

        val withdrawn = (engine.stop() as Outcome.Ok).value
        assertEquals(listOf("插话1"), withdrawn.steering)
        assertEquals(listOf("排队1"), withdrawn.followUp)

        drain()
        val s = engine.chat.value
        assertFalse(s.running)
        assertTrue(s.queue.isEmpty)
        assertTrue(s.items.any { it is ChatItem.Notice && it.kind == NoticeKind.STOPPED })
        // 撤回的消息不会再被发出
        val users = s.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertFalse(users.contains("排队1"))
        assertFalse(users.contains("插话1"))
    }

    @Test
    fun secondSendWithoutAdvancingTimeIsQueuedNotASecondReplay() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 10, clock = { 0L })
        assertEquals(Outcome.Ok(Disposition.STARTED), engine.send("第一条"))
        // 回放协程还没来得及应用 agent_start（running 仍为 false），第二条也必须排队
        assertFalse(engine.chat.value.running)
        assertEquals(Outcome.Ok(Disposition.QUEUED), engine.send("第二条"))
        assertEquals(Outcome.Ok(Disposition.QUEUED), engine.send("第三条"))

        drain()
        val users = engine.chat.value.items.filterIsInstance<ChatItem.User>().map { it.text }
        assertEquals(1, users.count { it == "第一条" })
        assertEquals(1, users.count { it == "第二条" })
        assertEquals(1, users.count { it == "第三条" })
        // 排队消息在第一轮（含夹具自带的排队消息）之后按顺序送达
        assertTrue(users.indexOf("第二条") < users.indexOf("第三条"))
        assertTrue(users.indexOf("第一条") < users.indexOf("第二条"))
        assertEquals(2, engine.chat.value.items.count { it is ChatItem.Tool })
        assertFalse(engine.chat.value.running)
    }

    @Test
    fun noOutputAfterStop() = runTest {
        val engine = FakeEngineClient(backgroundScope, stepDelayMs = 10, clock = { 0L })
        engine.send("第一条")
        engine.send("排队的")
        advanceTimeBy(50)
        runCurrent()

        engine.stop()
        val afterStop = engine.chat.value
        assertFalse(afterStop.running)
        assertTrue(afterStop.queue.isEmpty)

        drain()
        assertEquals("stop 之后不应再有任何输出", afterStop, engine.chat.value)
        assertTrue(engine.chat.value.items.filterIsInstance<ChatItem.User>().none { it.text == "排队的" })

        // 停止后可以重新开始一轮
        assertEquals(Outcome.Ok(Disposition.STARTED), engine.send("新的一条"))
        drain()
        assertTrue(engine.chat.value.items.filterIsInstance<ChatItem.User>().any { it.text == "新的一条" })
        assertFalse(engine.chat.value.running)
    }
}
