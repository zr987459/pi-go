package io.github.zr987459.pigo.core.workspace

import kotlin.math.max
import kotlin.math.roundToInt

/** 读 JPEG 的 EXIF Orientation（1–8）；不是 JPEG、没有 EXIF 或解析失败都返回 1。只读前 128 KB。 */
object ExifOrientation {
    fun read(bytes: ByteArray): Int = runCatching { parse(bytes) }.getOrNull()?.takeIf { it in 1..8 } ?: 1

    private fun parse(b: ByteArray): Int? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var p = 2
        val limit = minOf(b.size, 128 * 1024)
        while (p + 4 <= limit) {
            if (u8(b, p) != 0xFF) return null
            val marker = u8(b, p + 1)
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xFF) { p += 1; continue }
            if (marker == 0xDA || marker == 0xD9) return null // 图像数据开始：没有 EXIF
            val len = (u8(b, p + 2) shl 8) or u8(b, p + 3)
            if (len < 2) return null
            val seg = p + 4
            if (marker == 0xE1 && len >= 16 && seg + 6 <= b.size &&
                b[seg] == 'E'.code.toByte() && b[seg + 1] == 'x'.code.toByte() && b[seg + 2] == 'i'.code.toByte() && b[seg + 3] == 'f'.code.toByte()
            ) {
                return tiffOrientation(b, seg + 6, seg + len - 2)
            }
            p += 2 + len
        }
        return null
    }

    private fun tiffOrientation(b: ByteArray, t: Int, end: Int): Int? {
        if (t + 8 > end || end > b.size) return null
        val le = when {
            b[t] == 'I'.code.toByte() && b[t + 1] == 'I'.code.toByte() -> true
            b[t] == 'M'.code.toByte() && b[t + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        fun u16(o: Int) = if (le) u8(b, o) or (u8(b, o + 1) shl 8) else (u8(b, o) shl 8) or u8(b, o + 1)
        fun u32(o: Int) = if (le) u16(o).toLong() or (u16(o + 2).toLong() shl 16) else (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        if (u16(t + 2) != 42) return null
        val ifd = t + u32(t + 4).toInt()
        if (ifd + 2 > end) return null
        val n = u16(ifd)
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            if (e + 12 > end) return null
            if (u16(e) == 0x0112) return u16(e + 8)
        }
        return null
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** 方向 5–8 需要交换宽高。 */
    fun swapsAxes(orientation: Int) = orientation in 5..8
}

/** 解码 / 变换 / 编码的平台实现：Android 用 Bitmap，JVM 单测用 javax.imageio。 */
interface ImageCodec<B : Any> {
    /** 只读尺寸（不解码像素）；无法识别返回 null。 */
    fun bounds(bytes: ByteArray): Pair<Int, Int>?
    fun decode(bytes: ByteArray, sampleSize: Int): B?
    fun width(img: B): Int
    fun height(img: B): Int
    fun hasAlpha(img: B): Boolean

    /** 按 EXIF [orientation] 旋转 / 翻转并缩放到 [outW]×[outH]（输出尺寸，已是旋转后的方向）。 */
    fun transform(img: B, orientation: Int, outW: Int, outH: Int): B
    fun encode(img: B, png: Boolean, quality: Int): ByteArray
    fun release(img: B) {}

    /**
     * 非 JPEG（HEIC/HEIF、WebP、PNG 等）的 EXIF 方向（1–8）；读不到返回 null（按 1 处理）。
     * JPEG 由 [ExifOrientation] 解析，不经这里。Android 实现用 `androidx.exifinterface`。
     */
    fun orientation(bytes: ByteArray): Int? = null
}

class ImagePrepException(message: String) : Exception(message)

data class PreparedImage(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int) {
    override fun equals(other: Any?) = other is PreparedImage && other.mimeType == mimeType && other.width == width && other.height == height && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode() * 31 + mimeType.hashCode()
}

/**
 * 发给模型前的图片预处理（feature-gap C1）：长边 ≤ [maxEdge]（1568）、体积 ≤ [maxBytes]、按 EXIF 方向摆正。
 *
 * 体积上限默认 3.75 MB：base64 后约 5 MB，同时满足「原始 ≤ 5 MB」与按 base64 计 5 MB 的服务商。
 * 已经合规的 JPEG/PNG/WebP/GIF（方向为 1）原样发送，不重新编码；否则透明图编 PNG（超限再退回 JPEG），其余编 JPEG，
 * 质量 90 → 50 逐级降低，仍超限再按 0.8 倍缩小。
 */
class ImagePrep<B : Any>(
    private val codec: ImageCodec<B>,
    val maxEdge: Int = 1568,
    val maxBytes: Int = 3_750_000,
) {
    fun prepare(bytes: ByteArray, mimeHint: String?): PreparedImage {
        val (w0, h0) = codec.bounds(bytes) ?: throw ImagePrepException("无法识别的图片格式")
        if (w0 <= 0 || h0 <= 0) throw ImagePrepException("无法识别的图片格式")
        val mime = sniffMime(bytes) ?: mimeHint
        val orientation = if (mime == "image/jpeg") ExifOrientation.read(bytes)
        else (runCatching { codec.orientation(bytes) }.getOrNull()?.takeIf { it in 1..8 } ?: 1)
        val swap = ExifOrientation.swapsAxes(orientation)
        val ow = if (swap) h0 else w0
        val oh = if (swap) w0 else h0
        if (orientation == 1 && max(w0, h0) <= maxEdge && bytes.size <= maxBytes && mime in PASS_THROUGH) {
            return PreparedImage(bytes, mime!!, w0, h0)
        }
        // 解码时先按 2 的幂降采样，省内存；降采样后长边仍 ≥ maxEdge。
        var sample = 1
        while (max(w0, h0) / (sample * 2) >= maxEdge) sample *= 2
        val img = codec.decode(bytes, sample) ?: throw ImagePrepException("图片解码失败")
        try {
            var scale = minOf(1.0, maxEdge.toDouble() / max(ow, oh))
            val png = codec.hasAlpha(img)
            repeat(12) {
                val tw = max(1, (ow * scale).roundToInt())
                val th = max(1, (oh * scale).roundToInt())
                val out = codec.transform(img, orientation, tw, th)
                try {
                    if (png) {
                        val data = codec.encode(out, png = true, quality = 100)
                        if (data.size <= maxBytes) return PreparedImage(data, "image/png", tw, th)
                    }
                    for (q in QUALITIES) {
                        val data = codec.encode(out, png = false, quality = q)
                        if (data.size <= maxBytes) return PreparedImage(data, "image/jpeg", tw, th)
                    }
                } finally {
                    if (out !== img) codec.release(out)
                }
                scale *= 0.8
            }
            throw ImagePrepException("图片压缩后仍然过大")
        } finally {
            codec.release(img)
        }
    }

    companion object {
        private val QUALITIES = intArrayOf(90, 80, 70, 60, 50)
        val PASS_THROUGH = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

        /** 按文件头判断类型。 */
        fun sniffMime(b: ByteArray): String? = when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
            b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
                b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> "image/webp"
            else -> null
        }
    }
}
