package io.github.zr987459.pigo.feature.commands

import io.github.zr987459.pigo.feature.commands.data.SessionScanner
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PR #10 审查第 1 轮 Important 3：分块流式读取、跨块半行、超长行只计数。 */
class SessionScannerStreamingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun header() = """{"type":"session","version":3,"id":"abc","timestamp":"t","cwd":"/root/pi-cwd"}""" + "\n"
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun user(id: String, text: String) =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"t","message":{"role":"user","content":${q(text)},"timestamp":1}}""" + "\n"
    private fun userWithImage(id: String, text: String, base64: String) =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"t","message":{"role":"user","content":[{"type":"text","text":${q(text)}},{"type":"image","data":"$base64","mimeType":"image/png"}],"timestamp":1}}""" + "\n"
    private fun assistant(id: String, text: String) =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"t","message":{"role":"assistant","content":[{"type":"text","text":${q(text)}}],"stopReason":"stop","timestamp":2}}""" + "\n"
    private fun info(name: String) = """{"type":"session_info","id":"n","parentId":null,"timestamp":"t","name":${q(name)}}""" + "\n"

    @Test fun tinyChunksGiveSameResultAsDefault() {
        val f = tmp.newFile("s.jsonl")
        // 多字节 UTF-8 会被切在块边界上
        f.writeText(header() + user("u1", "中文🙂第一条") + assistant("a1", "回答".repeat(50)) + info("名字✓") + user("u2", "第二条"))
        val a = SessionScanner().scan(f)!!
        val scanner = SessionScanner(chunkBytes = 7)
        val b = scanner.scan(f)!!
        assertEquals(a, b)
        assertEquals("名字✓", b.name)
        assertEquals(listOf("中文🙂第一条", "第二条"), b.userTexts)
        assertEquals(3, b.messageCount)
        assertEquals(f.length(), scanner.bytesRead)
    }

    @Test fun overlongLineCountedButNotIndexed() {
        val f = tmp.newFile("s.jsonl")
        val big = "A".repeat(50_000)
        f.writeText(header() + user("u1", "普通问题") + userWithImage("u2", "看图说话", big) + assistant("a1", "x".repeat(60_000)) + user("u3", "之后的问题"))
        val s = SessionScanner(chunkBytes = 4096, maxLineBytes = 16 * 1024).scan(f)!!
        assertEquals("超长行也计数（user + assistant）", 4, s.messageCount)
        assertEquals("超长行不进全文索引", listOf("普通问题", "之后的问题"), s.userTexts)
        assertEquals("普通问题", s.firstMessage)
    }

    @Test fun overlongNonMessageLineIgnored() {
        val f = tmp.newFile("s.jsonl")
        val huge = """{"type":"custom","data":"${"z".repeat(40_000)}","role":"user"}""" + "\n"
        f.writeText(header() + huge + user("u1", "问"))
        val s = SessionScanner(chunkBytes = 1024, maxLineBytes = 8 * 1024).scan(f)!!
        assertEquals(1, s.messageCount)
        assertEquals(listOf("问"), s.userTexts)
    }

    @Test fun halfLineAcrossScansAndChunksResumesCorrectly() {
        val f = tmp.newFile("s.jsonl")
        val line = user("u2", "跨两次扫描的一行")
        f.writeText(header() + user("u1", "一") + line.substring(0, 20))
        val scanner = SessionScanner(chunkBytes = 5)
        val first = scanner.scan(f)!!
        assertEquals(listOf("一"), first.userTexts)
        f.appendText(line.substring(20) + user("u3", "三"))
        f.setLastModified(f.lastModified() + 2_000)
        val s = scanner.scan(f)!!
        assertEquals(listOf("一", "跨两次扫描的一行", "三"), s.userTexts)
        assertEquals(3, s.messageCount)
    }

    @Test fun largeFileStreamsWithoutReadingWholeFileAtOnce() {
        val f = tmp.newFile("big.jsonl")
        f.bufferedWriter().use { w ->
            w.write(header())
            w.write(user("u0", "开头"))
            repeat(3_000) { w.write(assistant("a$it", "y".repeat(1_000))) }
            w.write(userWithImage("img", "带图", "B".repeat(200_000)))
            w.write(user("u1", "结尾"))
        }
        assertTrue(f.length() > 3_000_000)
        val s = SessionScanner(chunkBytes = 64 * 1024, maxLineBytes = 64 * 1024).scan(File(f.path))!!
        assertEquals(3_003, s.messageCount)
        assertEquals(listOf("开头", "结尾"), s.userTexts)
    }
}
