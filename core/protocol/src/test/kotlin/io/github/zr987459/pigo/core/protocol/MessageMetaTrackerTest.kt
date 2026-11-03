package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.ToolStatus
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** messageMeta：ChatItem.key → pi entryId / usage / cost / model（S1、U1）。 */
class MessageMetaTrackerTest {
    private fun o(s: String) = RecordCodec.json.parseToJsonElement(s) as JsonObject

    private fun user(id: String, parent: String?, text: String) =
        o("""{"type":"message","id":"$id","parentId":${parent?.let { "\"$it\"" }},"message":{"role":"user","content":"$text"}}""")

    private fun asst(id: String, parent: String, out: Int, toolCall: String? = null) = o(
        """{"type":"message","id":"$id","parentId":"$parent","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat",""" +
            """"content":[${toolCall?.let { "{\"type\":\"toolCall\",\"id\":\"$it\",\"name\":\"bash\",\"arguments\":{}}" } ?: ""}],""" +
            """"usage":{"input":10,"output":$out,"cacheRead":0,"cacheWrite":0,"totalTokens":${10 + out},"cost":{"total":0.002}}}}""",
    )

    private fun toolResult(id: String, parent: String, call: String) =
        o("""{"type":"message","id":"$id","parentId":"$parent","message":{"role":"toolResult","toolCallId":"$call","toolName":"bash","content":[]}}""")

    @Test
    fun pairsFromTheEndAndToolResultsById() {
        val t = MessageMetaTracker()
        // 当前分支：e1 → e2(工具调用) → e3(toolResult) → e4 → e7 → e8；另一分支 e5 → e6 不参与。
        t.reset(
            listOf(
                user("e1", null, "第一句"), asst("e2", "e1", 5, toolCall = "c1"), toolResult("e3", "e2", "c1"), asst("e4", "e3", 7),
                user("e5", "e4", "旧分支"), asst("e6", "e5", 1),
                o("""{"type":"model_change","id":"m1","parentId":"e4","provider":"x","modelId":"y"}"""),
                user("e7", "m1", "第二句"), asst("e8", "e7", 9),
            ),
            leafId = "e8",
        )
        val items = listOf(
            ChatItem.User("u-0", "第一句", Delivery.SENT),
            ChatItem.Assistant("a-1", listOf(ContentBlock.ToolCall(0, "c1", "bash", "{}")), false, "toolUse"),
            ChatItem.Tool("tool-c1", "c1", "bash", null, ToolStatus.OK),
            ChatItem.Assistant("a-2", emptyList(), false, "stop"),
            ChatItem.User("u-3", "第二句", Delivery.SENT),
            ChatItem.Assistant("a-4", emptyList(), false, "stop"),
            ChatItem.User("local-0", "还没确认", Delivery.SENDING),
        )
        val m = t.compute(items, notPersisted = setOf("local-0"))
        assertEquals("e1", m.getValue("u-0").entryId)
        assertTrue(m.getValue("u-0").forkable)
        assertEquals("e7", m.getValue("u-3").entryId)
        assertEquals("e2", m.getValue("a-1").entryId)
        assertEquals("e3", m.getValue("tool-c1").entryId)
        assertEquals("e4", m.getValue("a-2").entryId)
        val last = m.getValue("a-4")
        assertEquals("e8", last.entryId)
        assertFalse(last.forkable)
        assertEquals(9L, last.usage!!.output)
        assertEquals(19L, last.usage!!.total)
        assertEquals(0.002, last.costUsd!!, 1e-9)
        assertEquals(ModelRef("deepseek", "deepseek-chat"), last.model)
        assertNull(m["local-0"])
    }

    @Test
    fun compactedTimelineAlignsAtTheTailAndAppendsIncrementally() {
        val t = MessageMetaTracker()
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 1), user("e3", "e2", "b"), asst("e4", "e3", 2)), leafId = "e4")
        // 压缩后时间线只剩最后一轮
        val items = mutableListOf<ChatItem>(ChatItem.User("u-0", "b", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"))
        assertEquals(mapOf("u-0" to "e3", "a-1" to "e4"), t.compute(items).mapValues { it.value.entryId })
        // 新一轮：entry_appended 增量
        val v = t.version
        items += ChatItem.User("u-2", "c", Delivery.SENT)
        t.append(user("e5", "e4", "c"))
        items += ChatItem.Assistant("a-3", emptyList(), streaming = true)
        assertTrue(t.version > v)
        val m = t.compute(items)
        assertEquals("e5", m.getValue("u-2").entryId)
        assertNull("流式中的助手消息还没写入", m["a-3"])
        t.append(asst("e6", "e5", 3))
        items[3] = ChatItem.Assistant("a-3", emptyList(), streaming = false, stopReason = "stop")
        assertEquals("e6", t.compute(items).getValue("a-3").entryId)
    }
}
