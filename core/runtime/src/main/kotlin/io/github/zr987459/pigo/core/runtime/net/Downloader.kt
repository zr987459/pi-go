package io.github.zr987459.pigo.core.runtime.net

import io.github.zr987459.pigo.core.runtime.util.Hashes
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * 断点续传下载 + SHA256 校验（DESIGN.md §4.3「下载完整性」）。
 *
 * - 下载到 `<name>.part`，旁边写 `<name>.part.src` 记录来源 URL；
 * - 续传只对**同一 URL** 用 `Range`；来源不同（换镜像）先删除 `.part` 重新下载；
 * - 服务端不支持 Range（返回 200）时从头写；
 * - 下载完成后校验 SHA256，不符删除文件并抛 [ChecksumMismatch]；通过后才 rename 为最终文件名；
 * - 下载前 [minBytesPerSec] 窗口（默认 6 s 后 <120 KB/s）判定过慢时抛 [TooSlow]，`.part` 保留以便同 URL 续传。
 */
class Downloader(
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val slowWindowMs: Long = 6_000,
    private val minBytesPerSec: Long = 120 * 1024,
) {
    class ChecksumMismatch(val url: String, val expected: String, val actual: String) :
        IOException("校验失败：$url 期望 $expected，实际 $actual")
    class TooSlow(val url: String, val bytesPerSec: Long) : IOException("下载过慢（$bytesPerSec B/s）：$url")
    class HttpError(val url: String, val code: Int) : IOException("HTTP $code：$url")

    data class Progress(val downloaded: Long, val total: Long?, val bytesPerSec: Long?)

    /**
     * 下载 [url] 到 [dest]，SHA256 必须等于 [sha256]（小写十六进制）。
     * [dest] 已存在且哈希正确时直接返回（已校验的文件不重下）。
     */
    suspend fun download(
        url: String,
        dest: File,
        sha256: String,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val expected = sha256.lowercase()
        if (dest.isFile) {
            if (Hashes.sha256(dest) == expected) return@withContext dest
            dest.delete()
        }
        dest.parentFile?.mkdirs()
        val part = partFile(dest)
        val src = srcFile(dest)

        // 来源绑定：不同 URL 的残留一律丢弃，绝不拼接不同镜像的内容。
        if (part.exists() && (!src.isFile || src.readText().trim() != url)) {
            part.delete()
        }
        src.writeText(url)

        var offset = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (offset > 0) builder.header("Range", "bytes=$offset-")

        client.newCall(builder.build()).execute().use { resp ->
            val code = resp.code
            if (code == 416 && offset > 0) {
                // 本地 .part 已是完整长度（或服务端文件变了）：先按完整文件校验，失败则重下由调用方决定
                return@use
            }
            if (!resp.isSuccessful) throw HttpError(url, code)
            val append = offset > 0 && code == 206
            if (!append) offset = 0
            val body = resp.body ?: throw HttpError(url, code)
            val total = body.contentLength().takeIf { it >= 0 }?.let { it + offset }

            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(offset) else raf.setLength(0)
                val start = clock()
                var windowChecked = false
                var downloaded = offset
                var sessionBytes = 0L
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        sessionBytes += n
                        val elapsed = clock() - start
                        val bps = if (elapsed > 0) sessionBytes * 1000 / elapsed else null
                        onProgress(Progress(downloaded, total, bps))
                        if (!windowChecked && elapsed >= slowWindowMs) {
                            windowChecked = true
                            val rate = sessionBytes * 1000 / elapsed
                            if (rate < minBytesPerSec && (total == null || downloaded < total)) throw TooSlow(url, rate)
                        }
                    }
                }
            }
        }

        val actual = Hashes.sha256(part)
        if (actual != expected) {
            part.delete()
            src.delete()
            throw ChecksumMismatch(url, expected, actual)
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        src.delete()
        dest
    }

    /** 小文件（SHA256SUMS 等）直接读成文本；不做续传。 */
    suspend fun fetchText(url: String, maxBytes: Long = 1024 * 1024): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(url, resp.code)
            val body = resp.body ?: throw HttpError(url, resp.code)
            if (body.contentLength() > maxBytes) throw IOException("响应过大：$url")
            val bytes = body.source().readByteArray()
            if (bytes.size > maxBytes) throw IOException("响应过大：$url")
            String(bytes, Charsets.UTF_8)
        }
    }

    companion object {
        fun partFile(dest: File) = File(dest.parentFile, dest.name + ".part")
        fun srcFile(dest: File) = File(dest.parentFile, dest.name + ".part.src")
    }
}
