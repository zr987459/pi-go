package io.github.zr987459.pigo.core.protocol

import java.io.InputStream

/**
 * pi RPC 的 JSONL 分帧器（DESIGN.md §3.3）。
 *
 * - 只按 LF（0x0A）切分，去掉行尾可选的 `\r`；U+2028 / U+2029 在 UTF-8 里是多字节序列，
 *   不含 0x0A，因此天然不会被切开（不要换成 `BufferedReader.readLine()`：它会把单独的 `\r` 也当换行）。
 * - 按字节累积后再整体 UTF-8 解码，字节块可以在任意位置断开（包括多字节字符中间）。
 * - 单行超过 [maxLineBytes] 时丢弃该行剩余内容直到下一个 LF，并报告一次 [Frame.Oversized]，
 *   之后继续正常分帧，不会因为一条坏记录卡死或耗尽内存。
 *
 * 非线程安全：由单个读线程使用。
 */
class JsonlFramer(
    val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    sealed interface Frame {
        /** 一条完整记录（已去掉 LF 与可选的 `\r`）。空行不会产出。 */
        data class Line(val text: String) : Frame

        /** 一条超长记录被丢弃；[bytes] 为该行的总字节数（不含 LF）。 */
        data class Oversized(val bytes: Long) : Frame
    }

    private var buf = ByteArray(INITIAL_CAPACITY)
    private var len = 0
    /** 当前行是否已超长、正在丢弃。 */
    private var discarding = false
    private var discardedBytes = 0L

    init {
        require(maxLineBytes > 0) { "maxLineBytes must be > 0" }
    }

    /** 喂入一块字节，按顺序回调产出的帧。 */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, sink: (Frame) -> Unit) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "bad range" }
        var start = offset
        val end = offset + length
        var i = offset
        while (i < end) {
            if (bytes[i] == LF) {
                appendOrDiscard(bytes, start, i - start)
                emitLine(sink)
                start = i + 1
            }
            i++
        }
        if (start < end) appendOrDiscard(bytes, start, end - start)
    }

    /** 便捷方法：喂入字符串的 UTF-8 字节。 */
    fun feed(text: String, sink: (Frame) -> Unit) {
        val b = text.toByteArray(Charsets.UTF_8)
        feed(b, 0, b.size, sink)
    }

    /** 流结束：若缓冲区里还有不以 LF 结尾的半行，按一条记录产出（pi 正常情况下每条都以 LF 结尾）。 */
    fun finish(sink: (Frame) -> Unit) {
        if (len > 0 || discarding) emitLine(sink)
    }

    /** 当前缓存的未完成字节数（测试用）。 */
    val pendingBytes: Int get() = len

    private fun appendOrDiscard(src: ByteArray, from: Int, count: Int) {
        if (count == 0) return
        if (discarding) {
            discardedBytes += count
            return
        }
        if (len.toLong() + count > maxLineBytes) {
            discarding = true
            discardedBytes = len.toLong() + count
            len = 0
            if (buf.size > INITIAL_CAPACITY * 16) buf = ByteArray(INITIAL_CAPACITY)
            return
        }
        ensureCapacity(len + count)
        System.arraycopy(src, from, buf, len, count)
        len += count
    }

    private fun emitLine(sink: (Frame) -> Unit) {
        if (discarding) {
            val n = discardedBytes
            discarding = false
            discardedBytes = 0
            len = 0
            sink(Frame.Oversized(n))
            return
        }
        var n = len
        if (n > 0 && buf[n - 1] == CR) n--
        len = 0
        if (n == 0) return
        val text = String(buf, 0, n, Charsets.UTF_8)
        // 一条大记录过后把缓冲区缩回来，避免长期占内存。
        if (buf.size > INITIAL_CAPACITY * 16) buf = ByteArray(INITIAL_CAPACITY)
        sink(Frame.Line(text))
    }

    private fun ensureCapacity(need: Int) {
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap = if (cap > Int.MAX_VALUE / 2) Int.MAX_VALUE else cap * 2
        buf = buf.copyOf(cap)
    }

    companion object {
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private const val INITIAL_CAPACITY = 8 * 1024

        /** 单条记录上限。`get_messages` 的长会话可达数 MB，留足余量。 */
        const val DEFAULT_MAX_LINE_BYTES = 32 * 1024 * 1024

        /**
         * 阻塞地从 [input] 读到 EOF，逐帧回调。用于专用读线程：只读、只投递，不做重活。
         * @return 读到的总字节数
         */
        fun pump(input: InputStream, framer: JsonlFramer = JsonlFramer(), chunkSize: Int = 64 * 1024, sink: (Frame) -> Unit): Long {
            val chunk = ByteArray(chunkSize)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                total += n
                framer.feed(chunk, 0, n, sink)
            }
            framer.finish(sink)
            return total
        }
    }
}
