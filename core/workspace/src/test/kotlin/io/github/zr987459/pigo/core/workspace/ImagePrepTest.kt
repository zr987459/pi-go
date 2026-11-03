package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.max

/** 测试用光栅。 */
class Raster(val w: Int, val h: Int, val px: IntArray, val alpha: Boolean) {
    fun get(x: Int, y: Int) = px[y * w + x]
}

/**
 * JVM 单测用的 codec（Android 单测的编译类路径里没有 javax.imageio）。
 * 文件格式模仿 JPEG 的段结构：`FFD8` + 可选 APP1/EXIF + `FFE9 "PGIM"` 段（宽、高、是否透明）+ deflate 压缩的像素。
 * 「质量」按步长量化颜色，质量越低越容易压缩——足以覆盖 [ImagePrep] 的缩放、方向与体积循环。
 * Android 真实 Bitmap 路径由 androidTest 的 AndroidImageCodecTest 覆盖。
 */
object TestCodec : ImageCodec<Raster> {
    fun write(r: Raster, quality: Int = 100, png: Boolean = false): ByteArray {
        val step = if (png) 1 else ((100 - quality) / 3 + 1)
        val bos = ByteArrayOutputStream()
        bos.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        bos.write(byteArrayOf(0xFF.toByte(), 0xE9.toByte(), 0, 15))
        DataOutputStream(bos).apply { writeBytes("PGIM"); writeInt(r.w); writeInt(r.h); writeByte(if (r.alpha) 1 else 0) }
        val z = DeflaterOutputStream(bos)
        val d = DataOutputStream(z)
        for (p in r.px) {
            val a = (p ushr 24) and 0xFF
            val c = ((p shr 16 and 0xFF) / step * step shl 16) or ((p shr 8 and 0xFF) / step * step shl 8) or ((p and 0xFF) / step * step)
            d.writeInt((if (r.alpha) a else 0xFF) shl 24 or c)
        }
        d.flush(); z.finish()
        return bos.toByteArray()
    }

    private fun header(b: ByteArray): Int? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 4 <= b.size && b[p] == 0xFF.toByte()) {
            val m = b[p + 1].toInt() and 0xFF
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (m == 0xE9) return p + 4
            p += 2 + len
        }
        return null
    }

    override fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val h = header(bytes) ?: return null
        val d = DataInputStream(ByteArrayInputStream(bytes, h + 4, 8))
        return d.readInt() to d.readInt()
    }

    override fun decode(bytes: ByteArray, sampleSize: Int): Raster? {
        val h = header(bytes) ?: return null
        val d0 = DataInputStream(ByteArrayInputStream(bytes, h + 4, 9))
        val w = d0.readInt(); val hh = d0.readInt(); val alpha = d0.readByte().toInt() == 1
        val d = DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, h + 13, bytes.size - h - 13)))
        val px = IntArray(w * hh) { d.readInt() }
        val full = Raster(w, hh, px, alpha)
        if (sampleSize == 1) return full
        val sw = w / sampleSize; val sh = hh / sampleSize
        return Raster(sw, sh, IntArray(sw * sh) { i -> full.get((i % sw) * sampleSize, (i / sw) * sampleSize) }, alpha)
    }

    override fun width(img: Raster) = img.w
    override fun height(img: Raster) = img.h
    override fun hasAlpha(img: Raster) = img.alpha

    override fun transform(img: Raster, orientation: Int, outW: Int, outH: Int): Raster {
        val w = img.w; val h = img.h
        val swap = ExifOrientation.swapsAxes(orientation)
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        val px = IntArray(outW * outH) { i ->
            val u = ((i % outW) * ow / outW).coerceIn(0, ow - 1)
            val v = ((i / outW) * oh / outH).coerceIn(0, oh - 1)
            val (x, y) = when (orientation) {
                2 -> (w - 1 - u) to v
                3 -> (w - 1 - u) to (h - 1 - v)
                4 -> u to (h - 1 - v)
                5 -> v to u
                6 -> v to (h - 1 - u)
                7 -> (w - 1 - v) to (h - 1 - u)
                8 -> (w - 1 - v) to u
                else -> u to v
            }
            img.get(x, y)
        }
        return Raster(outW, outH, px, img.alpha)
    }

    override fun encode(img: Raster, png: Boolean, quality: Int): ByteArray = write(img, quality, png)
}

class ImagePrepTest {
    /** 左半红、右半蓝的 JPEG，便于检查方向。 */
    private fun jpeg(w: Int, h: Int, noise: Boolean = false): ByteArray {
        val rnd = Random(1)
        val px = IntArray(w * h) { i ->
            val base = if (i % w < w / 2) 0xFF0000 else 0x0000FF
            (0xFF shl 24) or (if (noise) base xor (rnd.nextInt() and 0x3F3F3F) else base)
        }
        return TestCodec.write(Raster(w, h, px, alpha = false), quality = 100)
    }

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** 在 SOI 后插入只含 Orientation 的 APP1/EXIF 段（大端 TIFF）。 */
    private fun withOrientation(jpeg: ByteArray, o: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            0, 1, // 1 个条目
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0,
            0, 0, 0, 0,
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun decode(b: ByteArray): Raster = TestCodec.decode(b, 1)!!

    @Test
    fun exifOrientationIsRead() {
        val j = jpeg(20, 10)
        assertEquals(1, ExifOrientation.read(j))
        for (o in 1..8) assertEquals(o, ExifOrientation.read(withOrientation(j, o)))
        assertEquals(1, ExifOrientation.read(byteArrayOf(1, 2, 3)))
        // 小端 TIFF
        val le = withOrientation(j, 6).also { b ->
            val t = 2 + 4 + 6
            b[t] = 'I'.code.toByte(); b[t + 1] = 'I'.code.toByte(); b[t + 2] = 42; b[t + 3] = 0; b[t + 4] = 8; b[t + 7] = 0
            b[t + 8] = 1; b[t + 9] = 0; b[t + 10] = 0x12; b[t + 11] = 0x01; b[t + 12] = 3; b[t + 13] = 0; b[t + 14] = 1; b[t + 15] = 0; b[t + 17] = 0
            b[t + 18] = 6; b[t + 19] = 0
        }
        assertEquals(6, ExifOrientation.read(le))
    }

    @Test
    fun largePhotoIsScaledToMaxEdgeAndRotatedByExif() {
        // 3000×2000 横图，EXIF 6（需顺时针转 90°）→ 竖图，长边 1568
        val src = withOrientation(jpeg(3000, 2000), 6)
        val r = ImagePrep(TestCodec, maxEdge = 1568, maxBytes = 3_750_000).prepare(src, "image/jpeg")
        assertEquals("image/jpeg", r.mimeType)
        assertEquals(1045, r.width)
        assertEquals(1568, r.height)
        val img = decode(r.bytes)
        assertEquals(1045, img.w)
        assertEquals(1568, img.h)
        // 原图左半红：顺时针转 90° 后在上半部分
        val top = img.get(img.w / 2, img.h / 8)
        val bottom = img.get(img.w / 2, img.h * 7 / 8)
        assertTrue("上半应为红色", red(top) > 180 && blue(top) < 80)
        assertTrue("下半应为蓝色", blue(bottom) > 180 && red(bottom) < 80)
        assertEquals(1, ExifOrientation.read(r.bytes))
    }

    @Test
    fun orientation8RotatesCounterClockwise() {
        val r = ImagePrep(TestCodec).prepare(withOrientation(jpeg(400, 200), 8), "image/jpeg")
        assertEquals(200, r.width)
        assertEquals(400, r.height)
        val img = decode(r.bytes)
        val bottom = img.get(img.w / 2, img.h * 7 / 8)
        assertTrue("逆时针转后左半（红）在下", red(bottom) > 180 && blue(bottom) < 80)
    }

    @Test
    fun noisyImageIsCompressedUnderByteLimit() {
        val src = jpeg(1500, 1500, noise = true)
        val limit = 300_000
        assertTrue(src.size > limit)
        val r = ImagePrep(TestCodec, maxEdge = 1568, maxBytes = limit).prepare(src, "image/jpeg")
        assertTrue("压缩后 ${r.bytes.size} 字节", r.bytes.size <= limit)
        assertTrue(max(r.width, r.height) <= 1568)
    }

    @Test
    fun smallCompliantImageIsPassedThrough() {
        val src = jpeg(100, 50)
        val r = ImagePrep(TestCodec).prepare(src, null)
        assertTrue(r.bytes.contentEquals(src))
        assertEquals(100, r.width)
    }

    @Test
    fun transparentPngStaysPng() {
        val png = TestCodec.write(Raster(2000, 1000, IntArray(2000 * 1000), alpha = true), png = true)
        val r = ImagePrep(TestCodec).prepare(png, "image/png")
        assertEquals("image/png", r.mimeType)
        assertEquals(1568, r.width)
        assertEquals(784, r.height)
    }

    @Test
    fun garbageIsRejectedWithChineseMessage() {
        try {
            ImagePrep(TestCodec).prepare(byteArrayOf(1, 2, 3, 4), null)
            error("应失败")
        } catch (e: ImagePrepException) {
            assertEquals("无法识别的图片格式", e.message)
        }
    }

    @Test
    fun attachmentProcessorProducesBase64UnderContractLimit() = runBlocking {
        val tmp = Files.createTempDirectory("pigo-att").toFile()
        try {
            val ws = RootfsWorkspace(tmp, { _, _ -> error("不应执行命令") }, Dispatchers.Unconfined)
            val ap = WorkspaceAttachments(ws, TestCodec, io = Dispatchers.Unconfined)
            val r = ap.prepareImage(ByteArrayInputStream(withOrientation(jpeg(4000, 3000, noise = true), 3)), "image/jpeg", "IMG_1.jpg")
            val img = (r as Outcome.Ok).value
            assertTrue(img.base64Data.length <= 5L * 1024 * 1024)
            val raw = Base64.getDecoder().decode(img.base64Data)
            assertTrue(raw.size <= 5 * 1024 * 1024)
            assertEquals(1568, img.widthPx)
            assertEquals(1176, img.heightPx)
            assertEquals("IMG_1.jpg", img.displayName)
            assertEquals("image/jpeg", img.mimeType)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
