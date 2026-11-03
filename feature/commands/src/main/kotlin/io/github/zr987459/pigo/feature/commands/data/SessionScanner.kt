package io.github.zr987459.pigo.feature.commands.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 索引里的一条会话：列表显示与全文搜索要用的全部信息。 */
data class IndexedSession(
    val path: String,
    val id: String?,
    val cwd: String?,
    /** 最后一条 `session_info` 的名字；被清空（空串）时为 null。 */
    val name: String?,
    val createdAt: String?,
    val firstMessage: String,
    val messageCount: Int,
    val parentSession: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 用户消息正文（按出现顺序），供全文搜索；总量超过 [SessionScanner.MAX_TEXT_CHARS] 后不再追加。 */
    val userTexts: List<String>,
)

data class ScanHit(
    val path: String,
    val title: String,
    /** 命中名字时为 null；否则为命中的第几条用户消息（从 0 开始）。 */
    val userMessageIndex: Int?,
    val snippet: String,
)

/**
 * 会话文件的增量扫描索引（S3）。
 *
 * - 文件只**变大**、且头一行与续读点前 [RESUME_SAMPLE_BYTES] 字节没变时，从上次停下的位置接着读，
 *   不重读整个文件；否则整份重读。
 * - 名字取**最后一条** `session_info`，所以名字在文件中间、在尾部都能读对（修 G8：旧实现只读头尾各 64 KB）。
 * - 只解析以换行结束的完整行；最后半行留到下次。
 * - 流式分块读取（[chunkBytes]，默认 64 KB），跨块保留未结束的半行，内存只与单行长度有关、与文件大小无关；
 *   单行超过 [maxLineBytes]（带 base64 图片的消息、超长 bash 输出）时不解析整行：只看行首一小段判断是不是
 *   user/assistant 消息并计入 messageCount，不进 userTexts / firstMessage（审查第 1 轮 Important 3）。
 *
 * 思路移植自 agegr/pi-web@025e24e `lib/session-list-scanner.ts:44-66`（ResumePoint）与 `:140-260`（foldEntry），MIT。
 */
class SessionScanner(
    private val chunkBytes: Int = CHUNK_BYTES,
    private val maxLineBytes: Int = MAX_LINE_BYTES,
) {
    private data class Resume(
        val offset: Long,
        val headerHash: String,
        val sampleHash: String,
    )

    private class Acc(
        var id: String? = null,
        var cwd: String? = null,
        var name: String? = null,
        var createdAt: String? = null,
        var firstMessage: String = "",
        var messageCount: Int = 0,
        var parentSession: String? = null,
        val userTexts: MutableList<String> = mutableListOf(),
        var textChars: Int = 0,
    )

    private class Entry(val size: Long, val mtime: Long, val acc: Acc, val resume: Resume?)

    private val entries = LinkedHashMap<String, Entry>()

    /** 累计读取的字节数（测试用：验证追加时只读新增部分）。 */
    @Volatile
    var bytesRead: Long = 0L
        private set

    @Synchronized
    fun scan(file: File): IndexedSession? {
        if (!file.isFile) {
            entries.remove(file.path)
            return null
        }
        val size = file.length()
        val mtime = file.lastModified()
        val cached = entries[file.path]
        if (cached != null && cached.size == size && cached.mtime == mtime) return view(file.path, cached)
        val entry = if (cached?.resume != null && size > cached.size && canResume(file, cached.resume)) {
            read(file, cached.acc, cached.resume.offset, size, mtime)
        } else {
            read(file, Acc(), 0L, size, mtime)
        }
        entries[file.path] = entry
        return view(file.path, entry)
    }

    /** 扫描一批文件，并丢掉已不存在的条目。按最后修改时间倒序返回。 */
    @Synchronized
    fun scanAll(files: List<File>): List<IndexedSession> {
        val keep = files.map { it.path }.toSet()
        entries.keys.retainAll(keep)
        return files.mapNotNull { scan(it) }.sortedByDescending { it.lastModified }
    }

    @Synchronized
    fun forget(path: String) {
        entries.remove(path)
    }

    @Synchronized
    fun snapshot(): List<IndexedSession> = entries.map { (p, e) -> view(p, e) }.sortedByDescending { it.lastModified }

    /** 全文搜索：名字和用户消息，大小写不敏感；名字命中排在前面，再按最近修改排序。 */
    @Synchronized
    fun search(query: String, limit: Int = 50): List<ScanHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<ScanHit, Long>>()
        for ((path, e) in entries) {
            val a = e.acc
            val title = a.name ?: a.firstMessage.ifBlank { "新会话" }
            val name = a.name
            if (name != null && name.contains(q, ignoreCase = true)) {
                hits += ScanHit(path, title, null, snippet(name, q)) to e.mtime
                continue
            }
            val idx = a.userTexts.indexOfFirst { it.contains(q, ignoreCase = true) }
            if (idx >= 0) hits += ScanHit(path, title, idx, snippet(a.userTexts[idx], q)) to e.mtime
        }
        return hits.sortedWith(compareBy<Pair<ScanHit, Long>> { if (it.first.userMessageIndex == null) 0 else 1 }.thenByDescending { it.second })
            .take(limit).map { it.first }
    }

    // ---------------- 读取 ----------------

    private fun read(file: File, acc: Acc, from: Long, size: Long, mtime: Long): Entry {
        var lineStart = from // 当前（未结束）行在文件里的起点 = 已处理完整行之后的位置
        var headerEnd = -1L
        val line = LineBuffer(maxLineBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buf = ByteArray(chunkBytes)
            var pos = from
            while (pos < size) {
                val want = minOf(chunkBytes.toLong(), size - pos).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                bytesRead += n
                var segStart = 0
                for (i in 0 until n) {
                    if (buf[i] != NL) continue
                    line.append(buf, segStart, i - segStart)
                    if (line.overflowed) foldOverlong(acc, line.head()) else fold(acc, line.text())
                    line.reset()
                    lineStart = pos + i + 1
                    if (from == 0L && headerEnd < 0) headerEnd = lineStart
                    segStart = i + 1
                }
                line.append(buf, segStart, n - segStart)
                pos += n
            }
        }
        val offset = lineStart
        val resume = if (offset <= 0) null else {
            val hEnd = if (from == 0L) headerEnd else null
            headerHashOf(file, hEnd)?.let { h -> Resume(offset, h, sampleHash(file, offset)) }
        }
        return Entry(size, mtime, acc, resume)
    }

    /**
     * 一行的缓冲。超过上限后只保留行首 [HEAD_BYTES] 字节（用来判断消息角色），其余丢弃，直到这一行结束。
     */
    private class LineBuffer(private val limit: Int) {
        private var bytes = ByteArray(minOf(limit, 8 * 1024).coerceAtLeast(16))
        private var len = 0
        var overflowed = false
            private set

        fun append(src: ByteArray, off: Int, n: Int) {
            if (n <= 0) return
            if (overflowed) {
                // 只补齐行首片段
                val room = HEAD_BYTES - len
                if (room > 0) { ensure(len + minOf(room, n)); System.arraycopy(src, off, bytes, len, minOf(room, n)); len += minOf(room, n) }
                return
            }
            if (len.toLong() + n > limit) {
                overflowed = true
                if (len > HEAD_BYTES) len = HEAD_BYTES
                append(src, off, n)
                return
            }
            ensure(len + n)
            System.arraycopy(src, off, bytes, len, n)
            len += n
        }

        private fun ensure(cap: Int) {
            if (cap <= bytes.size) return
            var c = bytes.size
            while (c < cap) c = (c.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            bytes = bytes.copyOf(c)
        }

        fun text(): String = String(bytes, 0, len, Charsets.UTF_8).removeSuffix("\r")
        fun head(): String = String(bytes, 0, minOf(len, HEAD_BYTES), Charsets.UTF_8)

        fun reset() {
            len = 0
            overflowed = false
            if (bytes.size > 64 * 1024) bytes = ByteArray(8 * 1024) // 读过一条长行后不长期占着大缓冲
        }
    }

    /** 过长的一行：只按行首判断是不是 user/assistant 消息，计数，不解析正文。 */
    private fun foldOverlong(acc: Acc, head: String) {
        if (!TYPE_MESSAGE.containsMatchIn(head)) return
        if (ROLE_CHAT.containsMatchIn(head)) acc.messageCount++
    }

    private fun fold(acc: Acc, line: String) {
        if (line.isBlank()) return
        val o = try {
            JSON.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            return
        }
        when (str(o["type"])) {
            "session" -> {
                acc.id = str(o["id"]) ?: acc.id
                acc.cwd = str(o["cwd"]) ?: acc.cwd
                acc.createdAt = str(o["timestamp"]) ?: acc.createdAt
                acc.parentSession = str(o["parentSession"]) ?: acc.parentSession
            }
            "session_info" -> acc.name = str(o["name"])?.trim()?.takeIf { it.isNotEmpty() }
            "message" -> {
                val m = o["message"] as? JsonObject ?: return
                val role = str(m["role"])
                if (role == "user" || role == "assistant") acc.messageCount++
                if (role == "user") {
                    val text = textOf(m["content"]).trim()
                    if (text.isEmpty()) return
                    if (acc.firstMessage.isEmpty()) acc.firstMessage = text.take(200)
                    if (acc.textChars < MAX_TEXT_CHARS) {
                        val t = text.take(MAX_TEXT_CHARS - acc.textChars)
                        acc.userTexts += t
                        acc.textChars += t.length
                    }
                }
            }
        }
    }

    private fun canResume(file: File, r: Resume): Boolean =
        headerHashOf(file, null) == r.headerHash && sampleHash(file, r.offset) == r.sampleHash

    /** 头一行（到第一个换行为止，最多 [HEADER_MAX] 字节）的哈希。 */
    private fun headerHashOf(file: File, knownEnd: Long?): String? = RandomAccessFile(file, "r").use { raf ->
        val n = minOf(knownEnd ?: raf.length(), raf.length(), HEADER_MAX.toLong()).toInt()
        val buf = ByteArray(n)
        raf.readFully(buf)
        val end = buf.indexOf('\n'.code.toByte()).let { if (it < 0) n else it + 1 }
        md5(buf, 0, end)
    }

    private fun sampleHash(file: File, offset: Long): String = RandomAccessFile(file, "r").use { raf ->
        val from = (offset - RESUME_SAMPLE_BYTES).coerceAtLeast(0)
        val buf = ByteArray((offset - from).toInt())
        raf.seek(from)
        raf.readFully(buf)
        md5(buf, 0, buf.size)
    }

    private fun view(path: String, e: Entry) = IndexedSession(
        path = path,
        id = e.acc.id,
        cwd = e.acc.cwd,
        name = e.acc.name,
        createdAt = e.acc.createdAt,
        firstMessage = e.acc.firstMessage,
        messageCount = e.acc.messageCount,
        parentSession = e.acc.parentSession,
        sizeBytes = e.size,
        lastModified = e.mtime,
        userTexts = e.acc.userTexts.toList(),
    )

    // ---------------- 持久化（可选） ----------------

    /** 把索引写成 JSON，下次启动先载入再增量扫描。 */
    @Synchronized
    fun save(target: File) {
        val root = buildJsonObject {
            put("v", FORMAT_VERSION)
            put("entries", buildJsonArray {
                for ((p, e) in entries) add(buildJsonObject {
                    put("path", p); put("size", e.size); put("mtime", e.mtime)
                    e.resume?.let { put("offset", it.offset); put("hh", it.headerHash); put("sh", it.sampleHash) }
                    val a = e.acc
                    a.id?.let { put("id", it) }; a.cwd?.let { put("cwd", it) }; a.name?.let { put("name", it) }
                    a.createdAt?.let { put("created", it) }; a.parentSession?.let { put("parent", it) }
                    put("first", a.firstMessage); put("count", a.messageCount)
                    put("texts", JsonArray(a.userTexts.map { JsonPrimitive(it) }))
                })
            })
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    @Synchronized
    fun load(source: File) {
        if (!source.isFile) return
        val root = try { JSON.parseToJsonElement(source.readText()).jsonObject } catch (_: Exception) { return }
        if ((root["v"] as? JsonPrimitive)?.longOrNull != FORMAT_VERSION.toLong()) return
        val arr = root["entries"] as? JsonArray ?: return
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val path = str(o["path"]) ?: continue
            val acc = Acc(
                id = str(o["id"]), cwd = str(o["cwd"]), name = str(o["name"]), createdAt = str(o["created"]),
                firstMessage = str(o["first"]).orEmpty(), messageCount = num(o["count"]).toInt(), parentSession = str(o["parent"]),
            )
            (o["texts"] as? JsonArray)?.forEach { t -> str(t)?.let { acc.userTexts += it; acc.textChars += it.length } }
            val resume = str(o["hh"])?.let { hh -> Resume(num(o["offset"]), hh, str(o["sh"]).orEmpty()) }
            entries[path] = Entry(num(o["size"]), num(o["mtime"]), acc, resume)
        }
    }

    companion object {
        const val RESUME_SAMPLE_BYTES = 512
        const val HEADER_MAX = 64 * 1024
        const val MAX_TEXT_CHARS = 256 * 1024
        /** 每次读取的块大小。 */
        const val CHUNK_BYTES = 64 * 1024
        /** 单行超过这个长度就不整行解析（带图消息、超长工具输出）。 */
        const val MAX_LINE_BYTES = 4 * 1024 * 1024
        /** 过长行保留的行首字节数，用来判断 type / role。 */
        internal const val HEAD_BYTES = 4 * 1024
        private const val NL = '\n'.code.toByte()
        private val TYPE_MESSAGE = Regex(""""type"\s*:\s*"message"""")
        private val ROLE_CHAT = Regex(""""role"\s*:\s*"(user|assistant)"""")
        private const val FORMAT_VERSION = 1
        private val JSON = Json { ignoreUnknownKeys = true }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
        private fun num(e: JsonElement?): Long = (e as? JsonPrimitive)?.longOrNull ?: 0L

        /** content 可能是字符串，也可能是 [{type:text,text}, {type:image}] 数组。 */
        fun textOf(content: JsonElement?): String = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { b ->
                val o = b as? JsonObject ?: return@mapNotNull null
                if (str(o["type"]) == "text") str(o["text"]) else null
            }.joinToString("\n")
            else -> ""
        }

        fun snippet(text: String, q: String, radius: Int = 30): String {
            val flat = text.replace(Regex("\\s+"), " ")
            val i = flat.indexOf(q, ignoreCase = true)
            if (i < 0) return flat.take(radius * 2)
            val s = (i - radius).coerceAtLeast(0)
            val e = (i + q.length + radius).coerceAtMost(flat.length)
            return (if (s > 0) "…" else "") + flat.substring(s, e) + (if (e < flat.length) "…" else "")
        }

        private fun md5(b: ByteArray, off: Int, len: Int): String =
            MessageDigest.getInstance("MD5").apply { update(b, off, len) }.digest().joinToString("") { "%02x".format(it) }
    }
}
