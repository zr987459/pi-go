package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ChatState
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

    // ------------------------------------------------------------------ RunResult（N1）

    @Test
    fun runResults() {
        val ok = ChatState(
            sessionFile = "/s.jsonl",
            items = listOf(
                ChatItem.User("u", "q"),
                ChatItem.Assistant("a", listOf(ContentBlock.Text(0, "完成了\n全部  工作")), streaming = false, stopReason = "stop"),
            ),
        )
        assertEquals(RunResult.Completed("/s.jsonl", null, "完成了 全部 工作"), RunResults.onSettled(ok, aborted = false))
        assertNull("aborted 不通知", RunResults.onSettled(ok, aborted = true))
        val err = ok.copy(items = ok.items + ChatItem.Assistant("a2", emptyList(), false, "error", "401 Unauthorized"))
        assertTrue(RunResults.onSettled(err, false) is RunResult.Failed)
        val ask = ExtUiRequest("ui-1", "confirm", title = "要删除吗？")
        assertEquals(RunResult.NeedsInput("/s.jsonl", null, "ui-1", "要删除吗？"), RunResults.onUiRequest(ok, ask))
        assertNull(RunResults.onUiRequest(ok, ExtUiRequest("ui-2", "notify", message = "hi")))
    }
}
