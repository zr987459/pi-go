package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.GuestProcess
import io.github.zr987459.pigo.contract.GuestSpec
import io.github.zr987459.pigo.core.runtime.process.GuestProcessImpl
import io.github.zr987459.pigo.core.runtime.process.ProcessTree
import io.github.zr987459.pigo.core.runtime.process.Signaller
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 启动容器内进程。真实环境走 proot；单测可替换 [processFactory]。 */
class GuestRunner(
    private val paths: RuntimePaths,
    private val uid: Int,
    private val signaller: Signaller,
    private val tree: ProcessTree = ProcessTree(),
    /** argv 不含任何环境变量的值；[processEnv] 为 proot 进程的完整环境（含容器内变量与 Key）。 */
    private val processFactory: (argv: List<String>, processEnv: Map<String, String>) -> Process = ::defaultStart,
) {
    /**
     * 启动容器内进程（非挂起，签名由 `RuntimeManager.start` 决定）。
     *
     * 会做少量文件系统操作（检查 `files/lib` 中的库副本是否就位、创建工作目录、fork proot），
     * **调用方须在工作线程调用**（@WorkerThread 语义；`RealRuntimeManager.exec` 与安装流程已在 `Dispatchers.IO` 上）。
     * 库副本的复制与 SHA256 比对放在安装/修复流程（[RuntimeFiles.ensure]）里做；这里只在副本缺失时补一次复制，
     * 正常路径不读文件内容、不算哈希。
     */
    fun start(spec: GuestSpec): GuestProcess {
        if (!RuntimeFiles.copiesPresent(paths)) RuntimeFiles.ensure(paths)
        paths.guest(spec.cwd).mkdirs()
        val argv = ProotLauncher.buildArgv(paths, spec)
        val p = processFactory(argv, ProotLauncher.processEnv(paths, spec))
        return GuestProcessImpl(p, GuestProcessImpl.pidOf(p), uid, tree, signaller)
    }

    /**
     * 运行并收集输出。stdout/stderr 在各自的 IO 协程里持续排空（避免管道写满卡死子进程）。
     * 超时后按 §4.5 停止进程树并返回 `timedOut = true`。输出各自最多保留 [maxOutputBytes]。
     */
    suspend fun exec(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 60_000,
        cwd: String = "/root",
        stdinText: String? = null,
        maxOutputBytes: Int = 1 shl 20,
    ): ExecResult = coroutineScope {
        val gp = withContext(Dispatchers.IO) { start(GuestSpec(cmd, cwd = cwd, env = env)) }
        try {
            val out = async(Dispatchers.IO) { drain(gp.stdout, maxOutputBytes) }
            val err = async(Dispatchers.IO) { drain(gp.stderr, maxOutputBytes) }
            withContext(Dispatchers.IO) {
                runCatching {
                    if (stdinText != null) gp.stdin.write(stdinText.toByteArray())
                    gp.stdin.close()
                }
            }
            val code = withTimeoutOrNull(timeoutMs) { gp.awaitExit() }
            if (code == null) {
                gp.stop(graceMs = 1_000)
                ExecResult(-1, out.await(), err.await(), timedOut = true)
            } else {
                ExecResult(code, out.await(), err.await())
            }
        } finally {
            // 无论正常结束、超时还是被取消（用户中止安装、ViewModel 销毁），都先按 §4.5 停掉整棵进程树：
            // 进程退出后管道关闭，阻塞在 read() 的两个排空协程才能读到 EOF 结束，coroutineScope 才不会一直等，
            // apt/npm 也不会在取消后继续跑（安装锁随之释放）。进程已退出时 stop() 立即返回。
            withContext(NonCancellable) { gp.stop(graceMs = 1_000) }
        }
    }

    companion object {
        fun defaultStart(argv: List<String>, processEnv: Map<String, String>): Process {
            val pb = ProcessBuilder(argv)
            val env = pb.environment()
            // App 进程的环境只留 proot 必需的几项（进容器前由 `env -u` 去掉），其余全部来自 processEnv。
            env.keys.retainAll(ProotLauncher.HOST_PASSTHROUGH)
            env.putAll(processEnv)
            return pb.start()
        }

        internal fun drain(input: InputStream, max: Int): String {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            input.use {
                while (true) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    val room = max - buf.size()
                    if (room > 0) buf.write(chunk, 0, minOf(n, room))
                }
            }
            return buf.toString(Charsets.UTF_8.name())
        }
    }
}
