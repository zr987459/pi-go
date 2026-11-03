package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.process.GuestProcessImpl
import io.github.zr987459.pigo.core.runtime.process.ProcessTree
import io.github.zr987459.pigo.core.runtime.process.Signaller
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * GuestProcess.stop() 只结束以 proot 为根、属于本 uid 的进程树（DESIGN.md §4.5）。
 * 在 Linux JVM 上用真实进程验证；信号按 PID 发送（不按名字）。
 */
class GuestProcessStopTest {
    private val spawned = mutableListOf<Process>()
    private val tmp: File = Files.createTempDirectory("proc").toFile()

    @After fun cleanup() {
        spawned.forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
    }

    private val myUid: Int by lazy {
        File("/proc/self/status").readLines().first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))[0].toInt()
    }

    private val sent = mutableListOf<Pair<Int, Int>>()
    private val signaller = Signaller { pid, sig ->
        sent += pid to sig
        ProcessBuilder("kill", "-$sig", pid.toString()).start().waitFor()
    }

    private fun alive(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.exists()) return false
        // 僵尸（Z）视为已结束
        val state = runCatching { stat.readText().substringAfterLast(')').trim().split(' ')[0] }.getOrNull()
        return state != null && state != "Z"
    }

    private fun spawn(vararg cmd: String) = ProcessBuilder(*cmd).start().also { spawned += it }

    @Test fun stopKillsOnlyOwnTree() = runBlocking {
        assumeTrue(File("/proc/self/stat").exists())
        // 无关进程：同 uid、同名（sleep），但不在树上 → 不得被杀
        val bystander = spawn("sleep", "300")
        // 「proot」：一个忽略 SIGTERM 的 shell，带两个后代（其中一个是孙进程）
        val root = spawn("sh", "-c", "trap '' TERM; sleep 300 & (sleep 300 & wait) & wait")
        val rootPid = GuestProcessImpl.pidOf(root)
        val tree = ProcessTree()
        var members = emptyList<ProcessTree.Info>()
        for (i in 0 until 50) {
            members = tree.tree(rootPid, myUid)
            if (members.size >= 4) break
            Thread.sleep(50)
        }
        assertTrue("树上应至少有 root + 3 个后代：$members", members.size >= 4)
        assertFalse(members.any { it.pid == GuestProcessImpl.pidOf(bystander) })

        val gp = GuestProcessImpl(root, rootPid, myUid, tree, signaller, termWaitMs = 1_000)
        gp.stop(graceMs = 300)

        Thread.sleep(200)
        for (m in members) assertFalse("PID ${m.pid} 仍存活", alive(m.pid))
        assertTrue("无关进程被误杀", bystander.isAlive)
        assertTrue("向无关进程发了信号：$sent", sent.none { it.first == GuestProcessImpl.pidOf(bystander) })
        // 整棵树先收到 TERM；TERM 后仍存活的（忽略 TERM 的 sleep）再收到 KILL，且 KILL 只发给树上成员
        for (m in members) assertTrue("未向 ${m.pid} 发 TERM：$sent", sent.contains(m.pid to Signaller.SIGTERM))
        val killed = sent.filter { it.second == Signaller.SIGKILL }.map { it.first }.toSet()
        assertTrue("KILL 发给了树外进程：$sent", killed.all { k -> members.any { it.pid == k } })
    }

    @Test fun gracefulExitAfterStdinCloseSendsNoSignals() = runBlocking {
        val p = spawn("cat")
        val gp = GuestProcessImpl(p, GuestProcessImpl.pidOf(p), myUid, ProcessTree(), signaller)
        gp.stop(graceMs = 2_000)
        assertFalse(p.isAlive)
        assertTrue(sent.isEmpty())
    }

    @Test fun treeIgnoresOtherUidAndParsesTrickyComm() {
        val proc = File(tmp, "proc")
        fun fake(pid: Int, ppid: Int, uid: Int, comm: String) {
            val d = File(proc, "$pid").apply { mkdirs() }
            val fields = (List(17) { "0" } + listOf("4242") + List(10) { "0" }).joinToString(" ")
            File(d, "stat").writeText("$pid ($comm) S $ppid $fields\n")
            File(d, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        }
        fake(100, 1, 1000, "libproot.so")
        fake(101, 100, 1000, "node) S 1 (evil")
        fake(102, 101, 1000, "pi")
        fake(103, 100, 0, "su")          // 其他 uid：不碰
        fake(104, 103, 1000, "orphan")   // 挂在其他 uid 进程下：不碰
        fake(200, 1, 1000, "node")       // 同名无关进程
        val t = ProcessTree(proc)
        val pids = t.tree(100, 1000).map { it.pid }
        assertEquals(listOf(102, 101, 100), pids)
        assertEquals(4242L, t.info(101)!!.startTime)
    }
}
