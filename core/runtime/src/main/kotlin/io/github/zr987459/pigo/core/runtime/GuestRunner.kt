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
    private val processFactory: (argv: List<String>, hostEnv: Map<String, String>) -> Process = ::defaultStart,
) {
    fun start(spec: GuestSpec): GuestProcess {
        RuntimeFiles.ensure(paths)
        paths.guest(spec.cwd).mkdirs()
        val argv = ProotLauncher.buildArgv(paths, spec)
        val p = processFactory(argv, ProotLauncher.hostEnv(paths))
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
    }

    companion object {
        fun defaultStart(argv: List<String>, hostEnv: Map<String, String>): Process {
            val pb = ProcessBuilder(argv)
            val env = pb.environment()
            // 宿主环境也只给 proot 必需的几项，避免把 App 进程的环境原样带过去
            val keep = setOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH")
            env.keys.retainAll(keep)
            env.putAll(hostEnv)
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
