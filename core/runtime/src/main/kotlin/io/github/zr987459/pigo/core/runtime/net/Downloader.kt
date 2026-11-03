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
 * - 每个来源 URL 有自己的 `.part`：`<name>.<url 哈希>.part`，旁边写同名 `.part.src` 记录完整 URL
 *   （[partFile]/[srcFile]）。续传只对**同一 URL** 用 `Range`，`.src` 与当前 URL 不符的残留一律删除重下，
 *   因此换镜像时绝不会把不同来源的内容拼进同一个文件；
 * - 因过慢（[TooSlow]）换线时，原镜像自己的 `.part` 保留，之后回到该镜像（例如所有镜像都慢、回退到测速最好的那个）
 *   可以接着续传，而不是从头再下；
 * - 服务端不支持 Range（返回 200）时从头写；
 * - 下载完成后校验 SHA256，不符删除该 `.part` 并抛 [ChecksumMismatch]；通过后才 rename 为最终文件名，
 *   并清掉同一目标文件的所有 `.part`（含旧版本留下的 `<name>.part`）；
 * - [detectSlow] 为 true 时，开始后 [slowWindowMs]（默认 6 s）内平均低于 [minBytesPerSec]（默认 120 KB/s）判定过慢，抛 [TooSlow]。
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
        detectSlow: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val expected = sha256.lowercase()
        if (dest.isFile) {
            if (Hashes.sha256(dest) == expected) return@withContext dest
            dest.delete()
        }
        dest.parentFile?.mkdirs()
        val part = partFile(dest, url)
        val src = srcFile(dest, url)
        // 旧版本使用不带来源哈希的 `<name>.part`：来源无法与新命名对应，直接丢弃
        legacyPartFile(dest).delete()
        legacySrcFile(dest).delete()

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
                        if (detectSlow && !windowChecked && elapsed >= slowWindowMs) {
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
        // 已得到通过校验的完整文件：其他镜像留下的半截 .part 不再需要
        deleteAllParts(dest)
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
        /** [url] 专属的半截文件。不同 URL 的 `.part` 互不相干，续传时只会接上同一 URL 的内容。 */
        fun partFile(dest: File, url: String) = File(dest.parentFile, "${dest.name}.${urlKey(url)}.part")

        /** [partFile] 的来源记录（完整 URL），续传前逐字比对，防哈希碰撞或被改动。 */
        fun srcFile(dest: File, url: String) = File(dest.parentFile, "${dest.name}.${urlKey(url)}.part.src")

        /** [dest] 的所有半截文件及来源记录（任何 URL，含旧命名）。 */
        fun allParts(dest: File): List<File> {
            val prefix = dest.name + "."
            return dest.parentFile?.listFiles()?.filter {
                it.isFile && it.name.startsWith(prefix) && (it.name.endsWith(".part") || it.name.endsWith(".part.src"))
            }.orEmpty()
        }

        fun deleteAllParts(dest: File) {
            allParts(dest).forEach { it.delete() }
        }

        internal fun legacyPartFile(dest: File) = File(dest.parentFile, dest.name + ".part")
        internal fun legacySrcFile(dest: File) = File(dest.parentFile, dest.name + ".part.src")

        internal fun urlKey(url: String): String {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            return d.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
