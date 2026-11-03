package io.github.zr987459.pigo.core.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zr987459.pigo.contract.ExecResult
import io.github.zr987459.pigo.contract.FilePreview
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/** 真机 / 模拟器上的 core/workspace：Android Bitmap 的压缩与 EXIF 摆正、经 ContentResolver 导入后可列出与预览。 */
@RunWith(AndroidJUnit4::class)
class WorkspaceDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var rootfs: File
    private lateinit var ws: RootfsWorkspace

    @Before
    fun setUp() {
        rootfs = File(ctx.filesDir, "test-rootfs-" + System.nanoTime()).apply { File(this, "root/pi-cwd").mkdirs() }
        ws = RootfsWorkspace(rootfs, { _, _ -> ExecResult(1, "", "") })
    }

    @After
    fun tearDown() { rootfs.deleteRecursively() }

    private fun withOrientation(jpeg: ByteArray, o: Int): ByteArray {
        val tiff = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun photoIsScaledRotatedAndUnderLimit() = runBlocking {
        val bmp = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.BLUE); clipRect(0, 0, 1500, 2000); drawColor(Color.RED) }
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        val ap = WorkspaceAttachments(ws, AndroidImageCodec)
        val img = (ap.prepareImage(ByteArrayInputStream(withOrientation(out, 6)), "image/jpeg", "p.jpg") as Outcome.Ok).value
        assertEquals(1045, img.widthPx)
        assertEquals(1568, img.heightPx)
        assertTrue(img.base64Data.length <= 5 * 1024 * 1024)
        val raw = Base64.getDecoder().decode(img.base64Data)
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertEquals(1045, decoded.width)
        val top = decoded.getPixel(decoded.width / 2, decoded.height / 8)
        val bottom = decoded.getPixel(decoded.width / 2, decoded.height * 7 / 8)
        assertTrue("上半应为红色", Color.red(top) > 180 && Color.blue(top) < 80)
        assertTrue("下半应为蓝色", Color.blue(bottom) > 180 && Color.red(bottom) < 80)
    }

    /** 验收（§3.2 W3）：经 content://（SAF 同款，[TestDocsProvider]）导入文本文件，真实 RootfsWorkspace 能列出、预览一致。 */
    @Test
    fun importedThroughContentUriShowsInListAndPreviewMatches() = runBlocking {
        val text = "第一行\nsecond line\n"
        val uri = TestDocsProvider.put(ctx, "笔记.txt", text.toByteArray())
        assertEquals("content", uri.scheme)
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        val node = ctx.contentResolver.openInputStream(uri)!!.use { (ws.importStream(name, it, "/root/pi-cwd") as Outcome.Ok).value }
        assertEquals("/root/pi-cwd/笔记.txt", node.guestPath)
        val list = (ws.list("/root/pi-cwd") as Outcome.Ok).value
        assertTrue(list.any { it.guestPath == node.guestPath })
        val p = (ws.preview(node.guestPath) as Outcome.Ok).value as FilePreview.Text
        assertEquals(text, p.chunk.text)
    }

    /** 真实 WorkspaceAttachments + AndroidImageCodec：经 content:// 读入的大图被缩放、摆正（EXIF 8）并产出 ImageInput。 */
    @Test
    fun imageFromContentUriBecomesImageInputWithRealCodec() = runBlocking {
        val bmp = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(Color.BLUE); clipRect(0, 0, 1200, 1200); drawColor(Color.RED) }
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        val uri = TestDocsProvider.put(ctx, "wide.jpg", withOrientation(jpeg, 8))
        val ap = WorkspaceAttachments(ws, AndroidImageCodec)
        val img = ctx.contentResolver.openInputStream(uri)!!.use {
            (ap.prepareImage(it, ctx.contentResolver.getType(uri), "wide.jpg") as Outcome.Ok).value
        }
        assertEquals("image/jpeg", img.mimeType)
        assertEquals(784, img.widthPx)
        assertEquals(1568, img.heightPx)
        val raw = Base64.getDecoder().decode(img.base64Data)
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size)
        assertEquals(784, decoded.width)
        assertEquals(1568, decoded.height)
        // EXIF 8 = 逆时针转 90°：原图左半（红）转到下半
        val top = decoded.getPixel(decoded.width / 2, decoded.height / 8)
        val bottom = decoded.getPixel(decoded.width / 2, decoded.height * 7 / 8)
        assertTrue("上半应为蓝色", Color.blue(top) > 180 && Color.red(top) < 80)
        assertTrue("下半应为红色", Color.red(bottom) > 180 && Color.blue(bottom) < 80)
    }

    /** 真实附件导入：文件落到 <cwd>/.pigo/uploads/<日期>/，只放行该上传目录，不扩大到整个 cwd 以外。 */
    @Test
    fun importFileGoesToUploadDir() = runBlocking {
        val uri = TestDocsProvider.put(ctx, "a.txt", "hello".toByteArray())
        val ap = WorkspaceAttachments(ws, AndroidImageCodec)
        val ref = ctx.contentResolver.openInputStream(uri)!!.use { (ap.importFile("a.txt", it, "/root/pi-cwd") as Outcome.Ok).value }
        assertTrue(ref.node.guestPath.startsWith("/root/pi-cwd/.pigo/uploads/"))
        assertEquals("hello", File(rootfs, ref.node.guestPath.removePrefix("/")).readText())
    }
}
