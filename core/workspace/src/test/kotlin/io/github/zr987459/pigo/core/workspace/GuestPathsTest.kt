package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestPathsTest {
    private lateinit var tmp: File
    private lateinit var rootfs: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("pigo-ws").toFile()
        rootfs = File(tmp, "rootfs").apply { mkdirs() }
        outside = File(tmp, "secret.txt").apply { writeText("宿主机的秘密") }
        File(rootfs, "root/proj/src").mkdirs()
        File(rootfs, "root/proj/a.txt").writeText("hello")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("root:x:0:0")
        File(rootfs, "usr/lib").mkdirs()
        File(rootfs, "usr/lib/lib.txt").writeText("lib")
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    private fun link(at: String, target: String) {
        Files.createSymbolicLink(File(rootfs, at.removePrefix("/")).toPath(), File(target).toPath())
    }

    private fun paths() = GuestPaths(rootfs, listOf("/root"))

    private fun denied(block: () -> Unit) {
        try { block(); fail("应被拒绝") } catch (_: PathDeniedException) {}
    }

    @Test
    fun parentSegmentsAreRejected() {
        val p = paths()
        denied { p.resolve("/root/../etc/passwd") }
        denied { p.resolve("/root/proj/../../etc") }
        denied { p.resolve("root/proj") }
        denied { p.resolve("/etc/passwd") }
        assertEquals("/root/proj/a.txt", p.resolve("/root//proj/./a.txt"))
    }

    @Test
    fun relativeLinkInsideRootIsFollowed() {
        link("/root/proj/link.txt", "a.txt")
        assertEquals("/root/proj/a.txt", paths().resolve("/root/proj/link.txt"))
        assertEquals("hello", paths().hostFile("/root/proj/link.txt").readText())
    }

    @Test
    fun absoluteLinkResolvesAgainstRootfsNotHost() {
        // /root/proj/hostlink -> <宿主上 rootfs 外的文件>：容器语义下是 rootfs 里的同名路径（不存在），且在 /root 以外 → 拒绝
        link("/root/proj/hostlink", outside.absolutePath)
        denied { paths().hostFile("/root/proj/hostlink") }
        // 绝对链接指向容器内 /etc → 越出允许范围
        link("/root/proj/etc", "/etc")
        denied { paths().resolve("/root/proj/etc/passwd") }
        // 绝对链接指向容器内 /root/proj/src → 允许
        link("/root/proj/srclink", "/root/proj/src")
        assertEquals("/root/proj/src", paths().resolve("/root/proj/srclink"))
    }

    @Test
    fun relativeLinkClimbingOutOfRootfsIsClampedAndDenied() {
        // ../../../../.. 在容器语义里止于 /，然后进入 /etc，越出 /root → 拒绝；绝不会读到宿主文件
        link("/root/proj/up", "../../../../../../../" + outside.name)
        denied { paths().hostFile("/root/proj/up") }
        link("/root/proj/up2", "../../../../../../etc/passwd")
        denied { paths().hostFile("/root/proj/up2") }
    }

    @Test
    fun link2symlinkHostPathIsMappedBackIntoRootfs() {
        // PRoot link2symlink：链接目标是宿主绝对路径 <rootfs>/root/proj/.l2s.a
        File(rootfs, "root/proj/.l2s.a").writeText("hard")
        link("/root/proj/hard.txt", File(rootfs, "root/proj/.l2s.a").absolutePath)
        assertEquals("hard", paths().hostFile("/root/proj/hard.txt").readText())
    }

    @Test
    fun linkLoopIsRejected() {
        link("/root/proj/l1", "l2")
        link("/root/proj/l2", "l1")
        denied { paths().resolve("/root/proj/l1") }
    }

    @Test
    fun extraRootAllowsSessionCwdOutsideRoot() {
        File(rootfs, "work/x").mkdirs()
        val p = GuestPaths(rootfs, listOf("/root", "/work"))
        assertEquals("/work/x", p.resolve("/work/x"))
        assertTrue(p.isAllowedLexically("/work/x/y"))
        assertFalse(p.isAllowedLexically("/workshop"))
    }

    @Test
    fun workspaceListMarksEscapingLinksAndRefusesThem() = runBlocking {
        link("/root/proj/etc", "/etc")
        link("/root/proj/ok", "a.txt")
        val ws = RootfsWorkspace(rootfs, { _, _ -> ExecResult(1, "", "") }, Dispatchers.Unconfined)
        val list = (ws.list("/root/proj") as Outcome.Ok).value
        val byName = list.associateBy { it.name }
        assertTrue(byName.getValue("etc").isSymlink)
        assertFalse(byName.getValue("etc").isDir)
        assertTrue(byName.getValue("ok").isSymlink)
        assertEquals(5L, byName.getValue("ok").sizeBytes)
        assertTrue(ws.readText("/root/proj/etc/passwd") is Outcome.Err)
        assertTrue(ws.list("/root/../etc") is Outcome.Err)
        // 目录排在前面
        assertTrue(list.first().isDir)
    }

    @Test
    fun previewTruncatesAtUtf8BoundaryAndDetectsBinary() = runBlocking {
        File(rootfs, "root/proj/zh.txt").writeText("中".repeat(10))
        File(rootfs, "root/proj/bin.dat").writeBytes(byteArrayOf(1, 2, 0, 3, 4))
        val ws = RootfsWorkspace(rootfs, { _, _ -> ExecResult(1, "", "") }, Dispatchers.Unconfined)
        val chunk = (ws.readText("/root/proj/zh.txt", maxBytes = 7) as Outcome.Ok).value
        assertEquals("中中", chunk.text)
        assertTrue(chunk.truncated)
        assertEquals(30L, chunk.totalBytes)
        assertTrue((ws.preview("/root/proj/bin.dat") as Outcome.Ok).value is FilePreview.Binary)
        val md = File(rootfs, "root/proj/README.md").apply { writeText("# hi") }
        val p = (ws.preview("/root/proj/README.md") as Outcome.Ok).value as FilePreview.Text
        assertTrue(p.markdown)
        assertEquals("markdown", p.chunk.language)
        assertTrue(md.exists())
    }
}
