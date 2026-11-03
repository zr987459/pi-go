package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.feature.commands.data.SessionScanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S3：增量扫描、名字取最后一条 session_info、全文搜索（feature-gap W4 验收「会话索引单测」）。 */
class SessionScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun header(cwd: String = "/root/pi-cwd") =
        """{"type":"session","version":3,"id":"abc","timestamp":"2026-11-03T06:00:00.000Z","cwd":"$cwd"}""" + "\n"

    private fun user(id: String, text: String) =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"2026-11-03T06:00:01.000Z","message":{"role":"user","content":${q(text)},"timestamp":1}}""" + "\n"

    private fun assistant(id: String, text: String) =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"2026-11-03T06:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":${q(text)}}],"stopReason":"stop","timestamp":2}}""" + "\n"

    private fun info(name: String) = """{"type":"session_info","id":"n${name.hashCode()}","parentId":null,"timestamp":"2026-11-03T06:00:03.000Z","name":${q(name)}}""" + "\n"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** 用填充的助手消息把文件写到大约 [target] 字节。 */
    private fun fill(sb: StringBuilder, target: Int, prefix: String) {
        var i = 0
        while (sb.length < target) sb.append(assistant("$prefix${i++}", "x".repeat(900)))
    }

    @Test
    fun renameInTheMiddleOfA200kFileIsRead() {
        val f = tmp.newFile("s.jsonl")
        val sb = StringBuilder(header()).append(user("u1", "第一条")).append(info("旧名字"))
        fill(sb, 100_000, "a")
        sb.append(info("新名字"))
        fill(sb, 200_000, "b")
        f.writeText(sb.toString())
        assertTrue(f.length() >= 200_000)
        val s = SessionScanner().scan(f)!!
        assertEquals("新名字", s.name)
        assertEquals("第一条", s.firstMessage)
        assertEquals("/root/pi-cwd", s.cwd)
    }

    @Test
    fun appendOnlyReadsTheNewPart() {
        val f = tmp.newFile("s.jsonl")
        val sb = StringBuilder(header()).append(user("u1", "开始"))
        fill(sb, 200_000, "a")
        f.writeText(sb.toString())
        val scanner = SessionScanner()
        scanner.scan(f)
        val first = scanner.bytesRead
        assertEquals(f.length(), first)

        val extra = StringBuilder()
        fill(extra, 10_000, "c")
        extra.append(user("u2", "追加的问题"))
        f.appendText(extra.toString())
        f.setLastModified(f.lastModified() + 2_000)
        val s = scanner.scan(f)!!
        val delta = scanner.bytesRead - first
        assertTrue("只读新增部分（读了 $delta 字节）", delta in 10_000..(extra.toString().toByteArray().size.toLong()))
        assertEquals(listOf("开始", "追加的问题"), s.userTexts)
    }

    @Test
    fun rewriteInPlaceForcesFullRead() {
        val f = tmp.newFile("s.jsonl")
        f.writeText(header() + user("u1", "原来") + info("A"))
        val scanner = SessionScanner()
        scanner.scan(f)
        // 改写头部（另一个会话被换进同一路径），再变长：不能当作追加。
        f.writeText(header("/root/other") + user("u1", "完全不同的内容") + info("B") + user("u2", "更多"))
        f.setLastModified(f.lastModified() + 2_000)
        val s = scanner.scan(f)!!
        assertEquals("/root/other", s.cwd)
        assertEquals(listOf("完全不同的内容", "更多"), s.userTexts)
        assertEquals("B", s.name)
    }

    @Test
    fun partialLastLineIsLeftForNextScan() {
        val f = tmp.newFile("s.jsonl")
        val full = user("u2", "半行后补全")
        f.writeText(header() + user("u1", "一") + full.substring(0, 30))
        val scanner = SessionScanner()
        assertEquals(listOf("一"), scanner.scan(f)!!.userTexts)
        f.appendText(full.substring(30))
        f.setLastModified(f.lastModified() + 2_000)
        assertEquals(listOf("一", "半行后补全"), scanner.scan(f)!!.userTexts)
    }

    @Test
    fun emptyNameClearsAndSearchFindsThirdUserMessage() {
        val dir = tmp.newFolder("sessions")
        val a = File(dir, "a.jsonl").apply {
            writeText(header() + user("u1", "登录页点了没反应") + user("u2", "看下控制台报错") + user("u3", "改完跑一遍 Gradle 测试") + info("修 bug") + info(""))
        }
        val b = File(dir, "b.jsonl").apply { writeText(header("/root/blog") + user("u1", "博客换成深色主题") + info("博客主题")) }
        val scanner = SessionScanner()
        val list = scanner.scanAll(listOf(a, b))
        assertNull("空名字等于清除", list.single { it.path == a.path }.name)

        val hits = scanner.search("gradle")
        assertEquals(1, hits.size)
        assertEquals(a.path, hits[0].path)
        assertEquals(2, hits[0].userMessageIndex)
        assertTrue(hits[0].snippet.contains("Gradle"))

        val byName = scanner.search("博客主题")
        assertEquals(null, byName.single().userMessageIndex)
        assertTrue(scanner.search("   ").isEmpty())
    }

    @Test
    fun indexSurvivesSaveAndLoadThenResumes() {
        val f = tmp.newFile("s.jsonl")
        val sb = StringBuilder(header()).append(user("u1", "持久化"))
        fill(sb, 50_000, "a")
        f.writeText(sb.toString())
        val cache = File(tmp.root, "index.json")
        SessionScanner().apply { scan(f); save(cache) }

        f.appendText(user("u2", "重启后追加"))
        f.setLastModified(f.lastModified() + 2_000)
        val again = SessionScanner().apply { load(cache) }
        val s = again.scan(f)!!
        assertTrue("载入缓存后只读新增（读了 ${again.bytesRead}）", again.bytesRead < 1_000)
        assertEquals(listOf("持久化", "重启后追加"), s.userTexts)
    }
}
