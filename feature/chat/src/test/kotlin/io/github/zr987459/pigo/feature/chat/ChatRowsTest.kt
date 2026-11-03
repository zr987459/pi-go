package io.github.zr987459.pigo.feature.chat

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.fake.FakeChatReducer
import io.github.zr987459.pigo.contract.fake.FakeFixtures
import io.github.zr987459.pigo.feature.chat.logic.ChatRow
import io.github.zr987459.pigo.feature.chat.logic.ChatRows
import io.github.zr987459.pigo.feature.chat.logic.ErrorKind
import io.github.zr987459.pigo.feature.chat.logic.LocalSend
import io.github.zr987459.pigo.feature.chat.logic.ModelMenu
import io.github.zr987459.pigo.feature.chat.logic.ToolPhase
import io.github.zr987459.pigo.feature.chat.logic.ToolText
import io.github.zr987459.pigo.feature.chat.logic.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {

    private fun replayDemo(): ChatState {
        val reducer = FakeChatReducer()
        var s = ChatState()
        for (e in FakeFixtures.demoTurn("列出当前目录并读 README")) s = reducer.reduce(s, e)
        return s
    }

    @Test
    fun liveTurnIsNotFolded() {
        val reducer = FakeChatReducer()
        var s = ChatState()
        val events = FakeFixtures.demoTurn()
        // 回放到第二个工具卡开始之后（仍在运行）
        val cut = events.indexOfFirst { it is io.github.zr987459.pigo.contract.rpc.EngineEvent.ToolExecutionStart && it.toolName == "read" }
        for (e in events.take(cut + 1)) s = reducer.reduce(s, e)
        assertTrue(s.running)
        val rows = ChatRows.build(s.items, s.running)
        assertTrue(rows.none { it is ChatRow.TurnSummary })
        val tools = rows.filterIsInstance<ChatRow.Tool>()
        assertEquals(listOf("bash", "read"), tools.map { it.tool.toolName })
        assertEquals(ToolStatus.RUNNING, tools.last().tool.status)
    }

    @Test
    fun finishedTurnFoldsToolsIntoOneSummaryAndKeepsFinalAnswer() {
        val s = replayDemo()
        assertFalse(s.running)
        val rows = ChatRows.build(s.items, s.running)
        val kinds = rows.map { it::class.simpleName }
        assertEquals(listOf("User", "TurnSummary", "Assistant", "User", "Assistant"), kinds)

        val summary = rows[1] as ChatRow.TurnSummary
        assertEquals(2, summary.toolCount)
        assertEquals(0, summary.failedTools)
        assertEquals("2 个工具", summary.label)
        assertFalse(summary.expanded)

        val final = rows[2] as ChatRow.Assistant
        assertEquals("目录里有 README.md 和 src。README 说明这是一个示例项目。", final.item.text())
        assertFalse(final.showThinking)

        // 排队消息作为下一回合出现且只出现一次
        val users = rows.filterIsInstance<ChatRow.User>().map { it.text }
        assertEquals(listOf("列出当前目录并读 README", FakeFixtures.QUEUED_TEXT), users)
    }

    @Test
    fun expandingSummaryShowsProcessRows() {
        val s = replayDemo()
        val key = (ChatRows.build(s.items, false)[1] as ChatRow.TurnSummary).key
        val rows = ChatRows.build(s.items, false, expanded = setOf(key))
        val kinds = rows.map { it::class.simpleName }
        // 摘要 → 过程（中间说明 + 两张工具卡）→ 最终回答
        assertEquals(listOf("User", "TurnSummary", "Assistant", "Tool", "Tool", "Assistant", "User", "Assistant"), kinds)
        assertTrue((rows[1] as ChatRow.TurnSummary).expanded)
        // 所有 key 唯一（LazyColumn 要求）
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun summaryCountsThinkingAndFailures() {
        val items = listOf(
            ChatItem.User("u", "读不存在的文件"),
            ChatItem.Assistant(
                "a1",
                blocks = listOf(ContentBlock.Thinking(0, "想一想", durationMs = 12_000), ContentBlock.ToolCall(1, "t1", "read", "{}")),
                streaming = false,
                stopReason = "toolUse",
            ),
            ChatItem.Tool("tool-t1", "t1", "read", status = ToolStatus.ERROR, durationMs = 5),
            ChatItem.Tool("tool-t2", "t2", "bash", status = ToolStatus.OK, durationMs = 5),
            ChatItem.Tool("tool-t3", "t3", "bash", status = ToolStatus.OK, durationMs = 5),
            ChatItem.Assistant("a2", blocks = listOf(ContentBlock.Text(0, "文件不存在。")), streaming = false, stopReason = "stop"),
        )
        val summary = ChatRows.build(items, running = false).filterIsInstance<ChatRow.TurnSummary>().single()
        assertEquals("思考 12 秒 · 3 个工具 · 1 失败", summary.label)
    }

    @Test
    fun errorTurnShowsChineseErrorCardWithRetryOutsideFold() {
        val items = listOf(
            ChatItem.User("u", "你好"),
            ChatItem.Assistant("a", streaming = false, stopReason = "error", errorMessage = "401 Unauthorized: invalid api key"),
        )
        val rows = ChatRows.build(items, running = false)
        val err = rows.filterIsInstance<ChatRow.Error>().single()
        assertEquals(ErrorKind.BAD_KEY, err.info.kind)
        assertEquals("Key 错误", err.info.title)
        assertEquals("你好", err.retryText)
    }

    @Test
    fun noticesAndUnknownRolesStayVisible() {
        val items = listOf(
            ChatItem.User("u", "x"),
            ChatItem.Assistant("a", blocks = listOf(ContentBlock.Text(0, "半句")), streaming = false, stopReason = "aborted"),
            ChatItem.Notice("n", NoticeKind.STOPPED, "已停止"),
            ChatItem.Raw("r", "mystery", kotlinx.serialization.json.JsonObject(emptyMap())),
        )
        val kinds = ChatRows.build(items, running = false).map { it::class.simpleName }
        assertEquals(listOf("User", "Assistant", "Notice", "Raw"), kinds)
    }

    @Test
    fun rebuiltSessionRendersLikeLive() {
        // T4：get_messages 重建出的 ChatState 与实时归约结果结构一致时，界面行完全相同。
        val live = replayDemo()
        val rebuilt = live.copy(items = live.items.map { it }, queue = live.queue)
        assertEquals(ChatRows.build(live.items, false), ChatRows.build(rebuilt.items, false))
    }

    @Test
    fun localBubblesAreAppendedAtTheEnd() {
        val rows = ChatRows.build(
            items = listOf(ChatItem.User("u", "a")),
            running = false,
            local = listOf(LocalSend("s1", "b", Delivery.FAILED, error = "发送超时")),
        )
        val last = rows.last() as ChatRow.User
        assertEquals("b", last.text)
        assertEquals(Delivery.FAILED, last.delivery)
        assertEquals("s1", last.localId)
    }

    private fun failedTurn() = listOf(
        ChatItem.User("u", "读不存在的文件"),
        ChatItem.Assistant(
            "a1",
            blocks = listOf(ContentBlock.ToolCall(0, "t1", "read", "{}"), ContentBlock.ToolCall(1, "t2", "bash", "{}")),
            streaming = false,
            stopReason = "toolUse",
        ),
        ChatItem.Tool("tool-t1", "t1", "read", status = ToolStatus.ERROR, result = kotlinx.serialization.json.JsonPrimitive("ENOENT: no such file"), durationMs = 5),
        ChatItem.Tool("tool-t2", "t2", "bash", status = ToolStatus.OK, durationMs = 5),
        ChatItem.Assistant("a2", blocks = listOf(ContentBlock.Text(0, "文件不存在。")), streaming = false, stopReason = "stop"),
    )

    @Test
    fun failedToolCardStaysVisibleAfterTurnEnds() {
        // T2：回合结束（running=false）折叠后，失败工具卡仍在折叠区外显示，状态为 ERROR（红色、默认展开）。
        val rows = ChatRows.build(failedTurn(), running = false)
        assertEquals(listOf("User", "TurnSummary", "Tool", "Assistant"), rows.map { it::class.simpleName })
        val tool = rows.filterIsInstance<ChatRow.Tool>().single()
        assertEquals("t1", tool.tool.toolCallId)
        assertEquals(ToolPhase.ERROR, tool.phase)
        assertEquals("2 个工具 · 1 失败", rows.filterIsInstance<ChatRow.TurnSummary>().single().label)
    }

    @Test
    fun expandingFailedTurnShowsEachToolOnce() {
        val key = ChatRows.build(failedTurn(), running = false).filterIsInstance<ChatRow.TurnSummary>().single().key
        val rows = ChatRows.build(failedTurn(), running = false, expanded = setOf(key))
        assertEquals(listOf("t1", "t2"), rows.filterIsInstance<ChatRow.Tool>().map { it.tool.toolCallId })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun unfinishedToolsAfterSettleAreNotShownAsRunning() {
        // 模型发出 toolCall 后这一轮被停止：工具从未执行（PENDING）或执行中断（RUNNING），settled 后不能再转圈。
        val items = listOf(
            ChatItem.User("u", "跑一下测试"),
            ChatItem.Assistant(
                "a1",
                blocks = listOf(ContentBlock.ToolCall(0, "t1", "bash", "{}"), ContentBlock.ToolCall(1, "t2", "read", "{}")),
                streaming = false,
                stopReason = "aborted",
            ),
            ChatItem.Tool("tool-t1", "t1", "bash", status = ToolStatus.RUNNING, startedAtMillis = 1),
            ChatItem.Tool("tool-t2", "t2", "read", status = ToolStatus.PENDING),
            ChatItem.Notice("n", NoticeKind.STOPPED, "已停止"),
        )
        val folded = ChatRows.build(items, running = false)
        val summary = folded.filterIsInstance<ChatRow.TurnSummary>().single()
        assertTrue(summary.label, summary.label.contains("2 中断"))
        val expanded = ChatRows.build(items, running = false, expanded = setOf(summary.key))
        val phases = expanded.filterIsInstance<ChatRow.Tool>().associate { it.tool.toolCallId to it.phase }
        assertEquals(mapOf("t1" to ToolPhase.INTERRUPTED, "t2" to ToolPhase.NOT_RUN), phases)
        assertTrue(phases.values.none { it.spinning })

        // 仍在生成中时照常转圈
        val live = ChatRows.build(items, running = true).filterIsInstance<ChatRow.Tool>()
        assertTrue(live.all { it.phase == ToolPhase.RUNNING && it.phase.spinning })
    }

    @Test
    fun toolPhaseMapping() {
        assertEquals(ToolPhase.RUNNING, ToolPhase.of(ToolStatus.PENDING, live = true))
        assertEquals(ToolPhase.NOT_RUN, ToolPhase.of(ToolStatus.PENDING, live = false))
        assertEquals(ToolPhase.INTERRUPTED, ToolPhase.of(ToolStatus.RUNNING, live = false))
        assertEquals(ToolPhase.OK, ToolPhase.of(ToolStatus.OK, live = false))
        assertEquals(ToolPhase.ERROR, ToolPhase.of(ToolStatus.ERROR, live = true))
        assertEquals("未执行", ToolText.phaseLabel(ToolPhase.NOT_RUN))
        assertEquals("已中断", ToolText.phaseLabel(ToolPhase.INTERRUPTED))
        assertEquals(null, ToolText.phaseLabel(ToolPhase.OK))
    }

    @Test
    fun modelMenuDistinguishesFailureFromEmpty() {
        assertEquals("正在读取…", ModelMenu.placeholder(null, null))
        assertEquals("没有可用的模型，请先到「设置」配置", ModelMenu.placeholder(emptyList<Any>(), null))
        assertEquals("读取模型列表失败：超时（点「模型」重试）", ModelMenu.placeholder(null, "超时"))
        assertEquals(null, ModelMenu.placeholder(listOf("m"), null))
    }
}
