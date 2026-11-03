package io.github.zr987459.pigo.core.engine

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.LaunchEnvProvider
import io.github.zr987459.pigo.contract.LogLevel
import io.github.zr987459.pigo.contract.LogLine
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import io.github.zr987459.pigo.core.protocol.RecordCodec
import io.github.zr987459.pigo.core.protocol.str
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 脚本化的假 pi 进程：用管道代替 stdin/stdout/stderr，由一个专用线程按 [handler] 应答命令。
 * 写 stdout/stderr 统一走这个线程，避免 PipedInputStream 的「写线程已死」问题。
 */
class ScriptedPi(
    val spec: GuestSpec,
    private val handler: ScriptedPi.(JsonObject) -> Unit,
) : GuestProcess {
    private val outW = PipedOutputStream()
    private val outR = PipedInputStream(outW, 1 shl 20)
    private val errW = PipedOutputStream()
    private val errR = PipedInputStream(errW, 1 shl 16)
    private val inR = PipedInputStream(1 shl 20)
    private val inW = PipedOutputStream(inR)
    private val exit = CompletableFuture<Int>()
    private val jobs = LinkedBlockingQueue<() -> Unit>()

    /** 收到的全部命令（按到达顺序）。 */
    val received = CopyOnWriteArrayList<JsonObject>()
    val stopCalls: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    @Volatile var stdinClosed = false

    override val stdin: OutputStream = object : OutputStream() {
        override fun write(b: Int) = inW.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = inW.write(b, off, len)
        override fun flush() = inW.flush()
        override fun close() {
            stdinClosed = true
            inW.close()
        }
    }
    override val stdout: InputStream = outR
    override val stderr: InputStream = errR
    override val prootPid: Int = nextPid++

    init {
        Thread({ worker() }, "scripted-pi-$prootPid").apply { isDaemon = true }.start()
        Thread({ readCommands() }, "scripted-pi-in-$prootPid").apply { isDaemon = true }.start()
    }

    private fun worker() {
        while (true) {
            val job = jobs.poll(50, TimeUnit.MILLISECONDS)
            if (job == null) {
                if (exit.isDone) break
                continue
            }
            try {
                job()
            } catch (_: IOException) {
            }
        }
    }

    private fun readCommands() {
        val buf = java.io.ByteArrayOutputStream()
        try {
            while (true) {
                val b = inR.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val cmd = RecordCodec.json.parseToJsonElement(buf.toString(Charsets.UTF_8.name())) as JsonObject
                    buf.reset()
                    received += cmd
                    jobs += { handler(cmd) }
                } else {
                    buf.write(b)
                }
            }
        } catch (_: IOException) {
        }
        // stdin 关闭 = pi 的有序关闭
        jobs += { exitNow(0) }
    }

    /** 在脚本线程上写一行 stdout。 */
    fun emit(json: String) {
        if (exit.isDone) return
        outW.write((json + "\n").toByteArray(Charsets.UTF_8))
        outW.flush()
    }

    fun stderrLine(line: String) {
        if (exit.isDone) return
        errW.write((line + "\n").toByteArray(Charsets.UTF_8))
        errW.flush()
    }

    fun ok(cmd: JsonObject, data: String? = null) {
        val id = cmd.str("id")
        emit("""{"id":"$id","type":"response","command":"${cmd.str("type")}","success":true${data?.let { ",\"data\":$it" } ?: ""}}""")
    }

    fun fail(cmd: JsonObject, error: String) {
        emit("""{"id":"${cmd.str("id")}","type":"response","command":"${cmd.str("type")}","success":false,"error":${JsonPrimitive(error)}}""")
    }

    /** 稍后在脚本线程上执行（模拟异步事件）。 */
    fun later(delayMs: Long, block: ScriptedPi.() -> Unit) {
        Thread {
            Thread.sleep(delayMs)
            jobs += { block() }
        }.apply { isDaemon = true }.start()
    }

    fun exitNow(code: Int) {
        if (exit.isDone) return
        runCatching { outW.close() }
        runCatching { errW.close() }
        exit.complete(code)
    }

    /** 模拟进程自己崩溃：先写 stderr，再退出。 */
    fun crash(code: Int, vararg stderr: String) {
        jobs += {
            stderr.forEach { stderrLine(it) }
            Thread.sleep(20)
            exitNow(code)
        }
    }

    val exited: Boolean get() = exit.isDone

    override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { exit.get() }

    override suspend fun stop(graceMs: Long) {
        stopCalls += graceMs
        withContext(Dispatchers.IO) {
            runCatching { stdin.close() }
            jobs += { exitNow(if (graceMs == 0L) 137 else 0) }
            exit.get(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        @Volatile private var nextPid = 40_000
        const val STATE = """{"model":{"id":"m1","name":"M1","provider":"p","reasoning":true,"contextWindow":1000},"thinkingLevel":"medium","isStreaming":false,"steeringMode":"one-at-a-time","followUpMode":"one-at-a-time","sessionFile":"/root/.pi/agent/sessions/--root-pi-cwd--/s1.jsonl","sessionId":"s1","pendingMessageCount":0}"""
    }
}

/** 每次 start() 用 [script] 生成一个新的 [ScriptedPi]（参数为第几次启动，从 1 开始）。 */
class ScriptedRuntime(private val script: (Int) -> (ScriptedPi.(JsonObject) -> Unit)) : RuntimeManager {
    val processes = CopyOnWriteArrayList<ScriptedPi>()
    @Volatile var failStart: Exception? = null

    override val status: StateFlow<RuntimeStatus> = MutableStateFlow(RuntimeStatus(Phase.READY, "24.04.5", "v24.22.2", "1.5.0", null))
    override fun install(): Flow<InstallProgress> = emptyFlow()
    override suspend fun repair(): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun upgradePi(version: String): Outcome<String> = Outcome.Ok(version)
    override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long) = ExecResult(0, "", "")
    override fun hostFile(guestPath: String) = File(guestPath)

    override fun start(spec: GuestSpec): GuestProcess {
        failStart?.let { throw it }
        val p = ScriptedPi(spec, script(processes.size + 1))
        processes += p
        return p
    }

    val last: ScriptedPi get() = processes.last()
}

class StaticEnv(private val env: Map<String, String> = mapOf("DEEPSEEK_API_KEY" to "sk-test-secret-0001")) : LaunchEnvProvider {
    override suspend fun env(): Map<String, String> = env
}

class ListLog : AppLog {
    val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())
    override fun i(tag: String, msg: String) {
        lines += LogLine(0, LogLevel.INFO, tag, msg)
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        lines += LogLine(0, LogLevel.WARN, tag, msg + (t?.let { " $it" } ?: ""))
    }

    override fun tail(n: Int): List<LogLine> = synchronized(lines) { lines.takeLast(n) }
}
