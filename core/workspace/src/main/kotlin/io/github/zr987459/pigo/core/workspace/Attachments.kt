package io.github.zr987459.pigo.core.workspace

import io.github.zr987459.pigo.contract.Attachment
import io.github.zr987459.pigo.contract.AttachmentProcessor
import io.github.zr987459.pigo.contract.ErrCode
import io.github.zr987459.pigo.contract.ImageInput
import io.github.zr987459.pigo.contract.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
import java.util.Base64

/**
 * [AttachmentProcessor] 的实现（feature-gap C1、C2）。
 *
 * - 图片：读入（原图上限 [maxSourceBytes]），经 [ImagePrep] 压到长边 ≤ maxEdgePx、按 EXIF 摆正。体积上限按 base64 计：
 *   原始字节 ≤ maxBytes × 3/4，转成 base64 后仍 ≤ maxBytes，按 base64 计 5 MB 的服务商也不会拒收。
 * - 文件：导入到 `<cwd>/.pigo/uploads/<yyyy-MM-dd>/`，重名加序号，超过上限拒绝且不留半成品。
 */
class WorkspaceAttachments<B : Any>(
    private val workspace: RootfsWorkspace,
    private val codec: ImageCodec<B>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxSourceBytes: Long = 64L * 1024 * 1024,
) : AttachmentProcessor {

    override suspend fun prepareImage(
        input: InputStream,
        mimeType: String?,
        displayName: String?,
        maxEdgePx: Int,
        maxBytes: Long,
    ): Outcome<ImageInput> = withContext(io) {
        try {
            val bytes = readLimited(input, maxSourceBytes)
            val prep = ImagePrep(codec, maxEdge = maxEdgePx, maxBytes = (maxBytes * 3 / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val img = prep.prepare(bytes, mimeType)
            Outcome.Ok(
                ImageInput(
                    mimeType = img.mimeType,
                    base64Data = Base64.getEncoder().encodeToString(img.bytes),
                    widthPx = img.width,
                    heightPx = img.height,
                    displayName = displayName,
                ),
            )
        } catch (e: TooLargeException) {
            Outcome.Err(ErrCode.UNKNOWN, "图片太大（超过 ${maxSourceBytes / (1024 * 1024)} MB）", e)
        } catch (e: ImagePrepException) {
            Outcome.Err(ErrCode.UNKNOWN, e.message ?: "图片处理失败", e)
        } catch (e: OutOfMemoryError) {
            Outcome.Err(ErrCode.OOM, "图片太大，内存不足", null)
        } catch (e: IOException) {
            Outcome.Err(ErrCode.UNKNOWN, "读取图片失败：${e.message}", e)
        }
    }

    override suspend fun importFile(fileName: String, input: InputStream, cwd: String, maxBytes: Long): Outcome<Attachment.FileRef> =
        withContext(io) {
            try {
                workspace.allowRoot(cwd)
                val dir = uploadDir(cwd, LocalDate.now(clock))
                Outcome.Ok(Attachment.FileRef(workspace.importInto(fileName, input, dir, maxBytes)))
            } catch (e: TooLargeException) {
                Outcome.Err(ErrCode.UNKNOWN, "文件超过 ${maxBytes / (1024 * 1024)} MB，无法作为附件", e)
            } catch (e: PathDeniedException) {
                Outcome.Err(ErrCode.UNKNOWN, e.message ?: "无权访问", e)
            } catch (e: IOException) {
                val disk = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
                Outcome.Err(if (disk) ErrCode.DISK_FULL else ErrCode.UNKNOWN, if (disk) "存储空间不足" else "导入失败：${e.message}", e)
            }
        }

    companion object {
        fun uploadDir(cwd: String, day: LocalDate): String =
            GuestPaths.normalize(cwd).let { c -> (if (c == "/") "" else c) + "/" + AttachmentProcessor.UPLOAD_DIR + "/" + day }

        internal fun readLimited(input: InputStream, limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException(limit)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
