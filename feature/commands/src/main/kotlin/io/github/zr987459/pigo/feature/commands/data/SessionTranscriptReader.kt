package io.github.zr987459.pigo.feature.commands.data

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.ContentBlock
import io.github.zr987459.pigo.contract.NoticeKind
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 从会话文件读出给命名用的简化时间线（只有用户、助手正文和压缩摘要），不经 pi。
 * 只看当前分支：从最后一条 entry 沿 `parentId` 回溯。
 */
object SessionTranscriptReader {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun read(file: File, maxBytes: Long = 8L * 1024 * 1024): List<ChatItem> {
        if (!file.isFile) return emptyList()
        val byId = LinkedHashMap<String, JsonObject>()
        var last: String? = null
        val text = if (file.length() > maxBytes) {
            // 超大文件只读尾部：命名只需要开头和结尾，开头由 SessionScanner 的 firstMessage 补上。
            file.inputStream().use { it.skip(file.length() - maxBytes); it.readBytes().toString(Charsets.UTF_8) }.substringAfter('\n')
        } else file.readText()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val o = try { JSON.parseToJsonElement(line).jsonObject } catch (_: Exception) { continue }
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "session") continue
            byId[id] = o
            last = id
        }
        val chain = ArrayList<JsonObject>()
        var cur = last
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val o = byId[cur] ?: break
            chain += o
            cur = (o["parentId"] as? JsonPrimitive)?.contentOrNull
        }
        chain.reverse()
        return chain.mapNotNull(::toItem)
    }

    private fun toItem(o: JsonObject): ChatItem? {
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return null
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "message" -> {
                val m = o["message"] as? JsonObject ?: return null
                val t = SessionScanner.textOf(m["content"])
                when ((m["role"] as? JsonPrimitive)?.contentOrNull) {
                    "user" -> ChatItem.User(id, t)
                    "assistant" -> ChatItem.Assistant(id, listOf(ContentBlock.Text(0, t)), streaming = false)
                    else -> null
                }
            }
            "compaction" -> (o["summary"] as? JsonPrimitive)?.contentOrNull?.let { ChatItem.Notice(id, NoticeKind.COMPACTION, it) }
            else -> null
        }
    }
}
