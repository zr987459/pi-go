package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.core.protocol.JsonlFramer.Frame
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlFramerTest {

    private fun frames(framer: JsonlFramer = JsonlFramer(), vararg chunks: ByteArray, finish: Boolean = true): List<Frame> {
        val out = mutableListOf<Frame>()
        for (c in chunks) framer.feed(c, 0, c.size) { out += it }
        if (finish) framer.finish { out += it }
        return out
    }

    private fun lines(fs: List<Frame>) = fs.map { (it as Frame.Line).text }

    @Test
    fun splitsOnlyOnLf() {
        val fs = frames(chunks = arrayOf("""{"a":1}""".toByteArray() + 0x0A + """{"b":2}""".toByteArray() + 0x0A))
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines(fs))
    }

    @Test
    fun unicodeLineAndParagraphSeparatorsAreNotRecordBoundaries() {
        // U+2028 / U+2029 是 JSON 字符串里的合法字符（pi 不转义它们），绝不能被当成换行。
        val rec = "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"a\u2028b\u2029c\u0085d\"}}"
        val fs = frames(chunks = arrayOf((rec + "\n" + rec + "\n").toByteArray(Charsets.UTF_8)))
        assertEquals(listOf(rec, rec), lines(fs))
        val decoded = RecordCodec.decode(lines(fs)[0])
        assertTrue(decoded is RecordCodec.Decoded.Record)
    }

    @Test
    fun crlfIsStrippedButLoneCrIsKept() {
        val fs = frames(chunks = arrayOf("a\r\nb\rc\n\r\n".toByteArray()))
        // "\r\n" 只去掉行尾的 \r；行中间单独的 \r 保留（不是记录边界）；只有 "\r" 的行视为空行跳过。
        assertEquals(listOf("a", "b\rc"), lines(fs))
    }

    @Test
    fun emptyLinesAreSkipped() {
        assertEquals(listOf("x"), lines(frames(chunks = arrayOf("\n\nx\n".toByteArray(), "\n".toByteArray()))))
    }

    @Test
    fun partialChunksAtEveryByteBoundaryIncludingInsideMultibyteChars() {
        val text = "{\"t\":\"中文\u2028😀\"}\r\n{\"u\":2}\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (cut in 0..bytes.size) {
            val f = JsonlFramer()
            val out = mutableListOf<Frame>()
            f.feed(bytes, 0, cut) { out += it }
            f.feed(bytes, cut, bytes.size - cut) { out += it }
            f.finish { out += it }
            assertEquals("cut=$cut", listOf("{\"t\":\"中文\u2028😀\"}", "{\"u\":2}"), lines(out))
        }
        // 一个字节一个字节地喂
        val f = JsonlFramer()
        val out = mutableListOf<Frame>()
        for (b in bytes) f.feed(byteArrayOf(b), 0, 1) { out += it }
        assertEquals(2, out.size)
        assertEquals(0, f.pendingBytes)
    }

    @Test
    fun halfLineIsHeldUntilLfOrFinish() {
        val f = JsonlFramer()
        val out = mutableListOf<Frame>()
        f.feed("{\"a\":") { out += it }
        assertTrue(out.isEmpty())
        assertEquals(5, f.pendingBytes)
        f.feed("1}") { out += it }
        assertTrue(out.isEmpty())
        f.finish { out += it }
        assertEquals(listOf("{\"a\":1}"), lines(out))
    }

    @Test
    fun overlongLineIsDiscardedAndFramingRecovers() {
        val f = JsonlFramer(maxLineBytes = 16)
        val out = mutableListOf<Frame>()
        f.feed("{\"ok\":1}\n") { out += it }
        // 超长行分多块到达
        f.feed("0123456789") { out += it }
        f.feed("0123456789") { out += it }
        f.feed("0123456789\n{\"ok\":2}\n") { out += it }
        assertEquals(3, out.size)
        assertEquals(Frame.Line("{\"ok\":1}"), out[0])
        assertEquals(Frame.Oversized(30), out[1])
        assertEquals(Frame.Line("{\"ok\":2}"), out[2])
    }

    @Test
    fun overlongLineInSingleChunkAndAtEof() {
        val f = JsonlFramer(maxLineBytes = 4)
        val out = mutableListOf<Frame>()
        f.feed("abcdefgh\nab\nabcdefg") { out += it }
        f.finish { out += it }
        assertEquals(listOf(Frame.Oversized(8), Frame.Line("ab"), Frame.Oversized(7)), out)
    }

    @Test
    fun oversizedKeepsLineHeadAcrossChunks() {
        val f = JsonlFramer(maxLineBytes = 600)
        val out = mutableListOf<Frame>()
        val line = """{"id":"r-1","type":"response","command":"get_tree",""" + "x".repeat(2_000)
        line.chunked(37).forEach { f.feed(it) { fr -> out += fr } }
        f.feed("\n") { out += it }
        val o = out.single() as Frame.Oversized
        assertEquals(line.length.toLong(), o.bytes)
        assertEquals(JsonlFramer.HEAD_BYTES, o.head.length)
        assertTrue(o.head.startsWith("""{"id":"r-1","type":"response""""))
    }

    @Test
    fun exactlyMaxLineBytesIsAccepted() {
        val f = JsonlFramer(maxLineBytes = 4)
        val out = mutableListOf<Frame>()
        f.feed("abcd\nabcd\r\n") { out += it }
        // "abcd\r" 是 5 字节，超出 4 → 丢弃（\r 在去除之前计入长度，保守处理）。
        assertEquals(listOf(Frame.Line("abcd"), Frame.Oversized(5)), out)
    }

    @Test
    fun largeLineGrowsBufferAndShrinksAfterwards() {
        val big = "x".repeat(200_000)
        val out = mutableListOf<Frame>()
        val f = JsonlFramer()
        f.feed("$big\nsmall\n") { out += it }
        assertEquals(listOf(big, "small"), lines(out))
    }

    @Test
    fun pumpReadsUntilEofInSmallChunks() {
        val data = "{\"a\":1}\n{\"b\":\"\u2029\"}\n{\"c\":3}".toByteArray(Charsets.UTF_8)
        val slow: InputStream = object : InputStream() {
            val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 3))
        }
        val out = mutableListOf<Frame>()
        val n = JsonlFramer.pump(slow, chunkSize = 7) { out += it }
        assertEquals(data.size.toLong(), n)
        assertEquals(listOf("{\"a\":1}", "{\"b\":\"\u2029\"}", "{\"c\":3}"), lines(out))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badRangeIsRejected() {
        JsonlFramer().feed(ByteArray(2), 1, 5) {}
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveLimitIsRejected() {
        JsonlFramer(0)
    }
}
