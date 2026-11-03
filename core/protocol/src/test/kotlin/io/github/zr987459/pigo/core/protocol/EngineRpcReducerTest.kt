package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.CompactionPhase
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvents
import io.github.zr987459.pigo.contract.rpc.RpcImage
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** feat/engine-rpc 新增的归约：bash 卡、压缩卡、带图消息确认、新事件解码、完整 stats。 */
class EngineRpcReducerTest {

    private fun ev(line: String): EngineEvent = (RecordCodec.decode(line) as RecordCodec.Decoded.Record).record as EngineEvent

    private fun data(line: String) = ((RecordCodec.decode(line) as RecordCodec.Decoded.Record).record as RpcResponse).data

    private inline fun <reified T : ChatItem> ChatState.all() = items.filterIsInstance<T>()

    // ------------------------------------------------------------------ 解码

    @Test
    fun decodesNewEvents() {
        val b = ev("""{"type":"bash_execution_update","id":"c-7","delta":"hi\n"}""") as EngineEvents.BashExecutionUpdate
        assertEquals("c-7", b.id)
        assertEquals("hi\n", b.delta)
        assertTrue(ev("""{"type":"entry_appended","entry":{"id":"e1","type":"message"}}""") is EngineEvents.EntryAppended)
        val s = ev("""{"type":"summarization_retry_scheduled","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"x"}""")
            as EngineEvents.SummarizationRetryScheduled
        assertEquals(3, s.maxAttempts)
        assertEquals("compaction", (ev("""{"type":"summarization_retry_attempt_start","source":"compaction","reason":"manual"}""") as EngineEvents.SummarizationRetryAttemptStart).source)
        assertTrue(ev("""{"type":"summarization_retry_finished"}""") is EngineEvents.SummarizationRetryFinished)
    }

    // ------------------------------------------------------------------ bash（C6）

    @Test
    fun bashUpdatesGoToTheirOwnCardById() {
        val r = ChatReducer()
        var s = ChatState()
        s = r.startBash(s, "c-1", "echo a", excludeFromContext = false, nowMillis = 1_000)
        s = r.startBash(s, "c-2", "echo b", excludeFromContext = true, nowMillis = 1_100)
        for (line in listOf(
            """{"type":"bash_execution_update","id":"c-2","delta":"b1 "}""",
            """{"type":"bash_execution_update","id":"c-1","delta":"a1 "}""",
            """{"type":"bash_execution_update","id":"c-2","delta":"b2"}""",
            """{"type":"bash_execution_update","id":"c-9","delta":"没有这张卡"}""",
            """{"type":"bash_execution_update","id":"c-1","delta":"a2"}""",
        )) s = r.reduce(s, ev(line), 1_200)
        val (a, b) = s.all<ChatItem.Bash>()
        assertEquals("bash-c-1", a.key)
        assertEquals("a1 a2", a.output)
        assertEquals("b1 b2", b.output)
        assertTrue(b.excludeFromContext)
        assertTrue(a.running && b.running)

        val res = ExtraResponses.bash(data("""{"id":"c-1","type":"response","command":"bash","success":true,"data":{"output":"a1 a2\n","exitCode":0,"cancelled":false,"truncated":false}}"""))
        s = r.finishBash(s, "c-1", res, null, 1_500)
        val done = s.all<ChatItem.Bash>().first()
        assertFalse(done.running)
        assertEquals(0, done.exitCode)
        assertEquals("a1 a2\n", done.output)
        assertEquals(500L, done.durationMs)
        // 被中止：exitCode 为 null，cancelled
        val ab = ExtraResponses.bash(data("""{"id":"c-2","type":"response","command":"bash","success":true,"data":{"output":"b1 b2","cancelled":true,"truncated":false}}"""))
        s = r.finishBash(s, "c-2", ab, null, 1_600)
        val c = s.all<ChatItem.Bash>()[1]
        assertNull(c.exitCode)
        assertTrue(c.cancelled)
        // 后续的 delta 不再改已结束的卡
        s = r.reduce(s, ev("""{"type":"bash_execution_update","id":"c-1","delta":"late"}"""), 2_000)
        assertEquals("a1 a2\n", s.all<ChatItem.Bash>().first().output)
    }

    @Test
    fun bashFromHistoryBecomesCard() {
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"bashExecution","command":"echo hi","output":"hi\n","exitCode":0,"cancelled":false,"truncated":false,"timestamp":1}]""",
        ) as JsonArray
        val b = ChatReducer().fromMessages(msgs).all<ChatItem.Bash>().single()
        assertEquals("echo hi", b.command)
        assertEquals("hi\n", b.output)
        assertEquals(0, b.exitCode)
        assertFalse(b.running)
        assertNull(b.bashId)
    }

    @Test
    fun interruptEndsRunningBash() {
        val r = ChatReducer()
        var s = r.startBash(ChatState(), "c-1", "sleep 9", false, 0)
        s = r.interrupt(s, NoticeKind.INTERRUPTED, "引擎意外退出")
        val b = s.all<ChatItem.Bash>().single()
        assertFalse(b.running)
        assertTrue(b.errorMessage!!.isNotEmpty())
    }

    // ------------------------------------------------------------------ 压缩（U3）

    private val summary = "## 目标\n做测试\n\n<read-files>\nREADME.md\n</read-files>\n\n<modified-files>\nsrc/a.kt\nsrc/b.kt\n</modified-files>"

    @Test
    fun compactResponseAndEventsReduceToOneCard() {
        val r = ChatReducer()
        var s = ChatState(contextPercent = 87.5)
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"manual"}"""), 0)
        val running = s.all<ChatItem.Compaction>().single()
        assertEquals(CompactionPhase.RUNNING, running.phase)
        assertEquals("manual", running.reason)
        assertEquals(ChatReducer.COMPACTING, s.statusLine)

        val result = """{"summary":${RecordCodec.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(summary))},"firstKeptEntryId":"e5","tokensBefore":150000,"estimatedTokensAfter":32000}"""
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"manual","result":$result,"aborted":false,"willRetry":false}"""), 0)
        // compact 的响应在事件之后到达：同一张卡，不重复
        val resp = ExtraResponses.compaction(data("""{"id":"c-3","type":"response","command":"compact","success":true,"data":$result}"""))!!
        s = r.applyCompaction(s, resp, "manual")
        val card = s.all<ChatItem.Compaction>().single()
        assertEquals(CompactionPhase.DONE, card.phase)
        assertEquals(150_000L, card.tokensBefore)
        assertEquals(32_000L, card.tokensAfter)
        assertEquals("## 目标\n做测试", card.summary)
        assertEquals(listOf("README.md"), card.readFiles)
        assertEquals(listOf("src/a.kt", "src/b.kt"), card.modifiedFiles)
        assertNull("压缩后上下文占用未知", s.contextPercent)
        assertNull(s.statusLine)
    }

    @Test
    fun compactResponseWithoutEventsStillMakesCard() {
        val r = ChatReducer()
        val resp = ExtraResponses.compaction(RecordCodec.json.parseToJsonElement("""{"summary":"s","tokensBefore":10,"estimatedTokensAfter":2}"""))!!
        val s = r.applyCompaction(ChatState(), resp)
        val card = s.all<ChatItem.Compaction>().single()
        assertEquals(10L, card.tokensBefore)
        assertEquals(2L, card.tokensAfter)
    }

    @Test
    fun compactionFailureAndAbortLeaveNoticeNotCard() {
        val r = ChatReducer()
        var s = r.reduce(ChatState(), ev("""{"type":"compaction_start","reason":"manual"}"""), 0)
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false,"errorMessage":"Compaction failed: Nothing to compact"}"""), 0)
        assertTrue(s.all<ChatItem.Compaction>().isEmpty())
        assertTrue(s.all<ChatItem.Notice>().single().text.startsWith("压缩上下文失败"))
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"threshold"}"""), 0)
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"threshold","aborted":true,"willRetry":false}"""), 0)
        assertTrue(s.all<ChatItem.Compaction>().isEmpty())
        assertEquals("已取消压缩上下文", s.all<ChatItem.Notice>().last().text)
    }

    @Test
    fun compactionSummaryFromHistory() {
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"compactionSummary","summary":"正文\n\n<read-files>\na.md\n</read-files>","tokensBefore":9000,"timestamp":"2026-11-03T00:00:00Z"}]""",
        ) as JsonArray
        val c = ChatReducer().fromMessages(msgs).all<ChatItem.Compaction>().single()
        assertEquals(CompactionPhase.DONE, c.phase)
        assertEquals(9000L, c.tokensBefore)
        assertEquals("正文", c.summary)
        assertEquals(listOf("a.md"), c.readFiles)
    }

    // ------------------------------------------------------------------ 图片（C1）

    @Test
    fun imageBubbleIsConfirmedByTextAndImageSignature() {
        val r = ChatReducer()
        val img = RpcImage("iVBORw0KGgo=", "image/png")
        var s = r.addPendingUser(ChatState(), "local-0", "这是什么", listOf(img))
        s = r.reduce(
            s,
            ev("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"这是什么"},{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}],"timestamp":1}}"""),
            0,
        )
        val u = s.all<ChatItem.User>().single()
        assertEquals("local-0", u.key)
        assertEquals(Delivery.SENT, u.delivery)
        assertEquals(listOf(img), r.userImages["local-0"])
        // 历史重建也带回图片
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"user","content":[{"type":"text","text":"看"},{"type":"image","data":"AAAA","mimeType":"image/jpeg"}]}]""",
        ) as JsonArray
        val r2 = ChatReducer()
        val st = r2.fromMessages(msgs)
        val key = st.all<ChatItem.User>().single().key
        assertEquals(listOf(RpcImage("AAAA", "image/jpeg")), r2.userImages[key])
    }

    @Test
    fun differentImageDoesNotConfirmWrongBubble() {
        val r = ChatReducer()
        var s = r.addPendingUser(ChatState(), "local-0", "图", listOf(RpcImage("AAAA", "image/png")))
        s = r.addPendingUser(s, "local-1", "图", listOf(RpcImage("BBBB", "image/png")))
        s = r.reduce(
            s,
            ev("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"图"},{"type":"image","data":"BBBB","mimeType":"image/png"}]}}"""),
            0,
        )
        val users = s.all<ChatItem.User>()
        assertEquals(Delivery.SENT, users.single { it.key == "local-1" }.delivery)
        assertEquals(Delivery.SENDING, users.single { it.key == "local-0" }.delivery)
    }

    // ------------------------------------------------------------------ 响应解析

    @Test
    fun statsKeepNullPercentAfterCompaction() {
        val st = ExtraResponses.stats(
            data(
                """{"id":"c-1","type":"response","command":"get_session_stats","success":true,"data":{"sessionFile":"/s.jsonl","sessionId":"x","userMessages":2,"assistantMessages":3,"toolCalls":4,"toolResults":4,"totalMessages":13,"tokens":{"input":100,"output":20,"cacheRead":5,"cacheWrite":1,"total":126},"cost":0.0123,"contextUsage":{"tokens":null,"contextWindow":32000,"percent":null}}}""",
            ),
        )
        assertEquals(126L, st.tokens.total)
        assertEquals(4, st.toolCalls)
        assertEquals(0.0123, st.cost, 1e-9)
        assertEquals(32000L, st.contextUsage!!.contextWindow)
        assertNull(st.contextUsage!!.percent)
        assertNull(st.contextUsage!!.tokens)
        // 旧解析（EngineClient.stats）同样给 null
        assertNull(Responses.stats(st.raw).contextPercent)
    }

    @Test
    fun parsesCommandsForkTreeEntries() {
        val cmds = ExtraResponses.commands(
            data(
                """{"id":"c-1","type":"response","command":"get_commands","success":true,"data":{"commands":[{"name":"x","description":"模板 x","source":"prompt","sourceInfo":{"path":"/root/.pi/agent/prompts/x.md","source":"local","scope":"user","origin":"top-level"}},{"name":"skill:demo","source":"skill","sourceInfo":{"path":"/s/SKILL.md","source":"local","scope":"project","origin":"top-level"}},{"description":"无名"}]}}""",
            ),
        )
        assertEquals(listOf("x", "skill:demo"), cmds.map { it.name })
        assertEquals("/root/.pi/agent/prompts/x.md", cmds[0].path)
        assertEquals("project", cmds[1].scope)

        val fork = ExtraResponses.fork(data("""{"id":"c-2","type":"response","command":"fork","success":true,"data":{"text":"第一句","cancelled":false}}"""))
        assertEquals("第一句", fork.text)
        assertFalse(fork.cancelled)
        val fm = ExtraResponses.forkMessages(data("""{"id":"c-3","type":"response","command":"get_fork_messages","success":true,"data":{"messages":[{"entryId":"e1","text":"a"},{"entryId":"e3","text":"b"}]}}"""))
        assertEquals(listOf("e1", "e3"), fm.map { it.entryId })

        val tree = ExtraResponses.tree(
            data(
                """{"id":"c-4","type":"response","command":"get_tree","success":true,"data":{"leafId":"e3","tree":[{"entry":{"id":"e1","parentId":null,"type":"message","timestamp":"t","message":{"role":"user","content":"你好"}},"children":[{"entry":{"id":"e2","parentId":"e1","type":"message","timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":"嗨"}]}},"children":[{"entry":{"id":"e3","parentId":"e2","type":"compaction","timestamp":"t","summary":"摘要"},"children":[]}]},{"entry":{"id":"e4","parentId":"e1","type":"label","timestamp":"t","label":"分支"},"children":[],"label":"分支"}]}]}}""",
            ),
        )
        assertEquals("e3", tree.leafId)
        val root = tree.roots.single()
        assertEquals("user", root.entry.role)
        assertEquals("你好", root.entry.preview)
        assertEquals(2, root.children.size)
        assertEquals("嗨", root.children[0].entry.preview)
        assertEquals("[压缩] 摘要", root.children[0].children.single().entry.preview)
        assertEquals("分支", root.children[1].label)

        val entries = ExtraResponses.entries(data("""{"id":"c-5","type":"response","command":"get_entries","success":true,"data":{"entries":[{"id":"e5","parentId":"e4","type":"message","message":{"role":"bashExecution","command":"ls"}}],"leafId":"e5"}}"""))
        assertEquals("$ ls", entries.entries.single().preview)
        assertEquals("e5", entries.leafId)

        assertEquals("/tmp/x.html", ExtraResponses.exportPath(data("""{"id":"c-6","type":"response","command":"export_html","success":true,"data":{"path":"/tmp/x.html"}}""")))
        assertNull(ExtraResponses.lastAssistantText(data("""{"id":"c-7","type":"response","command":"get_last_assistant_text","success":true,"data":{"text":null}}""")))
        assertEquals("答", ExtraResponses.lastAssistantText(data("""{"id":"c-8","type":"response","command":"get_last_assistant_text","success":true,"data":{"text":"答"}}""")))
    }

    @Test
    fun veryDeepLinearTreeDoesNotOverflow() {
        val n = 5_000
        val sb = StringBuilder()
        repeat(n) { i -> sb.append("""{"entry":{"id":"e$i","parentId":null,"type":"message","message":{"role":"user","content":"m$i"}},"children":[""") }
        repeat(n) { sb.append("]}") }
        // 与 RpcChannel 的 stdout 读线程一样用大栈解析
        var parsed: kotlinx.serialization.json.JsonElement? = null
        val t = Thread(null, { parsed = RecordCodec.json.parseToJsonElement("""{"tree":[$sb],"leafId":"e${n - 1}"}""") }, "deep", RpcChannel.READER_STACK_BYTES)
        t.start()
        t.join()
        val tree = ExtraResponses.tree(parsed)
        fun count(nodes: List<io.github.zr987459.pigo.contract.rpc.SessionTreeNodeRecord>): Int {
            var c = 0
            val stack = ArrayDeque(nodes)
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                c++
                x.children.forEach { stack.addLast(it) }
            }
            return c
        }
        assertEquals(n, count(tree.roots))
    }

    @Test
    fun absurdlyDeepRecordIsMalformedNotCrash() {
        val deep = "[".repeat(200_000) + "]".repeat(200_000)
        assertTrue(RecordCodec.decode("""{"type":"x","v":$deep}""") is RecordCodec.Decoded.Malformed)
    }

    @Test
    fun compactionSummaryParser() {
        val p = CompactionSummary.parse(summary)
        assertEquals("## 目标\n做测试", p.body)
        assertEquals(listOf("README.md"), p.readFiles)
        assertEquals(listOf("src/a.kt", "src/b.kt"), p.modifiedFiles)
        // 正文中间的标签不算元数据
        val q = CompactionSummary.parse("说明 <read-files>x</read-files> 结束")
        assertEquals("说明 <read-files>x</read-files> 结束", q.body)
        assertTrue(q.readFiles.isEmpty())
    }

    @Suppress("unused")
    private fun obj(s: String) = RecordCodec.json.parseToJsonElement(s) as JsonObject
}
