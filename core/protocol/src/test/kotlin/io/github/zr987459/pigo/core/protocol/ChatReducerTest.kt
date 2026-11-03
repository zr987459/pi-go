package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.ModelInfo
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.PiState
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 归约器的客户端侧操作：乐观气泡、送达状态、强制中断、新会话。 */
class ChatReducerTest {
    private val r = ChatReducer()

    private fun ev(json: String): EngineEvent = RecordCodec.decodeObject(RecordCodec.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject) as EngineEvent

    private fun userStart(text: String) = ev("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}""")

    @Test
    fun optimisticBubbleIsConfirmedByMessageStartAndNotDuplicated() {
        var s = r.addPendingUser(ChatState(), "local-0", "你好")
        assertEquals(Delivery.SENDING, (s.items.single() as ChatItem.User).delivery)
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, userStart("你好"), 0)
        val u = s.items.single() as ChatItem.User
        assertEquals("local-0", u.key) // key 稳定
        assertEquals(Delivery.SENT, u.delivery)
        // response 晚到：已确认的不被改回
        s = r.setDelivery(s, "local-0", Delivery.QUEUED)
        assertEquals(Delivery.SENT, (s.items.single() as ChatItem.User).delivery)
    }

    @Test
    fun queuedBubbleMovesToEndWhenDelivered() {
        var s = r.addPendingUser(ChatState(), "local-0", "第一条")
        s = r.setDelivery(s, "local-0", Delivery.SENT)
        s = r.reduce(s, userStart("第一条"), 0)
        s = r.reduce(s, ev("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""), 0)
        s = r.addPendingUser(s, "local-1", "排队的")
        s = r.setDelivery(s, "local-1", Delivery.QUEUED)
        assertEquals(Delivery.QUEUED, (s.items[2] as ChatItem.User).delivery)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"答"}],"stopReason":"stop"}}"""), 0)
        // 另一条文本不同的 user 消息不会误确认排队气泡
        s = r.reduce(s, userStart("扩展插入的"), 0)
        assertEquals(Delivery.QUEUED, (s.items.first { it.key == "local-1" } as ChatItem.User).delivery)
        s = r.reduce(s, userStart("排队的"), 0)
        val last = s.items.last() as ChatItem.User
        assertEquals("local-1", last.key)
        assertEquals(Delivery.SENT, last.delivery)
        assertEquals(1, s.items.count { it is ChatItem.User && it.text == "排队的" })
    }

    @Test
    fun expandedTemplateTextStillConfirmsTheSendingBubble() {
        var s = r.addPendingUser(ChatState(), "local-0", "/skill:review")
        s = r.reduce(s, userStart("请审查以下代码……"), 0)
        val u = s.items.single() as ChatItem.User
        assertEquals("local-0", u.key)
        assertEquals("请审查以下代码……", u.text)
    }

    @Test
    fun failedAndHandledAndRemoved() {
        var s = r.addPendingUser(ChatState(), "local-0", "x")
        s = r.setDelivery(s, "local-0", Delivery.FAILED)
        assertEquals(Delivery.FAILED, (s.items.single() as ChatItem.User).delivery)
        s = r.addPendingUser(s, "local-1", "/cmd")
        s = r.setDelivery(s, "local-1", Delivery.SENT, confirmed = true)
        // handled 已确认：之后的 user 消息不会去匹配它
        s = r.reduce(s, userStart("别的"), 0)
        assertEquals(3, s.items.size)
        s = r.removeItem(s, "local-0")
        assertEquals(2, s.items.size)
        assertEquals(s, r.setDelivery(s, "nope", Delivery.SENT))
    }

    @Test
    fun interruptEndsStreamingAndFailsRunningTools() {
        var s = ChatState()
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, ev("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""), 0)
        s = r.reduce(s, ev("""{"type":"tool_execution_start","toolCallId":"t","toolName":"bash","args":{}}"""), 0)
        s = r.reduce(s, ev("""{"type":"queue_update","steering":["a"],"followUp":[]}"""), 0)
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"threshold"}"""), 0)
        s = r.interrupt(s, NoticeKind.STOPPED, "已强制停止")
        assertFalse(s.running)
        assertEquals(QueueSnapshot.EMPTY, s.queue)
        assertNull(s.statusLine)
        val a = s.items.filterIsInstance<ChatItem.Assistant>().single()
        assertFalse(a.streaming)
        assertEquals("aborted", a.stopReason)
        assertEquals(ToolStatus.ERROR, s.items.filterIsInstance<ChatItem.Tool>().single().status)
        assertEquals("已强制停止", (s.items.last() as ChatItem.Notice).text)
        // 之后的 delta 不会写进旧条目，而是新建条目
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"新"}}"""), 0)
        assertEquals(2, s.items.count { it is ChatItem.Assistant })
    }

    @Test
    fun clearedAndPiState() {
        val m = ModelInfo("deepseek", "deepseek-chat")
        var s = r.applyPiState(ChatState(), PiState(model = m, thinkingLevel = "high", sessionName = "名", sessionFile = "/f.jsonl"))
        assertEquals(m, s.model)
        assertEquals("high", s.thinkingLevel)
        assertEquals("名", s.sessionName)
        assertEquals("/f.jsonl", s.sessionFile)
        s = r.addPendingUser(s, "local-0", "x")
        s = r.addNotice(s, NoticeKind.INFO, "提示")
        val c = r.cleared(s)
        assertTrue(c.items.isEmpty())
        assertEquals(m, c.model)
        assertEquals("high", c.thinkingLevel)
        assertNull(c.sessionName)
        // 清空后，旧的乐观气泡不再参与确认
        val after = r.reduce(c, userStart("x"), 0)
        assertEquals(Delivery.SENT, (after.items.single() as ChatItem.User).delivery)
        assertTrue(after.items.single().key.startsWith("u-"))
    }

    @Test
    fun updateWithoutStartAndNonDeltaUpdatesAreSafe() {
        var s = ChatState()
        // 没有 message_start 的 done/start 事件：忽略
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"done","reason":"stop"}}"""), 0)
        assertTrue(s.items.isEmpty())
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}"""), 10)
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"想"}}"""), 20)
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"想好了"}}"""), 1_510)
        val th = (s.items.single() as ChatItem.Assistant).blocks.single() as ContentBlock.Thinking
        assertEquals("想好了", th.text)
        assertEquals(1_500L, th.durationMs)
        // message_end 没有 message_start 也能落地，思考时长保留
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"想好了"},{"type":"text","text":"答"}],"stopReason":"length"}}"""), 1_600)
        val a = s.items.filterIsInstance<ChatItem.Assistant>().single()
        assertEquals(1_500L, (a.blocks[0] as ContentBlock.Thinking).durationMs)
        assertEquals(NoticeKind.TRUNCATED, (s.items.last() as ChatItem.Notice).kind)
        // user message_end 没有 start：补一条，有 start 时不重复
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"user","content":"补的"}}"""), 0)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"user","content":"补的"}}"""), 0)
        assertEquals(1, s.items.count { it is ChatItem.User })
        // toolResult 没有 toolCallId：当未知内容显示
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"toolResult","content":[]}}"""), 0)
        assertTrue(s.items.last() is ChatItem.Raw)
    }

    @Test
    fun extensionErrorAndRetryKeepsAssistantWithContent() {
        var s = ChatState()
        s = r.reduce(s, ev("""{"type":"extension_error","error":"x"}"""), 0)
        assertEquals("扩展出错：x", (s.items.single() as ChatItem.Notice).text)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"写了一半"}],"stopReason":"error","errorMessage":"529 overloaded"}}"""), 0)
        assertEquals(NoticeKind.ERROR, (s.items.last() as ChatItem.Notice).kind)
        s = r.reduce(s, ev("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}"""), 0)
        // 有内容的失败消息保留，错误卡去掉，黄条出现
        assertTrue(s.items.any { it is ChatItem.Assistant })
        assertFalse(s.items.any { it is ChatItem.Notice && it.kind == NoticeKind.ERROR })
        assertEquals(1, s.retry!!.attempt)
        s = r.reduce(s, ev("""{"type":"auto_retry_end","success":true,"attempt":2}"""), 0)
        assertNull(s.retry)
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"manual","result":{"summary":"s"},"aborted":false,"willRetry":false}"""), 0)
        assertEquals(NoticeKind.COMPACTION, (s.items.last() as ChatItem.Notice).kind)
        val before = s
        s = r.reduce(s, ev("""{"type":"compaction_end","aborted":false,"willRetry":false}"""), 0)
        assertEquals(before, s)
    }

    private fun arr(vararg json: String) = kotlinx.serialization.json.JsonArray(json.map { RecordCodec.json.parseToJsonElement(it) })

    @Test
    fun interruptWithdrawsQueuedAndUnconfirmedMessagesAndRebuildKeepsThem() {
        // 审查 #2：崩溃 / 强制重启时排队中的消息不能悄悄消失。
        var s = r.addPendingUser(ChatState(), "local-0", "第一条")
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, userStart("第一条"), 0)
        s = r.addPendingUser(s, "local-1", "排队")
        s = r.setDelivery(s, "local-1", Delivery.QUEUED)
        s = r.addPendingUser(s, "local-2", "发送中")
        s = r.reduce(s, ev("""{"type":"queue_update","steering":["扩展插的"],"followUp":["排队"]}"""), 0)
        val res = r.interruptWithdrawing(s, NoticeKind.INTERRUPTED, "引擎意外退出")
        assertEquals(listOf("排队", "发送中", "扩展插的"), res.withdrawn.map { it.text })
        val users = res.state.items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf(Delivery.SENT, Delivery.FAILED, Delivery.FAILED, Delivery.FAILED), users.map { it.delivery })
        assertTrue(res.state.queue.isEmpty)
        assertTrue(res.state.items.any { it is ChatItem.Notice && it.text.startsWith("3 条消息未发出") })
        // 重建：历史里只有第一条；撤回的消息以「发送失败」接在后面（不自动重发）
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"第一条"}"""), base = res.state, carry = res.withdrawn)
        assertEquals(listOf("第一条", "排队", "发送中", "扩展插的"), rebuilt.items.filterIsInstance<ChatItem.User>().map { it.text })
        assertEquals(Delivery.FAILED, rebuilt.items.filterIsInstance<ChatItem.User>().last().delivery)
        assertTrue(rebuilt.items.last().let { it is ChatItem.Notice && it.text == ChatReducer.withdrawnText(3) })
        // 失败气泡已不在待确认集合：之后同文本的 user 消息不会把它「确认」
        val later = r.reduce(rebuilt, userStart("排队"), 0)
        assertEquals(Delivery.FAILED, (later.items.first { it.key == "local-1" } as ChatItem.User).delivery)
    }

    @Test
    fun sentButUnconfirmedMessageAlreadyInHistoryIsNotDuplicated() {
        var s = r.addPendingUser(ChatState(), "local-0", "刚发的")
        s = r.setDelivery(s, "local-0", Delivery.SENT)
        val res = r.interruptWithdrawing(s, NoticeKind.STOPPED, "已强制停止")
        assertEquals(1, res.withdrawn.size)
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"刚发的"}"""), base = res.state, carry = res.withdrawn)
        assertEquals(1, rebuilt.items.count { it is ChatItem.User })
        assertEquals(Delivery.SENT, (rebuilt.items.single() as ChatItem.User).delivery)
    }

    @Test
    fun repeatedUserMessageOnlyOneInHistoryKeepsSecondAsFailed() {
        // 用户连发两次「继续」，历史里只有第一次 → 第二次以 FAILED 保留
        var s = r.addPendingUser(ChatState(), "local-0", "继续")
        s = r.setDelivery(s, "local-0", Delivery.SENT)
        s = r.addPendingUser(s, "local-1", "继续")
        s = r.setDelivery(s, "local-1", Delivery.SENT)
        val res = r.interruptWithdrawing(s, NoticeKind.STOPPED, "已强制停止")
        assertEquals(2, res.withdrawn.size)
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"继续"}"""), base = res.state, carry = res.withdrawn)
        val users = rebuilt.items.filterIsInstance<ChatItem.User>()
        assertEquals(2, users.size)
        assertEquals(Delivery.SENT, users[0].delivery)
        assertEquals(Delivery.FAILED, users[1].delivery)
        assertEquals("local-1", users[1].key)
    }

    @Test
    fun earlierConfirmedSameTextMessageDoesNotConsumeCarry() {
        // 历史中更早已确认的同文本消息不会抵消 carry
        var s = r.addPendingUser(ChatState(), "local-0", "继续")
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, userStart("继续"), 0) // 确认为 SENT
        s = r.addPendingUser(s, "local-1", "继续")
        s = r.setDelivery(s, "local-1", Delivery.SENT) // 第二条未确认
        val res = r.interruptWithdrawing(s, NoticeKind.INTERRUPTED, "引擎意外退出")
        assertEquals(1, res.withdrawn.size)
        assertEquals("local-1", res.withdrawn.single().key)
        // 历史里只有早先确认的那一条
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"继续"}"""), base = res.state, carry = res.withdrawn)
        val users = rebuilt.items.filterIsInstance<ChatItem.User>()
        assertEquals(2, users.size)
        assertEquals(Delivery.SENT, users[0].delivery)
        assertEquals(Delivery.FAILED, users[1].delivery)
        assertEquals("local-1", users[1].key)
    }

    @Test
    fun timedOutPromptStaysConfirmableAndIsNotDuplicated() {
        // 审查 Nit #6：prompt 应答超时只是「不确定」，之后的 message_start(user) 确认这条气泡而不是追加一条。
        var s = r.addPendingUser(ChatState(), "local-0", "慢慢来")
        s = r.setDelivery(s, "local-0", Delivery.FAILED, uncertain = true)
        assertEquals(Delivery.FAILED, (s.items.single() as ChatItem.User).delivery)
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, userStart("慢慢来"), 0)
        val u = s.items.single() as ChatItem.User
        assertEquals("local-0", u.key)
        assertEquals(Delivery.SENT, u.delivery)
        // 被拒绝（确定失败）的则不再等待确认
        var t = r.addPendingUser(ChatState(), "local-1", "被拒")
        t = r.setDelivery(t, "local-1", Delivery.FAILED)
        t = r.reduce(t, userStart("被拒"), 0)
        assertEquals(2, t.items.size)
    }

    @Test
    fun toolCardsOfNonToolUseMessagesAreNotExecuted() {
        var s = r.reduce(ChatState(), ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"e1","name":"bash","arguments":{}}],"stopReason":"error","errorMessage":"boom"}}"""), 0)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"l1","name":"bash","arguments":{}}],"stopReason":"length"}}"""), 0)
        val tools = s.items.filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf(ToolStatus.ERROR, ToolStatus.ERROR), tools.map { it.status })
        assertTrue(tools.all { it.result.toString().contains(ChatReducer.NOT_EXECUTED) })
        // toolUse：正常 PENDING，settled 时没开始执行的落为「未执行」
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"t1","name":"bash","arguments":{}}],"stopReason":"toolUse"}}"""), 0)
        assertEquals(ToolStatus.PENDING, (s.items.first { it.key == "tool-t1" } as ChatItem.Tool).status)
        s = r.reduce(s, ev("""{"type":"agent_settled","aborted":true}"""), 0)
        assertEquals(ToolStatus.ERROR, (s.items.first { it.key == "tool-t1" } as ChatItem.Tool).status)
        // interrupt 同样关闭 PENDING
        var u = r.reduce(ChatState(), ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"t2","name":"bash","arguments":{}}],"stopReason":"toolUse"}}"""), 0)
        u = r.interrupt(u, NoticeKind.INTERRUPTED, "x")
        assertEquals(ToolStatus.ERROR, (u.items.first { it.key == "tool-t2" } as ChatItem.Tool).status)
    }

    @Test
    fun rebuildClosesPendingToolsUnlessLiveAndResetsQueueAndContext() {
        val msgs = arr(
            """{"role":"user","content":"跑"}""",
            """{"role":"assistant","content":[{"type":"toolCall","id":"p1","name":"bash","arguments":{}}],"stopReason":"toolUse"}""",
        )
        val base = ChatState(queue = QueueSnapshot(listOf("a"), listOf("b")), contextPercent = 40.0)
        val idle = r.fromMessages(msgs, base = base)
        assertEquals(ToolStatus.ERROR, idle.items.filterIsInstance<ChatItem.Tool>().single().status)
        assertTrue(idle.queue.isEmpty)
        assertNull(idle.contextPercent)
        val live = r.fromMessages(msgs, base = base, live = true)
        assertEquals(ToolStatus.PENDING, live.items.filterIsInstance<ChatItem.Tool>().single().status)
    }

    @Test
    fun handledBubbleDoesNotShiftCarryMatching() {
        // 审查 R3 Nit：扩展命令消费（handled）的气泡不写入会话，不能算进「已确认」条数，否则已写入的消息会重复成「发送失败」。
        var s = r.addPendingUser(ChatState(), "local-0", "/扩展命令")
        s = r.setDelivery(s, "local-0", Delivery.SENT, confirmed = true)
        s = r.addPendingUser(s, "local-1", "刚发的")
        s = r.setDelivery(s, "local-1", Delivery.SENT)
        val res = r.interruptWithdrawing(s, NoticeKind.STOPPED, "已强制停止")
        assertEquals(listOf("local-1"), res.withdrawn.map { it.key })
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"刚发的"}"""), base = res.state, carry = res.withdrawn)
        val users = rebuilt.items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf("刚发的"), users.map { it.text })
        assertEquals(Delivery.SENT, users.single().delivery)
    }

    @Test
    fun compactedHistoryKeepsCarryInsteadOfDropping() {
        // 已确认条数多于历史条数（压缩后历史只剩近期）：宁可保留为「发送失败」，不丢弃。
        val base = ChatState(
            items = listOf(
                ChatItem.User("u-a", "一", Delivery.SENT),
                ChatItem.User("u-b", "二", Delivery.SENT),
                ChatItem.User("u-c", "继续", Delivery.SENT),
            ),
        )
        val carry = listOf(ChatItem.User("local-9", "继续", Delivery.SENT))
        val rebuilt = r.fromMessages(arr("""{"role":"user","content":"继续"}"""), base = base, carry = carry)
        val users = rebuilt.items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf("继续", "继续"), users.map { it.text })
        assertEquals(Delivery.FAILED, users.last().delivery)
    }

    @Test
    fun incompleteBaseTimelineOnlyMatchesHistoryTail() {
        // 此前历史没加载出来（base 里没有已确认消息）：只在历史尾部匹配，旧的同文本短句不会把 carry 抵消掉。
        val carry = listOf(ChatItem.User("local-9", "继续", Delivery.SENT))
        val rebuilt = r.fromMessages(
            arr("""{"role":"user","content":"继续"}""", """{"role":"user","content":"别的"}"""),
            base = ChatState(),
            carry = carry,
        )
        val users = rebuilt.items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf("继续", "别的", "继续"), users.map { it.text })
        assertEquals(Delivery.FAILED, users.last().delivery)
    }

    @Test
    fun liveRebuildKeepsRunningAndQueue() {
        var s = r.addPendingUser(ChatState(), "local-0", "开始")
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 0)
        s = r.reduce(s, userStart("开始"), 0)
        s = r.reduce(s, ev("""{"type":"queue_update","steering":["插话"],"followUp":["排队"]}"""), 0)
        val live = r.fromMessages(arr("""{"role":"user","content":"开始"}"""), base = s, live = true)
        assertTrue(live.running)
        assertEquals(listOf("插话"), live.queue.steering)
        assertEquals(listOf("排队"), live.queue.followUp)
        val idle = r.fromMessages(arr("""{"role":"user","content":"开始"}"""), base = s, live = false)
        assertFalse(idle.running)
        assertTrue(idle.queue.isEmpty)
    }
}
