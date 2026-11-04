package io.github.zr987459.pigo.feature.files

import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import io.github.zr987459.pigo.contract.fake.FakeWorkspaceFs
import io.github.zr987459.pigo.feature.files.attach.AttachmentController
import io.github.zr987459.pigo.feature.files.attach.SourceItem
import io.github.zr987459.pigo.feature.files.output.Ansi
import io.github.zr987459.pigo.feature.files.output.OutputSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AnsiTest {
    @Test
    fun sgrColorsAndResetBecomeSpans() {
        val l = Ansi.parse("ok \u001B[1;31merror\u001B[0m done").single()
        assertEquals("ok error done", l.text)
        val sp = l.spans.single()
        assertEquals(3, sp.start); assertEquals(8, sp.end)
        assertTrue(sp.style.bold)
        assertEquals(0xFFCD3131.toInt(), sp.style.fg)
    }

    @Test
    fun colors256AndTrueColor() {
        val l = Ansi.parse("\u001B[38;5;196mA\u001B[48;2;1;2;3mB\u001B[39;49mC").single()
        assertEquals("ABC", l.text)
        assertEquals(Ansi.xterm256(196), l.spans[0].style.fg)
        assertEquals(0xFF010203.toInt(), l.spans[1].style.bg)
        assertEquals(2, l.spans.size) // C 是默认样式
    }

    @Test
    fun styleCarriesAcrossLinesAndCarriageReturnOverwrites() {
        val ls = Ansi.parse("\u001B[32mgreen\nstill\u001B[0m\n10%\r55%\r100%\n")
        assertEquals(listOf("green", "still", "100%"), ls.map { it.text })
        assertEquals(1, ls[1].spans.size)
        assertTrue(ls[2].spans.isEmpty())
    }

    @Test
    fun oscAndPiCursorMarkerAndOtherCsiAreDropped() {
        val l = Ansi.parse("\u001B]0;title\u0007a\u001B_pi:c\u0007b\u001B[2Kc\u001B[?25l").single()
        assertEquals("abc", l.text)
        assertEquals("x", Ansi.strip("\u001B[31mx\u001B[0m"))
    }

    @Test
    fun tabsExpandAndControlCharsVanish() {
        assertEquals("a       b", Ansi.parse("a\tb\u0007").single().text)
    }

    @Test
    fun searchFindsAllCaseInsensitive() {
        val hits = OutputSearch.find(listOf("Error one", "fine", "another error, ERROR"), "error")
        assertEquals(listOf(0 to 0, 2 to 8, 2 to 15), hits.map { it.line to it.start })
        assertTrue(OutputSearch.find(listOf("x"), "").isEmpty())
    }

    @Test
    fun tenThousandLinesParseQuickly() {
        val text = (1..10_000).joinToString("\n") { "\u001B[3${it % 8}mline $it\u001B[0m with some output text" }
        val t0 = System.nanoTime()
        val ls = Ansi.parse(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(10_000, ls.size)
        assertEquals("line 5000 with some output text", ls[4999].text)
        assertEquals(4999, OutputSearch.find(ls.map { it.text }, "line 5000 ").single().line)
        assertTrue("解析 1 万行用了 $ms ms", ms < 3_000)
    }
}


class AttachmentControllerTest {
    private class Proc : AttachmentProcessor {
        val imported = ArrayList<String>()
        override suspend fun prepareImage(input: InputStream, mimeType: String?, displayName: String?, maxEdgePx: Int, maxBytes: Long): Outcome<ImageInput> =
            if (displayName == "bad.jpg") Outcome.Err(ErrCode.UNKNOWN, "无法识别的图片格式") else Outcome.Ok(ImageInput("image/jpeg", "QUJD", 10, 10, displayName))

        override suspend fun importFile(fileName: String, input: InputStream, cwd: String, maxBytes: Long): Outcome<Attachment.FileRef> {
            imported += "$cwd|$fileName"
            return Outcome.Ok(Attachment.FileRef(io.github.zr987459.pigo.contract.FileNode("$cwd/.pigo/uploads/d/$fileName", fileName, false)))
        }
    }

    private fun src(name: String, mime: String?, size: Long? = 3) = SourceItem(name, mime, size) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }

    @Test
    fun imagesAndFilesAreProcessedAndComposed() {
        val p = Proc()
        val c = AttachmentController(p, CoroutineScope(Dispatchers.Unconfined), cwd = { "/root/w" })
        c.add(listOf(src("a.jpg", "image/jpeg"), src("bad.jpg", "image/jpeg")), asImages = true)
        c.add(listOf(src("r.pdf", "application/pdf"), src("huge.zip", "application/zip", size = 30L * 1024 * 1024)), asImages = false)
        val items = c.items.value
        assertEquals(4, items.size)
        assertTrue(items[0].result is Attachment.Image)
        assertEquals("无法识别的图片格式", items[1].error)
        assertTrue(items[2].result is Attachment.FileRef)
        assertTrue(items[3].error!!.contains("20 MB"))
        assertEquals(listOf("/root/w|r.pdf"), p.imported)
        val (text, images) = c.compose("请看")
        assertEquals("请看\n\n@/root/w/.pigo/uploads/d/r.pdf", text)
        assertEquals(1, images.size)
        c.remove(items[0].id)
        assertTrue(c.compose("x").second.isEmpty())
    }

    @Test
    fun modelWithoutVisionImportsImagesAsFilesAndSaysWhy() {
        val p = Proc()
        val c = AttachmentController(p, CoroutineScope(Dispatchers.Unconfined), cwd = { "/root/w" }, supportsImages = { false })
        c.add(listOf(src("a.png", "image/png")), asImages = true)
        assertTrue(c.items.value.single().result is Attachment.FileRef)
        assertTrue(c.notice.value!!.contains("不支持看图"))
        c.consumeNotice()
        assertNull(c.notice.value)
    }
}
