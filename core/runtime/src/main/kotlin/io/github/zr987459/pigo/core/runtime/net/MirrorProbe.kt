package io.github.zr987459.pigo.core.runtime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 镜像测速排序（DESIGN.md §4.3 第 1 步）：并行 HEAD + 约 2 s 吞吐采样。
 * HEAD 失败的镜像排到最后（仍保留，作为兜底）；其余按实测吞吐从高到低。
 */
open class MirrorProbe(
    private val client: OkHttpClient,
    private val sampleMs: Long = 2_000,
    private val sampleMaxBytes: Long = 4L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Score(val url: String, val ok: Boolean, val bytesPerSec: Long)

    /** [urls] 是同一文件在各镜像上的完整地址；返回排序后的列表与各自得分。 */
    open suspend fun rank(urls: List<String>): List<Score> = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { probe(url) } }.awaitAll()
            .sortedWith(compareByDescending<Score> { it.ok }.thenByDescending { it.bytesPerSec })
    }

    private suspend fun probe(url: String): Score = withContext(Dispatchers.IO) {
        val headOk = runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (!headOk) return@withContext Score(url, false, 0)
        var bytes = 0L
        val start = clock()
        withTimeoutOrNull(sampleMs + 1_000) {
            runCatching {
                client.newCall(
                    Request.Builder().url(url).header("Range", "bytes=0-${sampleMaxBytes - 1}").build(),
                ).execute().use { resp ->
                    val input = resp.body?.byteStream() ?: return@use
                    val buf = ByteArray(32 * 1024)
                    while (clock() - start < sampleMs && bytes < sampleMaxBytes) {
                        val n = input.read(buf)
                        if (n < 0) break
                        bytes += n
                    }
                }
            }
        }
        val elapsed = (clock() - start).coerceAtLeast(1)
        Score(url, true, bytes * 1000 / elapsed)
    }
}
