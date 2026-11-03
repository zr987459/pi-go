package io.github.zr987459.pigo.core.runtime.process

import io.github.zr987459.pigo.contract.GuestProcess
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * 包装 proot 的 `java.lang.Process`。[stop] 只结束以 [prootPid] 为根、属于本 App uid 的进程树（DESIGN.md §4.5）：
 * 关 stdin → 等 graceMs → SIGTERM 整棵树 → 等 [termWaitMs] → SIGKILL 仍存活者 → destroyForcibly。
 * 不使用 pkill / killall / pidof 或任何按名字匹配的方式。
 */
class GuestProcessImpl(
    private val process: Process,
    override val prootPid: Int,
    private val uid: Int,
    private val tree: ProcessTree = ProcessTree(),
    private val signaller: Signaller,
    private val termWaitMs: Long = 3_000,
) : GuestProcess {

    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream

    override suspend fun awaitExit(): Int = runInterruptible(Dispatchers.IO) { process.waitFor() }

    override suspend fun stop(graceMs: Long) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { process.outputStream.close() }
        if (waitExit(graceMs)) {
            // proot 已退出；--kill-on-exit 通常已带走子进程，这里再清一遍仍挂在原树上的残留（此时它们已被收养，找不到就算了）
            return@withContext
        }
        // 先拍快照：proot 先退出后子进程会被 init 收养，之后就无法从 PPID 找回。
        val members = tree.tree(prootPid, uid)
        for (m in members) if (tree.stillSame(m)) signaller.send(m.pid, Signaller.SIGTERM)

        val deadline = System.currentTimeMillis() + termWaitMs
        while (System.currentTimeMillis() < deadline) {
            if (members.none { tree.stillSame(it) } && !process.isAlive) break
            delay(50)
        }
        // 树上新冒出的后代也算（同一根下）
        val again = (members + tree.tree(prootPid, uid)).distinctBy { it.pid to it.startTime }
        for (m in again) if (tree.stillSame(m)) signaller.send(m.pid, Signaller.SIGKILL)
        process.destroyForcibly()
        waitExit(1_000)
        Unit
    }

    private fun waitExit(ms: Long): Boolean = runCatching { process.waitFor(ms, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    companion object {
        /** 取 `java.lang.Process` 的 PID：Java 9+ 有 `pid()`；Android 旧版本用 `UNIXProcess.pid` 字段。 */
        fun pidOf(p: Process): Int {
            runCatching {
                // 通过公开的 java.lang.Process 声明调用（Java 9+ / Android 新版本），避免反射非公开实现类
                val m = Process::class.java.getMethod("pid")
                return (m.invoke(p) as Number).toInt()
            }
            var c: Class<*>? = p.javaClass
            while (c != null) {
                runCatching {
                    val f = c.getDeclaredField("pid")
                    f.isAccessible = true
                    return f.getInt(p)
                }
                c = c.superclass
            }
            error("无法取得进程 PID")
        }
    }
}
