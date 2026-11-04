package io.github.zr987459.pigo.core.runtime

import io.github.zr987459.pigo.core.runtime.process.ProcessTree
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** r3/core：从 proot 进程树里找出 pi 进程（SessionHost.debugEnginePid，DESIGN-r3 §7.2 B 档）。 */
class ProcessTreeEnginePidTest {
    private val proc: File = Files.createTempDirectory("fakeproc").toFile()

    @After
    fun tearDown() { proc.deleteRecursively() }

    private fun p(pid: Int, ppid: Int, start: Long, exe: String, uid: Int = 10123) {
        val d = File(proc, "$pid").apply { mkdirs() }
        // 字段：pid (comm) state ppid ... 第 22 个字段 starttime
        val rest = MutableList(20) { "0" }
        rest[0] = "S"; rest[1] = "$ppid"; rest[19] = "$start"
        File(d, "stat").writeText("$pid (x y) " + rest.joinToString(" "))
        File(d, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        val target = File(proc, "bin/$exe").apply { parentFile.mkdirs(); writeText("") }
        Files.createSymbolicLink(File(d, "exe").toPath(), target.toPath())
    }

    @Test
    fun picksEarliestNonProotDescendant() {
        p(100, 1, 10, "libproot.so")
        p(101, 100, 11, "libproot.so")          // proot 的子 tracer
        p(102, 101, 12, "node")                 // pi
        p(150, 102, 50, "bash")                 // pi 运行工具时派生，启动更晚
        p(151, 150, 51, "sleep")
        p(200, 1, 5, "node")                    // 无关进程
        val t = ProcessTree(proc)
        assertEquals(102, t.enginePid(100, 10123))
    }

    @Test
    fun rootItselfWhenNoDescendant() {
        p(300, 1, 10, "node")
        assertEquals(300, ProcessTree(proc).enginePid(300, 10123))
    }

    @Test
    fun goneOrOtherUid() {
        assertNull(ProcessTree(proc).enginePid(999, 10123))
        p(400, 1, 10, "libproot.so", uid = 0)
        assertNull("不是自己的进程", ProcessTree(proc).enginePid(400, 10123))
    }
}
