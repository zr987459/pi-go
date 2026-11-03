package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.ExportFormat
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ImportExportTest {
    private lateinit var rootfs: File
    private lateinit var ws: RootfsWorkspace
    private val clock = Clock.fixed(Instant.parse("2026-11-03T07:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Before
    fun setUp() {
        rootfs = Files.createTempDirectory("pigo-imp").toFile()
        File(rootfs, "root/pi-cwd").mkdirs()
        ws = RootfsWorkspace(rootfs, { _, _ -> error("不执行命令") }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() { rootfs.deleteRecursively() }

    private fun stream(s: String) = ByteArrayInputStream(s.toByteArray())

    @Test
    fun duplicateNamesGetSequenceNumbers() = runBlocking {
        val a = (ws.importStream("a.txt", stream("1"), "/root/pi-cwd") as Outcome.Ok).value
        val b = (ws.importStream("a.txt", stream("2"), "/root/pi-cwd") as Outcome.Ok).value
        val c = (ws.importStream("a.txt", stream("3"), "/root/pi-cwd") as Outcome.Ok).value
        val d = (ws.importStream("README", stream("4"), "/root/pi-cwd") as Outcome.Ok).value
        val e = (ws.importStream("README", stream("5"), "/root/pi-cwd") as Outcome.Ok).value
        assertEquals(listOf("a.txt", "a (1).txt", "a (2).txt", "README", "README (1)"), listOf(a, b, c, d, e).map { it.name })
        assertEquals("/root/pi-cwd/a (1).txt", b.guestPath)
        assertEquals("2", File(rootfs, "root/pi-cwd/a (1).txt").readText())
        // 不留 .part 半成品
        assertTrue(File(rootfs, "root/pi-cwd").list()!!.none { it.endsWith(".part") })
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.sh", FileNames.sanitize("C:\\x\\evil.sh"))
        assertEquals("bashrc", FileNames.sanitize(".bashrc"))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("ab.txt", FileNames.sanitize("a\u0000b.txt"))
        val long = FileNames.sanitize("长".repeat(200) + ".pdf")
        assertTrue(long.endsWith(".pdf"))
        assertTrue(long.toByteArray().size <= 200)
    }

    @Test
    fun importOutsideAllowedRootsIsRejected() = runBlocking {
        File(rootfs, "etc").mkdirs()
        assertTrue(ws.importStream("x", stream("x"), "/etc") is Outcome.Err)
        assertFalse(File(rootfs, "etc/x").exists())
        assertTrue(ws.importStream("x", stream("x"), "/root/pi-cwd/../../etc") is Outcome.Err)
    }

    @Test
    fun attachmentGoesToDatedUploadDirAndHonoursLimit() = runBlocking {
        val ap = WorkspaceAttachments(ws, TestCodec, clock, Dispatchers.Unconfined)
        val ref = (ap.importFile("报告.pdf", stream("%PDF"), "/root/pi-cwd") as Outcome.Ok).value
        assertEquals("/root/pi-cwd/.pigo/uploads/2026-11-03/报告.pdf", ref.node.guestPath)
        assertEquals("@/root/pi-cwd/.pigo/uploads/2026-11-03/报告.pdf", ref.mention)
        val big = object : InputStream() {
            var left = 3L * 1024 * 1024
            override fun read(): Int = if (left-- > 0) 7 else -1
        }
        val r = ap.importFile("big.bin", big, "/root/pi-cwd", maxBytes = 2L * 1024 * 1024)
        assertTrue(r is Outcome.Err)
        assertTrue((r as Outcome.Err).message.contains("2 MB"))
        assertFalse(File(rootfs, "root/pi-cwd/.pigo/uploads/2026-11-03/big.bin").exists())
        assertTrue(File(rootfs, "root/pi-cwd/.pigo/uploads/2026-11-03").list()!!.none { it.endsWith(".part") })
        // compose：文件引用追加为 @路径
        val (text, images) = ap.compose("看看这个", listOf(ref))
        assertEquals("看看这个\n\n@/root/pi-cwd/.pigo/uploads/2026-11-03/报告.pdf", text)
        assertTrue(images.isEmpty())
        assertTrue(ref is Attachment.FileRef)
    }

    @Test
    fun exportToStreamCopiesBytes() = runBlocking {
        File(rootfs, "root/pi-cwd/out.txt").writeText("导出内容")
        val bos = ByteArrayOutputStream()
        assertEquals(Outcome.Ok(12L), ws.exportTo("/root/pi-cwd/out.txt", bos))
        assertEquals("导出内容", bos.toString(Charsets.UTF_8.name()))
    }

    private val session = listOf(
        """{"type":"session","version":3,"id":"s1","timestamp":"2026-11-03T06:00:00.000Z","cwd":"/root/pi-cwd"}""",
        """{"type":"message","id":"u1","parentId":null,"message":{"role":"user","content":"第一个问题"}}""",
        """{"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","model":"deepseek-chat","content":[{"type":"thinking","thinking":"想一想"},{"type":"text","text":"旧分支回答"}]}}""",
        """{"type":"message","id":"a2","parentId":"u1","message":{"role":"assistant","model":"deepseek-chat","content":[{"type":"text","text":"当前回答"},{"type":"toolCall","id":"t1","name":"bash","arguments":{"command":"ls ```"}}]}}""",
        """坏行{""",
        """{"type":"message","id":"r1","parentId":"a2","message":{"role":"toolResult","toolCallId":"t1","toolName":"bash","content":[{"type":"text","text":"a.txt"}],"isError":false}}""",
        """{"type":"session_info","id":"i1","parentId":"r1","name":"测试会话"}""",
    )

    @Test
    fun markdownExportFollowsCurrentBranch() = runBlocking {
        val dir = File(rootfs, "root/.pi/agent/sessions/--root-pi-cwd--").apply { mkdirs() }
        File(dir, "2026_s1.jsonl").writeText(session.joinToString("\n"))
        val out = Files.createTempDirectory("pigo-out").toFile()
        try {
            val ex = WorkspaceSessionExporter(ws, out, io = Dispatchers.Unconfined)
            val md = (ex.export("/root/.pi/agent/sessions/--root-pi-cwd--/2026_s1.jsonl", ExportFormat.MARKDOWN) as Outcome.Ok).value.readText()
            assertTrue(md.startsWith("# 测试会话\n"))
            assertTrue(md.contains("## 用户\n\n第一个问题"))
            assertTrue(md.contains("当前回答"))
            assertFalse("不导出旧分支", md.contains("旧分支回答"))
            assertTrue(md.contains("**调用工具：bash**"))
            assertTrue("围栏比内容里的反引号长", md.contains("````json"))
            assertTrue(md.contains("<details><summary>工具结果：bash</summary>"))
            val jsonl = (ex.export("/root/.pi/agent/sessions/--root-pi-cwd--/2026_s1.jsonl", ExportFormat.JSONL) as Outcome.Ok).value
            assertEquals(session.joinToString("\n"), jsonl.readText())
            assertTrue(ex.export("/root/.pi/agent/sessions/--root-pi-cwd--/2026_s1.jsonl", ExportFormat.HTML) is Outcome.Err)
            assertTrue(ex.export("/root/pi-cwd/../x.jsonl", ExportFormat.JSONL) is Outcome.Err)
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun htmlExportGoesThroughEngineAndIsCopiedOut() = runBlocking {
        val dir = File(rootfs, "root/.pi/agent/sessions/x").apply { mkdirs() }
        File(dir, "s.jsonl").writeText(session.first())
        val out = Files.createTempDirectory("pigo-out2").toFile()
        try {
            var asked: String? = null
            val ex = WorkspaceSessionExporter(ws, out, exportHtml = { p ->
                asked = p
                File(rootfs, p.removePrefix("/")).writeText("<html>ok</html>")
                Outcome.Ok(p)
            }, io = Dispatchers.Unconfined, currentSessionPath = { "/root/.pi/agent/sessions/x/./s.jsonl" })
            val f = (ex.export("/root/.pi/agent/sessions/x/s.jsonl", ExportFormat.HTML) as Outcome.Ok).value
            assertEquals("/root/.pigo/exports/s.html", asked)
            assertEquals("<html>ok</html>", f.readText())
            assertEquals(out, f.parentFile)
        } finally {
            out.deleteRecursively()
        }
    }

    /** 审查第 1 轮：请求的不是引擎当前会话时，不得用 export_html 导出当前会话冒充。 */
    @Test
    fun htmlExportOfNonCurrentSessionNeverUsesEngine() = runBlocking {
        val dir = File(rootfs, "root/.pi/agent/sessions/x").apply { mkdirs() }
        File(dir, "a.jsonl").writeText(session.first())
        File(dir, "b.jsonl").writeText(session.first())
        val out = Files.createTempDirectory("pigo-out3").toFile()
        try {
            var engineCalls = 0
            val engine: suspend (String) -> Outcome<String> = { p ->
                engineCalls++
                File(rootfs, p.removePrefix("/")).apply { parentFile.mkdirs() }.writeText("<html>当前会话 a</html>")
                Outcome.Ok(p)
            }
            // 1) 没有 CLI 兜底：返回 Err，提示先打开
            val noCli = WorkspaceSessionExporter(ws, out, engine, Dispatchers.Unconfined, currentSessionPath = { "/root/.pi/agent/sessions/x/a.jsonl" })
            val err = noCli.export("/root/.pi/agent/sessions/x/b.jsonl", ExportFormat.HTML)
            assertTrue(err is Outcome.Err && err.message.contains("请先打开该会话"))
            // 当前会话未知（未注入）同样不走引擎
            assertTrue(WorkspaceSessionExporter(ws, out, engine, Dispatchers.Unconfined).export("/root/.pi/agent/sessions/x/a.jsonl", ExportFormat.HTML) is Outcome.Err)
            assertEquals(0, engineCalls)

            // 2) 有 CLI：非当前会话走 pi --export，参数是请求的会话
            var cliArgs: Pair<String, String>? = null
            val withCli = WorkspaceSessionExporter(ws, out, engine, Dispatchers.Unconfined,
                currentSessionPath = { "/root/.pi/agent/sessions/x/a.jsonl" },
                cliExportHtml = { session, o ->
                    cliArgs = session to o
                    File(rootfs, o.removePrefix("/")).writeText("<html>会话 b</html>")
                    Outcome.Ok(o)
                })
            val f = (withCli.export("/root/.pi/agent/sessions/x/b.jsonl", ExportFormat.HTML) as Outcome.Ok).value
            assertEquals("/root/.pi/agent/sessions/x/b.jsonl" to "/root/.pigo/exports/b.html", cliArgs)
            assertEquals("<html>会话 b</html>", f.readText())
            assertEquals(0, engineCalls)
            // 当前会话仍走引擎
            assertEquals("<html>当前会话 a</html>", (withCli.export("/root/.pi/agent/sessions/x/a.jsonl", ExportFormat.HTML) as Outcome.Ok).value.readText())
            assertEquals(1, engineCalls)
        } finally {
            out.deleteRecursively()
        }
    }
}
