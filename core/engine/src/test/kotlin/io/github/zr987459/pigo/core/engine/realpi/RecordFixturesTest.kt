package io.github.zr987459.pigo.core.engine.realpi

import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 用桌面真 pi 1.5.0 + 本机假模型服务录制 `:core:protocol` 的回放夹具（docs/protocol/fixtures.md）。
 *
 * 默认跳过。录制：
 * ```
 * PIGO_PI_BIN=$(which pi) PIGO_RECORD_FIXTURES=$PWD/core/protocol/src/test/resources/fixtures/recorded \
 *   ./gradlew :core:engine:testDebugUnitTest --tests '*RecordFixturesTest*'
 * ```
 * 每个夹具：pi 的 stdout 原样逐行（只把主机临时路径换成容器路径、端口换成 PORT）；
 * 客户端发出的命令以 `//> ` 开头的注释行记录，回放时跳过。
 */
class RecordFixturesTest {

    private class RawPi(private val sandbox: PiSandbox, private val out: BufferedWriter) : AutoCloseable {
        private val proc: Process
        private val lines = LinkedBlockingQueue<String>()

        init {
            val spec = sandbox.engineConfig().spec(null, sandbox.cwd.absolutePath, sandbox.keyEnv)
            val argv = spec.argv.toMutableList().also { it[0] = PiSandbox.piBin!! }
            proc = ProcessBuilder(argv).directory(sandbox.cwd).apply {
                environment().keys.removeIf { it.lowercase().endsWith("_proxy") }
                environment().putAll(spec.env)
            }.start()
            Thread { pump(proc.inputStream) }.apply { isDaemon = true }.start()
            Thread { proc.errorStream.readBytes() }.apply { isDaemon = true }.start()
        }

        private fun pump(s: InputStream) {
            // 只按 LF 切（与 JsonlFramer 一致），不用 readLine()
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b = s.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    lines += buf.toString(Charsets.UTF_8.name()).removeSuffix("\r")
                    buf.reset()
                } else {
                    buf.write(b)
                }
            }
        }

        fun send(json: String) {
            out.write("//> " + sandbox.sanitize(json))
            out.newLine()
            proc.outputStream.write((json + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        }

        /** 记录 stdout，直到某行满足 [until]（含该行）。 */
        fun recordUntil(timeoutMs: Long = 30_000, until: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                check(left > 0) { "等待超时" }
                val l = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                out.write(sandbox.sanitize(l))
                out.newLine()
                if (until(l)) return l
            }
        }

        override fun close() {
            runCatching { proc.outputStream.close() }
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            out.close()
        }
    }

    private val outDir: File? = System.getenv("PIGO_RECORD_FIXTURES")?.let(::File)

    private fun record(name: String, body: RawPi.() -> Unit) {
        PiSandbox().use { sb ->
            outDir!!.mkdirs()
            val w = File(outDir, "$name.jsonl").bufferedWriter()
            w.write("// 由 RecordFixturesTest 用桌面真 pi 1.5.0 + MockOpenAiServer 录制；见 docs/protocol/fixtures.md")
            w.newLine()
            RawPi(sb, w).use { pi ->
                pi.send("""{"id":"boot-1","type":"get_state"}""")
                pi.recordUntil { it.contains("\"boot-1\"") }
                pi.body()
            }
        }
    }

    private fun settled(l: String) = l.contains("\"type\":\"agent_settled\"")

    @Test
    fun recordAll() {
        assumeTrue("设置 PIGO_PI_BIN 与 PIGO_RECORD_FIXTURES 才录制", PiSandbox.piBin != null && outDir != null)

        record("text-stream") {
            send("""{"id":"c-1","type":"prompt","message":"#text 打个招呼"}""")
            recordUntil(until = ::settled)
        }
        record("thinking") {
            send("""{"id":"c-1","type":"prompt","message":"#think 想一想再回答"}""")
            recordUntil(until = ::settled)
        }
        record("tool-success") {
            send("""{"id":"c-1","type":"prompt","message":"#tool-ok 运行一个命令"}""")
            recordUntil(until = ::settled)
            send("""{"id":"c-2","type":"get_messages"}""")
            recordUntil { it.contains("\"c-2\"") }
        }
        record("tool-failure") {
            send("""{"id":"c-1","type":"prompt","message":"#tool-fail 读一个不存在的文件"}""")
            recordUntil(until = ::settled)
        }
        record("follow-up") {
            send("""{"id":"c-1","type":"prompt","message":"#slow 慢慢说"}""")
            recordUntil { it.contains("text_delta") }
            send("""{"id":"c-2","type":"prompt","message":"#text 做完再说这个","streamingBehavior":"followUp"}""")
            recordUntil(until = ::settled)
        }
        record("steer") {
            send("""{"id":"c-1","type":"prompt","message":"#slow-tool 跑个慢命令"}""")
            recordUntil { it.contains("tool_execution_start") }
            send("""{"id":"c-2","type":"prompt","message":"#text 先别跑了","streamingBehavior":"steer"}""")
            recordUntil(until = ::settled)
        }
        record("clear-queue-abort") {
            send("""{"id":"c-1","type":"prompt","message":"#slow 慢慢说"}""")
            recordUntil { it.contains("text_delta") }
            send("""{"id":"c-2","type":"prompt","message":"#text 不该被发出","streamingBehavior":"followUp"}""")
            recordUntil { it.contains("\"c-2\"") }
            send("""{"id":"c-3","type":"clear_queue"}""")
            recordUntil { it.contains("\"c-3\"") }
            send("""{"id":"c-4","type":"abort"}""")
            var sawSettled = false
            recordUntil { sawSettled = sawSettled || settled(it); it.contains("\"c-4\"") }
            if (!sawSettled) runCatching { recordUntil(3_000, ::settled) }
        }
        record("retry") {
            send("""{"id":"c-1","type":"prompt","message":"#retry 第一次会失败"}""")
            recordUntil(60_000, ::settled)
        }
        record("compaction") {
            send("""{"id":"c-1","type":"prompt","message":"#text 先聊一句"}""")
            recordUntil(until = ::settled)
            send("""{"id":"c-2","type":"compact"}""")
            recordUntil(60_000) { it.contains("\"c-2\"") }
        }
        record("provider-error") {
            send("""{"id":"c-1","type":"prompt","message":"#fail401 用错误的 Key"}""")
            recordUntil(60_000, ::settled)
        }
        record("protocol-errors") {
            send("""not json""")
            recordUntil { it.contains("\"parse\"") }
            send("""{"id":"c-1","type":"set_model","provider":"nope","modelId":"missing"}""")
            recordUntil { it.contains("\"c-1\"") }
            send("""{"id":"c-2","type":"get_available_models"}""")
            recordUntil { it.contains("\"c-2\"") }
            send("""{"id":"c-3","type":"get_available_thinking_levels"}""")
            recordUntil { it.contains("\"c-3\"") }
            send("""{"id":"c-4","type":"get_session_stats"}""")
            recordUntil { it.contains("\"c-4\"") }
        }
    }
}
