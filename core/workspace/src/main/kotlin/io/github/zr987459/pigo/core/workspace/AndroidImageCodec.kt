package io.github.zr987459.pigo.core.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** [ImageCodec] 的 Android 实现（Bitmap）。EXIF 方向（JPEG 由 [ExifOrientation]、其他格式由 [orientation]）读出后在这里一次性旋转 + 缩放。 */
object AndroidImageCodec : ImageCodec<Bitmap> {
    override fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    override fun decode(bytes: ByteArray, sampleSize: Int): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })

    override fun width(img: Bitmap) = img.width
    override fun height(img: Bitmap) = img.height
    override fun hasAlpha(img: Bitmap) = img.hasAlpha()

    override fun transform(img: Bitmap, orientation: Int, outW: Int, outH: Int): Bitmap {
        val swap = ExifOrientation.swapsAxes(orientation)
        val srcW = if (swap) img.height else img.width
        val srcH = if (swap) img.width else img.height
        if (orientation == 1 && srcW == outW && srcH == outH) return img
        val m = Matrix()
        orientationMatrix(m, orientation, img.width.toFloat(), img.height.toFloat())
        m.postScale(outW.toFloat() / srcW, outH.toFloat() / srcH)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** EXIF 1–8 → 把原图摆正、并平移回第一象限的矩阵。 */
    fun orientationMatrix(m: Matrix, o: Int, w: Float, h: Float) {
        when (o) {
            2 -> { m.postScale(-1f, 1f); m.postTranslate(w, 0f) }
            3 -> { m.postRotate(180f); m.postTranslate(w, h) }
            4 -> { m.postScale(1f, -1f); m.postTranslate(0f, h) }
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) } // transpose
            6 -> { m.postRotate(90f); m.postTranslate(h, 0f) }
            7 -> { m.postRotate(-90f); m.postScale(-1f, 1f); m.postTranslate(h, w) } // transverse
            8 -> { m.postRotate(-90f); m.postTranslate(0f, w) }
        }
    }

    override fun encode(img: Bitmap, png: Boolean, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (png) img.compress(Bitmap.CompressFormat.PNG, 100, out)
        else {
            val opaque = if (img.hasAlpha()) flatten(img) else img
            opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (opaque !== img) opaque.recycle()
        }
        return out.toByteArray()
    }

    /** 透明图转 JPEG 时铺白底（否则透明处变黑）。 */
    private fun flatten(img: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(img, 0f, 0f, null)
        return b
    }

    /** HEIC/HEIF、WebP、PNG 等的 EXIF 方向（JPEG 走 [ExifOrientation]）。 */
    override fun orientation(bytes: ByteArray): Int? = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            .takeIf { it in 1..8 }
    }.getOrNull()

    override fun release(img: Bitmap) {
        if (!img.isRecycled) img.recycle()
    }
}
