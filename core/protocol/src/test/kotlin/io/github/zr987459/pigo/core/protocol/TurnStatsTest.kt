package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
import io.github.zr987459.pigo.contract.RunOutcome
import io.github.zr987459.pigo.contract.RunOutcomeKind
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.ExtUiRequest
import io.github.zr987459.pigo.contract.ToolStatus
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C10（耗时、速率、改动文件）与 N1（RunResult）的派生。 */
class TurnStatsTest {

    private fun ev(line: String): EngineEvent = (RecordCodec.decode(line) as RecordCodec.Decoded.Record).record as EngineEvent
    private fun args(s: String) = RecordCodec.json.parseToJsonElement(s) as JsonObject

    @Test
    fun writtenFilesOnlyFromSuccessfulWriteToolsInLastTurn() {
        val st = ChatState(
            items = listOf(
                ChatItem.User("u-0", "上一轮"),
                ChatItem.Tool("tool-a", "a", "write", args("""{"path":"old.txt"}"""), ToolStatus.OK),
                ChatItem.User("u-1", "这一轮"),
                ChatItem.Tool("tool-b", "b", "write", args("""{"path":"src/a.kt","content":"x"}"""), ToolStatus.OK),
                ChatItem.Tool("tool-c", "c", "edit", args("""{"path":"/abs/b.kt"}"""), ToolStatus.OK),
                ChatItem.Tool("tool-d", "d", "edit", args("""{"path":"fail.kt"}"""), ToolStatus.ERROR),
                ChatItem.Tool("tool-e", "e", "read", args("""{"path":"read.kt"}"""), ToolStatus.OK),
                ChatItem.Tool("tool-f", "f", "write", args("""{"file_path":"./src/../src/a.kt"}"""), ToolStatus.OK),
                ChatItem.Tool(
                    "tool-g", "g", "apply_patch",
                    args("""{"input":"*** Begin Patch\n*** Add File: n.md\n+x\n*** Delete File: gone.md\n*** End Patch"}"""),
                    ToolStatus.OK,
                ),
            ),
        )
        assertEquals(listOf("/root/pi-cwd/src/a.kt", "/abs/b.kt", "/root/pi-cwd/n.md"), TurnFiles.written(st, "/root/pi-cwd"))
        assertEquals(listOf("src/a.kt", "/abs/b.kt", "./src/../src/a.kt", "n.md"), TurnFiles.written(st, null))
    }

    @Test
    fun durationAndRateFromEvents() {
        val r = ChatReducer()
        var s = ChatState()
        s = r.reduce(s, ev("""{"type":"agent_start"}"""), 10_000)
        s = r.reduce(s, ev("""{"type":"message_start","message":{"role":"assistant","content":[]}}"""), 10_100)
        s = r.reduce(s, ev("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"12345678"}}"""), 11_000)
        // 进行中：已用时间、按字符估算（8 字符≈2 token）
        var live = TurnStats.derive(s, r.turnClock.snapshot(), now = 12_000)
        assertTrue(live.running)
        assertEquals(2_000L, live.durationMs)
        assertEquals(2L, live.outputTokens)
        assertEquals(2.0, live.tokensPerSecond!!, 1e-9)
        // pi 在 message_update 里给了 usage：以它为准
        s = r.reduce(s, ev("""{"type":"message_update","usage":{"output":40},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"9"}}"""), 13_000)
        live = TurnStats.derive(s, r.turnClock.snapshot(), now = 13_000)
        assertEquals(40L, live.outputTokens)
        s = r.reduce(s, ev("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"123456789"}],"usage":{"output":50},"durationMs":2500,"stopReason":"stop"}}"""), 13_100)
        s = r.reduce(s, ev("""{"type":"agent_settled","aborted":false}"""), 14_000)
        val done = TurnStats.derive(s, r.turnClock.snapshot(), now = 99_999)
        assertEquals(false, done.running)
        assertEquals(4_000L, done.durationMs)
        assertEquals(50L, done.outputTokens)
        assertEquals(20.0, done.tokensPerSecond!!, 1e-9) // 50 / 2.5 s
    }

    @Test
    fun emptyClock() {
        val st = TurnStats.derive(ChatState(), TurnClock().snapshot(), 0)
        assertNull(st.durationMs)
        assertNull(st.outputTokens)
        assertNull(st.tokensPerSecond)
    }

    @Test
    fun formatDuration() {
        assertEquals("14s", formatTurnDuration(14_900))
        assertEquals("3m 5s", formatTurnDuration(185_000))
        assertEquals("1h 2m 5s", formatTurnDuration(3_725_000))
    }

    // ------------------------------------------------------------------ RunOutcome（N1）

    @Test
    fun runResults() {
        val ok = ChatState(
            sessionFile = "/s.jsonl",
            items = listOf(
                ChatItem.User("u", "q"),
                ChatItem.Assistant("a", listOf(ContentBlock.Text(0, "完成了\n全部  工作 sk-SECRET")), streaming = false, stopReason = "stop"),
            ),
        )
        val redact: (String) -> String = { it.replace("sk-SECRET", "***") }
        val done = RunResults.onSettled(ok, aborted = false, atMillis = 5, cwd = "/root/p", redact = redact)
        assertEquals(RunOutcome(RunOutcomeKind.COMPLETED, "/s.jsonl", null, null, "完成了 全部 工作 ***", 5, "/root/p"), done)
        assertEquals(RunOutcomeKind.ABORTED, RunResults.onSettled(ok, aborted = true, atMillis = 0, cwd = null).kind)
        val err = ok.copy(items = ok.items + ChatItem.Assistant("a2", emptyList(), false, "error", "401 Unauthorized"))
        assertEquals(RunOutcomeKind.ERROR, RunResults.onSettled(err, false, 0, null).kind)
        val ask = ExtUiRequest("ui-1", "confirm", title = "要删除吗？")
        assertEquals(RunOutcome(RunOutcomeKind.NEEDS_INPUT, "/s.jsonl", null, "要删除吗？", null, 1, null), RunResults.onUiRequest(ok, ask, 1, null))
        assertNull(RunResults.onUiRequest(ok, ExtUiRequest("ui-2", "notify", message = "hi"), 1, null))
        val long = ok.copy(items = listOf(ChatItem.Assistant("a", listOf(ContentBlock.Text(0, "字".repeat(500))), false, "stop")))
        assertEquals(RunResults.PREVIEW_MAX, RunResults.onSettled(long, false, 0, null).lastTextPreview!!.length)
    }

    /** 审查第 1 轮 Nit：以 agent_start 为本轮起点，steer 进来的用户消息之前写过的文件不漏；相对路径按 cwd 转绝对。 */
    @Test
    fun turnFilesStartAtAgentStartAndResolveCwd() {
        fun args(p: String) = RecordCodec.json.parseToJsonElement("""{"path":"$p"}""") as kotlinx.serialization.json.JsonObject
        val items = listOf(
            io.github.zr987459.pigo.contract.ChatItem.User("u-0", "改", io.github.zr987459.pigo.contract.Delivery.SENT),
            io.github.zr987459.pigo.contract.ChatItem.Tool("tool-1", "1", "write", args("a.txt"), io.github.zr987459.pigo.contract.ToolStatus.OK),
            io.github.zr987459.pigo.contract.ChatItem.User("u-2", "顺便", io.github.zr987459.pigo.contract.Delivery.SENT),
            io.github.zr987459.pigo.contract.ChatItem.Tool("tool-3", "3", "edit", args("/abs/b.txt"), io.github.zr987459.pigo.contract.ToolStatus.OK),
        )
        val st = io.github.zr987459.pigo.contract.ChatState(items = items)
        assertEquals(listOf("/root/p/a.txt", "/abs/b.txt"), TurnFiles.written(st, "/root/p", turnStartAfterKey = "u-0"))
        assertEquals("无起点时退回最后一条用户消息之后", listOf("/abs/b.txt"), TurnFiles.written(st, "/root/p"))
    }
}
