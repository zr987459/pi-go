package io.github.zr987459.pigo.core.store

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 解析 pi 会话文件（`session-format.md`，DESIGN.md §3.8）得到列表需要的几项：
 *
 * - 首行 header：`type:"session"`、`id`、`timestamp`、`cwd`；
 * - 自尾向前最后一个 `session_info` 的 `name`；
 * - 第一条 `role:"user"` 消息的前 [PREVIEW_CHARS] 个字作预览。
 *
 * 只按 `\n` 分行（`\r\n` 去掉行尾 `\r`；不按 `\r`、U+2028/U+2029 切分）。任何一行解析失败都跳过，不抛异常。
 * 大文件只读头 [CHUNK] 字节 + 尾 [CHUNK] 字节；首条 user 消息不在头部时再向后最多读到 [PREVIEW_SCAN_LIMIT]。
 */
object SessionFileParser {
    const val CHUNK = 64 * 1024
    const val PREVIEW_CHARS = 80
    const val PREVIEW_SCAN_LIMIT = 512 * 1024

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Parsed(
        val id: String,
        val cwd: String,
        val name: String?,
        val preview: String,
        val createdAt: Instant,
        val headerOk: Boolean,
    )

    fun parse(file: File): Parsed {
        val size = file.length()
        val headLines: List<String>
        val tailLines: List<String>
        if (size <= 2L * CHUNK) {
            val all = splitLines(file.readBytes(), dropFirstPartial = false, dropLastPartial = false)
            headLines = all
            tailLines = all
        } else {
            headLines = readHead(file, CHUNK)
            tailLines = readTail(file, CHUNK)
        }

        val header = headLines.firstOrNull()?.let(::parseObject)?.takeIf { it.str("type") == "session" }
        val fallbackId = idFromFileName(file.name)
        val id = header?.str("id")?.takeIf { it.isNotBlank() } ?: fallbackId
        val cwd = header?.str("cwd") ?: ""
        val createdAt = header?.str("timestamp")?.let(::parseInstant)
            ?: timestampFromFileName(file.name)
            ?: Instant.ofEpochMilli(file.lastModified())

        // 名称：自尾向前找最后一个 session_info；尾部没有时再看头部（小文件两者相同）。
        val name = findLastName(tailLines) ?: if (size > 2L * CHUNK) findLastName(headLines) else null

        var preview = findPreview(headLines)
        if (preview == null && size > 2L * CHUNK) {
            preview = findPreview(readHead(file, PREVIEW_SCAN_LIMIT.coerceAtMost(size.toInt())))
        }

        return Parsed(
            id = id,
            cwd = cwd,
            name = name,
            preview = preview ?: "",
            createdAt = createdAt,
            headerOk = header != null,
        )
    }

    // ---------------- 行读取 ----------------

    /** 读开头 [limit] 字节，丢掉最后一行不完整的部分（除非读到了文件末尾）。 */
    internal fun readHead(file: File, limit: Int): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val n = minOf(limit.toLong(), raf.length()).toInt()
            val buf = ByteArray(n)
            raf.readFully(buf)
            return splitLines(buf, dropFirstPartial = false, dropLastPartial = n < raf.length())
        }
    }

    /** 读末尾 [limit] 字节，丢掉第一行不完整的部分（除非从文件开头读起）。 */
    internal fun readTail(file: File, limit: Int): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = maxOf(0L, len - limit)
            val buf = ByteArray((len - start).toInt())
            raf.seek(start)
            raf.readFully(buf)
            return splitLines(buf, dropFirstPartial = start > 0, dropLastPartial = false)
        }
    }

    internal fun splitLines(bytes: ByteArray, dropFirstPartial: Boolean, dropLastPartial: Boolean): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var first = true
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                if (!(first && dropFirstPartial)) out += decode(bytes, start, i)
                first = false
                start = i + 1
            }
        }
        if (start < bytes.size && !dropLastPartial && !(first && dropFirstPartial)) {
            out += decode(bytes, start, bytes.size)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun decode(bytes: ByteArray, from: Int, to: Int): String {
        var end = to
        if (end > from && bytes[end - 1] == '\r'.code.toByte()) end--
        return String(bytes, from, end - from, Charsets.UTF_8)
    }

    // ---------------- 字段提取 ----------------

    private fun parseObject(line: String): JsonObject? =
        try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: Exception) {
            null
        }

    private fun findLastName(lines: List<String>): String? {
        for (line in lines.asReversed()) {
            if (!line.contains("\"session_info\"")) continue
            val o = parseObject(line) ?: continue
            if (o.str("type") != "session_info") continue
            // 最后一条 session_info 为准；name 为空表示已清除名称。
            return o.str("name")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun findPreview(lines: List<String>): String? {
        for (line in lines) {
            if (!line.contains("\"user\"")) continue
            val o = parseObject(line) ?: continue
            if (o.str("type") != "message") continue
            val msg = o["message"] as? JsonObject ?: continue
            if (msg.str("role") != "user") continue
            val text = contentText(msg["content"]).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            return truncate(text, PREVIEW_CHARS)
        }
        return null
    }

    /** user 消息的 content 是字符串或 `(TextContent | ImageContent)[]`。 */
    private fun contentText(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.contentOrNull ?: ""
        is JsonArray -> e.mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "text") o.str("text") else null
        }.joinToString(" ")
        else -> ""
    }

    /** 按 Unicode 码点截断，不切开代理对。 */
    internal fun truncate(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max)
        return s.substring(0, end) + "…"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseInstant(s: String): Instant? = try {
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }

    /** `<timestamp>_<session-id>.jsonl` → session-id。 */
    internal fun idFromFileName(name: String): String {
        val stem = name.removeSuffix(".jsonl").replace(Regex("~\\d+$"), "")
        return stem.substringAfter('_', stem)
    }

    /** `2026-11-03T06-40-00-000Z_xxx.jsonl` → Instant。 */
    internal fun timestampFromFileName(name: String): Instant? {
        val ts = name.substringBefore('_')
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2})-(\\d{2})-(\\d{2})(?:-(\\d{3}))?Z$").find(ts) ?: return null
        val (d, h, mi, s) = m.destructured
        val ms = m.groupValues[5].ifEmpty { "000" }
        return parseInstant("${d}T$h:$mi:$s.${ms}Z")
    }
}
