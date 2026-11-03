package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.Disposition
import io.github.zr987459.pigo.contract.QueueSnapshot
import io.github.zr987459.pigo.contract.rpc.AssistantMessageEvent as A
import io.github.zr987459.pigo.contract.rpc.EngineEvent as E
import io.github.zr987459.pigo.contract.rpc.ExtUiRequestRecord
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import io.github.zr987459.pigo.contract.rpc.RpcRecord
import io.github.zr987459.pigo.contract.rpc.RpcResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCodecTest {

    private fun rec(line: String): RpcRecord = (RecordCodec.decode(line) as RecordCodec.Decoded.Record).record

    @Test
    fun responseWithIdAndData() {
        val r = rec("""{"id":"c-1","type":"response","command":"prompt","success":true,"data":{"disposition":"queued"},"extra":1}""") as RpcResponse
        assertEquals("c-1", r.id)
        assertEquals("prompt", r.command)
        assertTrue(r.success)
        assertFalse(r.isParseError)
        assertEquals(Disposition.QUEUED, Responses.disposition(r.data))
    }

    @Test
    fun parseErrorHasNoId() {
        // 真 pi 1.5.0 的原样输出
        val r = rec("""{"type":"response","command":"parse","success":false,"error":"Failed to parse command: Unexpected token 'o', \"not json\" is not valid JSON"}""") as RpcResponse
        assertNull(r.id)
        assertTrue(r.isParseError)
        assertFalse(r.success)
        assertTrue(r.error!!.startsWith("Failed to parse command"))
    }

    @Test
    fun responseWithNullDataAndWrongTypes() {
        val r = rec("""{"id":5,"type":"response","command":"abort","success":"yes","data":null}""") as RpcResponse
        assertEquals("5", r.id) // 数字 id 也能取到字符串形式
        assertFalse(r.success) // 类型不对时取默认值，不抛异常
        assertNull(r.data)
    }

    @Test
    fun malformedLines() {
        assertTrue(RecordCodec.decode("not json") is RecordCodec.Decoded.Malformed)
        assertTrue(RecordCodec.decode("[1,2]") is RecordCodec.Decoded.Malformed)
        assertTrue(RecordCodec.decode("{\"a\":") is RecordCodec.Decoded.Malformed)
        assertTrue(RecordCodec.decode("\"str\"") is RecordCodec.Decoded.Malformed)
    }

    @Test
    fun unknownTypeAndMissingType() {
        val u = rec("""{"type":"brand_new_event","x":1}""") as E.Unknown
        assertEquals("brand_new_event", u.type)
        val n = rec("""{"x":1}""") as E.Unknown
        assertNull(n.type)
        // 必要字段缺失的已知事件也降级为 Unknown，而不是抛异常
        assertTrue(rec("""{"type":"message_start"}""") is E.Unknown)
        assertTrue(rec("""{"type":"message_end","message":"oops"}""") is E.Unknown)
        assertTrue(rec("""{"type":"tool_execution_start"}""") is E.Unknown)
        assertTrue(rec("""{"type":"tool_execution_update"}""") is E.Unknown)
        assertTrue(rec("""{"type":"tool_execution_end"}""") is E.Unknown)
        assertTrue(rec("""{"type":"thinking_level_changed"}""") is E.Unknown)
        assertTrue(rec("""{"type":"extension_ui_request","id":"x"}""") is E.Unknown)
    }

    @Test
    fun allSessionEvents() {
        assertTrue(rec("""{"type":"agent_start"}""") is E.AgentStart)
        val end = rec("""{"type":"agent_end","messages":[{"role":"user"}],"willRetry":true}""") as E.AgentEnd
        assertTrue(end.willRetry)
        assertEquals(1, end.messages!!.size)
        assertTrue((rec("""{"type":"agent_settled","aborted":true}""") as E.AgentSettled).aborted)
        assertFalse((rec("""{"type":"agent_settled"}""") as E.AgentSettled).aborted)
        assertTrue(rec("""{"type":"turn_start"}""") is E.TurnStart)
        val te = rec("""{"type":"turn_end","message":{"role":"assistant"},"toolResults":[]}""") as E.TurnEnd
        assertEquals(0, te.toolResults!!.size)
        val ms = rec("""{"type":"message_start","message":{"role":"user","content":"hi"}}""") as E.MessageStart
        assertEquals("user", ms.message.str("role"))
        val me = rec("""{"type":"message_end","message":{"role":"assistant","stopReason":"stop"}}""") as E.MessageEnd
        assertEquals("stop", me.message.str("stopReason"))
        val ts = rec("""{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"ls"},"parentToolCallId":"p"}""") as E.ToolExecutionStart
        assertEquals("t1", ts.toolCallId)
        assertEquals("bash", ts.toolName)
        assertEquals("p", ts.parentToolCallId)
        assertEquals("ls", ts.args!!.str("command"))
        val tu = rec("""{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"content":[]}}""") as E.ToolExecutionUpdate
        assertTrue(tu.partialResult is JsonObject)
        val tend = rec("""{"type":"tool_execution_end","toolCallId":"t1","result":{"content":[]},"isError":true,"durationMs":12.0}""") as E.ToolExecutionEnd
        assertTrue(tend.isError)
        assertEquals(12L, tend.durationMs)
        val q = rec("""{"type":"queue_update","steering":["a",null,"b"],"followUp":["c"]}""") as E.QueueUpdate
        assertEquals(listOf("a", "b"), q.steering)
        assertEquals(listOf("c"), q.followUp)
        val rs = rec("""{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}""") as E.AutoRetryStart
        assertEquals(3, rs.maxAttempts)
        assertEquals(2000L, rs.delayMs)
        val re = rec("""{"type":"auto_retry_end","success":false,"attempt":3,"finalError":"boom"}""") as E.AutoRetryEnd
        assertEquals("boom", re.finalError)
        assertEquals("threshold", (rec("""{"type":"compaction_start","reason":"threshold"}""") as E.CompactionStart).reason)
        val ce = rec("""{"type":"compaction_end","result":null,"aborted":false,"willRetry":true,"errorMessage":"x"}""") as E.CompactionEnd
        assertNull(ce.result)
        assertTrue(ce.willRetry)
        assertEquals("x", ce.errorMessage)
        assertNull((rec("""{"type":"session_info_changed"}""") as E.SessionInfoChanged).name)
        assertEquals("high", (rec("""{"type":"thinking_level_changed","level":"high"}""") as E.ThinkingLevelChanged).level)
        val ee = rec("""{"type":"extension_error","extensionPath":"/a.ts","event":"tool_call","error":"bad"}""") as E.ExtensionError
        assertEquals("/a.ts", ee.extensionPath)
        val ui = rec("""{"type":"extension_ui_request","id":"u1","method":"select","options":["A","B"]}""") as ExtUiRequestRecord
        assertEquals("select", ui.method)
    }

    @Test
    fun assistantMessageEvents() {
        fun ev(json: String) = (rec("""{"type":"message_update","assistantMessageEvent":$json,"usage":{"input":1}}""") as E.MessageUpdate).event
        assertEquals(A.Start, ev("""{"type":"start"}"""))
        assertEquals(A.TextStart(0), ev("""{"type":"text_start","contentIndex":0}"""))
        assertEquals(A.TextDelta(1, "hi"), ev("""{"type":"text_delta","contentIndex":1,"delta":"hi"}"""))
        assertEquals(A.TextEnd(1, "hi!"), ev("""{"type":"text_end","contentIndex":1,"content":"hi!"}"""))
        assertEquals(A.ThinkingStart(0), ev("""{"type":"thinking_start","contentIndex":0}"""))
        assertEquals(A.ThinkingDelta(0, "h"), ev("""{"type":"thinking_delta","contentIndex":0,"delta":"h"}"""))
        assertEquals(A.ThinkingEnd(0, null), ev("""{"type":"thinking_end","contentIndex":0}"""))
        assertEquals(A.ToolcallStart(2, "t", "bash"), ev("""{"type":"toolcall_start","contentIndex":2,"id":"t","toolName":"bash"}"""))
        assertEquals(A.ToolcallDelta(2, "{\""), ev("""{"type":"toolcall_delta","contentIndex":2,"delta":"{\""}"""))
        val end = ev("""{"type":"toolcall_end","contentIndex":2,"toolCall":{"type":"toolCall","id":"t","name":"bash","arguments":{}}}""") as A.ToolcallEnd
        assertEquals("t", end.toolCall!!.str("id"))
        assertEquals(A.Done("stop"), ev("""{"type":"done","reason":"stop"}"""))
        assertEquals(A.Error("aborted"), ev("""{"type":"error","reason":"aborted"}"""))
        assertTrue(ev("""{"type":"future_thing"}""") is A.Other)
        val noEvent = rec("""{"type":"message_update"}""") as E.MessageUpdate
        assertTrue(noEvent.event is A.Other)
        assertNull(noEvent.usage)
    }

    @Test
    fun encodeCommandsIsOneLineEvenWithNewlinesAndSeparators() {
        val line = RecordCodec.encode(RpcCommand.Prompt("c-1", "第一行\n第二行\u2028第三行", RpcCommand.STREAMING_STEER))
        assertFalse(line.contains('\n'))
        val back = RecordCodec.json.parseToJsonElement(line) as JsonObject
        assertEquals("第一行\n第二行\u2028第三行", back.str("message"))
        assertEquals("steer", back.str("streamingBehavior"))
        assertEquals("c-1", back.str("id"))
        val ui = RecordCodec.json.parseToJsonElement(RecordCodec.encode(RpcCommand.ExtensionUiResponse("u1", confirmed = true))) as JsonObject
        assertEquals("extension_ui_response", ui.str("type"))
        assertEquals(true, ui.bool("confirmed"))
    }

    @Test
    fun lenientAccessors() {
        val o = RecordCodec.json.parseToJsonElement("""{"s":"x","n":3,"d":1.5,"b":true,"nul":null,"o":{},"a":[1,"y"]}""") as JsonObject
        assertEquals("x", o.str("s"))
        assertEquals("3", o.str("n"))
        assertEquals(3L, o.long("n"))
        assertEquals(1L, o.long("d"))
        assertEquals(1.5, o.double("d")!!, 0.0)
        assertEquals(true, o.bool("b"))
        assertNull(o.str("nul"))
        assertNull(o.str("o"))
        assertNull(o.long("s"))
        assertTrue(o.obj("o") is JsonObject)
        assertNull(o.obj("a"))
        assertTrue(o.arr("a") is JsonArray)
        assertEquals(listOf("1", "y"), o.strings("a"))
        assertEquals(emptyList<String>(), o.strings("missing"))
    }

    @Test
    fun responsesHelpers() {
        val state = Responses.state(
            RecordCodec.json.parseToJsonElement(
                """{"model":{"id":"deepseek-chat","name":"DeepSeek","provider":"deepseek","reasoning":true,"contextWindow":128000},
                   "thinkingLevel":"medium","isStreaming":true,"steeringMode":"one-at-a-time","followUpMode":"one-at-a-time",
                   "sessionFile":"/root/.pi/agent/sessions/x.jsonl","sessionId":"abc","sessionName":"n","pendingMessageCount":2}""",
            ),
        )
        assertEquals("deepseek", state.model!!.provider)
        assertEquals(128000L, state.model!!.contextWindow)
        assertTrue(state.model!!.reasoning)
        assertTrue(state.isStreaming)
        assertEquals(2, state.pendingMessageCount)
        assertEquals("n", state.sessionName)
        // 真 pi 在没有模型时返回 provider/id 都是 "unknown" 的占位对象
        val none = Responses.state(RecordCodec.json.parseToJsonElement("""{"model":{"id":"unknown","provider":"unknown","contextWindow":0}}"""))
        assertNull(none.model)
        assertEquals(Responses.state(null), Responses.state(JsonPrimitive(1)))
        assertNull(Responses.model(JsonObject(emptyMap())))

        assertEquals(Disposition.STARTED, Responses.disposition(null))
        assertEquals(Disposition.HANDLED, Responses.disposition(RecordCodec.json.parseToJsonElement("""{"disposition":"handled"}""")))
        assertEquals(QueueSnapshot(listOf("s"), listOf("f")), Responses.queue(RecordCodec.json.parseToJsonElement("""{"steering":["s"],"followUp":["f"]}""")))
        assertEquals(QueueSnapshot.EMPTY, Responses.queue(null))
        assertTrue(Responses.cancelled(RecordCodec.json.parseToJsonElement("""{"cancelled":true}""")))
        assertFalse(Responses.cancelled(null))
        assertEquals(1, Responses.models(RecordCodec.json.parseToJsonElement("""{"models":[{"id":"m","provider":"p"},{"bad":1}]}""")).size)
        assertEquals(1, Responses.models(RecordCodec.json.parseToJsonElement("""[{"id":"m","provider":"p"}]""")).size)
        assertEquals(0, Responses.models(null).size)
        assertEquals(listOf("off", "high"), Responses.thinkingLevels(RecordCodec.json.parseToJsonElement("""{"levels":["off","high",3]}""")))
        assertEquals(listOf("low"), Responses.thinkingLevels(RecordCodec.json.parseToJsonElement("""["low"]""")))
        assertEquals(0, Responses.thinkingLevels(null).size)
        val stats = Responses.stats(RecordCodec.json.parseToJsonElement("""{"tokens":{"total":105000},"cost":0.45,"contextUsage":{"percent":30}}"""))
        assertEquals(30.0, stats.contextPercent!!, 0.0)
        assertEquals(105000L, stats.totalTokens)
        assertEquals(0.45, stats.cost!!, 0.0)
        assertNull(Responses.stats(RecordCodec.json.parseToJsonElement("""{"contextUsage":{"percent":null}}""")).contextPercent)
        assertNull(Responses.stats(null).cost)
        assertEquals(2, Responses.messages(RecordCodec.json.parseToJsonElement("""{"messages":[{},{}]}""")).size)
        assertEquals(1, Responses.messages(RecordCodec.json.parseToJsonElement("""[{}]""")).size)
        assertEquals(0, Responses.messages(null).size)
    }
}
