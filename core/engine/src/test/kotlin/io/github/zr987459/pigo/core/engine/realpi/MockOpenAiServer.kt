package io.github.zr987459.pigo.core.engine.realpi

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 本机假 OpenAI 兼容服务（`/v1/chat/completions`，SSE 流式），给桌面真 pi 集成测试与夹具录制用。
 * 只监听 127.0.0.1，不访问任何外网；只用 java.net（Android 单测的编译类路径里没有 com.sun.net.httpserver）。
 * 行为由**最后一条 user 消息**里的标记决定：
 *
 * | 标记 | 行为 |
 * |---|---|
 * | `#text` | 流式输出三段文本（含 U+2028） |
 * | `#think` | 先 `reasoning_content` 再正文 |
 * | `#tool-ok` | 调 `bash`（`echo pi-go-ok`），拿到结果后回答 |
 * | `#tool-fail` | 调 `read` 读不存在的文件，拿到错误后回答 |
 * | `#slow` | 慢速输出约 3 s（供排队/插话/停止） |
 * | `#slow-tool` | 调 `bash`（`sleep 2`），之后回答 |
 * | `#retry` | 第一次 503 overloaded，之后正常（pi 自动重试） |
 * | `#fail401` | 401 Key 错误 |
 * | 其他 | 回 `OK: <原文前 20 字>` |
 *
 * 请求里的图片（`image_url`）记在 [imageUrls]。
 *
 * 没有工具定义且提到 summary 的请求视为压缩摘要请求，回一段摘要。
 */
class MockOpenAiServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mock-openai").apply { isDaemon = true } }
    private val json = Json { ignoreUnknownKeys = true }
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** 收到的模型请求数（测试断言用）。 */
    val requests = AtomicInteger(0)

    /** 请求里出现过的 `image_url.url`（C1 集成测试断言用，feat/engine-rpc 追加）。 */
    val imageUrls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    init {
        pool.execute {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { serve(sock) }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    /** 极简 HTTP/1.1：一个连接一个请求，响应用 chunked 编码后关闭连接。 */
    private class Exchange(val body: String, private val out: OutputStream) {
        private var started = false

        fun start(code: Int, contentType: String) {
            val reason = when (code) {
                200 -> "OK"
                401 -> "Unauthorized"
                503 -> "Service Unavailable"
                else -> "Status"
            }
            out.write(
                ("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nTransfer-Encoding: chunked\r\n" +
                    "Connection: close\r\nCache-Control: no-cache\r\n\r\n").toByteArray(),
            )
            out.flush()
            started = true
        }

        fun write(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.isEmpty()) return
            out.write((Integer.toHexString(b.size) + "\r\n").toByteArray())
            out.write(b)
            out.write("\r\n".toByteArray())
            out.flush()
        }

        fun finish() {
            if (started) out.write("0\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val path = lines.first().split(" ").getOrElse(1) { "/" }
                val headers = lines.drop(1).mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
                }.toMap()
                val body = when {
                    headers["content-length"] != null -> readN(input, headers.getValue("content-length").toInt())
                    headers["transfer-encoding"]?.contains("chunked") == true -> readChunked(input)
                    else -> ByteArray(0)
                }.toString(Charsets.UTF_8)
                val ex = Exchange(body, s.getOutputStream())
                if (path.startsWith("/v1/chat/completions")) {
                    handle(ex)
                } else {
                    respondJson(ex, 200, """{"object":"list","data":[{"id":"mock-model","object":"model"}]}""")
                }
                ex.finish()
            } catch (_: IOException) {
                // 客户端中止（abort）时连接被关，正常。
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.UTF_8.name()).trimEnd()
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return out
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) sizeLine.append(c.toChar())
            }
            val size = sizeLine.toString().substringBefore(';').trim().toIntOrNull(16) ?: 0
            if (size == 0) {
                readHead(input)
                break
            }
            out.write(readN(input, size))
            input.read()
            input.read()
        }
        return out.toByteArray()
    }

    private fun handle(ex: Exchange) {
        requests.incrementAndGet()
        val req = json.parseToJsonElement(ex.body) as JsonObject
        val messages = req["messages"] as? JsonArray ?: JsonArray(emptyList())
        for (m in messages) {
            val parts = (m as? JsonObject)?.get("content") as? JsonArray ?: continue
            for (p in parts) {
                val o = p as? JsonObject ?: continue
                if (o["type"]?.str() == "image_url") ((o["image_url"] as? JsonObject)?.get("url")?.str())?.let { imageUrls += it }
            }
        }
        val lastUser = messages.lastOrNull { (it as? JsonObject)?.get("role")?.str() == "user" }?.let { contentText(it as JsonObject) } ?: ""
        val lastRole = (messages.lastOrNull() as? JsonObject)?.get("role")?.str()
        val afterTool = lastRole == "tool"
        val isSummary = (req["tools"] as? JsonArray).isNullOrEmpty() &&
            messages.any { m -> contentText(m as JsonObject).contains("summar", ignoreCase = true) }
        when {
            isSummary -> sse(ex) { text("## 摘要\n用户做了测试。") }
            lastUser.contains("#fail401") -> respondJson(
                ex, 401,
                """{"error":{"message":"Incorrect API key provided: sk-mo***","type":"invalid_request_error","code":"invalid_api_key"}}""",
            )
            lastUser.contains("#retry") && counters.getOrPut(lastUser) { AtomicInteger() }.getAndIncrement() == 0 ->
                respondJson(ex, 503, """{"error":{"message":"The server is overloaded, please retry","type":"overloaded"}}""")
            // 流出半截正文后掐断连接：pi 得到带部分内容的 stopReason=error（errorMessage 如 "terminated"），按可重试错误进入自动重试。
            lastUser.contains("#halfdrop") -> sse(ex) {
                text("半截")
                Thread.sleep(50)
                throw IOException("mock: drop mid-stream")
            }
            lastUser.contains("#think") -> sse(ex) {
                reasoning("先想一想")
                reasoning("：用户要问候。")
                text("想好了。")
            }
            lastUser.contains("#tool-ok") && !afterTool -> sse(ex) { toolCall("call_ok_1", "bash", """{"command":"echo pi-go-ok"}""") }
            lastUser.contains("#tool-fail") && !afterTool -> sse(ex) { toolCall("call_fail_1", "read", """{"path":"/nonexistent/pi-go/missing.txt"}""") }
            lastUser.contains("#slow-tool") && !afterTool -> sse(ex) { toolCall("call_slow_1", "bash", """{"command":"sleep 2; echo slow-done"}""") }
            afterTool -> sse(ex) { text("工具已完成。") }
            lastUser.contains("#slow") -> sse(ex) {
                repeat(15) { i ->
                    text("片段$i ")
                    Thread.sleep(200)
                }
            }
            lastUser.contains("#text") -> sse(ex) {
                text("你好，")
                text("世界")
                text("\u2028第二行")
            }
            else -> sse(ex) { text("OK: " + lastUser.take(20)) }
        }
    }

    private class Sse(private val ex: Exchange) {
        private var toolIndex = 0
        var finish = "stop"

        fun chunk(delta: JsonObject, finishReason: String? = null) {
            val o = buildJsonObject {
                put("id", "chatcmpl-mock")
                put("object", "chat.completion.chunk")
                put("model", "mock-model")
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("delta", delta)
                            if (finishReason == null) put("finish_reason", JsonNull) else put("finish_reason", finishReason)
                        },
                    )
                }
            }
            ex.write("data: $o\n\n")
        }

        fun text(t: String) = chunk(buildJsonObject { put("content", t) })
        fun reasoning(t: String) = chunk(buildJsonObject { put("reasoning_content", t) })

        fun toolCall(id: String, name: String, args: String) {
            val idx = toolIndex++
            val half = args.length / 2
            chunk(
                buildJsonObject {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", idx)
                                    put("id", id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", name)
                                        put("arguments", args.substring(0, half))
                                    }
                                },
                            )
                        },
                    )
                },
            )
            chunk(
                buildJsonObject {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", idx)
                                    putJsonObject("function") { put("arguments", args.substring(half)) }
                                },
                            )
                        },
                    )
                },
            )
            finish = "tool_calls"
        }

        fun end() {
            val o = buildJsonObject {
                put("id", "chatcmpl-mock")
                put("object", "chat.completion.chunk")
                put("model", "mock-model")
                putJsonArray("choices") {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put("delta", JsonObject(emptyMap()))
                            put("finish_reason", finish)
                        },
                    )
                }
                putJsonObject("usage") {
                    put("prompt_tokens", 120)
                    put("completion_tokens", 12)
                    put("total_tokens", 132)
                }
            }
            ex.write("data: $o\n\n")
            ex.write("data: [DONE]\n\n")
        }
    }

    private fun sse(ex: Exchange, body: Sse.() -> Unit) {
        ex.start(200, "text/event-stream")
        val s = Sse(ex)
        s.chunk(buildJsonObject { put("role", "assistant"); put("content", "") })
        s.body()
        s.end()
    }

    private fun respondJson(ex: Exchange, code: Int, body: String) {
        ex.start(code, "application/json")
        ex.write(body)
    }

    private fun contentText(m: JsonObject): String = when (val c = m["content"]) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.joinToString("") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: "" }
        else -> ""
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.content
}
