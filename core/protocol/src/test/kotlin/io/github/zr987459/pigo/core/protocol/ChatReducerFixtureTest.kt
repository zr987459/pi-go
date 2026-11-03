package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reducer 夹具快照（DESIGN.md §6 engine 行、N3）。recorded 目录下的夹具由桌面真 pi 1.5.0 录制，
 * handwritten 目录下的覆盖真 pi 难以稳定触发的边角（未知事件/role、乱序等）。来源见 docs/protocol/fixtures.md。
 */
class ChatReducerFixtureTest {

    private fun snapshot(name: String): ChatState {
        val (state, trace) = Fixtures.replay(Fixtures.events(Fixtures.load(name)))
        Fixtures.assertSnapshot(name.replace('/', '_'), Fixtures.render(state, trace))
        return state
    }

    private inline fun <reified T : ChatItem> ChatState.all() = items.filterIsInstance<T>()

    private fun ChatState.texts() = all<ChatItem.Assistant>().flatMap { a -> a.blocks.filterIsInstance<ContentBlock.Text>().map { it.text } }

    @Test
    fun textStream() {
        val events = Fixtures.events(Fixtures.load("recorded/text-stream"))
        // S1：逐字增长——每条 text_delta 之后文本都是之前的延长
        val r = ChatReducer()
        var s = ChatState()
        var last = ""
        var deltas = 0
        for (e in events) {
            s = r.reduce(s, e, 0)
            if (e is EngineEvent.MessageUpdate && e.event is AssistantMessageEvent.TextDelta) {
                deltas++
                val now = (s.all<ChatItem.Assistant>().last().blocks.first { it is ContentBlock.Text } as ContentBlock.Text).text
                assertTrue(now.startsWith(last) && now.length > last.length)
                last = now
            }
        }
        assertTrue("应有多条 delta", deltas >= 2)
        val st = snapshot("recorded/text-stream")
        assertFalse(st.running)
        val a = st.all<ChatItem.Assistant>().single()
        assertFalse(a.streaming)
        assertEquals("stop", a.stopReason)
        assertTrue(st.texts().single().contains('\u2028'))
    }

    @Test
    fun thinking() {
        val st = snapshot("recorded/thinking")
        val a = st.all<ChatItem.Assistant>().single()
        val th = a.blocks.filterIsInstance<ContentBlock.Thinking>().single()
        assertTrue(th.text.isNotEmpty())
        assertTrue("思考块应有时长", (th.durationMs ?: -1) >= 0)
        assertTrue(a.blocks.any { it is ContentBlock.Text })
    }

    @Test
    fun toolSuccessAndRebuildMatchesLive() {
        val lines = Fixtures.load("recorded/tool-success")
        val st = snapshot("recorded/tool-success")
        val tool = st.all<ChatItem.Tool>().single()
        assertEquals(ToolStatus.OK, tool.status)
        assertEquals("bash", tool.toolName)
        assertTrue(tool.durationMs != null)
        // T4：get_messages 重建后，卡片与实时显示一致（忽略 key 与实测耗时）
        val messages = Responses.messages(Fixtures.responses(lines).single { it.command == "get_messages" }.data)
        val rebuilt = ChatReducer().fromMessages(messages)
        Fixtures.assertSnapshot("recorded_tool-success.rebuilt", Fixtures.render(rebuilt))
        assertEquals(
            st.items.map { Fixtures.renderItem(it, withKeys = false) },
            rebuilt.items.map { Fixtures.renderItem(it, withKeys = false) },
        )
    }

    @Test
    fun toolFailure() {
        val st = snapshot("recorded/tool-failure")
        val tool = st.all<ChatItem.Tool>().single()
        assertEquals(ToolStatus.ERROR, tool.status)
        assertEquals("read", tool.toolName)
    }

    @Test
    fun followUpIsQueuedThenDeliveredOnce() {
        val (st, trace) = Fixtures.replay(Fixtures.events(Fixtures.load("recorded/follow-up")))
        snapshot("recorded/follow-up")
        assertTrue(trace.toString(), trace.any { it.contains("followUp[\"#text 做完再说这个\"]") })
        assertTrue(st.queue.isEmpty)
        // 排队消息只作为 user 消息出现一次，且在第一条回答之后
        assertEquals(listOf("#slow 慢慢说", "#text 做完再说这个"), st.all<ChatItem.User>().map { it.text })
        assertEquals(2, st.all<ChatItem.Assistant>().size)
        // running 只在最终 agent_settled 才变 false（中间的 agent_end 不结束本轮）
        assertEquals(trace.toString(), 1, trace.count { it.contains("running=false") })
    }

    @Test
    fun steerIsDeliveredAfterCurrentTool() {
        val (st, trace) = Fixtures.replay(Fixtures.events(Fixtures.load("recorded/steer")))
        snapshot("recorded/steer")
        assertTrue(trace.toString(), trace.any { it.contains("steer[\"#text 先别跑了\"]") })
        val order = st.items.map { it.javaClass.simpleName + ":" + ((it as? ChatItem.User)?.text ?: (it as? ChatItem.Tool)?.toolName ?: "") }
        val toolIdx = order.indexOf("Tool:bash")
        val steerIdx = order.indexOf("User:#text 先别跑了")
        assertTrue("插话应在当前工具之后送达：$order", toolIdx in 0 until steerIdx)
        assertTrue(st.queue.isEmpty)
    }

    @Test
    fun clearQueueThenAbort() {
        val lines = Fixtures.load("recorded/clear-queue-abort")
        val st = snapshot("recorded/clear-queue-abort")
        val cleared = Fixtures.responses(lines).single { it.command == "clear_queue" }
        assertEquals(listOf("#text 不该被发出"), Responses.queue(cleared.data).followUp)
        assertFalse(st.running)
        assertTrue(st.queue.isEmpty)
        // 撤回的消息没有被发出
        assertFalse(st.all<ChatItem.User>().any { it.text.contains("不该被发出") })
        assertTrue(st.all<ChatItem.Notice>().any { it.kind == NoticeKind.STOPPED })
        assertEquals("aborted", st.all<ChatItem.Assistant>().last().stopReason)
    }

    @Test
    fun autoRetry() {
        val (st, trace) = Fixtures.replay(Fixtures.events(Fixtures.load("recorded/retry")))
        snapshot("recorded/retry")
        assertTrue(trace.toString(), trace.any { it.contains("retry=1/") })
        assertNull(st.retry)
        // 重试成功后，失败那次不留错误卡；最后一条回答是成功的
        assertFalse(st.all<ChatItem.Notice>().any { it.kind == NoticeKind.ERROR })
        assertEquals("stop", st.all<ChatItem.Assistant>().last().stopReason)
    }

    @Test
    fun compaction() {
        // 真 pi 1.5.0 录制：一轮对话后手动 compact，pi 回 "Nothing to compact (session too small)"。
        // 成功压缩的「已压缩上下文」由 ChatReducerTest 手写事件覆盖。
        val st = snapshot("recorded/compaction")
        assertTrue(st.all<ChatItem.Notice>().any { it.kind == NoticeKind.ERROR && it.text.startsWith("压缩上下文失败") })
        assertFalse(st.all<ChatItem.Notice>().any { it.kind == NoticeKind.COMPACTION })
        assertNull(st.statusLine)
        assertFalse(st.running)
    }

    @Test
    fun providerErrorShowsChineseHint() {
        val st = snapshot("recorded/provider-error")
        val a = st.all<ChatItem.Assistant>().last()
        assertEquals("error", a.stopReason)
        assertTrue(a.errorMessage!!.isNotEmpty())
        assertTrue(st.all<ChatItem.Notice>().any { it.kind == NoticeKind.ERROR && it.text.startsWith("Key 错误") })
    }

    @Test
    fun protocolErrorsRecording() {
        val rs = Fixtures.responses(Fixtures.load("recorded/protocol-errors"))
        assertTrue(rs.any { it.isParseError })
        assertFalse(rs.single { it.command == "set_model" }.success)
        assertTrue(Responses.models(rs.single { it.command == "get_available_models" }.data).isNotEmpty())
        assertTrue(Responses.thinkingLevels(rs.single { it.command == "get_available_thinking_levels" }.data).contains("off"))
        assertTrue(rs.single { it.command == "get_session_stats" }.success)
    }

    @Test
    fun unknownEventsAndRoles() {
        val st = snapshot("handwritten/unknown-event-role")
        assertTrue(st.all<ChatItem.Raw>().any { it.role == "futureRole" })
        assertFalse(st.running)
        assertNull(st.sessionName) // 不带 name 的 session_info_changed = 清除
        assertEquals("high", st.thinkingLevel)
    }

    @Test
    fun unknownRolesInHistory() {
        val lines = Fixtures.load("handwritten/unknown-event-role")
        val messages = Responses.messages(Fixtures.responses(lines).single { it.command == "get_messages" }.data)
        val rebuilt = ChatReducer().fromMessages(messages)
        Fixtures.assertSnapshot("handwritten_unknown-event-role.rebuilt", Fixtures.render(rebuilt))
        assertTrue(rebuilt.all<ChatItem.Raw>().any { it.role == "futureRole" })
        assertTrue(rebuilt.all<ChatItem.Notice>().any { it.text.startsWith("执行了命令") })
        val tools = rebuilt.all<ChatItem.Tool>().associateBy { it.toolCallId }
        assertEquals(ToolStatus.OK, tools.getValue("call_1").status)
        assertEquals(ToolStatus.ERROR, tools.getValue("call_2").status)
    }

    @Test
    fun authoritativeEndsCorrectDeltas() {
        val st = snapshot("handwritten/edge-cases")
        val a = st.all<ChatItem.Assistant>().first()
        // text_end 的权威内容覆盖了拼接结果；message_end 整体替换
        assertEquals("权威文本", (a.blocks.first { it is ContentBlock.Text } as ContentBlock.Text).text)
        assertEquals("嗯，想好了", (a.blocks.first { it is ContentBlock.Thinking } as ContentBlock.Thinking).text)
        assertTrue(st.all<ChatItem.User>().all { it.delivery == Delivery.SENT })
        val tools = st.all<ChatItem.Tool>().associateBy { it.toolCallId }
        assertEquals(ToolStatus.OK, tools.getValue("t1").status) // toolResult 消息不覆盖 tool_execution_end
        assertEquals(42L, tools.getValue("t1").durationMs)
        assertEquals(ToolStatus.ERROR, tools.getValue("t2").status) // 缺 start 也建卡
        assertEquals(ToolStatus.ERROR, tools.getValue("t3").status) // 本轮结束仍在运行 → 失败
        assertFalse(tools.containsKey("ghost"))
        assertNull(st.statusLine)
    }

    @Test
    fun abortDuringToolcallLeavesNoSpinningCards() {
        // 审查 #3：中止 / 出错 / 截断的消息里的 toolCall 不会被执行，卡片不能一直 PENDING。
        val st = snapshot("handwritten/abort-during-toolcall")
        assertFalse(st.running)
        val tools = st.all<ChatItem.Tool>()
        assertEquals(listOf("call_abort_1", "call_abort_2"), tools.map { it.toolCallId })
        assertTrue(tools.all { it.status == ToolStatus.ERROR })
        // 重建路径一致：没有 toolResult 的 toolCall 也落为「未执行」
        val messages = kotlinx.serialization.json.JsonArray(
            listOf(
                RecordCodec.json.parseToJsonElement("""{"role":"user","content":"列一下文件"}"""),
                RecordCodec.json.parseToJsonElement("""{"role":"assistant","content":[{"type":"toolCall","id":"call_abort_1","name":"bash","arguments":{}}],"stopReason":"aborted"}"""),
                RecordCodec.json.parseToJsonElement("""{"role":"user","content":"再试一次"}"""),
                RecordCodec.json.parseToJsonElement("""{"role":"assistant","content":[{"type":"toolCall","id":"call_abort_2","name":"read","arguments":{"path":"README.md"}}],"stopReason":"toolUse"}"""),
            ),
        )
        val rebuilt = ChatReducer().fromMessages(messages)
        assertTrue(rebuilt.all<ChatItem.Tool>().all { it.status == ToolStatus.ERROR })
        assertEquals(
            st.items.map { Fixtures.renderItem(it, withKeys = false) }.filterNot { it.startsWith("NOTICE") },
            rebuilt.items.map { Fixtures.renderItem(it, withKeys = false) }.filterNot { it.startsWith("NOTICE") },
        )
    }
}
