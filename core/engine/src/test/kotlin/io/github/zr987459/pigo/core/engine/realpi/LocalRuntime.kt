package io.github.zr987459.pigo.core.engine.realpi

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.contract.InstallProgress
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.Phase
import io.github.zr987459.pigo.contract.RuntimeManager
import io.github.zr987459.pigo.contract.RuntimeStatus
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 桌面版 [RuntimeManager]：不用 PRoot，直接在主机上起 pi（DESIGN.md §6 engine 行「开发期在桌面 Linux 上用真 pi」）。
 * `argv[0] == "pi"` 换成 [piBin]；GuestSpec 的 cwd 与环境原样使用（测试里已是主机路径）。
 */
class LocalRuntime(private val piBin: String) : RuntimeManager {
    override val status: StateFlow<RuntimeStatus> = MutableStateFlow(RuntimeStatus(Phase.READY, null, null, "1.5.0", null))
    override fun install(): Flow<InstallProgress> = emptyFlow()
    override suspend fun repair(): Outcome<Unit> = Outcome.Ok(Unit)
    override suspend fun upgradePi(version: String): Outcome<String> = Outcome.Ok(version)
    override fun hostFile(guestPath: String): File = File(guestPath)

    @Volatile var started = 0
        private set
    @Volatile var last: LocalProcess? = null
        private set

    override fun start(spec: GuestSpec): GuestProcess {
        val argv = spec.argv.toMutableList()
        if (argv.firstOrNull() == "pi") argv[0] = piBin
        File(spec.cwd).mkdirs()
        val pb = ProcessBuilder(argv).directory(File(spec.cwd))
        pb.environment().apply {
            // 测试机上的 HTTP(S)_PROXY 会把发往本机假模型服务的请求导走：桌面测试一律不走代理。
            keys.removeIf { it.lowercase().endsWith("_proxy") }
            putAll(spec.env)
        }
        started++
        return LocalProcess(pb.start()).also { last = it }
    }

    override suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(cmd).apply { environment().putAll(env) }.start()
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        ExecResult(if (done) p.exitValue() else -1, p.inputStream.readBytes().decodeToString(), p.errorStream.readBytes().decodeToString(), !done)
    }
}

/** 只结束自己的进程树（ProcessHandle.descendants），对应 §4.5。 */
class LocalProcess(val process: Process) : GuestProcess {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    // Android 单测的编译类路径是 android.jar，没有 Process.pid()/descendants()；运行在桌面 JDK 上，用反射调用。
    override val prootPid: Int get() = (Process::class.java.getMethod("pid").invoke(process) as Long).toInt()

    private fun descendants(): List<Any> {
        val m = Process::class.java.getMethod("descendants")
        @Suppress("UNCHECKED_CAST")
        val stream = m.invoke(process) as java.util.stream.Stream<Any>
        return stream.collect(java.util.stream.Collectors.toList())
    }

    private fun call(handle: Any, name: String) {
        runCatching { Class.forName("java.lang.ProcessHandle").getMethod(name).invoke(handle) }
    }

    override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }

    override suspend fun stop(graceMs: Long) = withContext(Dispatchers.IO) {
        runCatching { process.outputStream.close() }
        if (graceMs > 0 && process.waitFor(graceMs, TimeUnit.MILLISECONDS)) return@withContext
        val tree = descendants()
        tree.forEach { call(it, "destroy") }
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            tree.forEach { call(it, "destroyForcibly") }
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
        }
        Unit
    }

    /** 模拟被系统杀（SIGKILL，不经过我们的 stop）。 */
    fun kill9() {
        descendants().forEach { call(it, "destroyForcibly") }
        process.destroyForcibly()
    }
}
