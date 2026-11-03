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

    @Test
    fun messageMetaFromEntriesAndEntryAppended() = runBlocking {
        val rt = ScriptedRuntime {
            { cmd ->
                if (cmd.str("type") == "prompt") {
                    ok(cmd, """{"disposition":"started"}""")
                    emit("""{"type":"agent_start"}""")
                    emit("""{"type":"message_start","message":{"role":"user","content":"第二句"}}""")
                    emit("""{"type":"entry_appended","entry":{"type":"message","id":"e3","parentId":"e2","message":{"role":"user","content":"第二句"}}}""")
                    emit("""{"type":"message_start","message":{"role":"assistant","content":[]}}""")
                    emit("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"答二"}],"stopReason":"stop"}}""")
                    emit("""{"type":"entry_appended","entry":{"type":"message","id":"e4","parentId":"e3","message":{"role":"assistant","provider":"p","model":"m","content":[],"usage":{"output":7,"cost":{"total":0.25}}}}}""")
                    later(30) { emit("""{"type":"agent_settled","aborted":false}""") }
                } else {
                    basics(cmd)
                }
            }
        }
        val e = engine(rt)
        assertTrue(e.openSession("/root/.pi/agent/sessions/x.jsonl", "/root/pi-cwd") is Outcome.Ok)
        waitFor { e.messageMeta.value.size == 2 }
        val (u0, a0) = e.chat.value.items
        assertEquals("e1", e.messageMeta.value.getValue(u0.key).entryId)
        assertTrue(e.messageMeta.value.getValue(u0.key).forkable)
        assertEquals(0.5, e.messageMeta.value.getValue(a0.key).costUsd!!, 1e-9)
        e.send("第二句")
        waitFor { !e.chat.value.running && e.messageMeta.value.size == 4 }
        val items = e.chat.value.items
        assertEquals("e3", e.messageMeta.value.getValue(items[2].key).entryId)
        val last = e.messageMeta.value.getValue(items[3].key)
        assertEquals("e4", last.entryId)
        assertEquals(7L, last.usage!!.output)
        assertFalse(last.forkable)
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
}
