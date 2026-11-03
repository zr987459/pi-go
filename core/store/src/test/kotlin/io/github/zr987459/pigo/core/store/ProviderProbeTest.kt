package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.fake.FakeRuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** piweb-study G2（获取模型列表）与 G3（测试连接）：用本机 HttpServer 模拟上游，断言结果与脱敏。 */
class ProviderProbeTest {
    private lateinit var server: TinyHttp
    private var lastAuth: String? = null
    private var lastBody: String = ""
    private val key = "sk-test-1234567890abcdef"

    private fun base() = "http://127.0.0.1:${server.port}/v1"

    /** 单元测试跑在 android.jar 类路径上，没有 com.sun.net.httpserver：用 ServerSocket 写一个最小的 HTTP/1.1 服务。 */
    private inner class TinyHttp {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                c.use { handle(it) }
            }
        }

        private fun handle(c: Socket) {
            val input = c.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val lines = head.toString().split("\r\n")
            val path = lines[0].split(" ")[1]
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(len).also { var off = 0; while (off < len) { val n = input.read(it, off, len - off); if (n < 0) break; off += n } }
            lastAuth = headers["authorization"]
            val ok = lastAuth == "Bearer $key"
            val resp = when {
                path.endsWith("/models") -> if (ok) """{"object":"list","data":[{"id":"qwen-plus"},{"id":"qwen-max"},{"id":"qwen-plus"}]}"""
                    else """{"error":{"message":"Incorrect API key provided: $key"}}"""
                else -> {
                    lastBody = String(body)
                    if (ok) """{"choices":[{"message":{"content":"OK"}}]}""" else """{"error":"bad key $key"}"""
                }
            }.toByteArray()
            val status = if (ok) "200 OK" else "401 Unauthorized"
            val out = c.getOutputStream()
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${resp.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(resp)
            out.flush()
        }

        fun stop() { socket.close(); worker.join(1000) }
    }

    @Before
    fun setUp() {
        server = TinyHttp()
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun parsesOpenAiAnthropicAndGeminiLists() {
        assertEquals(listOf("a", "b"), ModelDiscovery.parse("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"}]}"""))
        assertEquals(listOf("claude-x"), ModelDiscovery.parse("""{"data":[{"id":"claude-x","display_name":"Claude X"}]}"""))
        assertEquals(listOf("gemini-2.5-pro"), ModelDiscovery.parse("""{"models":[{"name":"models/gemini-2.5-pro"}]}"""))
        assertEquals(listOf("m1"), ModelDiscovery.parse("""[{"id":"m1"}]"""))
        assertTrue(ModelDiscovery.parse("not json").isEmpty())
        assertEquals("https://x/v1/models", ModelDiscovery.modelsUrl(" https://x/v1/ "))
    }

    @Test
    fun discoverSendsCleanedBearerKeyAndReturnsSortedIds() = runBlocking {
        val r = ModelDiscovery.discover(base() + "/", "  Bearer $key  ")
        assertEquals("Bearer $key", lastAuth)
        assertEquals(Outcome.Ok(listOf("qwen-max", "qwen-plus")), r)
    }

    @Test
    fun discoverWithBadKeyGivesChineseReasonWithoutLeakingKey() = runBlocking {
        val r = ModelDiscovery.discover(base(), "sk-wrong-000000000")
        assertTrue(r is Outcome.Err)
        val msg = (r as Outcome.Err).message
        assertTrue(msg, msg.startsWith("Key 错误或没有权限（HTTP 401）"))
        assertFalse("错误文案里不能出现 Key", msg.contains(key))
    }

    @Test
    fun discoverUnreachableHostIsNetworkError() = runBlocking {
        val r = ModelDiscovery.discover("http://127.0.0.1:1/v1", key)
        assertTrue(r is Outcome.Err)
        assertFalse((r as Outcome.Err).message.contains(key))
    }

    @Test
    fun testConnectionOkAndBadKey() = runBlocking {
        val ok = ProviderTester.testOpenAiCompatible(base(), key, "qwen-plus")
        assertTrue(ok.message, ok.ok)
        assertEquals(200, ok.httpStatus)
        assertTrue(lastBody.contains("\"max_tokens\":16"))
        assertTrue(lastBody.contains(ProviderTester.PROMPT))
        val bad = ProviderTester.testOpenAiCompatible(base(), "sk-wrong-000000000", "qwen-plus")
        assertFalse(bad.ok)
        assertEquals(401, bad.httpStatus)
        assertTrue(bad.message.startsWith("Key 错误"))
        assertFalse(bad.message.contains(key))
        assertFalse(ProviderTester.testOpenAiCompatible(base(), key, " ").ok)
    }

    private class ExecRuntime(val result: ExecResult) : io.github.zr987459.pigo.contract.RuntimeManager by FakeRuntimeManager() {
        var lastCmd: List<String> = emptyList()
        var lastEnv: Map<String, String> = emptyMap()
        override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult {
            lastCmd = cmd; lastEnv = env
            return result
        }
    }

    @Test
    fun builtInProviderIsTestedWithPiPrintAndKeyOnlyInEnv() = runBlocking {
        val rt = ExecRuntime(ExecResult(0, "OK\n", ""))
        val r = ProviderTester.testBuiltIn(rt, "deepseek", "DEEPSEEK_API_KEY", " Bearer $key ")
        assertTrue(r.ok)
        assertEquals(listOf("pi", "--print", "--no-session", "--provider", "deepseek", ProviderTester.PROMPT), rt.lastCmd)
        assertEquals(mapOf("DEEPSEEK_API_KEY" to key), rt.lastEnv)
        assertFalse("Key 不能出现在命令行参数里", rt.lastCmd.any { it.contains(key) })

        val bad = ProviderTester.testBuiltIn(ExecRuntime(ExecResult(1, "", "Error: 401 Authentication Fails, key $key")), "deepseek", "DEEPSEEK_API_KEY", key)
        assertFalse(bad.ok)
        assertEquals(401, bad.httpStatus)
        assertTrue(bad.message, bad.message.startsWith("Key 错误"))
        assertFalse(bad.message.contains(key))

        val slow = ProviderTester.testBuiltIn(ExecRuntime(ExecResult(-1, "", "", timedOut = true)), "deepseek", "DEEPSEEK_API_KEY", key)
        assertFalse(slow.ok)
        assertFalse(ProviderTester.testBuiltIn(rt, "deepseek", "DEEPSEEK_API_KEY", "  ").ok)
    }
}
