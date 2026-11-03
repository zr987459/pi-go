package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.contract.AppLog
import io.github.zr987459.pigo.contract.InstallStep
import io.github.zr987459.pigo.contract.LogLine
import io.github.zr987459.pigo.core.runtime.install.DomesticInstaller
import io.github.zr987459.pigo.core.runtime.install.HostInfo
import io.github.zr987459.pigo.core.runtime.install.Mirror
import io.github.zr987459.pigo.core.runtime.install.Mirrors
import io.github.zr987459.pigo.core.runtime.install.Pins
import io.github.zr987459.pigo.core.runtime.process.GuestProcessImpl
import io.github.zr987459.pigo.core.runtime.process.ProcessTree
import io.github.zr987459.pigo.core.runtime.process.Signaller
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 审查第 1 轮 #1：exec 被取消时必须先停掉整棵进程树（管道随之关闭，排空协程读到 EOF），
 * 否则 coroutineScope 一直等阻塞在 read() 的排空协程，apt/npm 继续跑、安装锁不释放。
 *
 * 用真实 Linux 进程充当「proot」（processFactory 替换），信号按 PID 发送。
 */
class GuestRunnerCancelTest {
    private val tmp: File = Files.createTempDirectory("cancel").toFile()
    private val nativeDir = File(tmp, "native").apply {
        mkdirs()
        listOf("libproot.so", "libprootloader.so", "libprootloader32.so", "libtalloc.so", "libandroidshmem.so").forEach {
            File(this, it).apply { writeText("elf"); setExecutable(true) }
        }
    }
    private val paths = RuntimePaths(File(tmp, "files"), nativeDir, null)
    private val started = CopyOnWriteArrayList<Process>()
    private val tree = ProcessTree()

    private val myUid: Int by lazy {
        File("/proc/self/status").readLines().first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()
    }

    private val signaller = Signaller { pid, sig -> ProcessBuilder("kill", "-$sig", pid.toString()).start().waitFor() }

    /** 「apt-get」：忽略 SIGTERM 的 shell，带两个一直持有 stdout/stderr 的后代，永不自行退出。 */
    private val runner by lazy {
        GuestRunner(paths, myUid, signaller, tree) { _, _ ->
            ProcessBuilder("sh", "-c", "trap '' TERM; sleep 300 & sleep 300 & wait").start().also { started += it }
        }
    }

    @Before fun requireProc() {
        assumeTrue(File("/proc/self/stat").exists())
    }

    @After fun cleanup() {
        started.forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
    }

    private fun alive(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.exists()) return false
        val state = runCatching { stat.readText().substringAfterLast(')').trim().split(' ')[0] }.getOrNull()
        return state != null && state != "Z"
    }

    /** 等第 [index] 个启动的进程树长出 root + 2 个 sleep，返回快照。 */
    private fun awaitTree(index: Int): List<ProcessTree.Info> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val p = started.getOrNull(index)
            if (p != null) {
                val members = tree.tree(GuestProcessImpl.pidOf(p), myUid)
                if (members.size >= 3) return members
            }
            Thread.sleep(50)
        }
        error("进程树没有起来：$started")
    }

    @Test fun cancellingExecStopsProcessTreeAndReturnsPromptly() = runBlocking {
        val job = launch(Dispatchers.IO) { runner.exec(listOf("apt-get", "update"), timeoutMs = 20 * 60_000) }
        val members = awaitTree(0)
        job.cancel()
        // 修复前这里会一直等（排空协程阻塞在 read()，进程 20 分钟后才因超时被停）
        withTimeout(15_000) { job.join() }
        Thread.sleep(200)
        for (m in members) assertFalse("PID ${m.pid} 在取消后仍存活", alive(m.pid))
    }

    @Test fun normalExitStillReturnsOutput() = runBlocking {
        val r = GuestRunner(paths, myUid, signaller, tree) { _, _ ->
            ProcessBuilder("sh", "-c", "echo hi; echo oops >&2; exit 3").start().also { started += it }
        }.exec(listOf("x"), timeoutMs = 10_000)
        assertEquals(3, r.exitCode)
        assertEquals("hi\n", r.stdout)
        assertEquals("oops\n", r.stderr)
        assertFalse(r.timedOut)
    }

    @Test fun cancellingInstallDuringAptStopsTreeAndReleasesInstallLock() = runBlocking {
        // ROOTFS/NODE 已完成，安装直接走到 FIXUPS → APT（容器内命令 = 上面永不退出的进程树）
        listOf("usr/bin", "root", "tmp", "etc").forEach { File(paths.rootfs, it).mkdirs() }
        File(paths.rootfs, "etc/group").writeText("root:x:0:\n")
        val markers = DomesticInstaller.Markers(paths.markers)
        markers.mark(InstallStep.ROOTFS)
        markers.mark(InstallStep.NODE)

        val mgr = RealRuntimeManager(paths, pins, mirrors, Host(), runner, OkHttpClient(), NoLog)
        val job = launch(Dispatchers.IO) { runCatching { mgr.install().collect() } }
        val members = awaitTree(0)
        job.cancel()
        withTimeout(15_000) { job.join() }
        Thread.sleep(200)
        for (m in members) assertFalse("PID ${m.pid} 在中止安装后仍存活", alive(m.pid))

        // 锁已释放：再次安装能开始（第一条进度是 PREFLIGHT），而不是「安装已在进行中」
        val first = withTimeout(10_000) { mgr.install().first() }
        assertEquals(InstallStep.PREFLIGHT, first.step)
        assertTrue(mgr.status.value.error == null || !mgr.status.value.error!!.contains("进行中"))
    }

    private val pins = Pins(
        piPackage = "@earendil-works/pi-coding-agent", piVersion = "1.5.0", piIntegrity = "sha512-x",
        piCompat = ">=1.5.0 <2.0.0", piBlocked = emptyList(),
        nodeVersion = "24.22.2", nodeSha256 = mapOf("arm64" to "0".repeat(64), "x64" to "0".repeat(64)),
        ubuntuVersion = "24.04.5", ubuntuSha256 = mapOf("arm64" to "0".repeat(64), "amd64" to "0".repeat(64)),
    )

    private val mirrors = Mirrors(
        ubuntuBase = listOf(Mirror("none", "http://127.0.0.1:9/")),
        ubuntuBaseOfficial = emptyList(),
        node = listOf(Mirror("none", "http://127.0.0.1:9/")),
        nodeOfficial = emptyList(),
        apt = mapOf("arm64" to "http://mirrors.aliyun.com/ubuntu-ports/"),
        npmRegistry = "http://127.0.0.1:9/",
        dns = listOf("223.5.5.5"),
    )

    private class Host : HostInfo {
        override val abis = listOf("arm64-v8a")
        override fun freeBytes(dir: File) = 10L shl 30
        override fun networkAvailable() = true
        override fun dnsServers() = listOf("10.0.0.1")
        override fun gids() = listOf(0)
    }

    private object NoLog : AppLog {
        override fun i(tag: String, msg: String) {}
        override fun w(tag: String, msg: String, t: Throwable?) {}
        override fun tail(n: Int) = emptyList<LogLine>()
    }
}
