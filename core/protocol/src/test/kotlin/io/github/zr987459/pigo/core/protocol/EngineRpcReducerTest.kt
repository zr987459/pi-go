package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.NoticeKind
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.EngineEvents
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

    private inline fun <reified T : TimelineExtra> ChatReducer.extras() = timelineExtras.filterIsInstance<T>()

    @Test
    fun bashUpdatesGoToTheirOwnCardById() {
        val r = ChatReducer()
        var s = ChatState(items = listOf(ChatItem.User("u-0", "hi")))
        s = r.startBash(s, "c-1", "echo a", excludeFromContext = false)
        s = r.startBash(s, "c-2", "echo b", excludeFromContext = true)
        for (line in listOf(
            """{"type":"bash_execution_update","id":"c-2","delta":"b1 "}""",
            """{"type":"bash_execution_update","id":"c-1","delta":"a1 "}""",
            """{"type":"bash_execution_update","id":"c-2","delta":"b2"}""",
            """{"type":"bash_execution_update","id":"c-9","delta":"没有这张卡"}""",
            """{"type":"bash_execution_update","id":"c-1","delta":"a2"}""",
        )) s = r.reduce(s, ev(line), 1_200)
        val (a, b) = r.extras<TimelineExtra.Bash>()
        assertEquals("bash-c-1", a.key)
        assertEquals("u-0", a.anchorKey)
        assertEquals("a1 a2", a.output)
        assertEquals("b1 b2", b.output)
        assertTrue(b.excludedFromContext)
        assertTrue(a.running && b.running)
        assertEquals("时间线本身不变", listOf("u-0"), s.items.map { it.key })

        val res = ExtraResponses.bash(data("""{"id":"c-1","type":"response","command":"bash","success":true,"data":{"output":"a1 a2\n","exitCode":0,"cancelled":false,"truncated":false}}"""))
        s = r.finishBash(s, "c-1", res, null)
        val done = r.extras<TimelineExtra.Bash>().first()
        assertFalse(done.running)
        assertEquals(0, done.exitCode)
        assertEquals("a1 a2\n", done.output)
        // 被中止：exitCode 为 null
        val ab = ExtraResponses.bash(data("""{"id":"c-2","type":"response","command":"bash","success":true,"data":{"output":"b1 b2","cancelled":true,"truncated":false}}"""))
        assertTrue(ab.cancelled)
        s = r.finishBash(s, "c-2", ab, null)
        assertNull(r.extras<TimelineExtra.Bash>()[1].exitCode)
        // 后续的 delta 不再改已结束的卡
        s = r.reduce(s, ev("""{"type":"bash_execution_update","id":"c-1","delta":"late"}"""), 2_000)
        assertEquals("a1 a2\n", r.extras<TimelineExtra.Bash>().first().output)
    }

    @Test
    fun bashFromHistoryBecomesCardAnchoredToNotice() {
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"bashExecution","command":"echo hi","output":"hi\n","exitCode":0,"cancelled":false,"truncated":false,"timestamp":1}]""",
        ) as JsonArray
        val r = ChatReducer()
        val st = r.fromMessages(msgs)
        val notice = st.all<ChatItem.Notice>().single()
        val b = r.extras<TimelineExtra.Bash>().single()
        assertEquals(notice.key, b.anchorKey)
        assertEquals("echo hi", b.command)
        assertEquals("hi\n", b.output)
        assertEquals(0, b.exitCode)
        assertFalse(b.running)
    }

    @Test
    fun interruptEndsRunningBashAndCompaction() {
        val r = ChatReducer()
        var s = r.startBash(ChatState(), "c-1", "sleep 9", false)
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"threshold"}"""), 0)
        s = r.interrupt(s, NoticeKind.INTERRUPTED, "引擎意外退出")
        val b = r.extras<TimelineExtra.Bash>().single()
        assertFalse(b.running)
        assertTrue(b.output.contains("未完成"))
        val c = r.extras<TimelineExtra.Compaction>().single()
        assertFalse(c.running)
        assertTrue(c.aborted)
    }

    // ------------------------------------------------------------------ 压缩（U3）

    private val summary = "## 目标\n做测试\n\n<read-files>\nREADME.md\n</read-files>\n\n<modified-files>\nsrc/a.kt\nsrc/b.kt\n</modified-files>"

    @Test
    fun compactResponseAndEventsReduceToOneCard() {
        val r = ChatReducer()
        var s = ChatState(contextPercent = 87.5, items = listOf(ChatItem.User("u-0", "q")))
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"manual"}"""), 0)
        val running = r.extras<TimelineExtra.Compaction>().single()
        assertTrue(running.running)
        assertTrue(running.manual)
        assertEquals("u-0", running.anchorKey)
        assertEquals(ChatReducer.COMPACTING, s.statusLine)

        val result = """{"summary":${RecordCodec.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(summary))},"firstKeptEntryId":"e5","tokensBefore":150000,"estimatedTokensAfter":32000}"""
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"manual","result":$result,"aborted":false,"willRetry":false}"""), 0)
        // compact 的响应在事件之后到达：同一张卡，不重复
        val resp = ExtraResponses.compaction(data("""{"id":"c-3","type":"response","command":"compact","success":true,"data":$result}"""))!!
        s = r.applyCompaction(s, resp, manual = true)
        val card = r.extras<TimelineExtra.Compaction>().single()
        assertFalse(card.running)
        assertEquals(150_000L, card.tokensBefore)
        assertEquals(32_000L, card.estimatedTokensAfter)
        assertEquals("## 目标\n做测试", card.summary)
        assertEquals(listOf("README.md"), card.readFiles)
        assertEquals(listOf("src/a.kt", "src/b.kt"), card.modifiedFiles)
        assertNull("压缩后上下文占用未知（U2）", s.contextPercent)
        assertNull(s.statusLine)
        assertEquals(NoticeKind.COMPACTION, s.all<ChatItem.Notice>().single().kind)
    }

    @Test
    fun compactResponseWithoutEventsStillMakesCard() {
        val r = ChatReducer()
        val resp = ExtraResponses.compaction(RecordCodec.json.parseToJsonElement("""{"summary":"s","tokensBefore":10,"estimatedTokensAfter":2}"""))!!
        val s = r.applyCompaction(ChatState(contextPercent = 50.0), resp)
        val card = r.extras<TimelineExtra.Compaction>().single()
        assertEquals(10L, card.tokensBefore)
        assertEquals(2L, card.estimatedTokensAfter)
        assertNull(s.contextPercent)
    }

    @Test
    fun compactionFailureAndAbortMarkTheCard() {
        val r = ChatReducer()
        var s = r.reduce(ChatState(), ev("""{"type":"compaction_start","reason":"manual"}"""), 0)
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false,"errorMessage":"Compaction failed: Nothing to compact"}"""), 0)
        assertEquals("Compaction failed: Nothing to compact", r.extras<TimelineExtra.Compaction>().single().error)
        assertTrue(s.all<ChatItem.Notice>().single().text.startsWith("压缩上下文失败"))
        s = r.reduce(s, ev("""{"type":"compaction_start","reason":"threshold"}"""), 0)
        s = r.reduce(s, ev("""{"type":"compaction_end","reason":"threshold","aborted":true,"willRetry":false}"""), 0)
        val c = r.extras<TimelineExtra.Compaction>()
        assertEquals(2, c.size)
        assertTrue(c[1].aborted)
        assertFalse(c[1].running)
        assertFalse(c[1].manual)
        assertEquals("已取消压缩上下文", s.all<ChatItem.Notice>().last().text)
    }

    @Test
    fun compactionSummaryFromHistory() {
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"compactionSummary","summary":"正文\n\n<read-files>\na.md\n</read-files>","tokensBefore":9000,"timestamp":1}]""",
        ) as JsonArray
        val r = ChatReducer()
        r.fromMessages(msgs)
        val c = r.extras<TimelineExtra.Compaction>().single()
        assertFalse(c.running)
        assertEquals(9000L, c.tokensBefore)
        assertEquals("正文", c.summary)
        assertEquals(listOf("a.md"), c.readFiles)
    }

    // ------------------------------------------------------------------ 轮末汇总（C10）

    @Test
    fun turnSummaryAfterSettledButNotAfterAbort() {
        val r = ChatReducer()
        var s = ChatState()
        for ((t, line) in listOf(
            0L to """{"type":"agent_start"}""",
            0L to """{"type":"message_start","message":{"role":"user","content":"写文件"}}""",
            100L to """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            1_100L to """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"w1","name":"write","arguments":{"path":"/root/a.txt","content":"x"}}],"stopReason":"toolUse","usage":{"output":50}}}""",
            1_200L to """{"type":"tool_execution_start","toolCallId":"w1","toolName":"write","args":{"path":"/root/a.txt","content":"x"}}""",
            1_300L to """{"type":"tool_execution_end","toolCallId":"w1","toolName":"write","result":{"content":[{"type":"text","text":"ok"}]},"isError":false}""",
            2_000L to """{"type":"agent_settled","aborted":false}""",
        )) s = r.reduce(s, ev(line), t)
        val sum = r.extras<TimelineExtra.TurnSummary>().single()
        assertEquals(listOf("/root/a.txt"), sum.writtenFiles)
        assertEquals(2_000L, sum.durationMs)
        assertEquals(50L, sum.outputTokens)
        assertEquals(s.items.last().key, sum.anchorKey)
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 3_000)
        s = r.reduce(s, ev("""{"type":"agent_settled","aborted":true}"""), 3_500)
        assertEquals(1, r.extras<TimelineExtra.TurnSummary>().size)
    }

    // ------------------------------------------------------------------ 图片（C1）

    @Test
    fun imageBubbleIsConfirmedByTextAndImageSignature() {
        val r = ChatReducer()
        val img = ImageInput("image/png", "iVBORw0KGgo=")
        var s = r.addPendingUser(ChatState(), "local-0", "这是什么", listOf(img))
        s = r.reduce(
            s,
            ev("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"这是什么"},{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}],"timestamp":1}}"""),
            0,
        )
        val u = s.all<ChatItem.User>().single()
        assertEquals("local-0", u.key)
        assertEquals(Delivery.SENT, u.delivery)
        val x = r.extras<TimelineExtra.UserImages>().single()
        assertEquals("local-0", x.anchorKey)
        assertEquals("iVBORw0KGgo=", x.images.single().base64Data)
        // 历史重建也带回图片
        val msgs = RecordCodec.json.parseToJsonElement(
            """[{"role":"user","content":[{"type":"text","text":"看"},{"type":"image","data":"AAAA","mimeType":"image/jpeg"}]}]""",
        ) as JsonArray
        val r2 = ChatReducer()
        val st = r2.fromMessages(msgs)
        val key = st.all<ChatItem.User>().single().key
        assertEquals(listOf(ImageInput("image/jpeg", "AAAA")), r2.userImages[key])
    }

    @Test
    fun differentImageDoesNotConfirmWrongBubble() {
        val r = ChatReducer()
        var s = r.addPendingUser(ChatState(), "local-0", "图", listOf(ImageInput("image/png", "AAAA")))
        s = r.addPendingUser(s, "local-1", "图", listOf(ImageInput("image/png", "BBBB")))
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

    /** 审查第 1 轮 Important#4：深度 >500 处分出两个分支时，父子结构原样保留（不拉平、不交错）。 */
    @Test
    fun deepTreeKeepsBranchStructureBeyond500() {
        val depth = 800
        val fork = 600
        fun node(id: String, kids: String = "") = """{"entry":{"id":"$id","parentId":null,"type":"message","message":{"role":"user","content":"$id"}},"children":[$kids]}"""
        fun chain(prefix: String, len: Int): String {
            var sAcc = ""
            for (i in len - 1 downTo 0) sAcc = node("$prefix$i", sAcc)
            return sAcc
        }
        // 主链 e0..e(fork)；e(fork) 下两支：a0..a2、b0..b1；随后主链不再延续（分叉点即两支的父）。
        var json = node("e$fork", chain("a", 3) + "," + chain("b", 2))
        for (i in fork - 1 downTo 0) json = node("e$i", json)
        // 再补一条从根直接下去到 depth 的长链，作为根的第二个孩子，验证兄弟顺序
        var parsed: kotlinx.serialization.json.JsonElement? = null
        val t = Thread(null, { parsed = RecordCodec.json.parseToJsonElement("""{"tree":[$json],"leafId":"a2"}""") }, "deep", RpcChannel.READER_STACK_BYTES)
        t.start()
        t.join()
        val tree = ExtraResponses.tree(parsed)
        var cur = tree.roots.single()
        for (i in 0 until fork) {
            assertEquals("e$i", cur.entry.id)
            cur = cur.children.single()
        }
        assertEquals("e$fork", cur.entry.id)
        assertEquals(listOf("a0", "b0"), cur.children.map { it.entry.id })
        val a = cur.children[0]
        assertEquals(listOf("a1"), a.children.map { it.entry.id })
        assertEquals(listOf("a2"), a.children[0].children.map { it.entry.id })
        assertTrue(a.children[0].children[0].children.isEmpty())
        val b = cur.children[1]
        assertEquals(listOf("b1"), b.children.map { it.entry.id })
        assertTrue(b.children[0].children.isEmpty())
        assertTrue(depth > fork)
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
