package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.BashEvent
import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.QueueMode
import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import io.github.zr987459.pigo.contract.TimelineExtra
import io.github.zr987459.pigo.core.protocol.str
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** feat/engine-rpc：PiEngine 的 EngineExtras 实现（用脚本化的假 pi）。 */
class PiEngineExtrasTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = ListLog()
    private val fast = EngineConfig(frameMs = 0, bootTimeoutMs = 2_000, abortTimeoutMs = 300, defaultTimeoutMs = 1_000, getMessagesTimeoutMs = 1_000, stopGraceMs = 200)

    @After
    fun tearDown() = scope.cancel()

    private fun engine(rt: ScriptedRuntime) = PiEngine(rt, StaticEnv(), log, scope, fast)

    private suspend fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        withTimeout(timeoutMs) { while (!cond()) delay(10) }
    }

    private val history = """{"messages":[{"role":"user","content":"第一句"},{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"答一"}],"stopReason":"stop","usage":{"input":1,"output":2,"totalTokens":3,"cost":{"total":0.5}}}]}"""
    private val entries = """{"entries":[{"type":"message","id":"e1","parentId":null,"message":{"role":"user","content":"第一句"}},{"type":"message","id":"e2","parentId":"e1","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[],"usage":{"input":1,"output":2,"totalTokens":3,"cost":{"total":0.5}}}}],"leafId":"e2"}"""

    private val basics: ScriptedPi.(JsonObject) -> Unit = { cmd ->
        when (cmd.str("type")) {
            "get_state" -> ok(cmd, ScriptedPi.STATE)
            "get_messages" -> ok(cmd, history)
            "get_entries" -> ok(cmd, entries)
            "get_session_stats" -> ok(cmd, """{"contextUsage":{"tokens":null,"contextWindow":64000,"percent":null},"tokens":{"input":1,"output":2,"total":3},"cost":0.5}""")
            else -> ok(cmd)
        }
    }

    @Test
    fun promptWithImagesAndRunOutcomes() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"started"}""")
                    emit("""{"type":"agent_start"}""")
                    emit("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"这是什么"},{"type":"image","data":"iVBORw0KGgo=","mimeType":"image/png"}]}}""")
                    emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                    emit("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"一张图"}],"stopReason":"stop","usage":{"output":3}}}""")
                    later(100) { emit("""{"type":"agent_settled","aborted":false}""") }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        val outcomes = CopyOnWriteArrayList<RunOutcome>()
        scope.launch { e.runOutcomes.collect { outcomes += it } }
        assertTrue(e.start() is Outcome.Ok)
        assertTrue(e.send("这是什么", listOf(ImageInput("image/png", "iVBORw0KGgo="))) is Outcome.Ok)
        val sent = rt.last.received.last { it.str("type") == "prompt" }
        val img = (sent["images"] as JsonArray).single() as JsonObject
        assertEquals("image", img.str("type"))
        assertEquals("iVBORw0KGgo=", img.str("data"))
        assertEquals("image/png", img.str("mimeType"))
        waitFor { outcomes.isNotEmpty() }
        val o = outcomes.single()
        assertEquals(RunOutcomeKind.COMPLETED, o.kind)
        assertEquals("一张图", o.lastTextPreview)
        assertEquals("/root/pi-cwd", o.cwd)
        // 带图气泡被确认，图片以附加项挂在气泡上；本轮有轮末汇总
        waitFor { e.timelineExtras.value.any { it is TimelineExtra.TurnSummary } }
        val user = e.chat.value.items.first() as ChatItem.User
        val pics = e.timelineExtras.value.filterIsInstance<TimelineExtra.UserImages>().single()
        assertEquals(user.key, pics.anchorKey)
        e.close()
    }

    @Test
    fun abortedRunEmitsAbortedOutcome() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"started"}""")
                    emit("""{"type":"agent_start"}""")
                    later(50) { emit("""{"type":"agent_settled","aborted":true}""") }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        val outcomes = CopyOnWriteArrayList<RunOutcome>()
        scope.launch { e.runOutcomes.collect { outcomes += it } }
        e.send("x")
        waitFor { outcomes.isNotEmpty() }
        assertEquals(RunOutcomeKind.ABORTED, outcomes.single().kind)
        e.close()
    }

    @Test
    fun statsKeepsNullPercentInsteadOfOldValue() = runBlocking {
        var percent = "40.0"
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "get_session_stats") ok(cmd, """{"contextUsage":{"percent":$percent},"tokens":{"total":9},"cost":0.1}""") else basics(cmd)
            }
        }
        val e = engine(rt)
        e.start()
        e.stats()
        waitFor { e.chat.value.contextPercent == 40.0 }
        percent = "null"
        e.stats()
        waitFor { e.chat.value.contextPercent == null }
        assertNull(e.usageState.value!!.context!!.percent)
        e.close()
    }

    @Test
    fun compactProducesCardAndClearsPercent() = runBlocking {
        val result = """{"summary":"摘要\n\n<modified-files>\na.kt\n</modified-files>","firstKeptEntryId":"e2","tokensBefore":150000,"estimatedTokensAfter":32000}"""
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "compact" -> {
                        emit("""{"type":"compaction_start","reason":"manual"}""")
                        emit("""{"type":"compaction_end","reason":"manual","result":$result,"aborted":false,"willRetry":false}""")
                        later(20) { ok(cmd, result) }
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.start()
        val r = e.compact("只保留结论")
        assertTrue(r is Outcome.Ok)
        assertEquals(32_000L, (r as Outcome.Ok).value.estimatedTokensAfter)
        assertEquals("只保留结论", rt.last.received.last { it.str("type") == "compact" }.str("customInstructions"))
        waitFor { e.timelineExtras.value.any { it is TimelineExtra.Compaction && !it.running } }
        val card = e.timelineExtras.value.filterIsInstance<TimelineExtra.Compaction>().single()
        assertEquals(150_000L, card.tokensBefore)
        assertEquals(listOf("a.kt"), card.modifiedFiles)
        assertTrue(card.manual)
        assertNull(e.chat.value.contextPercent)
        e.close()
    }

    @Test
    fun bashStreamsByIdAndEndsWithDone() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "bash") {
                    val id = cmd.str("id")
                    emit("""{"type":"bash_execution_update","id":"$id","delta":"h"}""")
                    emit("""{"type":"bash_execution_update","id":"other","delta":"别人的"}""")
                    later(50) {
                        emit("""{"type":"bash_execution_update","id":"$id","delta":"i\n"}""")
                        later(50) { ok(cmd, """{"output":"hi\n","exitCode":0,"cancelled":false,"truncated":false}""") }
                    }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.start()
        val events = e.bash("echo hi", excludeFromContext = true).toList()
        val done = events.last() as BashEvent.Done
        assertEquals("hi\n", done.result.output)
        assertEquals(0, done.result.exitCode)
        assertTrue(events.filterIsInstance<BashEvent.Output>().none { it.chunk == "别人的" })
        val sent = rt.last.received.last { it.str("type") == "bash" }
        assertEquals("echo hi", sent.str("command"))
        waitFor { e.timelineExtras.value.any { it is TimelineExtra.Bash && !it.running } }
        val card = e.timelineExtras.value.filterIsInstance<TimelineExtra.Bash>().single()
        assertEquals("bash-" + sent.str("id"), card.key)
        assertEquals("hi\n", card.output)
        assertTrue(card.excludedFromContext)
        e.close()
    }

    /**
     * 按 pi 1.5.0 的真实序列：普通消息在 message_end 后写盘、**不发 entry_appended**；自动重试时失败的助手消息留在原始历史，
     * 由 context_edit（replacement=null，发 entry_appended）从投影排除。get_entries 只返回已写盘的条目，支持 since。
     */
    @Test
    fun messageMetaFollowsReal150SequenceWithAutoRetry() = runBlocking {
        val persisted = java.util.Collections.synchronizedList(mutableListOf(
            """{"type":"message","id":"e1","parentId":null,"message":{"role":"user","content":"第一句"}}""",
            """{"type":"message","id":"e2","parentId":"e1","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[],"usage":{"input":1,"output":2,"totalTokens":3,"cost":{"total":0.5}}}}""",
        ))
        val ids = listOf("e1", "e2", "e3", "e4", "x1", "e5")
        fun entriesJson(since: String?): String = synchronized(persisted) {
            val all = persisted.toList()
            val from = if (since == null) 0 else ids.indexOf(since) + 1
            """{"entries":[${all.drop(from).joinToString(",")}],"leafId":"${ids[all.size - 1]}"}"""
        }
        val failed = """{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"半截"}],"stopReason":"error","errorMessage":"overloaded","usage":{"input":3,"output":1,"totalTokens":4,"cost":{"total":0.01}}}"""
        val good = """{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"答二"}],"stopReason":"stop","usage":{"input":3,"output":8,"totalTokens":11,"cost":{"total":0.25}}}"""
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "get_entries" -> ok(cmd, entriesJson(cmd.str("since")))
                    "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":"第二句"}}""")
                        emit("""{"type":"message_end","message":{"role":"user","content":"第二句"}}""")
                        persisted += """{"type":"message","id":"e3","parentId":"e2","message":{"role":"user","content":"第二句"}}"""
                        later(60) {
                            emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                            emit("""{"type":"message_end","message":$failed}""")
                            persisted += """{"type":"message","id":"e4","parentId":"e3","message":$failed}"""
                            emit("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":50,"errorMessage":"overloaded"}""")
                            val edit = """{"type":"context_edit","id":"x1","parentId":"e4","targetId":"e4","replacement":null}"""
                            persisted += edit
                            emit("""{"type":"entry_appended","entry":$edit}""")
                            later(80) {
                                emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                                emit("""{"type":"message_end","message":$good}""")
                                persisted += """{"type":"message","id":"e5","parentId":"x1","message":$good}"""
                                emit("""{"type":"auto_retry_end","success":true,"attempt":1}""")
                                emit("""{"type":"agent_end","messages":[]}""")
                                later(30) { emit("""{"type":"agent_settled","aborted":false}""") }
                            }
                        }
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd") is Outcome.Ok)
        waitFor { e.messageMeta.value.size == 2 }
        val (u0, a0) = e.chat.value.items
        assertEquals("e1", e.messageMeta.value.getValue(u0.key).entryId)
        assertEquals(0.5, e.messageMeta.value.getValue(a0.key).costUsd!!, 1e-9)

        // 整轮过程中任何一刻：除 u0 外没有气泡配到 e1，除 a0 外没有助手配到 e2；失败消息 e4 永远不被配对。
        val bad = java.util.concurrent.CopyOnWriteArrayList<String>()
        val watch = launch(Dispatchers.Default) {
            e.messageMeta.collect { m ->
                m.forEach { (k, v) ->
                    if (v.entryId == "e1" && k != u0.key) bad += "$k→e1"
                    if (v.entryId == "e2" && k != a0.key) bad += "$k→e2"
                    if (v.entryId == "e4") bad += "$k→e4"
                }
            }
        }
        e.send("第二句")
        waitFor(5_000) { !e.chat.value.running && e.messageMeta.value.values.any { it.entryId == "e5" } }
        watch.cancel()
        assertTrue("错配：$bad", bad.isEmpty())
        val items = e.chat.value.items
        val meta = e.messageMeta.value
        val u1 = items.filterIsInstance<ChatItem.User>().last()
        assertEquals("e3", meta.getValue(u1.key).entryId)
        assertTrue(meta.getValue(u1.key).forkable)
        val ok = items.filterIsInstance<ChatItem.Assistant>().last()
        assertEquals("e5", meta.getValue(ok.key).entryId)
        assertEquals(8L, meta.getValue(ok.key).usage!!.output)
        assertEquals(0.25, meta.getValue(ok.key).costUsd!!, 1e-9)
        assertEquals("e2", meta.getValue(a0.key).entryId)
        // 增量：首次全量后，后续 get_entries 都带 since
        val gets = rt.last.received.filter { it.str("type") == "get_entries" }
        assertTrue(gets.size >= 2)
        assertTrue(gets.drop(1).any { it.str("since") != null })
        e.close()
    }

    @Test
    fun forkReloadsTimelineAndModesAndCycleModel() = runBlocking {
        var forked = false
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "fork" -> {
                        forked = true
                        ok(cmd, """{"text":"第一句","cancelled":false}""")
                    }
                    "get_messages" -> ok(cmd, if (forked) """{"messages":[]}""" else history)
                    "cycle_model" -> ok(cmd, """{"model":{"provider":"deepseek","id":"deepseek-reasoner","reasoning":true},"thinkingLevel":"high","isScoped":true}""")
                    "get_commands" -> ok(cmd, """{"commands":[{"name":"x","source":"prompt","sourceInfo":{"path":"/root/.pi/agent/prompts/x.md","scope":"user","origin":"top-level"}}]}""")
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd")
        val f = e.fork("e1")
        assertEquals("第一句", (f as Outcome.Ok).value.text)
        waitFor { e.chat.value.items.isEmpty() }
        val cm = (e.cycleModelResult() as Outcome.Ok).value!!
        assertEquals("deepseek-reasoner", cm.model.id)
        assertEquals("high", cm.thinkingLevel)
        assertTrue(cm.isScoped)
        assertEquals("high", e.chat.value.thinkingLevel)
        assertTrue(e.setFollowUpMode(QueueMode.ALL) is Outcome.Ok)
        assertEquals("all", rt.last.received.last { it.str("type") == "set_follow_up_mode" }.str("mode"))
        assertTrue(e.setAutoRetry(false) is Outcome.Ok)
        assertEquals(false, (e.sessionSettings() as Outcome.Ok).value.autoRetry)
        val cmds = (e.commands() as Outcome.Ok).value
        assertEquals("/x", cmds.single().invocation)
        e.commands()
        assertEquals("按 cwd 缓存", 1, rt.last.received.count { it.str("type") == "get_commands" })
        e.close()
    }

    /**
     * 审查第 2 轮 Important 1：按 pi 1.5.0 真实序列（agent-session.js _prepareRetry / _finishCancelledRetry，真 pi 抓包核对）
     * 在自动重试退避期间停止 / 放弃重试。失败消息有部分内容（时间线保留它），被 context_edit 从投影排除，其后没有助手消息。
     * 失败气泡不得配到任何条目，上一条助手（答1）仍配 e2，下一轮新助手配 e7。
     */
    private fun stopOrAbortDuringRetry(useStop: Boolean) = runBlocking {
        val persisted = java.util.Collections.synchronizedList(mutableListOf(
            """{"type":"message","id":"e1","parentId":null,"message":{"role":"user","content":"第一句"}}""",
            """{"type":"message","id":"e2","parentId":"e1","message":{"role":"assistant","provider":"fake","model":"m","content":[{"type":"text","text":"答1"}],"stopReason":"stop","usage":{"input":1,"output":5,"totalTokens":6,"cost":{"total":0.5}}}}""",
        ))
        val order = listOf("e1", "e2", "e3", "e4", "x1", "e6", "e7")
        fun entriesJson(since: String?, cmd: JsonObject, pi: ScriptedPi) = synchronized(persisted) {
            val all = persisted.toList()
            val ids = all.map { Regex("\"id\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
            if (since != null && since !in ids) { pi.fail(cmd, "Entry not found: $since"); return@synchronized }
            val from = if (since == null) 0 else ids.indexOf(since) + 1
            pi.ok(cmd, """{"entries":[${all.drop(from).joinToString(",")}],"leafId":"${ids.last()}"}""")
        }
        val failed = """{"role":"assistant","provider":"fake","model":"m","content":[{"type":"text","text":"半截2"}],"stopReason":"error","errorMessage":"terminated","usage":{"input":0,"output":0,"totalTokens":0,"cost":{"total":0}}}"""
        val good = """{"role":"assistant","provider":"fake","model":"m","content":[{"type":"text","text":"答3"}],"stopReason":"stop","usage":{"input":2,"output":9,"totalTokens":11,"cost":{"total":0.25}}}"""
        var round = 0
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "get_entries" -> entriesJson(cmd.str("since"), cmd, this)
                    "prompt" -> {
                        round++
                        ok(cmd, """{"disposition":"started"}""")
                        if (round == 1) {
                            emit("""{"type":"agent_start"}""")
                            emit("""{"type":"turn_start"}""")
                            emit("""{"type":"message_start","message":{"role":"user","content":"第二句"}}""")
                            emit("""{"type":"message_end","message":{"role":"user","content":"第二句"}}""")
                            persisted += """{"type":"message","id":"e3","parentId":"e2","message":{"role":"user","content":"第二句"}}"""
                            emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                            emit("""{"type":"message_update","message":{"role":"assistant","content":[{"type":"text","text":"半截2"}]},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"半截2"}}""")
                            emit("""{"type":"message_end","message":$failed}""")
                            persisted += """{"type":"message","id":"e4","parentId":"e3","message":$failed}"""
                            emit("""{"type":"turn_end","message":$failed,"toolResults":[]}""")
                            emit("""{"type":"agent_end","messages":[],"willRetry":true}""")
                            emit("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":10000,"errorMessage":"terminated"}""")
                            val edit = """{"type":"context_edit","id":"x1","parentId":"e4","targetId":"e4","replacement":null}"""
                            persisted += edit
                            emit("""{"type":"entry_appended","entry":$edit}""")
                        } else {
                            emit("""{"type":"agent_start"}""")
                            emit("""{"type":"message_start","message":{"role":"user","content":"第三句"}}""")
                            emit("""{"type":"message_end","message":{"role":"user","content":"第三句"}}""")
                            persisted += """{"type":"message","id":"e6","parentId":"x1","message":{"role":"user","content":"第三句"}}"""
                            emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                            emit("""{"type":"message_end","message":$good}""")
                            persisted += """{"type":"message","id":"e7","parentId":"e6","message":$good}"""
                            emit("""{"type":"turn_end","message":$good,"toolResults":[]}""")
                            emit("""{"type":"agent_end","messages":[],"willRetry":false}""")
                            emit("""{"type":"agent_settled","aborted":false}""")
                        }
                    }
                    // 退避中被打断：_finishCancelledRetry → auto_retry_end{success:false} → agent_settled；abort 在 settled 之后才回包
                    "abort" -> {
                        emit("""{"type":"auto_retry_end","success":false,"attempt":1,"finalError":"Retry cancelled"}""")
                        emit("""{"type":"agent_settled","aborted":true}""")
                        ok(cmd)
                    }
                    "abort_retry" -> {
                        ok(cmd)
                        emit("""{"type":"auto_retry_end","success":false,"attempt":1,"finalError":"Retry cancelled"}""")
                        emit("""{"type":"agent_settled","aborted":false}""")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd") is Outcome.Ok)
        waitFor { e.messageMeta.value.size == 2 }
        val a1 = e.chat.value.items.last { it is ChatItem.Assistant }
        assertEquals("e2", e.messageMeta.value.getValue(a1.key).entryId)
        val bad = CopyOnWriteArrayList<String>()
        val watch = launch(Dispatchers.Default) {
            e.messageMeta.collect { m -> m.forEach { (k, v) -> if (v.entryId == "e2" && k != a1.key) bad += "$k→e2"; if (v.entryId == "e4") bad += "$k→e4" } }
        }
        e.send("第二句")
        waitFor { e.chat.value.retry != null }
        if (useStop) assertTrue(e.stop() is Outcome.Ok) else assertTrue(e.abortRetry() is Outcome.Ok)
        waitFor { !e.chat.value.running && e.chat.value.retry == null && rt.last.received.count { it.str("type") == "get_entries" } >= 3 }
        delay(300)
        val failedBubble = e.chat.value.items.last { it is ChatItem.Assistant } as ChatItem.Assistant
        assertEquals("error", failedBubble.stopReason)
        assertNull("失败气泡不应有 meta", e.messageMeta.value[failedBubble.key])
        assertEquals("e2", e.messageMeta.value.getValue(a1.key).entryId)
        assertEquals("e3", e.messageMeta.value.getValue(e.chat.value.items.last { it is ChatItem.User }.key).entryId)

        e.send("第三句")
        waitFor { !e.chat.value.running && e.messageMeta.value.values.any { it.entryId == "e7" } }
        watch.cancel()
        assertTrue("错配：$bad", bad.isEmpty())
        val meta = e.messageMeta.value
        val a3 = e.chat.value.items.last { it is ChatItem.Assistant }
        assertEquals("e7", meta.getValue(a3.key).entryId)
        assertEquals(9L, meta.getValue(a3.key).usage!!.output)
        assertEquals("e2", meta.getValue(a1.key).entryId)
        assertNull(meta[failedBubble.key])
        e.close()
    }

    @Test
    fun stopDuringRetryBackoffDoesNotShiftMeta() = stopOrAbortDuringRetry(useStop = true)

    @Test
    fun abortRetryDuringBackoffDoesNotShiftMeta() = stopOrAbortDuringRetry(useStop = false)

    /**
     * 审查第 2 轮 Important 2：fork 成功但 get_messages 失败（长会话超 32 MB 或超时）。pi 已是新会话：
     * 旧时间线清空、messageMeta 为空（不再允许按旧 entryId 分叉），旧 lastId 增量拿到 Entry not found 也不会把新 entry 配到旧气泡。
     */
    @Test
    fun forkThenGetMessagesFailureInvalidatesMeta() = runBlocking {
        val history2 = """{"messages":[{"role":"user","content":"第一句"},{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"答一"}],"stopReason":"stop"},{"role":"user","content":"第二句"},{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[{"type":"text","text":"答二"}],"stopReason":"stop"}]}"""
        val old = """{"entries":[{"type":"message","id":"e1","parentId":null,"message":{"role":"user","content":"第一句"}},{"type":"message","id":"e2","parentId":"e1","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[],"stopReason":"stop"}},{"type":"message","id":"e3","parentId":"e2","message":{"role":"user","content":"第二句"}},{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[],"stopReason":"stop"}}],"leafId":"e4"}"""
        // fork(e3)：新会话只有 e3 之前的路径（真 pi 保留原 id）
        val forkedEntries = """{"entries":[{"type":"message","id":"e1","parentId":null,"message":{"role":"user","content":"第一句"}},{"type":"message","id":"e2","parentId":"e1","message":{"role":"assistant","provider":"deepseek","model":"deepseek-chat","content":[],"stopReason":"stop"}}],"leafId":"e2"}"""
        var forked = false
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "fork" -> { forked = true; ok(cmd, """{"text":"第二句","cancelled":false}""") }
                    "get_messages" -> if (forked) fail(cmd, "Response too large") else ok(cmd, history2)
                    "get_entries" -> when {
                        !forked -> ok(cmd, old)
                        cmd.str("since") != null && cmd.str("since") !in setOf("e1", "e2") -> fail(cmd, "Entry not found: ${cmd.str("since")}")
                        else -> ok(cmd, forkedEntries)
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd") is Outcome.Ok)
        waitFor { e.messageMeta.value.size == 4 }
        val oldKeys = e.chat.value.items.filter { it is ChatItem.User || it is ChatItem.Assistant }.map { it.key }.toSet()
        val u2 = e.chat.value.items.last { it is ChatItem.User }
        val f = e.fork(e.messageMeta.value.getValue(u2.key).entryId)
        assertEquals("第二句", (f as Outcome.Ok).value.text)
        waitFor { e.chat.value.items.any { it is ChatItem.Notice } }
        delay(300)
        assertTrue("旧会话气泡应清掉：${e.chat.value.items}", e.chat.value.items.none { it.key in oldKeys })
        assertTrue("messageMeta 应为空：${e.messageMeta.value}", e.messageMeta.value.isEmpty())
        val gets = rt.last.received.filter { it.str("type") == "get_entries" }
        assertNull("fork 后应全量重拉，不拿旧 lastId 增量", gets.last().str("since"))
        e.close()
    }

    /**
     * 审查第 3 轮 Nit 2：全量 get_entries 因会话太大失败后，同一会话内的 message_end 不再触发全量重拉；
     * 手动 entries(null) 成功或换会话后才再拉。
     */
    @Test
    fun tooLargeFullEntriesBacksOffUntilSessionChanges() = runBlocking {
        var tooLarge = true
        val rt = ScriptedRuntime {
            { cmd ->
                when (cmd.str("type")) {
                    "get_entries" -> if (tooLarge && cmd.str("since") == null) fail(cmd, io.github.zr987459.pigo.core.protocol.RpcChannel.TOO_LARGE) else ok(cmd, entries)
                    "prompt" -> {
                        ok(cmd, """{"disposition":"started"}""")
                        emit("""{"type":"agent_start"}""")
                        emit("""{"type":"message_start","message":{"role":"user","content":"再一句"}}""")
                        emit("""{"type":"message_end","message":{"role":"user","content":"再一句"}}""")
                        emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                        emit("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"好"}],"stopReason":"stop"}}""")
                        emit("""{"type":"agent_end","messages":[]}""")
                        emit("""{"type":"agent_settled","aborted":false}""")
                    }
                    else -> basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd") is Outcome.Ok)
        fun fulls() = rt.last.received.count { it.str("type") == "get_entries" && it.str("since") == null }
        waitFor { fulls() >= 1 }
        delay(300)
        val after = fulls()
        repeat(2) {
            e.send("再一句")
            waitFor { !e.chat.value.running }
            delay(200)
        }
        assertEquals("同一会话内不再自动全量重拉", after, fulls())
        assertTrue(e.messageMeta.value.isEmpty())
        // 手动全量重试成功：解除退避，messageMeta 恢复
        tooLarge = false
        assertTrue(e.entries(null) is Outcome.Ok)
        waitFor { e.messageMeta.value.size == 2 }
        // 换会话（epoch 变化）后同样会再拉
        tooLarge = true
        val before = fulls()
        assertTrue(e.openSession("/root/.pi/agent/sessions/y.jsonl", "/root/pi-cwd") is Outcome.Ok)
        waitFor { fulls() > before }
        e.close()
    }
}
