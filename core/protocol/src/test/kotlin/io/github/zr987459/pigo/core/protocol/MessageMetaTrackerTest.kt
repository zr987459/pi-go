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
            covered = setOf("u-0", "a-1", "a-2", "u-3", "a-4"),
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
    fun compactedTimelineAlignsAtTheTailAndMergesIncrementally() {
        val t = MessageMetaTracker()
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 1), user("e3", "e2", "b"), asst("e4", "e3", 2)), leafId = "e4", covered = setOf("u-0", "a-1"))
        // 压缩后时间线只剩最后一轮
        val items = mutableListOf<ChatItem>(ChatItem.User("u-0", "b", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"))
        assertEquals(mapOf("u-0" to "e3", "a-1" to "e4"), t.compute(items).mapValues { it.value.entryId })
        assertEquals("e4", t.lastId)
        // 新一轮：get_entries(since=e4) 增量
        val v = t.version
        items += ChatItem.User("u-2", "c", Delivery.SENT)
        t.merge(listOf(user("e5", "e4", "c")), leafId = "e5", covered = setOf("u-0", "a-1", "u-2"))
        items += ChatItem.Assistant("a-3", emptyList(), streaming = true)
        assertTrue(t.version > v)
        val m = t.compute(items)
        assertEquals("e5", m.getValue("u-2").entryId)
        assertNull("流式中的助手消息还没写入", m["a-3"])
        t.merge(listOf(asst("e6", "e5", 3)), leafId = "e6", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        items[3] = ChatItem.Assistant("a-3", emptyList(), streaming = false, stopReason = "stop")
        assertEquals("e6", t.compute(items).getValue("a-3").entryId)
    }

    /** 审查第 1 轮①：新用户气泡已 SENT 但还没写盘（不在 covered）时不参与配对，绝不配到上一条的 entryId。 */
    @Test
    fun unpersistedTailBubbleIsNotPairedWithPreviousEntry() {
        val t = MessageMetaTracker()
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 1)), leafId = "e2", covered = setOf("u-0", "a-1"))
        val items = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT), // pi 已确认，但 get_entries 快照里还没有
        )
        val m = t.compute(items)
        assertEquals("e1", m.getValue("u-0").entryId)
        assertNull("尾部未写盘的气泡不能配到 e1", m["u-2"])
        assertEquals("e2", m.getValue("a-1").entryId)
    }

    /** 审查第 1 轮②③：轮中 context_edit 把失败的助手消息从投影排除（自动重试），之前助手消息的 usage 不错位。 */
    @Test
    fun contextEditOmitsRetriedFailureAndKeepsEarlierAssistantsAligned() {
        val t = MessageMetaTracker()
        val failed = o("""{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"半截"}],"stopReason":"error","errorMessage":"overloaded","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":2,"cost":{"total":0}}}}""")
        val edit = o("""{"type":"context_edit","id":"x1","parentId":"e4","targetId":"e4","replacement":null}""")
        t.reset(
            listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed, edit, asst("e5", "x1", 8)),
            leafId = "e5", covered = setOf("u-0", "a-1", "u-2", "a-3", "a-4"),
        )
        // 时间线保留了有内容的失败消息 a-3，随后是重试结果 a-4
        val items = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT),
            ChatItem.Assistant("a-3", listOf(ContentBlock.Text(0, "半截")), false, "error", "overloaded"),
            ChatItem.Assistant("a-4", emptyList(), false, "stop"),
        )
        val m = t.compute(items)
        assertEquals("e5", m.getValue("a-4").entryId)
        assertEquals(8L, m.getValue("a-4").usage!!.output)
        assertEquals("e2", m.getValue("a-1").entryId)
        assertEquals(5L, m.getValue("a-1").usage!!.output)
        assertNull("已重试的失败消息不配对", m["a-3"])
        assertEquals("e3", m.getValue("u-2").entryId)
        // 重试中（横幅还在，重试结果未到）：最后一条失败消息同样不配对
        t.reset(
            listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed, edit),
            leafId = "x1", covered = setOf("u-0", "a-1", "u-2", "a-3"),
        )
        val m2 = t.compute(items.take(4), retrying = true)
        assertNull(m2["a-3"])
        assertEquals("e2", m2.getValue("a-1").entryId)
        assertEquals("e3", m2.getValue("u-2").entryId)
    }

    /**
     * 审查第 2 轮 Important 1（真 pi 1.5.0 + 假 OpenAI 抓到的序列）：重试退避中停止 / 放弃重试。
     * 失败消息 e4（有内容，时间线保留 a-3）被 context_edit 排除，retry 已为 null，其后没有助手消息。
     * 旧实现把 a-3 配到 e2、a-1 配不到；现在 a-3 与 e4 一起跳过。
     */
    @Test
    fun stopOrAbortDuringRetryDoesNotShiftAssistants() {
        val t = MessageMetaTracker()
        val failed = o("""{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"fake","model":"m","content":[{"type":"text","text":"半截2"}],"stopReason":"error","errorMessage":"terminated","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"total":0}}}}""")
        val edit = o("""{"type":"context_edit","id":"x1","parentId":"e4","targetId":"e4","replacement":null}""")
        val base = listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed, edit)
        t.reset(base, leafId = "x1", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        val items = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT),
            ChatItem.Assistant("a-3", listOf(ContentBlock.Text(0, "半截2")), false, "error", "terminated"),
        )
        val m = t.compute(items, retrying = false)
        assertNull("失败气泡不配对", m["a-3"])
        assertEquals("e2", m.getValue("a-1").entryId)
        assertEquals(5L, m.getValue("a-1").usage!!.output)
        assertEquals("e3", m.getValue("u-2").entryId)

        // 再发一轮：仍对齐
        t.merge(listOf(user("e6", "x1", "c"), asst("e7", "e6", 9)), leafId = "e7", covered = setOf("u-0", "a-1", "u-2", "a-3", "u-5", "a-6"))
        val items2 = items + ChatItem.User("u-5", "c", Delivery.SENT) + ChatItem.Assistant("a-6", emptyList(), false, "stop")
        val m2 = t.compute(items2)
        assertEquals("e7", m2.getValue("a-6").entryId)
        assertEquals("e2", m2.getValue("a-1").entryId)
        assertNull(m2["a-3"])
        assertEquals("e6", m2.getValue("u-5").entryId)

        // 重建后（fromMessages 已不含被排除的失败消息）：只跳条目
        val rebuilt = listOf(items[0], items[1], items[2], ChatItem.User("u-5", "c", Delivery.SENT), ChatItem.Assistant("a-6", emptyList(), false, "stop"))
        t.reset(base + listOf(user("e6", "x1", "c"), asst("e7", "e6", 9)), leafId = "e7", covered = rebuilt.map { it.key }.toSet())
        val m3 = t.compute(rebuilt)
        assertEquals("e7", m3.getValue("a-6").entryId)
        assertEquals("e2", m3.getValue("a-1").entryId)
    }

    /** 有失败条目但 context_edit 还没写盘、这一轮还在进行（扩展 turn_end/agent_end 异步 await、auto_retry_start 之前的瞬时窗口）：暂不配，也不错位。 */
    @Test
    fun failedEntryBeforeContextEditIsSkippedNotShifted() {
        val t = MessageMetaTracker()
        val failed = o("""{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"fake","model":"m","content":[],"stopReason":"error","errorMessage":"overloaded"}}""")
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed), leafId = "e4", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        val withBubble = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT), ChatItem.Assistant("a-3", emptyList(), false, "error", "overloaded"),
        )
        val m = t.compute(withBubble, running = true)
        assertNull(m["a-3"])
        assertEquals("e2", m.getValue("a-1").entryId)
        // 空失败消息已被 onRetryStart 从时间线删掉：只跳条目
        val m2 = t.compute(withBubble.take(3), retrying = true, running = true)
        assertEquals("e2", m2.getValue("a-1").entryId)
    }

    /** 审查第 3 轮 Nit 1：不会重试的错误（401、重试次数用尽）留在上下文里、未被排除；轮已结束时两边都是 error 照常配对。 */
    @Test
    fun nonRetriedErrorStaysInContextAndPairs() {
        val t = MessageMetaTracker()
        val failed = o("""{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"fake","model":"m","content":[],"stopReason":"error","errorMessage":"401 Incorrect API key","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"total":0}}}}""")
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed, user("e5", "e4", "c"), asst("e6", "e5", 7)), leafId = "e6",
            covered = setOf("u-0", "a-1", "u-2", "a-3", "u-4", "a-5"))
        val items = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT), ChatItem.Assistant("a-3", emptyList(), false, "error", "401 Incorrect API key"),
            ChatItem.User("u-4", "c", Delivery.SENT), ChatItem.Assistant("a-5", emptyList(), false, "stop"),
        )
        val m = t.compute(items)
        assertEquals("e4", m.getValue("a-3").entryId)
        assertEquals("e2", m.getValue("a-1").entryId)
        assertEquals("e6", m.getValue("a-5").entryId)
        // 刚结束、它是最后一条时也照常配对
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), failed), leafId = "e4", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        val m2 = t.compute(items.take(4))
        assertEquals("e4", m2.getValue("a-3").entryId)
        assertEquals("e2", m2.getValue("a-1").entryId)
    }

    /**
     * 审查第 3 轮 Important：截断恢复（pi 1.5.0 `_omitRecoveryAttempt`）。stopReason=length 且输出低于 maxTokens 的回复被
     * context_edit(replacement=null) 排除；时间线上截断气泡的 stopReason 是 length。旧实现只跳条目，a-3 拿到 e2、a-1 为空。
     */
    @Test
    fun lengthRecoveryOmittedEntrySkipsWithLengthBubble() {
        val t = MessageMetaTracker()
        val cut = o("""{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"fake","model":"m","content":[{"type":"text","text":"截断的回"}],"stopReason":"length","usage":{"input":120,"output":12,"cacheRead":0,"cacheWrite":0,"totalTokens":132,"cost":{"total":0}}}}""")
        val edit = o("""{"type":"context_edit","id":"x1","parentId":"e4","targetId":"e4","replacement":null}""")
        t.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), cut, edit), leafId = "x1", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        val items = listOf(
            ChatItem.User("u-0", "a", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop"),
            ChatItem.User("u-2", "b", Delivery.SENT), ChatItem.Assistant("a-3", listOf(ContentBlock.Text(0, "截断的回")), false, "length"),
        )
        val m = t.compute(items)
        assertNull("截断气泡不配对", m["a-3"])
        assertEquals("e2", m.getValue("a-1").entryId)
        assertEquals(5L, m.getValue("a-1").usage!!.output)
        // 恢复后重试成功：新助手配新条目
        t.merge(listOf(asst("e5", "x1", 9)), leafId = "e5", covered = setOf("u-0", "a-1", "u-2", "a-3", "a-4"))
        val m2 = t.compute(items + ChatItem.Assistant("a-4", emptyList(), false, "stop"))
        assertEquals("e5", m2.getValue("a-4").entryId)
        assertEquals("e2", m2.getValue("a-1").entryId)
        assertNull(m2["a-3"])
        // 被排除条目与气泡 stopReason 不同（重建后截断消息已不在时间线）：只跳条目
        val m3 = t.compute(listOf(items[0], items[1], items[2], ChatItem.Assistant("a-4", emptyList(), false, "stop")))
        assertEquals("e5", m3.getValue("a-4").entryId)
        assertEquals("e2", m3.getValue("a-1").entryId)
        // 未被排除的普通截断（输出达到 maxTokens，不恢复）照常配对
        val plain = MessageMetaTracker()
        plain.reset(listOf(user("e1", null, "a"), asst("e2", "e1", 5), user("e3", "e2", "b"), cut), leafId = "e4", covered = setOf("u-0", "a-1", "u-2", "a-3"))
        assertEquals("e4", plain.compute(items).getValue("a-3").entryId)
    }

    /** 链断（parentId 指向快照里没有的条目）时只用已知的叶子一侧，不整个清空。 */
    @Test
    fun brokenChainUsesKnownSuffix() {
        val t = MessageMetaTracker()
        t.reset(listOf(user("e3", "missing", "b"), asst("e4", "e3", 2)), leafId = "e4", covered = setOf("u-0", "a-1"))
        val m = t.compute(listOf(ChatItem.User("u-0", "b", Delivery.SENT), ChatItem.Assistant("a-1", emptyList(), false, "stop")))
        assertEquals("e3", m.getValue("u-0").entryId)
        assertEquals("e4", m.getValue("a-1").entryId)
    }
}
