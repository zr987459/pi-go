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
}
