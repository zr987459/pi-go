package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.rpc.BashResultRecord
import io.github.zr987459.pigo.contract.rpc.CompactionResultRecord
import io.github.zr987459.pigo.contract.rpc.ContextUsageRecord
import io.github.zr987459.pigo.contract.rpc.ForkMessageRecord
import io.github.zr987459.pigo.contract.rpc.ForkResultRecord
import io.github.zr987459.pigo.contract.rpc.RpcImage
import io.github.zr987459.pigo.contract.rpc.SessionEntriesRecord
import io.github.zr987459.pigo.contract.rpc.SessionEntryRecord
import io.github.zr987459.pigo.contract.rpc.SessionStatsRecord
import io.github.zr987459.pigo.contract.rpc.SessionTreeNodeRecord
import io.github.zr987459.pigo.contract.rpc.SessionTreeRecord
import io.github.zr987459.pigo.contract.rpc.SlashCommandRecord
import io.github.zr987459.pigo.contract.rpc.TokenTotalsRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi 1.5.0 其余命令 `response.data` → `rpc/RpcRecords.kt` 里的记录类型（字段名取自 `rpc-types.d.ts`）。
 * 与 [Responses] 一样宽松：缺字段取默认值，从不抛异常。
 */
object ExtraResponses {

    /** `get_commands` → `data.commands`。没有 `name` 的项丢弃。 */
    fun commands(data: JsonElement?): List<SlashCommandRecord> {
        val arr = (data as? JsonObject)?.arr("commands") ?: (data as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val info = o.obj("sourceInfo")
            SlashCommandRecord(
                name = name,
                description = o.str("description"),
                source = o.str("source") ?: "extension",
                path = info?.str("path"),
                scope = info?.str("scope"),
                raw = o,
            )
        }
    }

    /** `get_fork_messages` → `data.messages`。 */
    fun forkMessages(data: JsonElement?): List<ForkMessageRecord> {
        val arr = (data as? JsonObject)?.arr("messages") ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("entryId") ?: return@mapNotNull null
            ForkMessageRecord(id, o.str("text") ?: "")
        }
    }

    /** `fork` → `data{text,cancelled}`。 */
    fun fork(data: JsonElement?): ForkResultRecord {
        val o = data as? JsonObject
        return ForkResultRecord(text = o?.str("text") ?: "", cancelled = o?.bool("cancelled") ?: false)
    }

    /** `bash` → `BashResult`。 */
    fun bash(data: JsonElement?): BashResultRecord {
        val o = data as? JsonObject ?: JsonObject(emptyMap())
        return BashResultRecord(
            output = o.str("output") ?: "",
            exitCode = o.long("exitCode")?.toInt(),
            cancelled = o.bool("cancelled") ?: false,
            truncated = o.bool("truncated") ?: false,
            fullOutputPath = o.str("fullOutputPath"),
            raw = o,
        )
    }

    /** `compact` 的 `data` / `compaction_end.result` → `CompactionResult`；不是对象时返回 null。 */
    fun compaction(data: JsonElement?): CompactionResultRecord? {
        val o = data as? JsonObject ?: return null
        return CompactionResultRecord(
            summary = o.str("summary") ?: "",
            firstKeptEntryId = o.str("firstKeptEntryId"),
            tokensBefore = o.long("tokensBefore"),
            estimatedTokensAfter = o.long("estimatedTokensAfter"),
            raw = o,
        )
    }

    /**
     * `get_session_stats` → 完整 `SessionStats`。
     * `contextUsage` 缺失（没有模型或 contextWindow 未知）→ null；压缩后到下一次回复之前 `percent`/`tokens` 为 null，原样保留。
     */
    fun stats(data: JsonElement?): SessionStatsRecord {
        val o = data as? JsonObject ?: JsonObject(emptyMap())
        val t = o.obj("tokens")
        val tokens = TokenTotalsRecord(
            input = t?.long("input") ?: 0,
            output = t?.long("output") ?: 0,
            cacheRead = t?.long("cacheRead") ?: 0,
            cacheWrite = t?.long("cacheWrite") ?: 0,
            total = t?.long("total") ?: 0,
        )
        val ctx = o.obj("contextUsage")?.let { c ->
            ContextUsageRecord(tokens = c.long("tokens"), contextWindow = c.long("contextWindow"), percent = c.double("percent"))
        }
        return SessionStatsRecord(
            sessionFile = o.str("sessionFile"),
            sessionId = o.str("sessionId"),
            userMessages = o.long("userMessages")?.toInt() ?: 0,
            assistantMessages = o.long("assistantMessages")?.toInt() ?: 0,
            toolCalls = o.long("toolCalls")?.toInt() ?: 0,
            toolResults = o.long("toolResults")?.toInt() ?: 0,
            totalMessages = o.long("totalMessages")?.toInt() ?: 0,
            tokens = tokens,
            cost = o.double("cost") ?: 0.0,
            contextUsage = ctx,
            raw = o,
        )
    }

    /** `export_html` → `data.path`。 */
    fun exportPath(data: JsonElement?): String? = (data as? JsonObject)?.str("path")

    /** `get_last_assistant_text` → `data.text`（可为 null）。 */
    fun lastAssistantText(data: JsonElement?): String? = (data as? JsonObject)?.str("text")

    /** `get_entries` → `data{entries,leafId}`。 */
    fun entries(data: JsonElement?): SessionEntriesRecord {
        val o = data as? JsonObject
        val arr = o?.arr("entries") ?: JsonArray(emptyList())
        return SessionEntriesRecord(arr.mapNotNull { entry(it) }, o?.str("leafId"))
    }

    /**
     * `get_tree` → `data{tree,leafId}`。用显式栈后序构建，**不设深度上限**、保留每一层的真实父子结构
     * （长会话一次工具调用就 2+ 条目，深度很容易过 500；拉平会把多个分支交错在一起，审查第 1 轮 Important#4）。
     * 消费方（[ContractMapping.node]）同样用显式栈，不会递归爆栈。
     */
    fun tree(data: JsonElement?): SessionTreeRecord {
        val o = data as? JsonObject
        val roots = o?.arr("tree") ?: JsonArray(emptyList())
        return SessionTreeRecord(buildForest(roots), o?.str("leafId"))
    }

    private class Frame(val o: JsonObject, val entry: SessionEntryRecord, val kids: List<JsonElement>) {
        var next = 0
        val built = ArrayList<SessionTreeNodeRecord>(kids.size)
    }

    private fun buildForest(roots: List<JsonElement>): List<SessionTreeNodeRecord> {
        val out = ArrayList<SessionTreeNodeRecord>(roots.size)
        val stack = ArrayDeque<Frame>()
        fun push(e: JsonElement): Boolean {
            val o = e as? JsonObject ?: return false
            val entry = entry(o["entry"]) ?: return false // 无效节点连同子树丢弃（与旧实现一致）
            stack.addLast(Frame(o, entry, o.arr("children").orEmpty()))
            return true
        }
        for (r in roots) {
            if (!push(r)) continue
            while (stack.isNotEmpty()) {
                val top = stack.last()
                if (top.next < top.kids.size) {
                    push(top.kids[top.next++])
                    continue
                }
                stack.removeLast()
                val node = SessionTreeNodeRecord(top.entry, top.built, top.o.str("label"))
                if (stack.isEmpty()) out += node else stack.last().built += node
            }
        }
        return out
    }

    /** 一条 `SessionEntry`：没有 `id` 的丢弃。 */
    fun entry(e: JsonElement?): SessionEntryRecord? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val type = o.str("type") ?: ""
        val message = o.obj("message")
        return SessionEntryRecord(
            id = id,
            parentId = o.str("parentId"),
            type = type,
            timestamp = o.str("timestamp"),
            role = if (type == "message") message?.str("role") else null,
            preview = preview(type, o, message).let { if (it.length > PREVIEW_MAX) it.take(PREVIEW_MAX) + "…" else it },
            raw = o,
        )
    }

    private fun preview(type: String, o: JsonObject, message: JsonObject?): String = when (type) {
        "message" -> when (message?.str("role")) {
            "bashExecution" -> "$ " + (message.str("command") ?: "")
            "toolResult" -> "[" + (message.str("toolName") ?: "tool") + "]"
            else -> Messages.text(message?.get("content")).ifEmpty {
                // 只有工具调用的助手消息：列出调用的工具名
                message?.arr("content").orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "toolCall" }?.str("name") }
                    .joinToString(", ") { "[$it]" }
            }
        }
        "compaction" -> "[压缩] " + (o.str("summary") ?: "")
        "branch_summary" -> "[分支摘要] " + (o.str("summary") ?: "")
        "model_change" -> "[模型] " + (o.str("provider") ?: "") + "/" + (o.str("modelId") ?: "")
        "thinking_level_change" -> "[思考] " + (o.str("thinkingLevel") ?: "")
        "session_info" -> "[会话名] " + (o.str("name") ?: "")
        "label" -> "[标签] " + (o.str("label") ?: "")
        "custom_message" -> Messages.text(o["content"])
        else -> "[$type]"
    }.replace('\n', ' ').trim()

    /** `ImageContent[]` → [RpcImage]（缺 data/mimeType 的丢弃）。用于用户消息内容与 `images` 往返。 */
    fun images(content: JsonElement?): List<RpcImage> = (content as? JsonArray).orEmpty().mapNotNull { b ->
        val o = b as? JsonObject ?: return@mapNotNull null
        if (o.str("type") != "image") return@mapNotNull null
        val data = o.str("data") ?: return@mapNotNull null
        RpcImage(data, o.str("mimeType") ?: "image/png")
    }

    /** 用户消息的纯文本部分（不含图片占位），用于乐观气泡匹配。 */
    fun textOnly(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> if (content.isString) content.content else ""
        is JsonArray -> content.mapNotNull { b -> (b as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("")
        else -> ""
    }

    private const val PREVIEW_MAX = 200
}
