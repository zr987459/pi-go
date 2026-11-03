package io.github.zr987459.pigo.core.store

import io.github.zr987459.pigo.contract.Outcome
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {
    private lateinit var rootfs: File
    private lateinit var paths: GuestPaths

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("pigo-rootfs").toFile()
        paths = GuestPaths(rootfs)
    }

    @After fun tearDown() { rootfs.deleteRecursively() }

    private fun header(id: String, cwd: String = "/root/pi-cwd", ts: String = "2026-11-03T06:40:00.000Z") =
        """{"type":"session","version":3,"id":"$id","timestamp":"$ts","cwd":"$cwd"}"""

    private fun user(text: String, id: String = "u1") =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"2026-11-03T06:41:00.000Z","message":{"role":"user","content":${jsonString(text)},"timestamp":1}}"""

    private fun userBlocks(text: String) =
        """{"type":"message","id":"u2","parentId":null,"timestamp":"2026-11-03T06:41:00.000Z","message":{"role":"user","content":[{"type":"image","data":"xx","mimeType":"image/png"},{"type":"text","text":${jsonString(text)}}],"timestamp":1}}"""

    private fun assistant(text: String) =
        """{"type":"message","id":"a1","parentId":"u1","timestamp":"2026-11-03T06:42:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":${jsonString(text)}}],"stopReason":"stop","timestamp":2}}"""

    private fun system() =
        """{"type":"message","id":"s1","parentId":null,"timestamp":"2026-11-03T06:40:01.000Z","message":{"role":"system","content":"","sections":{"preamble":"You are an expert coding assistant. user"},"timestamp":0}}"""

    private fun info(name: String) =
        """{"type":"session_info","id":"i1","parentId":"a1","timestamp":"2026-11-03T06:43:00.000Z","name":${jsonString(name)}}"""

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun writeSession(sub: String, file: String, vararg lines: String, mtime: Long? = null): File {
        val f = File(paths.sessionsDir, "$sub/$file")
        f.parentFile.mkdirs()
        f.writeText(lines.joinToString("\n") + "\n")
        mtime?.let { f.setLastModified(it) }
        return f
    }

    // ---------------- 解析 ----------------

    @Test fun parsesHeaderNameAndPreview() {
        val f = writeSession(
            "--root-pi-cwd--", "2026-11-03T06-40-00-000Z_abc.jsonl",
            header("abc"), system(), user("  帮我\n看看   登录页  "), assistant("好的"), info("旧名字"), info("修登录 bug"),
        )
        val p = SessionFileParser.parse(f)
        assertTrue(p.headerOk)
        assertEquals("abc", p.id)
        assertEquals("/root/pi-cwd", p.cwd)
        assertEquals("修登录 bug", p.name) // 最后一个 session_info 为准
        assertEquals("帮我 看看 登录页", p.preview) // 系统消息里的 "user" 不算；空白折叠
        assertEquals(Instant.parse("2026-11-03T06:40:00Z"), p.createdAt)
    }

    @Test fun previewFromContentBlocksAndTruncatedTo80CodePoints() {
        val long = "中".repeat(100)
        val f = writeSession("--x--", "2026-11-03T06-40-00-000Z_b.jsonl", header("b"), userBlocks(long))
        val p = SessionFileParser.parse(f)
        assertEquals("中".repeat(80) + "…", p.preview)
        assertNull(p.name)
    }

    @Test fun emptyNameMeansCleared() {
        val f = writeSession("--x--", "t_c.jsonl", header("c"), user("hi"), info("有名字"), info(""))
        assertNull(SessionFileParser.parse(f).name)
    }

    @Test fun badLinesAreSkipped() {
        val f = writeSession(
            "--x--", "2026-11-03T06-40-00-000Z_d.jsonl",
            header("d"), "{not json", "[1,2,3]", "\"str\"", """{"type":"message","message":"oops"}""",
            user("真正的第一条"), """{"type":"session_info","name":""", // 截断的半行
        )
        val p = SessionFileParser.parse(f)
        assertEquals("真正的第一条", p.preview)
        assertNull(p.name)
    }

    @Test fun brokenHeaderFallsBackToFileName() {
        val f = writeSession("--x--", "2026-11-02T10-00-00-000Z_e-123.jsonl", "garbage", user("hello"))
        val p = SessionFileParser.parse(f)
        assertFalse(p.headerOk)
        assertEquals("e-123", p.id)
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), p.createdAt)
        assertEquals("hello", p.preview)
    }

    @Test fun crlfAndUnicodeLineSeparatorsHandled() {
        val f = File(paths.sessionsDir, "--x--/t_f.jsonl").apply { parentFile.mkdirs() }
        // U+2028 在 JSON 字符串里是合法字符，不能当成换行切开
        f.writeText(header("f") + "\r\n" + user("第一行\u2028第二行") + "\r\n")
        val p = SessionFileParser.parse(f)
        assertEquals("f", p.id)
        assertEquals("第一行\u2028第二行".replace(Regex("\\s+"), " "), p.preview)
    }

    @Test fun largeFileReadsOnlyHeadAndTail() {
        val filler = assistant("x".repeat(1000))
        val lines = mutableListOf(header("big"), user("开头的问题"))
        repeat(400) { lines += filler } // 约 400 KB，远大于头尾各 64 KB
        lines += info("中间的名字不该被读到也没关系")
        repeat(200) { lines += filler }
        lines += info("结尾的名字")
        val f = writeSession("--x--", "t_big.jsonl", *lines.toTypedArray())
        assertTrue(f.length() > 4L * SessionFileParser.CHUNK)
        val p = SessionFileParser.parse(f)
        assertEquals("big", p.id)
        assertEquals("开头的问题", p.preview)
        assertEquals("结尾的名字", p.name)
    }

    @Test fun headAndTailDropPartialLines() {
        val f = File(rootfs, "t.txt")
        f.writeText("aaaa\nbbbb\ncccc\n")
        assertEquals(listOf("aaaa", "bbbb"), SessionFileParser.readHead(f, 12)) // "cc" 半行丢掉
        assertEquals(listOf("cccc"), SessionFileParser.readTail(f, 7)) // "bb" 半行丢掉
        assertEquals(listOf("aaaa", "bbbb", "cccc"), SessionFileParser.readHead(f, 1000))
    }

    @Test fun firstUserMessageBeyondHeadIsStillFound() {
        val bigSystem = """{"type":"message","id":"s1","parentId":null,"timestamp":"t","message":{"role":"system","content":"${"y".repeat(100_000)}"}}"""
        val lines = mutableListOf(header("g"), bigSystem, user("系统提示后面的第一句"))
        repeat(200) { lines += assistant("z".repeat(1000)) }
        val f = writeSession("--x--", "t_g.jsonl", *lines.toTypedArray())
        assertEquals("系统提示后面的第一句", SessionFileParser.parse(f).preview)
    }

    // ---------------- 仓库：列表、回收、恢复 ----------------

    @Test fun listsSortedByUpdatedAtDescending() = runTest {
        writeSession("--a--", "1_old.jsonl", header("old"), user("旧"), mtime = 1_000_000L)
        writeSession("--b--", "2_new.jsonl", header("new", cwd = "/root/proj"), user("新"), mtime = 9_000_000L)
        File(paths.sessionsDir, "--a--/notes.txt").writeText("不是会话")
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        val list = repo.observe().first { it.isNotEmpty() }
        assertEquals(listOf("new", "old"), list.map { it.id })
        assertEquals("/root/.pi/agent/sessions/--b--/2_new.jsonl", list[0].guestPath)
        assertEquals("/root/proj", list[0].cwd)
        assertEquals(Instant.ofEpochMilli(9_000_000L), list[0].updatedAt)
    }

    @Test fun moveToTrashAndRestore() = runTest {
        val f = writeSession("--root-pi-cwd--", "2026_s1.jsonl", header("s1"), user("要删掉的"))
        val original = f.readBytes()
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        repo.refresh()
        val guest = repo.observe().first().single().guestPath

        val moved = repo.moveToTrash(guest)
        val trashPath = (moved as Outcome.Ok).value
        assertEquals("/root/.pi/agent/sessions-trash/--root-pi-cwd--/2026_s1.jsonl", trashPath)
        assertFalse(f.exists())
        assertTrue(paths.hostFile(trashPath).readBytes().contentEquals(original)) // 内容完整
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(listOf("s1"), repo.observeTrash().first().map { it.id })

        val restored = (repo.restore(trashPath) as Outcome.Ok).value
        assertEquals(guest, restored)
        assertTrue(f.readBytes().contentEquals(original))
        assertEquals(listOf("s1"), repo.observe().first().map { it.id })
        assertTrue(repo.observeTrash().first().isEmpty())
    }

    @Test fun trashNameCollisionGetsSuffixAndRestoreStripsIt() = runTest {
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        writeSession("--x--", "same.jsonl", header("one"), user("一"))
        val t1 = (repo.moveToTrash("/root/.pi/agent/sessions/--x--/same.jsonl") as Outcome.Ok).value
        writeSession("--x--", "same.jsonl", header("two"), user("二"))
        val t2 = (repo.moveToTrash("/root/.pi/agent/sessions/--x--/same.jsonl") as Outcome.Ok).value
        assertEquals("/root/.pi/agent/sessions-trash/--x--/same~1.jsonl", t2)
        assertEquals(2, repo.observeTrash().first().size)

        val back = (repo.restore(t2) as Outcome.Ok).value
        assertEquals("/root/.pi/agent/sessions/--x--/same.jsonl", back)
        // 原位置已占用时不覆盖，保留带后缀的名字
        val back1 = (repo.restore(t1) as Outcome.Ok).value
        assertEquals("/root/.pi/agent/sessions/--x--/same~1.jsonl", back1)
        assertEquals(setOf("one", "two"), repo.observe().first().map { it.id }.toSet())
    }

    @Test fun rejectsPathsOutsideSessionDirs() = runTest {
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        File(rootfs, "root/important.jsonl").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(repo.moveToTrash("/root/important.jsonl") is Outcome.Err)
        assertTrue(repo.moveToTrash("/root/.pi/agent/sessions/../../important.jsonl") is Outcome.Err)
        assertTrue(repo.moveToTrash("relative.jsonl") is Outcome.Err)
        assertTrue(repo.restore("/root/important.jsonl") is Outcome.Err)
        assertTrue(File(rootfs, "root/important.jsonl").exists())
        assertTrue(repo.moveToTrash("/root/.pi/agent/sessions/--x--/missing.jsonl") is Outcome.Err)
    }

    @Test fun cacheRefreshesWhenFileChanges() = runTest {
        val f = writeSession("--x--", "c.jsonl", header("c"), user("hi"), mtime = 1_000_000L)
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        repo.refresh()
        assertNull(repo.observe().first().single().name)
        f.appendText(info("新名字") + "\n")
        f.setLastModified(2_000_000L)
        repo.refresh()
        assertEquals("新名字", repo.observe().first().single().name)
    }

    @Test fun purgeTrashDeletesOnlyTrash() = runTest {
        val repo = FileSessionRepository(paths, StandardTestDispatcher(testScheduler))
        writeSession("--x--", "a.jsonl", header("a"), user("a"))
        writeSession("--x--", "b.jsonl", header("b"), user("b"))
        repo.moveToTrash("/root/.pi/agent/sessions/--x--/a.jsonl")
        assertEquals(Outcome.Ok(1), repo.purgeTrash())
        assertTrue(repo.observeTrash().first().isEmpty())
        assertEquals(listOf("b"), repo.observe().first().map { it.id })
    }

    @Test fun guestPathMapping() {
        assertEquals(File(rootfs, "root/x/y"), paths.hostFile("/root/x/y"))
        assertEquals("/root/x/y", paths.guestPath(File(rootfs, "root/x/y")))
        assertNull(paths.guestPath(File("/etc/passwd")))
    }
}
