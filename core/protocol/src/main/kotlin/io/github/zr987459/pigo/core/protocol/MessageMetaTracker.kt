package io.github.zr987459.pigo.core.protocol

import io.github.zr987459.pigo.contract.ChatItem
import io.github.zr987459.pigo.contract.Delivery
import io.github.zr987459.pigo.contract.MessageMeta
import io.github.zr987459.pigo.contract.ModelRef
import io.github.zr987459.pigo.contract.TokenUsage
import kotlinx.serialization.json.JsonObject

/**
 * `EngineExtras.messageMeta` 的簿记（S1、U1）：把时间线条目（[ChatItem.key]）与 pi 会话文件里的 message entry 对上。
 *
 * - 数据源：`get_entries`（打开 / 重建时全量，[reset]）+ `entry_appended`（增量，[append]）。
 * - 只看**当前分支**：从 leafId 沿 parentId 走到根，得到按顺序的 message entry（其他分支的 entry 不参与配对）。
 * - 配对规则：
 *   - `toolResult` 按 `toolCallId` 对到 `tool-<id>` 卡（不能按顺序配对）；
 *   - `user`、`assistant` 各自**从末尾往前**一一配对：get_messages 的时间线在压缩后只含保留部分，头部对不齐，尾部总是对齐的。
 *   - 参与配对的时间线条目：已确认（[Delivery.SENT]）且已写入会话的用户气泡（调用方给出 [notPersisted]），非流式中的助手消息。
 *     乐观气泡、发送失败的气泡、扩展消费掉的输入（handled）没有条目。
 * - 重建时间线会换 key：调用方在每次时间线或 entry 变化后调用 [compute] 重新配对。
 * 不按文本匹配（同一句话可能出现多次）。
 */
class MessageMetaTracker {
    private class Msg(val id: String, val parentId: String?, val role: String?, val message: JsonObject?)

    private val byId = LinkedHashMap<String, Msg>()
    private var leafId: String? = null

    /** 当前 entry 的版本号：每次 [reset]/[append] 递增，调用方据此判断是否需要重新配对。 */
    var version = 0L
        private set

    /** `get_entries` 全量结果（不带 since）。[entries] 为原始 SessionEntry JSON。 */
    fun reset(entries: List<JsonObject>, leafId: String?) {
        byId.clear()
        entries.forEach { add(it) }
        this.leafId = leafId ?: entries.lastOrNull()?.str("id")
        version++
    }

    /** `entry_appended`：pi 只在当前叶子之后追加，新条目成为叶子。 */
    fun append(entry: JsonObject) {
        val id = add(entry) ?: return
        leafId = id
        version++
    }

    fun clear() {
        byId.clear()
        leafId = null
        version++
    }

    private fun add(o: JsonObject): String? {
        val id = o.str("id") ?: return null
        val message = if (o.str("type") == "message") o.obj("message") else null
        byId[id] = Msg(id, o.str("parentId"), message?.str("role") ?: o.str("type"), message)
        return id
    }

    /** 当前分支上的条目，按根 → 叶顺序。防环。 */
    private fun branch(): List<Msg> {
        val out = ArrayList<Msg>()
        val seen = HashSet<String>()
        var cur = leafId?.let { byId[it] }
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parentId?.let { byId[it] }
        }
        out.reverse()
        return out
    }

    fun compute(items: List<ChatItem>, notPersisted: Set<String> = emptySet()): Map<String, MessageMeta> {
        val path = branch().filter { it.message != null }
        if (path.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MessageMeta>()

        // toolResult：按 toolCallId。
        val toolKeys = items.mapNotNullTo(HashSet()) { (it as? ChatItem.Tool)?.key }
        for (m in path) {
            if (m.role != "toolResult") continue
            val callId = m.message?.str("toolCallId") ?: continue
            val key = ChatReducer.toolKey(callId)
            if (key in toolKeys) out[key] = meta(m)
        }

        fun pairFromEnd(role: String, timeline: List<ChatItem>) {
            val entries = path.filter { it.role == role }
            var i = timeline.size - 1
            var j = entries.size - 1
            while (i >= 0 && j >= 0) {
                out[timeline[i].key] = meta(entries[j])
                i--
                j--
            }
        }
        pairFromEnd("user", items.filter { it is ChatItem.User && it.delivery == Delivery.SENT && it.key !in notPersisted })
        pairFromEnd("assistant", items.filter { it is ChatItem.Assistant && !it.streaming })
        return out
    }

    private fun meta(m: Msg): MessageMeta {
        val msg = m.message
        val usage = msg?.obj("usage")
        val tokens = usage?.let {
            TokenUsage(
                input = it.long("input") ?: 0,
                output = it.long("output") ?: 0,
                cacheRead = it.long("cacheRead") ?: 0,
                cacheWrite = it.long("cacheWrite") ?: 0,
                total = it.long("totalTokens") ?: ((it.long("input") ?: 0) + (it.long("output") ?: 0) + (it.long("cacheRead") ?: 0) + (it.long("cacheWrite") ?: 0)),
            )
        }
        val provider = msg?.str("provider")
        val model = msg?.str("model")
        return MessageMeta(
            entryId = m.id,
            role = m.role,
            usage = if (m.role == "assistant") tokens else null,
            costUsd = if (m.role == "assistant") usage?.obj("cost")?.double("total") else null,
            model = if (m.role == "assistant" && provider != null && model != null) ModelRef(provider, model) else null,
        )
    }
}
