package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.rpc.EngineEvent
import io.github.zr987459.pigo.contract.rpc.ExtUiRequestRecord
import io.github.zr987459.pigo.contract.rpc.RpcCommand
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用管道模拟 pi 进程：测试读到客户端写的命令，再按任意顺序写回记录。 */
class RpcChannelTest {
    private val toClient = PipedOutputStream()
    private val clientStdout = PipedInputStream(toClient, 1 shl 16)
    private val fromClient = PipedInputStream(1 shl 16)
    private val clientStdin = PipedOutputStream(fromClient)
    private val stderrWriter = PipedOutputStream()
    private val clientStderr = PipedInputStream(stderrWriter, 1 shl 16)
    private val commands = LinkedBlockingQueue<JsonObject>()
    private val errors = Collections.synchronizedList(mutableListOf<RpcChannel.ProtocolError>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    private val channel = RpcChannel(
        stdout = clientStdout,
        stdin = clientStdin,
        stderr = clientStderr,
        redactor = Redactor { listOf("my-secret-key-123") },
        log = { _, m -> logs += m },
        onProtocolError = { errors += it },
    )

    init {
        Thread {
            val r = BufferedReader(InputStreamReader(fromClient, Charsets.UTF_8))
            try {
                while (true) {
                    val line = r.readLine() ?: break
                    commands += RecordCodec.json.parseToJsonElement(line) as JsonObject
                }
            } catch (_: IOException) {
            }
        }.apply { isDaemon = true }.start()
        channel.start()
    }

    @After
    fun tearDown() {
        runCatching { toClient.close() }
        runCatching { stderrWriter.close() }
    }

    private fun piWrites(vararg lines: String) {
        for (l in lines) toClient.write((l + "\n").toByteArray(Charsets.UTF_8))
        toClient.flush()
    }

    /** 在 IO 线程上等，别把 runBlocking 的事件循环卡住（请求协程要在这个循环上把命令写出去）。 */
    private suspend fun nextCommand(): JsonObject =
        withContext(Dispatchers.IO) { commands.poll(5, TimeUnit.SECONDS) } ?: error("没有收到命令")

    @Test
    fun responsesAreCorrelatedByIdNotOrder() = runBlocking {
        val a = async { channel.request(RpcCommand.GetState("c-1")) }
        val b = async { channel.request(RpcCommand.GetAvailableModels("c-2")) }
        val c = async { channel.request(RpcCommand.Prompt("c-3", "hi")) }
        val ids = (1..3).map { nextCommand().str("id") }.toSet()
        assertEquals(setOf("c-1", "c-2", "c-3"), ids)
        // 与真 pi 相同：响应完全乱序，中间夹着事件
        piWrites(
            """{"id":"c-3","type":"response","command":"prompt","success":false,"error":"No API key found for the selected model."}""",
            """{"type":"agent_start"}""",
            """{"id":"c-2","type":"response","command":"get_available_models","success":true,"data":{"models":[]}}""",
            """{"id":"c-1","type":"response","command":"get_state","success":true,"data":{"isStreaming":false}}""",
        )
        val ra = a.await() as Outcome.Ok
        assertEquals("get_state", ra.value.command)
        assertEquals("get_available_models", (b.await() as Outcome.Ok).value.command)
        val rc = c.await() as Outcome.Err
        assertEquals(ErrCode.RPC_REJECTED, rc.code)
        assertTrue(rc.message.startsWith("No API key"))
        assertTrue(withTimeout(5_000) { channel.events.receive() } is EngineEvent.AgentStart)
        assertEquals(0, channel.pendingCount)
    }

    @Test
    fun timeoutRemovesOnlyThatWaiterAndLateResponseIsOrphan() = runBlocking {
        val slow = channel.request(RpcCommand.Abort("c-9"), timeoutMs = 100)
        assertEquals(ErrCode.RPC_TIMEOUT, (slow as Outcome.Err).code)
        assertEquals(0, channel.pendingCount)
        nextCommand()
        // 晚到的响应：不崩溃，记为孤儿；之后的请求照常工作
        piWrites("""{"id":"c-9","type":"response","command":"abort","success":true}""")
        val ok = async { channel.request(RpcCommand.GetState("c-10")) }
        nextCommand()
        piWrites("""{"id":"c-10","type":"response","command":"get_state","success":true}""")
        assertTrue(ok.await() is Outcome.Ok)
        assertTrue(errors.any { it is RpcChannel.ProtocolError.Orphan && it.response.id == "c-9" })
    }

    @Test
    fun parseErrorWithoutIdIsReportedAndDoesNotCompleteAnyRequest() = runBlocking {
        val pending = async { channel.request(RpcCommand.GetState("c-1"), timeoutMs = 2_000) }
        nextCommand()
        piWrites(
            """{"type":"response","command":"parse","success":false,"error":"Failed to parse command: Unexpected token"}""",
            """{"type":"response","command":"weird","success":true}""",
            "garbage that is not json",
            """{"id":"c-1","type":"response","command":"get_state","success":true}""",
        )
        assertTrue(pending.await() is Outcome.Ok)
        val parse = errors.filterIsInstance<RpcChannel.ProtocolError.ParseRejected>()
        assertEquals(1, parse.size)
        assertTrue(parse[0].error!!.contains("Failed to parse"))
        assertTrue(errors.any { it is RpcChannel.ProtocolError.Malformed })
        assertTrue(errors.any { it is RpcChannel.ProtocolError.Orphan && it.response.command == "weird" })
    }

    /** 审查第 1 轮 Nit：等待中的请求，其 response 因嵌套过深解析失败时立即以 RPC_REJECTED 结束，不空等超时。 */
    @Test
    fun malformedResponseFailsItsRequestImmediately() = runBlocking {
        val pending = async { channel.request(RpcCommand.GetState("c-7"), timeoutMs = 30_000) }
        nextCommand()
        val deep = "[".repeat(3_000_000) + "]".repeat(3_000_000)
        val started = System.currentTimeMillis()
        piWrites("""{"id":"c-7","type":"response","command":"get_tree","success":true,"data":{"tree":$deep}}""")
        val r = pending.await()
        assertTrue(r is Outcome.Err)
        assertEquals(ErrCode.RPC_REJECTED, (r as Outcome.Err).code)
        assertTrue(r.message.contains("会话太大"))
        assertTrue("不应等到超时", System.currentTimeMillis() - started < 10_000)
    }

    @Test
    fun eofFailsAllPendingRequestsImmediately() = runBlocking {
        val a = async { channel.request(RpcCommand.GetState("c-1"), timeoutMs = 30_000) }
        val b = async { channel.request(RpcCommand.GetMessages("c-2"), timeoutMs = 30_000) }
        nextCommand()
        nextCommand()
        toClient.close() // pi 退出
        assertEquals(ErrCode.ENGINE_EXIT, (withTimeout(5_000) { a.await() } as Outcome.Err).code)
        assertEquals(ErrCode.ENGINE_EXIT, (b.await() as Outcome.Err).code)
        withTimeout(5_000) { channel.closed.await() }
        assertTrue(channel.isClosed)
        // 关闭后的请求立即失败，不会挂起
        assertEquals(ErrCode.ENGINE_EXIT, (channel.request(RpcCommand.GetState("c-3")) as Outcome.Err).code)
        // 事件通道已关闭
        assertTrue(channel.events.receiveCatching().isClosed)
    }

    @Test
    fun eventsAndUiRequestsAreDeliveredInOrder() = runBlocking {
        piWrites(
            """{"type":"agent_start"}""",
            """{"type":"extension_ui_request","id":"u1","method":"confirm","title":"OK?"}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"a\u2028b"}}""",
        )
        val got = withTimeout(5_000) { List(3) { channel.events.receive() } }
        assertTrue(got[0] is EngineEvent.AgentStart)
        assertEquals("u1", (got[1] as ExtUiRequestRecord).id)
        assertTrue(got[2] is EngineEvent.MessageUpdate)
    }

    @Test
    fun sendWritesExtensionUiResponseWithoutWaiting() = runBlocking {
        assertTrue(channel.send(RpcCommand.ExtensionUiResponse("u1", value = "Allow")))
        val cmd = nextCommand()
        assertEquals("extension_ui_response", cmd.str("type"))
        assertEquals("u1", cmd.str("id"))
        assertEquals("Allow", cmd.str("value"))
    }

    @Test
    fun stderrIsDrainedRedactedAndKeptInRing() {
        stderrWriter.write("boot ok\nAuthorization: Bearer abcdefghijkl\nkey=my-secret-key-123 sk-live1234567890\n".toByteArray())
        stderrWriter.flush()
        val deadline = System.currentTimeMillis() + 5_000
        while (channel.stderrTail.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val tail = channel.stderrTail.tail()
        assertEquals("boot ok", tail[0])
        assertEquals("Authorization: Bearer ***", tail[1])
        assertFalse(tail[2].contains("my-secret-key-123"))
        assertFalse(tail[2].contains("sk-live1234567890"))
        assertFalse(logs.any { it.contains("my-secret-key-123") })
    }

    @Test
    fun oversizedRecordIsReported() {
        val small = RpcChannel(
            stdout = java.io.ByteArrayInputStream(("x".repeat(100) + "\n{\"type\":\"agent_start\"}\n").toByteArray()),
            stdin = OutputStream.nullOutputStream(),
            framer = JsonlFramer(maxLineBytes = 40),
            onProtocolError = { errors += it },
        )
        small.start()
        runBlocking {
            assertTrue(withTimeout(5_000) { small.events.receive() } is EngineEvent.AgentStart)
            withTimeout(5_000) { small.closed.await() }
        }
        assertTrue(errors.any { it is RpcChannel.ProtocolError.Oversized && it.bytes == 100L })
    }

    /** 审查第 2 轮 Nit：超长（> maxLineBytes）的 response 按行首 id 立即以 RPC_REJECTED 结束，不等满超时。 */
    @Test
    fun oversizedResponseFailsItsRequestImmediately() = runBlocking {
        val out = PipedOutputStream()
        val inp = PipedInputStream(out, 1 shl 16)
        val ch = RpcChannel(
            stdout = inp,
            stdin = OutputStream.nullOutputStream(),
            framer = JsonlFramer(maxLineBytes = 4_096),
            onProtocolError = { errors += it },
        )
        ch.start()
        val pending = async { ch.request(RpcCommand.GetEntries("c-9", null), timeoutMs = 30_000) }
        delay(100)
        val started = System.currentTimeMillis()
        val big = "x".repeat(20_000)
        launch(Dispatchers.IO) {
            out.write("""{"id":"c-9","type":"response","command":"get_entries","success":true,"data":{"entries":[{"type":"message","id":"e1","pad":"$big"}],"leafId":"e1"}}""".toByteArray() + "\n".toByteArray())
            out.flush()
        }
        val r = pending.await()
        assertTrue(r is Outcome.Err)
        assertEquals(ErrCode.RPC_REJECTED, (r as Outcome.Err).code)
        assertTrue("不应等到超时", System.currentTimeMillis() - started < 10_000)
        assertTrue(errors.any { it is RpcChannel.ProtocolError.Oversized })
        out.close()
    }

    @Test
    fun writeFailureIsReportedAsEngineExit() = runBlocking {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
        }
        val ch = RpcChannel(stdout = PipedInputStream(PipedOutputStream()), stdin = broken)
        ch.start()
        assertEquals(ErrCode.ENGINE_EXIT, (ch.request(RpcCommand.GetState("c-1")) as Outcome.Err).code)
        assertFalse(ch.send(RpcCommand.ExtensionUiResponse("u", cancelled = true)))
        ch.closeStdin()
    }

    @Test
    fun startIsIdempotentAndDuplicateIdIsRejected() = runBlocking {
        channel.start()
        val first = async { channel.request(RpcCommand.GetState("dup"), timeoutMs = 2_000) }
        nextCommand()
        val second = runCatching { channel.request(RpcCommand.GetState("dup")) }
        assertTrue(second.exceptionOrNull() is IllegalStateException)
        piWrites("""{"id":"dup","type":"response","command":"get_state","success":true}""")
        assertTrue(first.await() is Outcome.Ok)
    }

    @Test
    fun lineRingAndIdsAndTimeouts() {
        val ring = LineRing(3)
        (1..5).forEach { ring.add("l$it") }
        assertEquals(listOf("l3", "l4", "l5"), ring.tail())
        assertEquals(listOf("l5"), ring.tail(1))
        assertEquals(emptyList<String>(), ring.tail(-1))
        ring.clear()
        assertEquals(0, ring.size)
        val ids = IdGenerator()
        assertEquals("c-1", ids.next())
        assertEquals("c-2", ids.next())
        assertEquals(20_000L, RpcTimeouts.forCommand("abort"))
        assertEquals(30_000L, RpcTimeouts.forCommand("get_messages"))
        assertEquals(15_000L, RpcTimeouts.forCommand("prompt"))
    }
}
